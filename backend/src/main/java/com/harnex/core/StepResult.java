package com.harnex.core;

/** An execution result, independent of transport events and of Goal completion arbitration. */
public record StepResult(String runId, String stepId, Reason reason, String output, String detail,
    String errorCode, long totalTokens, boolean persisted) {
    public StepResult {
        if (stepId == null || stepId.isBlank() || reason == null || totalTokens < 0) {
            throw new IllegalArgumentException("invalid step result");
        }
        output = output == null ? "" : output;
    }
    public enum Reason {
        RETURNED, STEP_LIMIT, RUN_BUDGET, NEEDS_INPUT, PAUSED, SUPERSEDED, CANCELLED, FAILED
    }

    public boolean incomplete() {
        return reason == Reason.STEP_LIMIT || reason == Reason.RUN_BUDGET || reason == Reason.NEEDS_INPUT;
    }
}
