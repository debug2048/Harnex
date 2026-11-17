package com.harnex.core;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Monotonic, execution-only time budget. Waiting for a worker is accounted for by the scheduler separately. */
public final class AgentRuntimeBudget {
    private final AgentRuntimeBudget parent;
    private final LongSupplier ticker;
    private final long startedAt;
    private final long hardLimitMs;
    private final long checkpointIntervalMs;
    private long nextCheckpointMs;

    public AgentRuntimeBudget(AgentRuntimeBudget parent, long hardLimitMs, long checkpointIntervalMs) {
        this(parent, hardLimitMs, checkpointIntervalMs, System::nanoTime);
    }

    AgentRuntimeBudget(AgentRuntimeBudget parent, long hardLimitMs, long checkpointIntervalMs, LongSupplier ticker) {
        this.parent = parent;
        this.ticker = ticker;
        this.startedAt = ticker.getAsLong();
        this.hardLimitMs = Math.max(0, hardLimitMs);
        this.checkpointIntervalMs = Math.max(0, checkpointIntervalMs);
        this.nextCheckpointMs = this.checkpointIntervalMs;
    }

    public long elapsedMs() {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(ticker.getAsLong() - startedAt));
    }

    public long remainingMs() {
        long own = hardLimitMs > 0 ? Math.max(0, hardLimitMs - elapsedMs()) : Long.MAX_VALUE;
        return parent == null ? own : Math.min(own, parent.remainingMs());
    }

    /** Only the owning loop consumes checkpoints, at safe model-call boundaries. No timer interrupts healthy work. */
    public boolean checkpointDue() {
        if (checkpointIntervalMs <= 0 || elapsedMs() < nextCheckpointMs) return false;
        nextCheckpointMs = saturatedAdd(elapsedMs(), checkpointIntervalMs);
        return true;
    }

    /** Reserve up to one minute (at most 10% of a short configured limit) to hand back usable partial results. */
    public boolean shouldFinalize() {
        long reserve = hardLimitMs > 0 ? Math.min(60_000L, hardLimitMs / 10) : 0;
        return hardLimitMs > 0 && remainingMs() <= reserve || parent != null && parent.shouldFinalize();
    }

    public static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
