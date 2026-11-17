package com.harnex.execution;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import com.harnex.model.StreamEvent;
import com.harnex.run.RunControlSignal;

/** Public execution context. Agent state, snapshots and tools remain within the Agent adapter. */
public record RunExecutionContext(String scopeId, String conversationId, String turnId, String runId,
    RunControlSignal control, Instant deadline, ExecutionBudget budget, Consumer<StreamEvent> events) {
    public RunExecutionContext {
        Objects.requireNonNull(scopeId);
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(turnId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(control);
        Objects.requireNonNull(deadline);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(events);
    }

    public void checkActive() {
        if (control.isRunCancelRequested() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("run cancelled");
        }
        if (!Instant.now().isBefore(deadline)) throw new CancellationException("run deadline exceeded");
    }

    /** The callback must cancel the actual network call; closing the registration prevents stale callbacks. */
    public AutoCloseable onCancel(Runnable cancel) { return control.onRunCancel(cancel); }
}
