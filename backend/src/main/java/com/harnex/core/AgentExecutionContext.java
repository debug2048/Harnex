package com.harnex.core;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.harnex.model.StreamEvent;
import com.harnex.snapshot.SnapshotService;

/** Execution identities and inherited controls, budgets and snapshot ownership for one step. */
public record AgentExecutionContext(String runId, String stepId, Consumer<StreamEvent> events,
    AtomicReference<AgentCtx> contextRef, Consumer<AgentCtx> onContextReady, SubAgentBudget parentBudget,
    SnapshotService.SnapshotCaptureContext snapshotContext) {
    public AgentExecutionContext {
        if (stepId == null || stepId.isBlank()) stepId = UUID.randomUUID().toString();
    }

    public static AgentExecutionContext local(Consumer<StreamEvent> events) {
        return new AgentExecutionContext(null, null, events, null, null, null, null);
    }
}
