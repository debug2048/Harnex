package com.harnex.gateway.im.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.gateway.im.ImTypes.*;
import com.harnex.gateway.im.ImTypes.Connection;
import java.util.Map;
import okhttp3.*;

/** Shared application authentication for cards and proactive notifications. Never retries a message implicitly. */
final class DingTalkApi {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection config;
    private final OkHttpClient http;
    private final String base;
    private String accessToken;
    private long expiresAt;
    DingTalkApi(Connection config, OkHttpClient http, String base) {
        this.config = config; this.http = http; this.base = base.replaceAll("/+$", "");
    }
    private synchronized String token() throws DeliveryException {
        if (accessToken != null && expiresAt > System.currentTimeMillis()) return accessToken;
        try {
            Request request = new Request.Builder().url(base + "/v1.0/oauth2/accessToken")
                .post(RequestBody.create(JSON.writeValueAsString(Map.of("appKey", config.clientId(), "appSecret", config.clientSecret())), MediaType.get("application/json"))).build();
            try (Response response = http.newCall(request).execute()) {
                if (response.code() == 429 || response.code() >= 500) throw new DeliveryException(DeliveryException.Kind.RETRYABLE, "暂时无法取得机器人访问凭据");
                if (!response.isSuccessful() || response.body() == null) throw new DeliveryException(DeliveryException.Kind.PERMANENT, "机器人认证失败，请检查应用凭据和权限");
                JsonNode value = JSON.readTree(response.body().string());
                String token = value.path("accessToken").asText();
                if (token.isBlank()) throw new DeliveryException(DeliveryException.Kind.PERMANENT, "平台没有返回机器人访问凭据");
                accessToken = token; expiresAt = System.currentTimeMillis() + Math.max(1, value.path("expireIn").asLong(7200) - 60) * 1000;
                return token;
            }
        } catch (DeliveryException failure) { throw failure; }
        catch (Exception failure) { throw new DeliveryException(DeliveryException.Kind.RETRYABLE, "暂时无法取得机器人访问凭据"); }
    }
    JsonNode call(String method, String path, Object body) throws DeliveryException {
        String token = token();
        try {
            Request request = new Request.Builder().url(base + path).header("x-acs-dingtalk-access-token", token)
                .method(method, RequestBody.create(JSON.writeValueAsString(body), MediaType.get("application/json"))).build();
            try (Response response = http.newCall(request).execute()) {
                if (response.code() == 401) {
                    synchronized (this) { accessToken = null; }
                    throw new DeliveryException(DeliveryException.Kind.RETRYABLE, "机器人访问凭据已过期，稍后重试");
                }
                if (response.code() == 429) throw new DeliveryException(DeliveryException.Kind.RETRYABLE, "平台限流，稍后重试");
                if (response.code() >= 500) throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "平台处理结果未知，未自动重复发送");
                if (!response.isSuccessful()) throw new DeliveryException(DeliveryException.Kind.PERMANENT,
                    "平台拒绝发送（HTTP " + response.code() + "），请检查机器人权限、应用可见范围和消息接收范围");
                if (response.body() == null) throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "平台未返回发送确认");
                return JSON.readTree(response.body().string());
            }
        } catch (DeliveryException failure) { throw failure; }
        catch (Exception failure) { throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "发送结果未知，请在目标会话核对"); }
    }
}
