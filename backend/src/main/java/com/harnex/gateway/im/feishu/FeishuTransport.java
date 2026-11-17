package com.harnex.gateway.im.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.gateway.im.ImInteractionCard;
import com.harnex.gateway.im.ImSocket;
import com.harnex.gateway.im.ImTypes;
import com.harnex.gateway.im.ImTypes.*;
import com.harnex.gateway.im.ImTypes.Connection;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import okhttp3.*;

import okio.ByteString;

import lombok.extern.slf4j.Slf4j;

/** Feishu wire adapter: app-authenticated WebSocket events in, replies to the originating chat out. */
@Slf4j
public final class FeishuTransport extends ImSocket {
    private final FeishuApi api;
    private final CardMessages cards;
    private final boolean localFixture;
    private final FeishuFrames.Fragments fragments = new FeishuFrames.Fragments();
    private Function<CardAction, CardResult> actions = action -> new CardResult(false, "交互处理器未就绪", null);
    private String botId = "";
    private volatile String identityError = "";
    private String setupError = "";
    private volatile String cardError = "";
    private volatile long lastCardActionAt;
    private volatile String lastCardAction = "";
    private final AtomicLong cardCallbackCount = new AtomicLong();
    private volatile int pingSeconds = 120;
    private volatile long lastPong;
    private int serviceId;
    private long nextPing;
    private ScheduledFuture<?> heartbeat;

