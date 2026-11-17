package com.harnex.gateway.webhook.outbound;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * DingTalk custom-robot outbound push.
 *
 * <p>
 * Endpoint shape (custom group robot — the simplest deployment, no app credential rotation):
 * 
 * <pre>
 * POST https://oapi.dingtalk.com/robot/send?access_token=...[&amp;timestamp=...&amp;sign=...]
 * Content-Type: application/json
 * {"msgtype":"text","text":{"content":"..."}}
 * </pre>
 *
 * <p>
 * When the operator has enabled the "签名" security mode on the bot the {@code sign} query param is mandatory and is
 * computed as {@code base64(HmacSHA256(secret, "{timestamp}\n{secret}"))} — same algorithm as inbound, but the signed
 * string is the OUTGOING timestamp. We URL-encode the resulting base64 because the "+" / "=" characters in a base64
 * string break query parsing on some HTTP intermediaries.
 *
 * <p>
 * See: <a href="https://open.dingtalk.com/document/orgapp/custom-robot-access">custom robot access</a>.
 */
@Slf4j
@Component
@ProjectRuntime
public class DingTalkOutboundClient implements ChannelOutboundClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final AgentConfig cfg;
    private final OkHttpClient http;

    public DingTalkOutboundClient(AgentConfig cfg) {
        this.cfg = cfg;
        // Short timeouts: the platform's incoming-webhook endpoint is supposed to be
        // sub-second. A longer timeout would let a wedged push pin a dispatcher worker
        // thread well past the 5-min hard async dispatch budget.
        this.http = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).build();
    }

    @Override
    public String channel() {
        return "dingtalk";
    }

    @Override
    public boolean isConfigured() {
        var o = cfg.getGateway().getDingtalkOutbound();
        return o != null && o.getWebhookUrl() != null && !o.getWebhookUrl().isBlank();
    }

    @Override
    public void push(String chatId, String text) throws OutboundException {
        var o = cfg.getGateway().getDingtalkOutbound();
        if (o == null || o.getWebhookUrl() == null || o.getWebhookUrl().isBlank()) {
            throw new OutboundException("dingtalk-outbound.webhook-url not configured");
        }
        String url = o.getWebhookUrl();
        if (o.getSignSecret() != null && !o.getSignSecret().isBlank()) {
            long ts = System.currentTimeMillis();
            String sign;
            try {
                sign = computeSign(ts, o.getSignSecret());
            } catch (Exception e) {
                throw new OutboundException("dingtalk sign compute failed: " + e.getMessage(), e);
            }
            String encoded = URLEncoder.encode(sign, StandardCharsets.UTF_8);
            url = url + (url.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign=" + encoded;
        }
        String body;
        try {
            body = JSON.writeValueAsString(Map.of("msgtype", "text", "text", Map.of("content", text)));
        } catch (Exception e) {
            throw new OutboundException("dingtalk body serialise failed: " + e.getMessage(), e);
        }
        Request req = new Request.Builder().url(url).post(RequestBody.create(body, JSON_MEDIA)).build();
        OutboundHttp.Outcome out = OutboundHttp.executeWithRetry(http, req);
        if (!out.success) {
            if (out.ioError != null) {
                throw new OutboundException("dingtalk push I/O after retries: " + out.ioError.getMessage(),
                    out.ioError);
            }
            throw new OutboundException("dingtalk push HTTP " + out.code + " after retries: " + out.body);
        }
        // DingTalk returns 200 even on logical failure with {"errcode":N,"errmsg":"..."} —
        // parse JSON and check the typed field instead of string-matching (whitespace + substring
        // collisions made the old contains() check unreliable). A {@code null} parse result means
        // we received 200 but the body is non-JSON / missing errcode (e.g. an upstream proxy
        // error page) — treat as failure, not silent success.
        Integer errcode = OutboundHttp.readIntField(JSON, out.body, "errcode");
        if (errcode == null) {
            throw new OutboundException(
                "dingtalk push: HTTP 200 but no JSON errcode field, body=" + OutboundHttp.bodyHead(out.body, 200));
        }
        if (errcode != 0) {
            throw new OutboundException(
                "dingtalk push errcode=" + errcode + ": " + OutboundHttp.bodyHead(out.body, 200));
        }
    }

    private static String computeSign(long ts, String secret) throws Exception {
        String stringToSign = ts + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig);
    }
}
