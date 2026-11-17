package com.harnex.conversation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.harnex.model.ChatMessage;
import com.harnex.run.IdempotencyReceiptStore;

/** Derived text that accompanied one model input. Replay it verbatim without rewriting its archive source. */
public record RequestContextOverlay(int version, String baseFingerprint, String text,
    Map<String, String> stateHashes, List<ContextEvidence.Fragment> evidence, List<ContextEvidence.Fragment> recallEvidence, Branch branch) {
    public static final int VERSION = 1;
    public static final String CHANNEL = "request-context";
    public static final String CARRIER = "[system_reminder] Runtime context checkpoint.";

    public RequestContextOverlay {
        stateHashes = stateHashes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stateHashes));
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        recallEvidence = recallEvidence == null ? List.of() : List.copyOf(recallEvidence);
    }
    public RequestContextOverlay(int version, String baseFingerprint, String text, Map<String, String> stateHashes,
        List<ContextEvidence.Fragment> evidence) { this(version, baseFingerprint, text, stateHashes, evidence, List.of(), null); }
    public RequestContextOverlay(int version, String baseFingerprint, String text, Map<String, String> stateHashes,
        List<ContextEvidence.Fragment> evidence, List<ContextEvidence.Fragment> recallEvidence) {
        this(version, baseFingerprint, text, stateHashes, evidence, recallEvidence, null);
    }
    public record Branch(String scopeId, String sourceMessageId, String runId) {}

    public boolean validFor(ChatMessage message) {
        return version == VERSION && text != null && !text.isBlank() && baseFingerprint != null
            && baseFingerprint.equals(fingerprint(message));
    }

    /** Only raw source fields participate. Derived text and provider-bound copies never change the source identity. */
    public static String fingerprint(ChatMessage message) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("role", message.getRole()); base.put("content", message.getContent());
        base.put("originalInput", message.getOriginalInput()); base.put("toolCallId", message.getToolCallId());
        base.put("toolCalls", message.getToolCalls()); base.put("recalledHistory", message.getRecalledHistory());
        base.put("responseOutputItems", message.getResponseOutputItems());
        base.put("responseOutputProvider", message.getResponseOutputProvider());
        base.put("attachments", message.getAttachments() == null ? List.of() : message.getAttachments().stream()
            .map(a -> Map.of("id", Objects.toString(a.getArtifactId(), ""), "mime", Objects.toString(a.getMime(), ""), "size", a.getSize())).toList());
        return IdempotencyReceiptStore.requestHash(base);
    }

    public static String hash(String value) {
        return value == null || value.isBlank() ? "" : IdempotencyReceiptStore.requestHash(Map.of("text", value));
    }

    /** Channel and a server-produced overlay are both required; user-supplied marker text alone is insufficient. */
    public static boolean isCarrier(ChatMessage message) {
        if (message == null || !"user".equals(message.getRole()) || !CHANNEL.equals(message.getChannel())) return false;
        if (message.getPreparedContext() != null) return true;
        var overlay = message.getRequestContextOverlay();
        return CARRIER.equals(message.getContent()) && overlay != null && overlay.validFor(message);
    }

    /** Runtime-only commit handle. It is deliberately excluded from persistence, equality and provider payloads. */
    public record Prepared(ChatMessage origin, ChatMessage expectedTail, RequestContextOverlay overlay, boolean appended,
        String expectedTailFingerprint) {
        public Prepared(ChatMessage origin, ChatMessage expectedTail, RequestContextOverlay overlay, boolean appended) {
            this(origin, expectedTail, overlay, appended, expectedTail == null ? null : fingerprint(expectedTail));
        }
    }
}