    public FeishuTransport(Connection config, OkHttpClient http, Consumer<Inbound> inbound, CardMessages cards) {
        this(config, http, inbound, cards, "https://open.feishu.cn", false);
    }
    FeishuTransport(Connection config, OkHttpClient http, Consumer<Inbound> inbound, CardMessages cards, String base, boolean localFixture) {
        super(config, http, inbound); this.api = new FeishuApi(config, http, base); this.cards = cards; this.localFixture = localFixture;
    }
    @Override protected Request request() throws Exception {
        state = "AUTHENTICATING"; setupError = "";
        JsonNode endpoint;
        try { endpoint = api.endpoint().path("data"); }
        catch (DeliveryException failure) {
            setupError = failure.getMessage();
            if (failure.kind == DeliveryException.Kind.PERMANENT) throw new AuthenticationException();
            throw failure;
        }
        String address = endpoint.path("URL").asText();
        var uri = URI.create(address);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean secure = "wss".equals(uri.getScheme()) && (host.equals("feishu.cn") || host.endsWith(".feishu.cn"))
            && (uri.getPort() == -1 || uri.getPort() == 443);
        if (uri.getUserInfo() != null || !(secure || localFixture && Set.of("localhost", "127.0.0.1").contains(host)))
            throw new IllegalArgumentException("untrusted Feishu socket endpoint");
        HttpUrl url = HttpUrl.get(address.replaceFirst("^wss:", "https:").replaceFirst("^ws:", "http:"));
        serviceId = Integer.parseInt(Objects.requireNonNull(url.queryParameter("service_id")));
        configure(endpoint.path("ClientConfig"));
        // Bot info may be unavailable until the first version is published. Do not block the connection
        // needed to save the platform's long-connection subscription settings.
        try { botId = api.botId(); identityError = ""; }
        catch (DeliveryException failure) { botId = ""; identityError = "长连接可用，但群聊身份未就绪：" + failure.getMessage() + "。完成后请重新连接。"; }
        return new Request.Builder().url(address).header("User-Agent", "Harnex/IM").build();
    }
    @Override protected void authenticationFailed() { super.authenticationFailed(); if (!setupError.isBlank()) detail = setupError; }
    @Override protected void opened(WebSocket ws) {
        connected(); lastPong = System.currentTimeMillis(); nextPing = lastPong;
        if (heartbeat != null) heartbeat.cancel(false);
        heartbeat = timer.scheduleWithFixedDelay(() -> {
            if (closed || socket != ws || !"CONNECTED".equals(state)) return;
            long now = System.currentTimeMillis();
            if (now - lastPong > pingSeconds * 2500L + 15_000) { reconnect("飞书心跳超时，正在重连"); return; }
            if (now >= nextPing) {
                nextPing = now + pingSeconds * 1000L;
                if (!ws.send(ByteString.of(FeishuFrames.ping(serviceId)))) reconnect("飞书心跳发送失败，正在重连");
            }
        }, 0, 1, TimeUnit.SECONDS);
    }
    @Override protected void received(WebSocket ws, JsonNode frame) { /* Feishu events use binary frames. */ }
    @Override protected void receivedBinary(WebSocket ws, ByteString bytes) throws Exception {
        FeishuFrames frame = FeishuFrames.decode(bytes.toByteArray());
        if (frame.method() == 0) {
            if ("pong".equals(frame.header("type"))) {
                lastPong = System.currentTimeMillis();
                if (frame.payload().length > 0) configure(JSON.readTree(frame.payload()));
            }
            return;
        }
        if (frame.method() != 1) return;
        byte[] payload = fragments.accept(frame);
        if (payload == null) return;
        long started = System.currentTimeMillis();
        Map<String, Object> ack = new LinkedHashMap<>(); ack.put("code", 200);
        try {
            JsonNode envelope = JSON.readTree(payload);
            String app = envelope.path("header").path("app_id").asText();
            if (!app.isBlank() && !app.equals(config.clientId())) throw new IllegalArgumentException("wrong Feishu app");
            String type = envelope.path("header").path("event_type").asText();
            if ("im.message.receive_v1".equals(type)) receiveMessage(envelope.path("event"));
            else if ("card.action.trigger".equals(type)) {
                Map<String, Object> result = receiveCard(envelope.path("event"), envelope.path("header").path("event_id").asText(frame.header("message_id")));
                ack.put("data", Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(result)));
            }
        } catch (Exception failure) {
            ack.put("code", 500);
            log.warn("Feishu event handling failed: event={}, errorType={}", ImTypes.key(frame.header("message_id")).substring(0, 12), failure.getClass().getSimpleName());
        }
        // Acceptance is durable before this ACK. Card callback data is base64 JSON, matching the official SDK.
        if (!ws.send(ByteString.of(frame.reply(JSON.writeValueAsBytes(ack), System.currentTimeMillis() - started))))
            throw new IllegalStateException("Feishu ACK queue closed");
    }
    private void receiveMessage(JsonNode event) throws Exception {
        JsonNode message = event.path("message"), sender = event.path("sender");
        String user = sender.path("sender_id").path("open_id").asText();
        String chat = message.path("chat_id").asText(), id = message.path("message_id").asText();
        String chatType = message.path("chat_type").asText();
        if (!"user".equals(sender.path("sender_type").asText()) || user.isBlank() || chat.isBlank() || id.isBlank()
            || !Set.of("p2p", "group").contains(chatType)) return;
        List<String> ownMentions = new ArrayList<>();
        for (JsonNode mention : message.path("mentions")) {
            if (!botId.isBlank() && botId.equals(mention.path("id").path("open_id").asText())) {
                String key = mention.path("key").asText(); if (!key.isBlank()) ownMentions.add(key);
            }
        }
        if ("group".equals(chatType) && ownMentions.isEmpty()) return;
        String text = "";
        if ("text".equals(message.path("message_type").asText())) {
            JsonNode content = JSON.readTree(message.path("content").asText("{}"));
            text = content == null ? "" : content.path("text").asText();
            for (String mention : ownMentions) text = text.replace(mention, "");
        }
        inbound.accept(new Inbound("feishu", config.clientId(), user, chat, user, id, text.trim(), "", 0, id,
            "p2p".equals(chatType) ? "single" : "group"));
    }
    private Map<String, Object> receiveCard(JsonNode event, String eventId) {
        lastCardActionAt = System.currentTimeMillis(); cardCallbackCount.incrementAndGet();
        String user = event.path("operator").path("open_id").asText();
        String chat = event.path("context").path("open_chat_id").asText();
        String messageId = event.path("context").path("open_message_id").asText();
        JsonNode value = event.path("action").path("value");
        String cardId = value.path("card_id").asText(), action = value.path("action").asText();
        CardResult result;
        try {
            result = user.isBlank() || chat.isBlank() || messageId.isBlank() || cardId.isBlank() || action.isBlank() || eventId.isBlank()
                || !"im_message".equals(event.path("host").asText()) || !messageId.equals(cards.messageId(cardId))
                ? new CardResult(false, "卡片与当前请求不一致，请在原会话发送 /状态", null)
                : actions.apply(new CardAction("feishu", config.clientId(), user, "im", chat, cardId, action, eventId));
        } catch (Exception failure) {
            log.warn("Feishu card callback failed: event={}, errorType={}", ImTypes.key(eventId).substring(0, 12), failure.getClass().getSimpleName());
            result = new CardResult(false, "本次卡片操作处理失败，请在原会话发送 /状态", null);
        }
        lastCardAction = result.message();
        log.info("Feishu card callback result: event={}, accepted={}, detail={}", ImTypes.key(eventId).substring(0, 12), result.accepted(), result.message());
        return FeishuCards.response(result);
    }
    private void configure(JsonNode config) { if (config != null && config.has("PingInterval")) pingSeconds = Math.max(5, Math.min(300, config.path("PingInterval").asInt(120))); }
    @Override public void onCardAction(Function<CardAction, CardResult> handler) { actions = handler; }
    @Override public void send(Inbound target, String id, String text) throws DeliveryException { api.send(target.chatId(), "text", Map.of("text", text), id); }
    @Override public boolean sendCard(Inbound target, ImInteractionCard card) throws DeliveryException {
        try {
            String id = api.send(target.chatId(), "interactive", FeishuCards.body(card), card.id());
            try { cards.rememberMessage(card.id(), id); }
            catch (RuntimeException failure) { throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "飞书卡片已发送，但消息关联未保存；请使用文字编号处理"); }
            cardError = ""; return true;
        } catch (DeliveryException failure) { cardError = failure.getMessage(); throw failure; }
    }
    @Override public boolean updateCard(Inbound target, ImInteractionCard card) throws DeliveryException {
        String id = cards.messageId(card.id());
        if (id == null || id.isBlank()) return false;
        try { api.update(id, FeishuCards.body(card)); cardError = ""; return true; }
        catch (DeliveryException failure) { cardError = failure.getMessage(); throw failure; }
    }
    @Override public Map<String, Object> status() {
        Map<String, Object> value = new LinkedHashMap<>(super.status());
        if ("CONNECTED".equals(state) && !identityError.isBlank()) value.put("detail", identityError);
        value.put("cardMode", "NATIVE"); value.put("cardDetail", cardError);
        value.put("lastCardActionAt", lastCardActionAt); value.put("lastCardAction", lastCardAction); value.put("cardCallbackCount", cardCallbackCount.get());
        return value;
    }
    @Override protected void disconnected() { if (heartbeat != null) heartbeat.cancel(false); fragments.clear(); }
}
