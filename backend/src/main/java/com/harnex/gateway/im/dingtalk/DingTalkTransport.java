package com.harnex.gateway.im.dingtalk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.harnex.gateway.im.ImSocket;
import com.harnex.gateway.im.ImTypes;
import com.harnex.gateway.im.ImInteractionCard;

import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.gateway.im.ImTypes.*;
import com.harnex.gateway.im.ImTypes.Connection;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import okhttp3.*;

import lombok.extern.slf4j.Slf4j;

/** DingTalk Stream robot subscription; replies use the signed source session, never a fixed group webhook. */
@Slf4j
public final class DingTalkTransport extends ImSocket {
    static final String TOPIC = "/v1.0/im/bot/messages/get";
    static final String CARD_TOPIC = "/v1.0/card/instances/callback";
    private final String openUrl;
    private final boolean testEndpoint;
    private final DingTalkCards cards;
    private final DingTalkApi api;
    private volatile String cardError = "";
    private volatile long lastCardActionAt;
    private volatile String lastCardAction = "";
    private final AtomicLong cardCallbackCount = new AtomicLong();
    private Function<CardAction, CardResult> actions = action -> new CardResult(false, "交互处理器未就绪", null);
    public DingTalkTransport(Connection config, OkHttpClient http, Consumer<Inbound> inbound) {
        this(config, http, inbound, "https://api.dingtalk.com/v1.0/gateway/connections/open", false);
    }
    DingTalkTransport(Connection config, OkHttpClient http, Consumer<Inbound> inbound, String openUrl, boolean testEndpoint) {
        super(config, http, inbound); this.openUrl = openUrl; this.testEndpoint = testEndpoint;
        api = new DingTalkApi(config, http, testEndpoint ? HttpUrl.get(openUrl).newBuilder().encodedPath("/").query(null).build().toString() : "https://api.dingtalk.com");
        cards = new DingTalkCards(config, api);
    }
    @Override protected Request request() throws Exception {
        String payload = JSON.writeValueAsString(Map.of("clientId", config.clientId(), "clientSecret", config.clientSecret(),
            "ua", "Harnex/1.0", "subscriptions", List.of(Map.of("type", "CALLBACK", "topic", TOPIC), Map.of("type", "CALLBACK", "topic", CARD_TOPIC))));
        try (Response response = http.newCall(new Request.Builder().url(openUrl)
            .post(RequestBody.create(payload, MediaType.get("application/json"))).build()).execute()) {
            if (response.code() == 401 || response.code() == 403) throw new AuthenticationException();
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("connection open failed");
            JsonNode value = JSON.readTree(response.body().string());
            String endpoint = value.path("endpoint").asText();
            if (!testEndpoint && !endpoint.startsWith("wss://")) throw new IllegalArgumentException("insecure endpoint");
            HttpUrl url = HttpUrl.get(endpoint.replaceFirst("^wss:", "https:").replaceFirst("^ws:", "http:"))
                .newBuilder().addQueryParameter("ticket", value.path("ticket").asText()).build();
            return new Request.Builder().url(url).build();
        }
    }
    @Override protected void opened(WebSocket ws) { connected(); }
    @Override protected void received(WebSocket ws, JsonNode frame) throws Exception {
        String type = frame.path("type").asText();
        String topic = frame.path("headers").path("topic").asText();
        String id = frame.path("headers").path("messageId").asText();
        String data = frame.path("data").asText("{}");
        if ("SYSTEM".equals(type)) {
            ack(ws, id, data, 200);
            if ("disconnect".equalsIgnoreCase(topic)) reconnect("平台要求重新连接");
            return;
        }
        if ("CALLBACK".equals(type) && CARD_TOPIC.equals(topic)) {
            lastCardActionAt = System.currentTimeMillis();
            long count = cardCallbackCount.incrementAndGet();
            String eventKey = ImTypes.key(id).substring(0, 12);
            CardResult result;
            try {
                JsonNode value = callbackObject(frame.path("data"));
                JsonNode content = callbackObject(value.path("content"));
                String action = content.path("cardPrivateData").path("params").path("action").asText();
                if (action.isBlank()) action = content.path("cardPrivateData").path("actionIds").path(0).asText();
                CallbackSpace space = callbackSpace(value.path("spaceType").asText(""), value.path("spaceId").asText(""));
                String rawUserType = value.path("userIdType").asText("");
                String userType = rawUserType.matches("[0-9]{1,2}") ? rawUserType : rawUserType.isBlank() ? "absent" : "unknown";
                // Log only protocol shape and hashed identifiers; never frames, actor ids or callback URLs.
                String rawSpaceType = value.path("spaceType").asText("");
                String safeSpaceType = rawSpaceType.matches("[A-Za-z0-9_.:-]{0,80}") ? rawSpaceType : "non-enum";
                log.info("DingTalk card callback received: event={}, count={}, card={}, actorPresent={}, userIdType={}, rawSpaceType={}, spaceType={}, spacePresent={}, actionPresent={}",
                    eventKey, count, ImTypes.key(value.path("outTrackId").asText()).substring(0, 12),
                    !value.path("userId").asText("").isBlank(), userType, safeSpaceType, space.type(),
                    !space.id().isBlank(), !action.isBlank());
                result = !Set.of("absent", "1").contains(userType)
                    ? new CardResult(false, "卡片回调的用户身份类型与发送时不一致，请在原会话使用带编号的命令", null)
                    : actions.apply(new CardAction("dingtalk", config.clientId(), value.path("userId").asText(), space.type(),
                        space.id(), value.path("outTrackId").asText(), action, id));
            } catch (JsonProcessingException | IllegalArgumentException invalid) {
                log.warn("DingTalk card callback invalid: event={}, errorType={}", eventKey, invalid.getClass().getSimpleName());
                result = new CardResult(false, "无法识别卡片回调格式，请在原会话发送 /状态 并使用文字命令处理", null);
            } catch (Exception failure) {
                log.warn("DingTalk card callback processing failed: event={}, errorType={}", eventKey, failure.getClass().getSimpleName());
                result = new CardResult(false, "卡片处理结果未确认，请在原会话发送 /状态 查看，未自动重试", null);
            }
            lastCardAction = result.message();
            cardError = result.accepted() ? "" : result.message();
            log.info("DingTalk card callback result: event={}, accepted={}, detail={}", eventKey, result.accepted(), result.message());
            // Official CallbackHandler.raw_process wraps the callback result in data.response.
            ack(ws, id, JSON.writeValueAsString(Map.of("response", DingTalkCards.callback(result))), 200); return;
        }
        if (!"CALLBACK".equals(type) || !TOPIC.equals(topic)) { ack(ws, id, "{}", 200); return; }
        JsonNode message = JSON.readTree(data);
        String user = message.path("senderStaffId").asText(message.path("senderId").asText());
        String chat = message.path("conversationId").asText();
        String messageId = message.path("msgId").asText(id);
        String reply = message.path("sessionWebhook").asText();
        if (user.isBlank() || chat.isBlank() || messageId.isBlank() || !validReplyUrl(reply, testEndpoint)) {
            ack(ws, id, "{}", 400); return;
        }
        String text = "text".equals(message.path("msgtype").asText())
            ? message.path("text").path("content").asText().trim() : "";
        inbound.accept(new Inbound("dingtalk", config.clientId(), user, chat,
            message.path("senderNick").asText(user), messageId, text, reply,
            message.path("sessionWebhookExpiredTime").asLong(System.currentTimeMillis() + 60_000), id,
            "2".equals(message.path("conversationType").asText()) ? "group" : "1".equals(message.path("conversationType").asText()) ? "single" : "unknown"));
        // The inbox is durable before ACK; reconnect/redelivery cannot launch the task twice.
        ack(ws, id, "{}", 200);
    }
    private static JsonNode callbackObject(JsonNode value) throws JsonProcessingException {
        if (value.isTextual()) value = JSON.readTree(value.asText());
        if (value == null || !value.isObject()) throw new IllegalArgumentException("callback object required");
        return value;
    }
    record CallbackSpace(String type, String id) {}
    static CallbackSpace callbackSpace(String rawType, String rawId) {
        String type = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        String id = rawId == null ? "" : rawId;
        // Card callbacks can carry the complete openSpaceId used by createAndDeliver.
        // Only canonicalise the known space prefix; conversation/user ids remain case-sensitive and exact.
        for (String kind : List.of("IM_GROUP", "IM_ROBOT")) {
            String prefix = "dtv1.card//" + kind + ".";
            if (!id.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
            if (!type.isBlank() && !type.equals("IM") && !type.equals(kind)) throw new IllegalArgumentException("conflicting card space types");
            type = kind; id = id.substring(prefix.length());
            if (id.isBlank()) throw new IllegalArgumentException("empty card space identity");
            break;
        }
        return new CallbackSpace(switch (type) { case "" -> ""; case "IM" -> "im"; case "IM_GROUP" -> "group"; case "IM_ROBOT" -> "single"; default -> "unknown"; }, id);
    }
    @Override public void onCardAction(Function<CardAction, CardResult> handler) { actions = handler; }
    @Override public boolean sendCard(Inbound target, ImInteractionCard card) throws DeliveryException {
        try { boolean sent = cards.send(target, card); cardError = ""; return sent; }
        catch (DeliveryException error) { cardError = error.getMessage(); throw error; }
    }
    @Override public boolean updateCard(Inbound target, ImInteractionCard card) throws DeliveryException { return cards.update(card); }
    @Override public Map<String, Object> status() {
        Map<String, Object> value = new LinkedHashMap<>(super.status());
        value.put("cardMode", config.cardTemplateId().isBlank() ? "TEXT" : "NATIVE"); value.put("cardDetail", cardError);
        value.put("lastCardActionAt", lastCardActionAt); value.put("lastCardAction", lastCardAction);
        value.put("cardCallbackCount", cardCallbackCount.get()); return value;
    }
    private void ack(WebSocket ws, String id, String data, int code) throws Exception {
        sendJson(ws, Map.of("code", code, "message", code == 200 ? "OK" : "invalid message",
            "headers", Map.of("messageId", id, "contentType", "application/json"), "data", data));
    }
    static boolean validReplyUrl(String value, boolean test) {
        try {
            URI uri = URI.create(value);
            if (test && "http".equals(uri.getScheme()) && "localhost".equals(uri.getHost())) return true;
            return "https".equals(uri.getScheme()) && "oapi.dingtalk.com".equals(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                && "/robot/sendBySession".equals(uri.getPath());
        } catch (Exception e) { return false; }
    }
    @Override public void send(Inbound target, String deliveryId, String text) throws DeliveryException {
        if (!validReplyUrl(target.replyUrl(), testEndpoint))
            throw new DeliveryException(DeliveryException.Kind.PERMANENT, "回复地址无效");
        if (target.replyExpiresAt() <= System.currentTimeMillis())
            throw new DeliveryException(DeliveryException.Kind.PERMANENT, "回复窗口已过期，请在原会话发送 /结果");
        try {
            String body = JSON.writeValueAsString(Map.of("msgtype", "text", "text", Map.of("content", text)));
            Request request = new Request.Builder().url(target.replyUrl())
                .post(RequestBody.create(body, MediaType.get("application/json"))).build();
            try (Response response = http.newCall(request).execute()) {
                if (response.code() == 429) throw new DeliveryException(DeliveryException.Kind.RETRYABLE, "平台限流，稍后重试");
                if (!response.isSuccessful() || response.body() == null)
                    throw new DeliveryException(DeliveryException.Kind.PERMANENT, "平台拒绝回复（HTTP " + response.code() + "）");
                JsonNode result = JSON.readTree(response.body().string());
                if (!result.has("errcode")) throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "平台未返回发送确认");
                if (result.path("errcode").asInt(-1) != 0)
                    throw new DeliveryException(DeliveryException.Kind.PERMANENT, "平台拒绝回复，错误码 " + result.path("errcode").asText());
            }
        } catch (DeliveryException e) { throw e; }
        catch (Exception e) { throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "发送结果未知，请在原会话发送 /结果 查看"); }
    }
    /** DingTalk Robot 1.0 BatchSendOTO / OrgGroupSend; does not use an expiring session webhook. */
    @Override public void sendNotification(Inbound target, String deliveryId, String text) throws DeliveryException {
        if (!Set.of("single", "group").contains(target.chatType()))
            throw new DeliveryException(DeliveryException.Kind.PERMANENT, "会话类型未知，请在目标会话给机器人发送一条消息后重新选择通知会话");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("robotCode", config.clientId()); body.put("msgKey", "sampleText");
        try { body.put("msgParam", JSON.writeValueAsString(Map.of("content", text))); }
        catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
        boolean group = "group".equals(target.chatType());
        body.put(group ? "openConversationId" : "userIds", group ? target.chatId() : List.of(target.userId()));
        JsonNode result = api.call("POST", group ? "/v1.0/robot/groupMessages/send" : "/v1.0/robot/oToMessages/batchSend", body);
        if (!result.path("invalidStaffIdList").isEmpty() || !result.path("filteredStaffIdList").isEmpty())
            throw new DeliveryException(DeliveryException.Kind.PERMANENT, "机器人无法向所选用户发送通知，请检查用户标识和应用可见范围");
        if (!result.path("flowControlledStaffIdList").isEmpty())
            throw new DeliveryException(DeliveryException.Kind.RETRYABLE, "用户消息限流，稍后重试");
        if (result.path("processQueryKey").asText().isBlank())
            throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "平台未返回通知发送确认，请在目标会话核对");
    }
}
