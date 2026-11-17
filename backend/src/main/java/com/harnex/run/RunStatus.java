package com.harnex.run;

public enum RunStatus {
    CREATED,
    QUEUED,
    RUNNING,
    PAUSE_REQUESTED,
    PAUSED,
    WAITING_RETRY,
    READY_FOR_REVIEW,
    COMPLETED,
    INCOMPLETE,
    BLOCKED,
    BUDGET_LIMITED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    EXPIRED;

    public boolean isTerminal() {
        return this == COMPLETED || this == INCOMPLETE || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
