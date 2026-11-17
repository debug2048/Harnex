package com.harnex.run;

import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import com.harnex.model.ConversationThread;
import com.harnex.model.StreamEvent;

/** AgentLoop-facing safe-point contract for durable Steering input. */
public interface RunInputCheckpoint {

    boolean hasPendingSteering(String runId);

    ApplyResult applySteering(String runId, ConversationThread thread, String stepId,
        Consumer<StreamEvent> emit);

    default ApplyResult applySteering(String runId, ConversationThread thread, String stepId,
        Consumer<StreamEvent> emit, BooleanSupplier mayApply) {
        if (mayApply != null && !mayApply.getAsBoolean()) return ApplyResult.none();
        return applySteering(runId, thread, stepId, emit);
    }

    default ApplyResult applySteering(String runId, ConversationThread thread, String stepId,
        Consumer<StreamEvent> emit, BooleanSupplier mayApply, Runnable beforeDurableApply) {
        ApplyResult result = applySteering(runId, thread, stepId, emit, mayApply);
        if (result.applied() && beforeDurableApply != null) beforeDurableApply.run();
        return result;
    }

    record ApplyResult(int claimedCount, int appliedCount, int rejectedCount, boolean recovered) {
        public static ApplyResult none() { return new ApplyResult(0, 0, 0, false); }
        public boolean applied() { return appliedCount > 0; }
    }

    static RunInputCheckpoint noop() {
        return new RunInputCheckpoint() {
            @Override public boolean hasPendingSteering(String runId) { return false; }
            @Override public ApplyResult applySteering(String runId, ConversationThread thread, String stepId,
                Consumer<StreamEvent> emit) { return ApplyResult.none(); }
        };
    }
}
