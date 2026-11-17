package com.harnex.gateway.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.storage.SharedFileTransaction;
import com.harnex.execution.TextTarget;
import com.harnex.gateway.im.ImTypes.Inbound;
import com.harnex.safety.SecretRedactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Component;

/** Durable inbox and delivery ledger. Restart never re-executes an accepted Agent task. */
@Component
@ProjectRuntime
public class ImJournal implements ImTypes.CardMessages {
    public static class Receipt {
        public String id;
        public Inbound source;
        public long createdAt;
        public String state = "ACCEPTED";
        public String threadId = "";
        public String runId = "";
        public String reply = "";
        public Receipt() {}
        Receipt(Inbound source) { this.id = source.receiptKey(); this.source = source; createdAt = System.currentTimeMillis(); }
    }
    public static class Delivery {
        public String id;
        public Inbound destination;
        public String text;
        public String state = "PENDING";
        public String error = "";
        public long createdAt;
        public long nextAttempt;
        public long expiresAt;
        public int attempts;
        public String previousId = "";
        public String interactionCode = "";
        public String runId = "";
        public boolean requiresAuthorization;
        public String kind = "TEXT";
        public String notificationEvent = "";
        public ImInteractionCard card;
        public Delivery() {}
        Delivery(Inbound target, String text, long expiresAt) {
            this.id = ImTypes.id(); destination = target; this.text = text;
            this.expiresAt = expiresAt; createdAt = System.currentTimeMillis();
        }
    }
    public static class CardRecord {
        public Inbound source;
        public ImInteractionCard value;
        public String mode = "QUEUED";
        public int deliveredRevision;
        public String messageId = "";
        public CardRecord() {}
        CardRecord(Inbound source, ImInteractionCard value) { this.source = source; this.value = value; }
    }
    public static class State {
        public Map<String, Receipt> receipts = new LinkedHashMap<>();
        public Map<String, Delivery> deliveries = new LinkedHashMap<>();
        public Map<String, CardRecord> cards = new LinkedHashMap<>();
        public Map<String, TextTarget> models = new LinkedHashMap<>();
        public Map<String, String> permissionModes = new LinkedHashMap<>();
        public Map<String, List<Conversation>> conversations = new LinkedHashMap<>();
        public Map<String, String> selectedConversations = new LinkedHashMap<>();
        public Map<String, String> notificationEvents = new LinkedHashMap<>();
    }
    public record Conversation(String key, String threadId, String title, long updatedAt) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private State state;
    public ImJournal(AgentConfig cfg) {
        file = Path.of(cfg.getDataDir()).toAbsolutePath().resolve("automation/im/journal.json");
        try {
            state = Files.exists(file) ? JSON.readValue(Files.readAllBytes(file), State.class) : new State();
            boolean recovered = false;
            for (Receipt receipt : state.receipts.values()) {
                if (Set.of("ACCEPTED", "RUNNING").contains(receipt.state)) {
                    receipt.state = "INTERRUPTED";
                    receipt.reply = "Harnex 已重启，上次任务已中断，未自动重做。请检查工作区结果，再发送新的指令。";
                    recovered = true;
                }
            }
            for (Delivery delivery : state.deliveries.values()) {
                if ("SENDING".equals(delivery.state)) {
                    delivery.state = "UNKNOWN";
                    delivery.error = "发送时连接中断，结果未知；未自动重复发送";
                    recovered = true;
                    if ("CARD".equals(delivery.kind) && delivery.card != null) {
                        CardRecord card = state.cards.get(delivery.card.id());
                        if (card != null) card.mode = "CARD";
                    }
                }
            }
            for (CardRecord card : state.cards.values()) {
                if (card.value.pending()) { card.value = card.value.withState("RESTARTED"); recovered = true; }
            }
            if (recovered) persist();
        } catch (Exception e) { throw new IllegalStateException("IM journal cannot be read", e); }
    }
    public synchronized Receipt accept(Inbound source) {
        if (state.receipts.containsKey(source.receiptKey())) return null;
        compact();
        if (state.receipts.size() >= 10000) throw new IllegalStateException("IM inbox is full");
        Receipt receipt = new Receipt(source);
        state.receipts.put(receipt.id, receipt);
        try { persist(); } catch (RuntimeException e) { state.receipts.remove(receipt.id); throw e; }
        return receipt;
    }
    public synchronized TextTarget selectedModel(Inbound source) { return state.models.get(source.peerKey()); }
    public synchronized Inbound latestSource(String peerKey) {
        return state.receipts.values().stream().filter(receipt -> receipt.source.peerKey().equals(peerKey))
            .max(Comparator.comparingLong(receipt -> receipt.createdAt)).map(receipt -> receipt.source).orElse(null);
    }
    public synchronized boolean notificationRecorded(String runId, String event) {
        return event.equals(state.notificationEvents.get(runId));
    }
    /** Outbox entries and deduplication marker commit together, surviving retention of old delivered messages. */
    public synchronized void enqueueNotification(String runId, String event, Inbound target, List<String> parts) {
        if (notificationRecorded(runId, event)) return;
        compact();
        if (state.deliveries.size() + parts.size() > 10000) throw new IllegalStateException("IM delivery queue is full");
        List<String> added = new ArrayList<>();
        String previous = "";
        for (int index = 0; index < parts.size(); index++) {
            Delivery delivery = new Delivery(target, parts.get(index), 0);
            delivery.id = ImTypes.key("notification", runId, event, Integer.toString(index));
            delivery.kind = "NOTIFICATION"; delivery.runId = runId; delivery.previousId = previous;
            delivery.notificationEvent = event;
            delivery.requiresAuthorization = true;
            state.deliveries.put(delivery.id, delivery); added.add(delivery.id); previous = delivery.id;
        }
        String marker = state.notificationEvents.put(runId, event);
        try { persist(); }
        catch (RuntimeException failure) {
            added.forEach(state.deliveries::remove);
            if (marker == null) state.notificationEvents.remove(runId); else state.notificationEvents.put(runId, marker);
            throw failure;
        }
    }
    public synchronized String conversationKey(Inbound source) { return state.selectedConversations.getOrDefault(source.peerKey(), ""); }
    public synchronized List<Conversation> conversations(Inbound source) {
        return List.copyOf(state.conversations.getOrDefault(source.peerKey(), List.of()));
    }
    public synchronized void selectConversation(Inbound source, String key) {
        String previous = state.selectedConversations.put(source.peerKey(), key);
        try { persist(); }
        catch (RuntimeException failure) {
            if (previous == null) state.selectedConversations.remove(source.peerKey()); else state.selectedConversations.put(source.peerKey(), previous);
            throw failure;
        }
    }
    public synchronized void rememberConversation(Inbound source, String threadId) {
        String peer = source.peerKey(), selected = conversationKey(source);
        List<Conversation> previous = state.conversations.get(peer);
        List<Conversation> items = new ArrayList<>(previous == null ? List.of() : previous);
        Conversation old = items.stream().filter(item -> item.key().equals(selected)).findFirst().orElse(null);
        String title = old == null || !old.threadId().equals(threadId) ? SecretRedactor.redact(source.text()).replaceAll("\\s+", " ") : old.title();
        if (title.codePointCount(0, title.length()) > 60) title = title.substring(0, title.offsetByCodePoints(0, 60)) + "…";
        items.removeIf(item -> item.key().equals(selected));
        items.add(0, new Conversation(selected, threadId, title, System.currentTimeMillis()));
        if (items.size() > 100) items = new ArrayList<>(items.subList(0, 100));
        state.conversations.put(peer, items);
        try { persist(); }
        catch (RuntimeException failure) {
            if (previous == null) state.conversations.remove(peer); else state.conversations.put(peer, previous);
            throw failure;
        }
    }
    public synchronized Receipt latest(Inbound source, String threadId) {
        return state.receipts.values().stream().filter(receipt -> receipt.source.peerKey().equals(source.peerKey())
            && receipt.threadId.equals(threadId) && !receipt.runId.isBlank())
            .max(Comparator.comparingLong(receipt -> receipt.createdAt)).orElse(null);
    }
    public synchronized String permissionMode(Inbound source) { return state.permissionModes.getOrDefault(source.peerKey(), "ask"); }
    public synchronized void permissionMode(Inbound source, String mode) {
        if (!Set.of("ask", "yolo").contains(mode)) throw new IllegalArgumentException("invalid IM permission mode");
        String key = source.peerKey();
        if (!"ask".equals(mode) && !state.permissionModes.containsKey(key) && state.permissionModes.size() >= 1000)
            throw new IllegalStateException("IM permission preference limit reached");
        String previous = state.permissionModes.get(key);
        if ("ask".equals(mode)) state.permissionModes.remove(key); else state.permissionModes.put(key, mode);
        try { persist(); }
        catch (RuntimeException failure) {
            if (previous == null) state.permissionModes.remove(key); else state.permissionModes.put(key, previous);
            throw failure;
        }
    }
    public synchronized void selectModel(Inbound source, TextTarget target) {
        String key = source.peerKey();
        if (target != null && !state.models.containsKey(key) && state.models.size() >= 1000)
            throw new IllegalStateException("IM model preference limit reached");
        TextTarget previous = state.models.get(key);
        if (target == null) state.models.remove(key); else state.models.put(key, target);
        try { persist(); }
        catch (RuntimeException failure) {
            if (previous == null) state.models.remove(key); else state.models.put(key, previous);
            throw failure;
        }
    }
    public synchronized void started(String id, String threadId, String runId) {
        Receipt receipt = state.receipts.get(id);
        receipt.threadId = threadId; receipt.runId = runId; receipt.state = "RUNNING"; persist();
    }
    public synchronized void complete(String id, String reply) {
        Receipt receipt = state.receipts.get(id);
        receipt.state = "DONE"; receipt.reply = reply; persist();
    }
    public synchronized void putCard(Inbound source, ImInteractionCard card) {
        CardRecord record = state.cards.get(card.id());
        if (record == null) state.cards.put(card.id(), new CardRecord(source, card));
        else record.value = card;
        persist();
    }
    public synchronized CardRecord card(String id) { return state.cards.get(id); }
    @Override public synchronized String messageId(String cardId) {
        CardRecord card = state.cards.get(cardId);
        return card == null ? "" : card.messageId;
    }
    @Override public synchronized void rememberMessage(String cardId, String messageId) {
        CardRecord card = state.cards.get(cardId);
        if (card == null || messageId == null || messageId.isBlank()) throw new IllegalArgumentException("unknown IM card or empty message ID");
        String previous = card.messageId;
        card.messageId = messageId;
        try { persist(); } catch (RuntimeException failure) { card.messageId = previous; throw failure; }
    }
    public synchronized void cardDelivered(String id, String mode, int revision) {
        CardRecord record = state.cards.get(id);
        if (record == null) return;
        record.mode = mode; record.deliveredRevision = Math.max(record.deliveredRevision, revision); persist();
    }
    public synchronized List<CardRecord> cardUpdates() {
        return state.cards.values().stream().filter(c -> !c.value.pending() && !"QUEUED".equals(c.mode)
            && c.value.revision() > c.deliveredRevision).limit(20).toList();
    }
    public synchronized void enqueueCard(CardRecord record, boolean update) {
        String id = ImTypes.key(record.value.id(), String.valueOf(record.value.revision()), update ? "update" : "create");
        if (state.deliveries.containsKey(id)) return;
        Delivery delivery = new Delivery(record.source, record.value.text(), update ? 0 : record.value.expiresAt());
        delivery.id = id; delivery.kind = update ? "CARD_UPDATE" : "CARD"; delivery.card = record.value;
        delivery.interactionCode = update ? "" : record.value.code(); delivery.requiresAuthorization = true;
        state.deliveries.put(id, delivery); persist();
    }
    public synchronized Receipt latest(String peerKey) {
        Receipt latest = null;
        for (Receipt receipt : state.receipts.values()) {
            if (receipt.source.peerKey().equals(peerKey) && (!receipt.runId.isBlank()
                || "INTERRUPTED".equals(receipt.state) && !receipt.source.text().startsWith("/"))) latest = receipt;
        }
        return latest;
    }
    public synchronized String enqueue(Inbound target, String text, long expiresAt, String previousId,
        String interactionCode, boolean requiresAuthorization) {
        return enqueue(target, text, expiresAt, previousId, interactionCode, requiresAuthorization, "");
    }
    public synchronized String enqueue(Inbound target, String text, long expiresAt, String previousId,
        String interactionCode, boolean requiresAuthorization, String runId) {
        compact();
        if (state.deliveries.size() >= 10000) throw new IllegalStateException("IM delivery queue is full");
        Delivery delivery = new Delivery(target, text, expiresAt);
        delivery.previousId = previousId; delivery.interactionCode = interactionCode;
        delivery.requiresAuthorization = requiresAuthorization;
        delivery.runId = runId;
        state.deliveries.put(delivery.id, delivery);
        try { persist(); } catch (RuntimeException e) { state.deliveries.remove(delivery.id); throw e; }
        return delivery.id;
    }
    public synchronized List<Delivery> ready() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Delivery delivery : state.deliveries.values()) {
            Delivery previous = state.deliveries.get(delivery.previousId);
            if ("PENDING".equals(delivery.state) && previous != null
                && Set.of("UNKNOWN", "FAILED", "EXPIRED").contains(previous.state)) {
                delivery.state = "FAILED"; delivery.error = "前一段回复未确认送达，未发送后续段落"; changed = true;
            }
        }
        if (changed) persist();
        return state.deliveries.values().stream().filter(d -> "PENDING".equals(d.state) && d.nextAttempt <= now)
            .filter(d -> d.previousId.isEmpty() || state.deliveries.containsKey(d.previousId)
                && "DELIVERED".equals(state.deliveries.get(d.previousId).state))
            .limit(20).toList();
    }
    public synchronized void deliveryState(Delivery delivery, String status, String error, long retryAt) {
        delivery.state = status; delivery.error = error; delivery.nextAttempt = retryAt;
        if ("SENDING".equals(status)) delivery.attempts++;
        persist();
    }
    public synchronized List<Map<String, Object>> deliveryViews() {
        List<Delivery> deliveries = new ArrayList<>(state.deliveries.values());
        Collections.reverse(deliveries);
        return deliveries.stream().limit(30).map(d -> Map.<String, Object>of("id", d.id,
            "channel", d.destination.channel(), "userId", d.destination.userId(), "chatId", d.destination.chatId(),
            "state", d.state, "error", d.error, "createdAt", d.createdAt, "kind", d.kind, "runId", d.runId)).toList();
    }
    private void compact() {
        long cutoff = System.currentTimeMillis() - 7L * 86400_000;
        state.receipts.values().removeIf(r -> r.createdAt < cutoff && !Set.of("ACCEPTED", "RUNNING").contains(r.state));
        state.deliveries.values().removeIf(d -> d.createdAt < cutoff && !Set.of("PENDING", "SENDING").contains(d.state));
        state.cards.values().removeIf(c -> c.value.expiresAt() < cutoff && !c.value.pending());
    }
    private void persist() {
        try { SharedFileTransaction.writeAtomically(file, JSON.writeValueAsBytes(state), false, true); }
        catch (Exception e) { throw new IllegalStateException("IM journal could not be saved", e); }
    }
}
