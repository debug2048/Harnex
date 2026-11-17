package com.harnex.gateway.im.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.gateway.im.ImTypes;
import com.harnex.gateway.im.ImTypes.Connection;
import com.harnex.gateway.im.ImTypes.DeliveryException;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import okhttp3.*;

/** Feishu application identity and message API. No Agent state or workspace authority lives here. */
final class FeishuApi {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection config;
    private final OkHttpClient http;
    private final HttpUrl base;
    private String accessToken;
    private long expiresAt;
    FeishuApi(Connection config, OkHttpClient http, String base) {
        this.config = config; this.http = http; this.base = HttpUrl.get(base);
    }
    JsonNode endpoint() throws DeliveryException {
        return request("POST", "/callback/ws/endpoint", Map.of("AppID", config.clientId(), "AppSecret", config.clientSecret()), false, false);
    }
    String botId() throws DeliveryException {
        String value = request("GET", "/open-apis/bot/v3/info", null, true, false).path("bot").path("open_id").asText();
        if (value.isBlank()) throw new DeliveryException(DeliveryException.Kind.PERMANENT, "请在飞书应用中启用机器人能力并发布版本");
        return value;
    }
    String send(String chatId, String kind, Object content, String deliveryId) throws DeliveryException {
        try {
            JsonNode result = request("POST", "/open-apis/im/v1/messages?receive_id_type=chat_id",
                Map.of("receive_id", chatId, "msg_type", kind, "content", JSON.writeValueAsString(content),
                    "uuid", ImTypes.key("feishu", config.clientId(), deliveryId).substring(0, 50)), true, true);
            String id = result.path("data").path("message_id").asText();
            if (id.isBlank()) throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "飞书未返回消息 ID，请在原会话发送 /结果 查看");
            return id;
        } catch (DeliveryException failure) { throw failure; }
        catch (Exception failure) { throw new DeliveryException(DeliveryException.Kind.PERMANENT, "飞书消息无法编码"); }
    }
    void update(String messageId, Object card) throws DeliveryException {
        try {
            String path = base.newBuilder().encodedPath("/open-apis/im/v1/messages").addPathSegment(messageId).build().encodedPath();
            request("PATCH", path, Map.of("content", JSON.writeValueAsString(card)), true, true);
        } catch (DeliveryException failure) { throw failure; }
        catch (Exception failure) { throw new DeliveryException(DeliveryException.Kind.PERMANENT, "飞书卡片无法编码"); }
    }
    private synchronized String token() throws DeliveryException {
        if (accessToken != null && expiresAt > System.currentTimeMillis()) return accessToken;
        JsonNode value = request("POST", "/open-apis/auth/v3/tenant_access_token/internal",
            Map.of("app_id", config.clientId(), "app_secret", config.clientSecret()), false, false);
        String token = value.path("tenant_access_token").asText();
        if (token.isBlank()) throw new DeliveryException(DeliveryException.Kind.PERMANENT, "飞书未返回应用访问凭据，请检查 App ID 和 App Secret");
        accessToken = token;
        expiresAt = System.currentTimeMillis() + Math.max(1, value.path("expire").asLong(7200) - 60) * 1000;
        return accessToken;
    }
    private JsonNode request(String method, String path, Object body, boolean authenticated, boolean mutation) throws DeliveryException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                RequestBody payload = body == null ? null : RequestBody.create(JSON.writeValueAsBytes(body), MediaType.get("application/json"));
                var builder = new Request.Builder().url(base.resolve(path)).header("User-Agent", "Harnex/IM").method(method, payload);
                if (authenticated) builder.header("Authorization", "Bearer " + token());
                try (Response response = http.newCall(builder.build()).execute()) {
                    if (response.code() == 429) throw new DeliveryException(DeliveryException.Kind.RETRYABLE, "飞书限流，稍后重试");
                    if (response.code() >= 500) throw new DeliveryException(mutation ? DeliveryException.Kind.UNKNOWN : DeliveryException.Kind.RETRYABLE,
                        "飞书服务暂不可用（HTTP " + response.code() + "）");
                    if (!response.isSuccessful()) throw new DeliveryException(DeliveryException.Kind.PERMANENT, "飞书拒绝请求（HTTP " + response.code() + "）");
                    if (response.body() == null) throw new IOException("empty Feishu response");
                    byte[] data = response.body().byteStream().readNBytes(2_000_001);
                    if (data.length > 2_000_000) throw new IOException("oversized Feishu response");
                    JsonNode value = JSON.readTree(data);
                    if (value == null || !value.has("code")) throw new IOException("missing Feishu result code");
                    int code = value.path("code").asInt(-1);
                    if (code == 0) return value;
                    if (authenticated && attempt == 0 && Set.of(99991661, 99991663, 99991664, 99991668).contains(code)) {
                        synchronized (this) { accessToken = null; }
                        continue;
                    }
                    DeliveryException.Kind kind = Set.of(1, 99991400, 99991401).contains(code)
                        ? DeliveryException.Kind.RETRYABLE : DeliveryException.Kind.PERMANENT;
                    throw new DeliveryException(kind, "飞书 API 拒绝请求，错误码 " + code + "；请检查应用凭据、权限、发布状态和可用范围");
                }
            } catch (DeliveryException failure) { throw failure; }
            catch (IOException failure) {
                throw new DeliveryException(mutation ? DeliveryException.Kind.UNKNOWN : DeliveryException.Kind.RETRYABLE,
                    mutation ? "飞书发送结果未知，请在原会话发送 /结果 查看" : "无法连接飞书，请检查网络或代理设置");
            }
        }
        throw new DeliveryException(DeliveryException.Kind.PERMANENT, "飞书应用凭据失效，请重新连接");
    }
}
