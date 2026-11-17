package com.harnex.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.model.ChatMessage;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.run.IdempotencyReceiptStore;

/** Rebuildable request projections only: immutable raw message bodies remain solely in the archive. */
final class ConversationProjectionCache {
    static final int VERSION = 1;
    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();

    ConversationProjectionCache(Path directory) { this.directory = directory; }

    record StableBlock(ChatMessage message, ConversationContextPlanner.Block trace, List<ContextEvidence.Fragment> fragments) {}
    record Prefix(int version, String scope, String conversation, String key, String anchorLeaf, long anchorRevision,
        String rawStart, long rawStartRevision, List<StableBlock> blocks, String summaryId) {}
    record Tail(int version, String scope, String conversation, String key, String owner, long ownerRevision,
        String ownerHash, String text, List<HistoryReference> sources, List<ContextEvidence.Fragment> fragments) {}

    Prefix prefix(String scope, String conversation, String key) {
        Prefix value = read(path("prefix", scope, conversation, key), Prefix.class);
        return value != null && value.version() == VERSION && scope.equals(value.scope()) && conversation.equals(value.conversation())
            && key.equals(value.key()) && value.blocks() != null && value.anchorLeaf() != null ? value : null;
    }

    Tail tail(String scope, String conversation, String key, MessageRecord owner) {
        if (owner == null) return null;
        Tail value = read(path("tail", scope, conversation, key + ":" + owner.messageId()), Tail.class);
        return value != null && value.version() == VERSION && scope.equals(value.scope()) && conversation.equals(value.conversation())
            && key.equals(value.key()) && owner.messageId().equals(value.owner()) && owner.revision() == value.ownerRevision()
            && hash(owner).equals(value.ownerHash()) && value.text() != null && value.sources() != null && value.fragments() != null ? value : null;
    }

    void save(Prefix prefix) { write(path("prefix", prefix.scope(), prefix.conversation(), prefix.key()), prefix); }

    void save(String scope, String conversation, String key, MessageRecord owner, String text,
        List<HistoryReference> sources, List<ContextEvidence.Fragment> fragments) {
        if (owner == null || owner.isMutable() || owner.role() != MessageRecord.Role.USER) return;
        write(path("tail", scope, conversation, key + ":" + owner.messageId()), new Tail(VERSION, scope, conversation,
            key, owner.messageId(), owner.revision(), hash(owner), Objects.toString(text, ""), List.copyOf(sources), List.copyOf(fragments)));
    }

    private <T> T read(Path path, Class<T> type) {
        try { return Files.isRegularFile(path) ? json.readValue(Files.readAllBytes(path), type) : null; }
        catch (IOException | RuntimeException unavailable) { return null; }
    }
    private void write(Path path, Object value) {
        try { AtomicJsonFiles.write(json, path, value); }
        catch (IOException | RuntimeException unavailable) { /* Derived cache only; never block the conversation. */ }
    }
    private Path path(String kind, String scope, String conversation, String key) {
        return directory.resolve(kind).resolve(IdempotencyReceiptStore.requestHash(Map.of(
            "scope", scope, "conversation", conversation, "key", key)) + ".json");
    }
    private static String hash(MessageRecord source) { return IdempotencyReceiptStore.requestHash(Map.of("parts", source.contentParts())); }
}
