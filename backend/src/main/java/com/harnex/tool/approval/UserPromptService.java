package com.harnex.tool.approval;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.PendingSlots;
import com.harnex.interaction.InteractionStateBoundary;

import jakarta.annotation.PreDestroy;

/**
 * Suspend/resume coordination for the {@code ask_user} → user-answers → resume round-trip. The agent emits a
 * {@code user_question} event and blocks here until the user replies over {@code POST /api/question/...} (WebUI) or
 * stdin (CLI); the resolved value is the free-text / selected answer.
 *
 * <p>
 * Uses {@link PendingSlots} for the mechanics — a sibling of {@link com.harnex.core.PlanReviewService}. Kept separate
 * from {@link ApprovalService} on purpose: approval resolves to a safety enum (ALLOW/ASK/DENY) with dangerous-pattern +
 * whitelist logic, whereas this resolves to arbitrary user text.
 * </p>
 */
@Component
@ProjectRuntime
public class UserPromptService {

    private final PendingSlots<String> slots = new PendingSlots<>("ask-user-cleaner");
    @Autowired
    public void setInteractionBoundary(InteractionStateBoundary boundary) { slots.setBoundary(boundary, "question"); }

    /** Pre-register the waiter BEFORE emitting the event (closes the synchronous-CLI race; mirrors ApprovalService). */
    public void registerPending(String threadId, String callId) {
        slots.registerPending(threadId, callId);
    }

    /** Variant capturing the question payload so pending-list clients can render it (docs/03 §3.14). */
    public void registerPending(String threadId, String callId, Map<String, Object> payload) {
        slots.registerPending(threadId, callId, payload);
    }

    /** Unresolved questions (optionally filtered by thread), oldest first. */
    public List<PendingSlots.PendingView> listPending(String threadIdOrNull) {
        return slots.listPending(threadIdOrNull);
    }

    /** Block until an answer arrives or the timeout elapses; returns {@code null} on timeout/interruption. */
    public String await(String threadId, String callId, long timeoutMs, BooleanSupplier stopped) {
        return slots.await(threadId, callId, timeoutMs, stopped);
    }

    public String await(String threadId, String callId, long timeoutMs) {
        return slots.await(threadId, callId, timeoutMs);
    }

    /**
     * Record the user's answer. Returns {@code false} if no question is pending (expired / already answered / unknown)
     * so the controller can surface a 410.
     */
    public boolean recordAnswer(String threadId, String callId, String value) {
        return slots.complete(threadId, callId, value == null ? "" : value);
    }

    public void purgeThread(String threadId) {
        slots.purgeThread(threadId);
    }

    public int supersedePendingForThread(String threadId) {
        int count = slots.listPending(threadId).size();
        slots.purgeThread(threadId);
        return count;
    }

    @PreDestroy
    public void close() {
        slots.shutdown();
    }
}
