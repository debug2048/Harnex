package com.harnex.gateway.webhook.outbound;

import java.util.Map;
import java.util.concurrent.TimeUnit;

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
 * WeCom (企业微信) group-robot outbound push.
 *
 * <p>
 * Endpoint shape — the simplest WeCom integration, no corpsecret / agentid / access_token rotation needed:
 * 
 * <pre>
 * POST https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key={bot-key}
 * Content-Type: application/json
 * {"msgtype":"text","text":{"content":"..."}}
 * </pre>
 *
 * <p>
 * The bot key is itself the only credential; rotation = create a new bot in the group. See
 * <a href="https://developer.work.weixin.qq.com/document/path/91770">群机器人配置说明</a>.
 */
@Slf4j
@Component
@ProjectRuntime
public class WeComOutboundClient implements ChannelOutboundClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final AgentConfig cfg;
    private final OkHttpClient http;

    public WeComOutboundClient(AgentConfig cfg) {
        this.cfg = cfg;
        this.http = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).build();
    }

    @Override
    public String channel() {
        return "wecom";
    }

    @Override
    public boolean isConfigured() {
        var o = cfg.getGateway().getWecomOutbound();
        return o != null && o.getWebhookUrl() != null && !o.getWebhookUrl().isBlank();
    }

    @Override
    public void push(String chatId, String text) throws OutboundException {
        var o = cfg.getGateway().getWecomOutbound();
        if (o == null || o.getWebhookUrl() == null || o.getWebhookUrl().isBlank()) {
            throw new OutboundException("wecom-outbound.webhook-url not configured");
        }
        String body;
        try {
            body = JSON.writeValueAsString(Map.of("msgtype", "text", "text", Map.of("content", text)));
        } catch (Exception e) {
            throw new OutboundException("wecom body serialise failed: " + e.getMessage(), e);
        }
        Request req = new Request.Builder().url(o.getWebhookUrl()).post(RequestBody.create(body, JSON_MEDIA)).build();
        OutboundHttp.Outcome out = OutboundHttp.executeWithRetry(http, req);
        if (!out.success) {
            if (out.ioError != null) {
                throw new OutboundException("wecom push I/O after retries: " + out.ioError.getMessage(), out.ioError);
            }
            throw new OutboundException("wecom push HTTP " + out.code + " after retries: " + out.body);
        }
        // WeCom returns 200 with {"errcode":N,"errmsg":"..."} on logical errors. Null parse
        // = non-JSON body / missing field (e.g. proxy error page) — fail loudly instead of
        // silently treating as success.
        Integer errcode = OutboundHttp.readIntField(JSON, out.body, "errcode");
        if (errcode == null) {
            throw new OutboundException(
                "wecom push: HTTP 200 but no JSON errcode field, body=" + OutboundHttp.bodyHead(out.body, 200));
        }
        if (errcode != 0) {
            throw new OutboundException("wecom push errcode=" + errcode + ": " + OutboundHttp.bodyHead(out.body, 200));
        }
    }
}
