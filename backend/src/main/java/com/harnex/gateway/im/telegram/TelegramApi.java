package com.harnex.gateway.im.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.gateway.im.ImTypes.DeliveryException;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.*;

/** Telegram puts its secret in the URL: never propagate response bodies, URLs or exception causes. */
final class TelegramApi implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    private final OkHttpClient http, polling;
    private final String endpoint;
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile long retryAt;

    TelegramApi(OkHttpClient http, String baseUrl, String token) {
        this.http = http;
        polling = http.newBuilder().readTimeout(Duration.ofSeconds(40)).callTimeout(Duration.ofSeconds(45)).build();
        endpoint = baseUrl + "/bot" + token + "/";
    }
    static final class Failure extends DeliveryException {
        final int code;
        final long delay;
        Failure(Kind kind, int code, String detail, long delay) { super(kind, detail); this.code = code; this.delay = delay; }
    }
    JsonNode call(String method, Map<String, ?> body) throws Failure {
        boolean sending = "sendMessage".equals(method);
        boolean poll = "getUpdates".equals(method);
        if (closed) throw new Failure(DeliveryException.Kind.RETRYABLE, 0, "Telegram 连接已关闭", 1000);
        long remaining = retryAt - System.currentTimeMillis();
        if (remaining > 0) throw new Failure(DeliveryException.Kind.RETRYABLE, 429, "Telegram 请求频率受限，稍后自动重试", remaining);
        Call request = null;
        try {
            request = (poll ? polling : http).newCall(new Request.Builder().url(endpoint + method)
                .post(RequestBody.create(JSON.writeValueAsBytes(body), MediaType.get("application/json"))).build());
            calls.add(request);
            if (closed) request.cancel();
            try (Response response = request.execute()) {
                byte[] bytes = response.body() == null ? new byte[0] : response.peekBody(4 * 1024 * 1024 + 1).bytes();
                if (bytes.length > 4 * 1024 * 1024) throw new IOException();
                JsonNode value;
                try { value = JSON.readTree(bytes); }
                catch (IOException malformed) { value = null; }
                if (value != null && response.isSuccessful() && value.path("ok").asBoolean()) return value.path("result");
                if (response.isSuccessful() && (value == null || !value.has("ok"))) throw new IOException();
                int code = value == null ? response.code() : value.path("error_code").asInt(response.code());
                // Repeating an idempotent edit is expected after a restart or a lost response.
                if (method.equals("editMessageText") && code == 400 && value != null
                    && value.path("description").asText().contains("message is not modified")) return JSON.nullNode();
                long delay = code == 429 ? Math.min(3600, Math.max(1, value == null ? 5 : value.path("parameters").path("retry_after").asLong(5))) * 1000 : 2000;
                if (code == 429) retryAt = System.currentTimeMillis() + delay;
                String detail = switch (code) {
                    case 401, 404 -> "Telegram Bot Token 无效，请检查后重新连接";
                    case 409 -> "Telegram 机器人正被另一处长轮询或 Webhook 使用，请停止另一处接入后重新连接";
                    case 403 -> "Telegram 机器人无权在此会话发送消息，请检查是否被屏蔽或移出群聊";
                    case 429 -> "Telegram 请求频率受限，稍后自动重试";
                    default -> "Telegram 请求失败（状态 " + code + "）";
                };
                var kind = code == 429 ? DeliveryException.Kind.RETRYABLE : code >= 500
                    ? sending ? DeliveryException.Kind.UNKNOWN : DeliveryException.Kind.RETRYABLE : DeliveryException.Kind.PERMANENT;
                throw new Failure(kind, code, detail, delay);
            }
        } catch (Failure known) { throw known; }
        catch (Exception failure) {
            throw new Failure(sending ? DeliveryException.Kind.UNKNOWN : DeliveryException.Kind.RETRYABLE, 0,
                sending ? "Telegram 发送结果未知，未自动重发；可发送 /结果 查看" : "Telegram 网络请求失败，请检查网络和全局代理", 2000);
        } finally { if (request != null) calls.remove(request); }
    }
    @Override public void close() { closed = true; calls.forEach(Call::cancel); }
}
