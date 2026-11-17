package com.harnex.gateway.webhook.outbound;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Feishu / Lark custom group-robot outbound push.
 *
 * <p>
 * Endpoint shape:
 * 
 * <pre>
 * POST https://open.feishu.cn/open-apis/bot/v2/hook/{hook-id}
 * Content-Type: application/json
 * {"msg_type":"text","content":{"text":"..."}}
 * </pre>
 *
 * <p>
 * When the bot has "签名校验" turned on, the payload MUST also include {@code timestamp} (UNIX seconds) and {@code sign} =
 * {@code base64(HmacSHA256(empty-bytes, "{ts}\n{secret}"))}. Feishu's sign algorithm is unusual: the HMAC key is the
 * {@code "{ts}\n{secret}"} string and the message is empty — we mirror that exactly. See
 * <a href="https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot#3c6592d6">add custom bot</a>.
 */
@Slf4j
@Component
@ProjectRuntime
public class FeishuOutboundClient implements ChannelOutboundClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final AgentConfig cfg;
    private final OkHttpClient http;

    public FeishuOutboundClient(AgentConfig cfg) {
        this.cfg = cfg;
        this.http = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).build();
    }

    @Override
    public String channel() {
        return "feishu";
    }

    @Override
    public boolean isConfigured() {
        var o = cfg.getGateway().getFeishuOutbound();
        return o != null && o.getWebhookUrl() != null && !o.getWebhookUrl().isBlank();
    }

    @Override
    public void push(String chatId, String text) throws OutboundException {
        var o = cfg.getGateway().getFeishuOutbound();
        if (o == null || o.getWebhookUrl() == null || o.getWebhookUrl().isBlank()) {
            throw new OutboundException("feishu-outbound.webhook-url not configured");
        }
        // Build payload. Use a LinkedHashMap so msg_type appears before content in the JSON —
        // not semantically required but makes the wire payload easier to eyeball in a tcpdump.
        Map<String, Object> payload = new LinkedHashMap<>();
        if (o.getSignSecret() != null && !o.getSignSecret().isBlank()) {
            long ts = System.currentTimeMillis() / 1000L;
            String sign;
            try {
                sign = computeSign(ts, o.getSignSecret());
            } catch (Exception e) {
                throw new OutboundException("feishu sign compute failed: " + e.getMessage(), e);
            }
            payload.put("timestamp", Long.toString(ts));
            payload.put("sign", sign);
        }
        payload.put("msg_type", "text");
        payload.put("content", Map.of("text", text));

        String body;
        try {
            body = JSON.writeValueAsString(payload);
        } catch (Exception e) {
            throw new OutboundException("feishu body serialise failed: " + e.getMessage(), e);
        }
        Request req = new Request.Builder().url(o.getWebhookUrl()).post(RequestBody.create(body, JSON_MEDIA)).build();
        OutboundHttp.Outcome out = OutboundHttp.executeWithRetry(http, req);
        if (!out.success) {
            if (out.ioError != null) {
                throw new OutboundException("feishu push I/O after retries: " + out.ioError.getMessage(), out.ioError);
            }
            throw new OutboundException("feishu push HTTP " + out.code + " after retries: " + out.body);
        }
        // Feishu returns 200 with {"code":N,"msg":"..."} on logical errors; code != 0 means failure.
        // Some legacy endpoints emit "StatusCode" instead of "code" — fall back to that field.
        // Null on BOTH fields = non-JSON 200 body (proxy error page etc) — fail loudly.
        Integer code = OutboundHttp.readIntField(JSON, out.body, "code");
        if (code == null) {
            code = OutboundHttp.readIntField(JSON, out.body, "StatusCode");
        }
        if (code == null) {
            throw new OutboundException(
                "feishu push: HTTP 200 but no JSON code field, body=" + OutboundHttp.bodyHead(out.body, 200));
        }
        if (code != 0) {
            throw new OutboundException("feishu push code=" + code + ": " + OutboundHttp.bodyHead(out.body, 200));
        }
    }

    /** Feishu sign: HMAC-SHA256 where the KEY is {@code "{ts}\n{secret}"} and the message is empty. */
    private static String computeSign(long ts, String secret) throws Exception {
        String key = ts + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(new byte[0]);
        return Base64.getEncoder().encodeToString(sig);
    }
}
