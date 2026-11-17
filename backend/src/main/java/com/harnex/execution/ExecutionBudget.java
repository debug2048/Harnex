package com.harnex.execution;

import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-flow admission counters. Work already sent upstream is never refunded. */
public final class ExecutionBudget {
    private final int maxModelCalls;
    private final int maxImageOperations;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger imageOperations = new AtomicInteger();

    public ExecutionBudget(int maxModelCalls, int maxImageOperations) {
        if (maxModelCalls < 0 || maxImageOperations < 0) throw new IllegalArgumentException("negative budget");
        this.maxModelCalls = maxModelCalls;
        this.maxImageOperations = maxImageOperations;
    }

    public void acquireModelCall() { acquire(modelCalls, maxModelCalls, "model call"); }
    public void acquireImageOperation() { acquire(imageOperations, maxImageOperations, "image operation"); }
    public int modelCalls() { return modelCalls.get(); }
    public int imageOperations() { return imageOperations.get(); }

    private static void acquire(AtomicInteger counter, int limit, String kind) {
        while (true) {
            int current = counter.get();
            if (current >= limit) throw new IllegalStateException(kind + " budget exhausted");
            if (counter.compareAndSet(current, current + 1)) return;
        }
    }
}
