package com.harnex.gateway.im;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.media.Schema;

/** Platform identity is kept separate from reply capabilities and Agent authority. */
public final class ImTypes {
    private ImTypes() {}
    public static final List<String> CHANNELS = List.of("dingtalk", "wecom", "feishu", "telegram");
    public static final String MASK = "••••••••";

    @Schema(name = "ImPeer")
    public record Peer(String userId, String chatId, String label, String chatType) {
        public Peer(String userId, String chatId, String label) { this(userId, chatId, label, "unknown"); }
    }

    @Schema(name = "ImConnection")
    public record Connection(boolean enabled, String clientId, String clientSecret,
        boolean allowMutations, List<Peer> peers, String cardTemplateId, boolean allowYolo) {
        public Connection {
            clientId = clientId == null ? "" : clientId.trim();
            clientSecret = clientSecret == null ? "" : clientSecret.trim();
            peers = peers == null ? List.of() : List.copyOf(peers);
            cardTemplateId = cardTemplateId == null ? "" : cardTemplateId.trim();
            allowYolo = allowYolo && allowMutations;
        }
        public Connection(boolean enabled, String clientId, String clientSecret, boolean allowMutations, List<Peer> peers, String cardTemplateId) {
            this(enabled, clientId, clientSecret, allowMutations, peers, cardTemplateId, false);
        }
        public Connection(boolean enabled, String clientId, String clientSecret, boolean allowMutations, List<Peer> peers) {
            this(enabled, clientId, clientSecret, allowMutations, peers, "");
        }
        public static Connection empty() { return new Connection(false, "", "", false, List.of()); }
        public Connection masked() {
            return new Connection(enabled, clientId, clientSecret.isEmpty() ? "" : MASK, allowMutations, peers, cardTemplateId, allowYolo);
        }
        public boolean permits(Inbound message) {
            return enabled && authorizes(message);
        }
        public boolean authorizes(Inbound message) {
            return clientId.equals(message.account()) && peers.stream().anyMatch(peer ->
                peer.userId().equals(message.userId()) && peer.chatId().equals(message.chatId()));
        }
    }

    /** replyUrl is a short-lived credential, never included in a UI view or log. */
    public record Inbound(String channel, String account, String userId, String chatId, String userName,
        String messageId, String text, String replyUrl, long replyExpiresAt, String requestId, String chatType) {
        public Inbound(String channel, String account, String userId, String chatId, String userName,
            String messageId, String text, String replyUrl, long replyExpiresAt, String requestId) {
            this(channel, account, userId, chatId, userName, messageId, text, replyUrl, replyExpiresAt, requestId,
                userId.equals(chatId) ? "single" : "group");
        }
        public Inbound {
            chatType = chatType == null ? "unknown" : chatType;
            text = text == null ? "" : text.trim();
            // Some group transports retain the bot mention. Remove only a leading mention before a control command.
            text = text.replaceFirst("^(?:(?:<@[^>]+>|@[^\\s]+)\\s+)+(?=/(?i:绑定|帮助|状态|工作区|新建|会话|继续|取消|结果|同意|拒绝|回答|详情|模型|切换模型|强度|模式|pair|help|status|workspace|new|reset|sessions|resume|cancel|result|approve|deny|answer|details|models?|effort|mode)(?:\\s|$))", "");
        }
        public String peerKey() { return key(channel, account, userId, chatId); }
        public String receiptKey() { return key(channel, account, userId, chatId, messageId); }
        @Override public String toString() { return "Inbound[" + channel + ", " + receiptKey() + "]"; }
    }

    public static String key(String... parts) {
        try {
            byte[] bytes = new ObjectMapper().writeValueAsBytes(parts);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String id() { return UUID.randomUUID().toString(); }

    public interface Transport extends AutoCloseable {
        void start();
        void send(Inbound destination, String deliveryId, String text) throws DeliveryException;
        default void sendNotification(Inbound destination, String deliveryId, String text) throws DeliveryException { send(destination, deliveryId, text); }
        default boolean sendCard(Inbound destination, ImInteractionCard card) throws DeliveryException { return false; }
        default boolean updateCard(Inbound destination, ImInteractionCard card) throws DeliveryException { return false; }
        default void onCardAction(Function<CardAction, CardResult> handler) {}
        Map<String, Object> status();
        @Override void close();
    }

    public record CardAction(String channel, String account, String userId, String spaceType, String spaceId,
        String cardId, String action, String eventId) {}
    public record CardResult(boolean accepted, String message, ImInteractionCard card) {}

    /** Platform message IDs belong to the durable card record, not a transport's reconnect-local cache. */
    public interface CardMessages {
        String messageId(String cardId);
        void rememberMessage(String cardId, String messageId);
    }

    public static class DeliveryException extends Exception {
        public enum Kind { RETRYABLE, UNKNOWN, PERMANENT }
        public final Kind kind;
        public DeliveryException(Kind kind, String message) { super(message); this.kind = kind; }
    }
}
