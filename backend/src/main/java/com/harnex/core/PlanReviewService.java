package com.harnex.core;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.interaction.InteractionStateBoundary;

import jakarta.annotation.PreDestroy;

/**
 * Suspend/resume coordination for the {@code submit_plan} → user-review → resume round-trip. A plan review resolves to
 * {@link PlanDecision}: APPROVE, or REJECT carrying the user's feedback for the model to revise against.
 *
 * <p>
 * Uses {@link PendingSlots} for the mechanics — independent of {@code ApprovalService} (whose future is bound to its
 * ALLOW/ASK/DENY enum) and of the future ask_user {@code UserPromptService} (a sibling using the same pattern).
 * </p>
 */
@Component
@ProjectRuntime
public class PlanReviewService {

    /** APPROVE (execute) or REJECT (stay in plan-mode, revise against {@code feedback}). */
    public record PlanDecision(boolean approved, String feedback) {
        public static PlanDecision approve() {
            return new PlanDecision(true, null);
        }

        public static PlanDecision reject(String feedback) {
            return new PlanDecision(false, feedback);
        }
    }

    private final PendingSlots<PlanDecision> slots = new PendingSlots<>("plan-review-cleaner");
    @Autowired
    public void setInteractionBoundary(InteractionStateBoundary boundary) { slots.setBoundary(boundary, "plan_review"); }

    public void registerPending(String threadId, String callId) {
        slots.registerPending(threadId, callId);
    }

    /** Variant capturing the plan markdown so pending-list clients can render the review (docs/03 §3.14). */
    public void registerPending(String threadId, String callId, Map<String, Object> payload) {
        slots.registerPending(threadId, callId, payload);
    }

    /** Unresolved plan reviews (optionally filtered by thread), oldest first. */
    public List<PendingSlots.PendingView> listPending(String threadIdOrNull) {
        return slots.listPending(threadIdOrNull);
    }

    public PlanDecision await(String threadId, String callId, long timeoutMs, BooleanSupplier stopped) {
        return slots.await(threadId, callId, timeoutMs, stopped);
    }

    public PlanDecision await(String threadId, String callId, long timeoutMs) {
        return slots.await(threadId, callId, timeoutMs);
    }

    /** Record a user decision. Returns {@code false} if no review is pending (expired / already decided / unknown). */
    public boolean record(String threadId, String callId, PlanDecision decision) {
        return slots.complete(threadId, callId, decision);
    }

    /**
     * Cancel all pending plan-reviews for the given thread by completing them with {@code null}. This wakes any tool
     * thread blocked in {@link #await} so it can observe that plan-mode was disabled.
     */
    public void cancelPendingForThread(String threadId) {
        slots.purgeThread(threadId);
    }

    public int supersedePendingForThread(String threadId) {
        int count = slots.listPending(threadId).size();
        slots.purgeThread(threadId);
        return count;
    }

    public void purgeThread(String threadId) {
        slots.purgeThread(threadId);
    }

    @PreDestroy
    public void close() {
        slots.shutdown();
    }
}
