package com.harnex.gateway.im.wecom;

import com.harnex.gateway.im.ImSocket;
import com.harnex.gateway.im.ImTypes;
import com.harnex.gateway.im.ImInteractionCard;

import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.gateway.im.ImTypes.*;
import com.harnex.gateway.im.ImTypes.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import okhttp3.*;

import lombok.extern.slf4j.Slf4j;

/** Native WeCom AI-bot long connection, independent of legacy group webhook callbacks. */
@Slf4j
public final class WeComTransport extends ImSocket {
    private final String url;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private String authId;
    private volatile long lastPong;
    private ScheduledFuture<?> heartbeat;
    private Function<CardAction, CardResult> actions = action -> new CardResult(false, "交互处理器未就绪", null);
    private record CardUpdate(String cardId, int revision, CompletableFuture<Boolean> ack) {}
    private final Map<String, CardUpdate> updateRequests = new ConcurrentHashMap<>();
    private final Map<String, CardUpdate> cardUpdates = new ConcurrentHashMap<>();
    private volatile String cardError = "";
    private volatile long lastCardActionAt;
    private volatile String lastCardAction = "";
    private final AtomicLong cardCallbackCount = new AtomicLong();
    public WeComTransport(Connection config, OkHttpClient http, Consumer<Inbound> inbound) {
        this(config, http, inbound, "wss://openws.work.weixin.qq.com");
    }
    WeComTransport(Connection config, OkHttpClient http, Consumer<Inbound> inbound, String url) {
        super(config, http, inbound); this.url = url;
    }
    @Override protected Request request() { return new Request.Builder().url(url).build(); }
    @Override protected void opened(WebSocket ws) throws Exception {
        state = "AUTHENTICATING"; authId = "auth_" + ImTypes.id();
        sendJson(ws, Map.of("cmd", "aibot_subscribe", "headers", Map.of("req_id", authId),
            "body", Map.of("bot_id", config.clientId(), "secret", config.clientSecret())));
    }
    @Override protected void received(WebSocket ws, JsonNode frame) throws Exception {
        String requestId = frame.path("headers").path("req_id").asText();
        if (frame.has("errcode")) {
            if (requestId.equals(authId)) {
                if (frame.path("errcode").asInt(-1) != 0) { authenticationFailed(); return; }
                connected(); lastPong = System.currentTimeMillis();
                if (heartbeat != null) heartbeat.cancel(false);
                heartbeat = timer.scheduleWithFixedDelay(() -> {
                    try {
                        WebSocket current = socket;
                        if (current == null || !"CONNECTED".equals(state)) return;
                        if (System.currentTimeMillis() - lastPong > 75_000) { reconnect("心跳超时，正在重连"); return; }
                        sendJson(current, Map.of("cmd", "ping", "headers", Map.of("req_id", "ping_" + ImTypes.id())));
                    } catch (Exception e) { reconnect("心跳失败，正在重连"); }
                }, 25, 25, TimeUnit.SECONDS);
            } else if (requestId.startsWith("ping_") && frame.path("errcode").asInt(-1) == 0) {
                lastPong = System.currentTimeMillis();
            } else {
                CardUpdate update = updateRequests.remove(requestId);
                if (update != null) {
                    int code = frame.path("errcode").asInt(-1);
                    cardError = code == 0 ? "" : "卡片状态更新失败，平台错误码 " + code + "；处理结果将通过文字通知。";
                    update.ack.complete(code == 0);
                    log.info("WeCom card update result: event={}, accepted={}, code={}", ImTypes.key(requestId).substring(0, 12), code == 0, code);
                }
                CompletableFuture<JsonNode> ack = pending.remove(requestId);
                if (ack != null) ack.complete(frame);
            }
            return;
        }
        if (!"CONNECTED".equals(state)) return;
        if ("aibot_event_callback".equals(frame.path("cmd").asText())) {
            String event = frame.path("body").path("event").path("eventtype").asText();
            if ("disconnected_event".equals(event)) {
                authenticationFailed(); detail = "机器人在另一处建立了连接，请关闭另一实例后重新连接";
            }
            if ("template_card_event".equals(event)) {
                lastCardActionAt = System.currentTimeMillis();
                long count = cardCallbackCount.incrementAndGet();
                JsonNode body = frame.path("body");
                String user = body.path("from").path("userid").asText();
                String type = chatType(body);
                String chat = "single".equals(type) ? user : body.path("chatid").asText();
                JsonNode envelope = body.path("event");
                // Native callbacks nest the fields under template_card_event. Some SDK examples flatten them.
                JsonNode payload = envelope.has("template_card_event") ? envelope.path("template_card_event") : envelope;
                String cardId = payload.path("task_id").asText();
                String key = payload.path("event_key").asText();
                String action = !cardId.isBlank() && key.startsWith(cardId + ":") ? key.substring(cardId.length() + 1) : "";
                String eventId = body.path("msgid").asText(requestId);
                String eventHash = ImTypes.key(requestId).substring(0, 12);
                log.info("WeCom card callback received: event={}, count={}, nested={}, cardPresent={}, actorPresent={}, actionPresent={}",
                    eventHash, count, envelope.has("template_card_event"), !cardId.isBlank(), !user.isBlank(), !action.isBlank());
                CardResult result;
                try {
                    result = user.isBlank() || cardId.isBlank() || action.isBlank() || eventId.isBlank()
                        ? new CardResult(false, "卡片点击缺少有效的请求或按钮标识，请在原会话发送 /状态", null)
                        : actions.apply(new CardAction("wecom", config.clientId(), user, type, chat, cardId, action, eventId));
                } catch (Exception failure) {
                    log.warn("WeCom card callback processing failed: event={}, errorType={}", eventHash, failure.getClass().getSimpleName());
                    result = new CardResult(false, "本次卡片操作处理失败，请在原会话发送 /状态 查看当前请求", null);
                }
                lastCardAction = result.message();
                log.info("WeCom card callback result: event={}, accepted={}, detail={}", eventHash, result.accepted(), result.message());
                if (cardId.isBlank() || user.isBlank()) return;
                Map<String, Object> updated = result.card() == null
                    ? Map.of("card_type", "text_notice", "main_title", Map.of("title", result.message()), "task_id", cardId)
                    : WeComCards.body(result.card());
                if (result.card() != null && !result.card().pending()) {
                    CardUpdate update = new CardUpdate(cardId, result.card().revision(), new CompletableFuture<>());
                    updateRequests.put(requestId, update); cardUpdates.put(cardId, update);
                    if (cardUpdates.size() > 1000) cardUpdates.entrySet().removeIf(entry -> entry.getValue().ack.isDone() && !entry.getKey().equals(cardId));
                }
                sendJson(ws, Map.of("cmd", "aibot_respond_update_msg", "headers", Map.of("req_id", requestId),
                    "body", Map.of("response_type", "update_template_card", "template_card", updated, "userids", List.of(user))));
            }
            return;
        }
        if (!"aibot_msg_callback".equals(frame.path("cmd").asText())) return;
        JsonNode body = frame.path("body");
        String user = body.path("from").path("userid").asText();
        String chat = body.path("chatid").asText();
        if ("single".equals(chatType(body))) chat = user;
        String id = body.path("msgid").asText();
        if (user.isBlank() || chat.isBlank() || id.isBlank()) return;
        String type = body.path("msgtype").asText();
        String text = "text".equals(type) || "voice".equals(type) ? body.path(type).path("content").asText().trim() : "";
        inbound.accept(new Inbound("wecom", config.clientId(), user, chat, user, id, text, "", 0, requestId, chatType(body)));
    }
    private static String chatType(JsonNode body) {
        String explicit = body.path("chattype").asText();
        // WeCom event callbacks make chattype optional; chatid is supplied only for a group.
        return explicit.isBlank() ? body.path("chatid").asText().isBlank() ? "single" : "group" : explicit;
    }
    @Override public void onCardAction(Function<CardAction, CardResult> handler) { actions = handler; }
    @Override public boolean sendCard(Inbound target, ImInteractionCard card) throws DeliveryException {
        try {
            sendBody("card_" + card.id(), Map.of("chatid", target.chatId(), "msgtype", "template_card", "template_card", WeComCards.body(card)));
            cardError = ""; return true;
        } catch (DeliveryException error) { cardError = error.getMessage(); throw error; }
    }
    @Override public boolean updateCard(Inbound target, ImInteractionCard card) {
        CardUpdate update = cardUpdates.get(card.id());
        if (update == null || update.revision < card.revision()) return false;
        try { return update.ack.get(3, TimeUnit.SECONDS); }
        catch (Exception e) { return false; }
    }
    @Override public Map<String, Object> status() {
        Map<String, Object> value = new LinkedHashMap<>(super.status());
        value.put("cardMode", "NATIVE"); value.put("cardDetail", cardError);
        value.put("lastCardActionAt", lastCardActionAt); value.put("lastCardAction", lastCardAction);
        value.put("cardCallbackCount", cardCallbackCount.get()); return value;
    }
    @Override public void send(Inbound target, String deliveryId, String text) throws DeliveryException {
        sendBody("send_" + deliveryId, Map.of("chatid", target.chatId(), "msgtype", "markdown", "markdown", Map.of("content", text)));
    }
    private void sendBody(String requestId, Map<String, Object> body) throws DeliveryException {
        WebSocket current = socket;
        if (current == null || !"CONNECTED".equals(state))
            throw new DeliveryException(DeliveryException.Kind.RETRYABLE, "连接未就绪，等待重连");
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        pending.put(requestId, result);
        try {
            sendJson(current, Map.of("cmd", "aibot_send_msg", "headers", Map.of("req_id", requestId), "body", body));
            JsonNode ack = result.get(5, TimeUnit.SECONDS);
            if (ack.path("errcode").asInt(-1) != 0)
                throw new DeliveryException(DeliveryException.Kind.PERMANENT, "平台拒绝回复，错误码 " + ack.path("errcode").asText());
        } catch (DeliveryException e) { throw e; }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "未收到发送回执，请在原会话发送 /结果 查看");
        } finally { pending.remove(requestId, result); }
    }
    @Override protected void disconnected() {
        if (heartbeat != null) heartbeat.cancel(false);
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("disconnected")));
        pending.clear();
        updateRequests.values().forEach(update -> update.ack.complete(false)); updateRequests.clear();
    }
}
