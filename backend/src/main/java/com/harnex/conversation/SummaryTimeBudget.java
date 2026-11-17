package com.harnex.conversation;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounds auxiliary summary work only; exhaustion never cancels or finalizes the owning task. */
public final class SummaryTimeBudget {
    private final long started;
    private final long limitMs;
    private final LongSupplier remainingTaskMs;
    private final LongSupplier ticker;

    public SummaryTimeBudget(long limitMs, LongSupplier remainingTaskMs) {
        this(limitMs, remainingTaskMs, System::nanoTime);
    }

    SummaryTimeBudget(long limitMs, LongSupplier remainingTaskMs, LongSupplier ticker) {
        this.limitMs = Math.max(1, limitMs);
        this.remainingTaskMs = remainingTaskMs;
        this.ticker = ticker;
        this.started = ticker.getAsLong();
    }

    public long remainingMillis() {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(Math.max(0, ticker.getAsLong() - started));
        return Math.max(0, Math.min(limitMs - elapsed, remainingTaskMs.getAsLong()));
    }

    public long attemptMillis(long ceilingMs) {
        return Math.min(remainingMillis(), Math.max(1, ceilingMs));
    }
}
