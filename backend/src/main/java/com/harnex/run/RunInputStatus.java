package com.harnex.run;

/** Durable RunInput lifecycle. AgentLoop consumption is introduced by S2. */
public enum RunInputStatus {
    QUEUED,
    APPLYING,
    APPLIED,
    CANCELLED,
    HELD,
    SUPERSEDED,
    REJECTED;

    public boolean isPending() {
        return this == QUEUED || this == APPLYING || this == HELD;
    }

    public boolean isWithdrawable() {
        return this == QUEUED || this == HELD;
    }
}
