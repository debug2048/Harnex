package com.harnex.gateway.im.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.storage.SharedFileTransaction;
import com.harnex.gateway.im.ImInteractionCard;
import com.harnex.gateway.im.ImTypes;
import com.harnex.gateway.im.ImTypes.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import okhttp3.OkHttpClient;

/** Outbound-only long polling. The factory's bot lease also owns the shared durable update cursor. */
public final class TelegramTransport implements Transport {
    private final Connection config;
    private final Consumer<Inbound> inbound;
    private final CardMessages cards;
    private final TelegramApi api;
    private final Path cursorFile;
    private final String scopeId;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "im-telegram-poll"); thread.setDaemon(true); return thread;
    });
    private volatile Function<CardAction, CardResult> actions = a -> new CardResult(false, "连接尚未准备好", null);
    private volatile String state = "DISCONNECTED", detail = "", cardError = "", lastCardAction = "";
    private volatile long lastMessageAt, lastCardActionAt;
    private volatile boolean closed;
    private boolean started, initialized;
    private String username = "";
    private Cursor cursor;
    record Cursor(long offset, String scopeId, long discardThrough) {}

    public TelegramTransport(Connection config, OkHttpClient http, Consumer<Inbound> inbound, CardMessages cards, Path cursorFile, String scopeId) {
        this(config, http, inbound, cards, cursorFile, scopeId, "https://api.telegram.org");
    }
    TelegramTransport(Connection config, OkHttpClient http, Consumer<Inbound> inbound, CardMessages cards,
        Path cursorFile, String scopeId, String baseUrl) {
        this.config = config; this.inbound = inbound; this.cards = cards; this.cursorFile = cursorFile; this.scopeId = scopeId;
        api = new TelegramApi(http, baseUrl, config.clientSecret());
    }
    @Override public synchronized void start() {
        if (started || closed) return;
        started = true; state = "CONNECTING"; worker.execute(this::poll);
    }
    private void initialize() throws Exception {
        state = "AUTHENTICATING";
        JsonNode bot = api.call("getMe", Map.of());
        if (!bot.path("is_bot").asBoolean() || !bot.path("id").asText().equals(config.clientId()) || bot.path("username").asText().isBlank())
            throw new TelegramApi.Failure(DeliveryException.Kind.PERMANENT, 401, "Telegram 机器人身份与凭据不一致，请重新填写 Token", 0);
        username = bot.path("username").asText();
        if (!api.call("getWebhookInfo", Map.of()).path("url").asText().isBlank())
            throw new TelegramApi.Failure(DeliveryException.Kind.PERMANENT, 409, "此机器人已配置 Webhook，请先在原接入服务关闭 Webhook，再连接 Harnex", 0);
        cursor = Files.exists(cursorFile) ? TelegramApi.JSON.readValue(Files.readAllBytes(cursorFile), Cursor.class) : new Cursor(0, scopeId, 0);
        if (!scopeId.equals(cursor.scopeId())) cursor = new Cursor(cursor.offset(), scopeId, System.currentTimeMillis() / 1000);
        saveCursor(); initialized = true; state = "CONNECTED"; detail = "";
    }
    private void saveCursor() throws IOException {
        SharedFileTransaction.writeAtomically(cursorFile, TelegramApi.JSON.writeValueAsBytes(cursor), false, true);
    }
    private void poll() {
        if (closed) return;
        long delay = 100;
        try {
            if (!initialized) initialize();
            JsonNode updates = api.call("getUpdates", Map.of("offset", cursor.offset(), "timeout", 25,
                "limit", 100, "allowed_updates", List.of("message", "callback_query")));
            if (!updates.isArray()) throw new IllegalStateException("invalid update envelope");
            if (closed) return;
            state = "CONNECTED"; detail = "";
            for (JsonNode update : updates) {
                if (closed) return;
                long id = update.path("update_id").asLong(-1);
                if (id < 0 || id < cursor.offset()) continue;
                if (update.has("message")) message(update.path("message"));
                else if (update.has("callback_query")) callback(update.path("callback_query"));
                // Persist only after shared admission persisted the receipt; the next poll acknowledges this offset.
                cursor = new Cursor(id + 1, scopeId, cursor.discardThrough()); saveCursor();
            }
        } catch (TelegramApi.Failure failure) {
            if (closed) return;
            detail = failure.getMessage();
            if (failure.kind == DeliveryException.Kind.PERMANENT) { state = "ERROR"; return; }
            state = "RECONNECTING"; delay = failure.delay;
        } catch (Exception failure) {
            if (closed) return;
            state = "ERROR"; detail = "Telegram 消息处理或游标保存失败，请检查本地数据目录后重新连接"; return;
        }
        if (!closed) try { worker.schedule(this::poll, delay, TimeUnit.MILLISECONDS); } catch (RejectedExecutionException ignored) {}
    }
    private void message(JsonNode message) {
        JsonNode from = message.path("from"), chat = message.path("chat");
        String type = chat.path("type").asText(), user = from.path("id").asText(), chatId = chat.path("id").asText();
        if (from.path("is_bot").asBoolean(true) || message.has("sender_chat") || user.isBlank() || chatId.isBlank()
            || !Set.of("private", "group", "supergroup").contains(type) || message.path("message_id").asLong() <= 0
            || message.path("date").asLong() <= cursor.discardThrough()) return;
        // Forum topics have separate routing semantics; do not accidentally reply to the group's general topic.
        if (message.has("message_thread_id") || message.path("is_topic_message").asBoolean()) return;
        String text = message.path("text").asText();
        boolean addressed = type.equals("private");
        List<int[]> mentions = new ArrayList<>();
        for (JsonNode entity : message.path("entities")) {
            int offset = entity.path("offset").asInt(-1), length = entity.path("length").asInt(-1);
            if (offset < 0 || length <= 0 || offset > text.length() - length) continue;
            String value = text.substring(offset, offset + length);
            if (entity.path("type").asText().equals("mention") && value.equalsIgnoreCase("@" + username)) {
                addressed = true; mentions.add(new int[] {offset, length});
            } else if (entity.path("type").asText().equals("bot_command") && value.contains("@")) {
                String suffix = value.substring(value.lastIndexOf('@') + 1);
                if (!suffix.equalsIgnoreCase(username)) return;
                addressed = true; mentions.add(new int[] {offset + value.lastIndexOf('@'), suffix.length() + 1});
            }
        }
        if (!addressed) return;
        mentions.sort((a, b) -> Integer.compare(b[0], a[0]));
        int previous = text.length();
        for (int[] span : mentions) if (span[0] + span[1] <= previous) {
            text = text.substring(0, span[0]) + text.substring(span[0] + span[1]); previous = span[0];
        }
        text = text.trim();
        if (text.equalsIgnoreCase("/start")) text = "/help";
        lastMessageAt = System.currentTimeMillis();
        inbound.accept(new Inbound("telegram", config.clientId(), user, chatId,
            (from.path("first_name").asText() + " " + from.path("last_name").asText()).trim(),
            message.path("message_id").asText(), text, "", 0, "", type.equals("private") ? "single" : "group"));
    }
    private void callback(JsonNode query) {
        if (query.path("id").asText().isBlank()) return;
        JsonNode message = query.path("message");
        String data = query.path("data").asText(), chat = message.path("chat").path("id").asText();
        String[] parts = data.split("\\|", 2);
        CardResult result = new CardResult(false, "卡片不属于当前工作区或已失效，请发送 /workspace 查看", null);
        if (parts.length == 2 && parts[0].matches("im_[a-f0-9]{32}") && !query.path("from").path("is_bot").asBoolean(true)
            && message.path("date").asLong() > 0 && !chat.isBlank()
            && message.path("from").path("id").asText().equals(config.clientId())
            && (chat + ":" + message.path("message_id").asText()).equals(cards.messageId(parts[0]))) {
            result = actions.apply(new CardAction("telegram", config.clientId(), query.path("from").path("id").asText(),
                "im", chat, parts[0], parts[1], query.path("id").asText()));
        }
        lastCardActionAt = System.currentTimeMillis(); lastCardAction = result.message();
        try { api.call("answerCallbackQuery", Map.of("callback_query_id", query.path("id").asText(),
            "text", TelegramCards.clip(result.message(), 200), "show_alert", !result.accepted())); }
        catch (TelegramApi.Failure failure) { cardError = failure.getMessage(); }
        // The shared journal schedules the durable edit; a lost toast must not replay the decision.
    }
    @Override public void onCardAction(Function<CardAction, CardResult> handler) { actions = handler; }
    @Override public void send(Inbound target, String id, String text) throws DeliveryException {
        api.call("sendMessage", Map.of("chat_id", target.chatId(), "text", text));
    }
    @Override public boolean sendCard(Inbound target, ImInteractionCard card) throws DeliveryException {
        try {
            JsonNode sent = api.call("sendMessage", Map.of("chat_id", target.chatId(), "text", TelegramCards.text(card),
                "reply_markup", TelegramCards.keyboard(card), "protect_content", true));
            if (sent.path("message_id").asLong() <= 0) throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "Telegram 卡片发送结果缺少消息标识，请使用文字编号处理");
            try { cards.rememberMessage(card.id(), target.chatId() + ":" + sent.path("message_id").asText()); }
            catch (RuntimeException failure) { throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "Telegram 卡片已发送，但消息关联未保存，请使用文字编号处理"); }
            cardError = ""; return true;
        } catch (DeliveryException failure) { cardError = failure.getMessage(); throw failure; }
    }
    @Override public boolean updateCard(Inbound target, ImInteractionCard card) throws DeliveryException {
        String saved = cards.messageId(card.id()), prefix = target.chatId() + ":";
        if (saved == null || !saved.startsWith(prefix)) return false;
        try {
            api.call("editMessageText", Map.of("chat_id", target.chatId(), "message_id", saved.substring(prefix.length()),
                "text", TelegramCards.text(card), "reply_markup", TelegramCards.keyboard(card)));
            cardError = ""; return true;
        } catch (DeliveryException failure) { cardError = failure.getMessage(); throw failure; }
    }
    @Override public Map<String, Object> status() {
        return Map.of("state", state, "detail", detail, "lastMessageAt", lastMessageAt, "cardMode", "NATIVE",
            "cardDetail", cardError, "lastCardActionAt", lastCardActionAt, "lastCardAction", lastCardAction);
    }
    @Override public synchronized void close() {
        closed = true; api.close(); worker.shutdownNow();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        state = "DISCONNECTED";
    }
}
