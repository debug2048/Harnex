package com.harnex.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import com.harnex.config.ProjectRuntime;
import com.harnex.execution.RunExecutionContext;
import com.harnex.run.ActiveAgentRegistry;
import com.harnex.run.RunControlSignal;
import com.harnex.run.RunCoordinator.RunLease;
import com.harnex.run.RunWorker;

/** Agent-only in-memory contexts, shared by Agent execution and control adapters. */
@Component
@ProjectRuntime
public final class AgentExecutionRegistry {
    private final ActiveAgentRegistry active;
    private final ConcurrentHashMap<String, Binding> bindings = new ConcurrentHashMap<>();

    public AgentExecutionRegistry(ActiveAgentRegistry active) { this.active = active; }

    public Binding bind(RunLease lease) {
        return bindings.computeIfAbsent(lease.runId(), ignored -> {
            Binding binding = new Binding(lease);
            lease.attachWorker(binding);
            return binding;
        });
    }

    public AgentCtx context(String runId) {
        Binding binding = bindings.get(runId);
        return binding == null ? null : binding.context.get();
    }

    public Binding bind(RunExecutionContext context) {
        Binding binding = new Binding(context.runId(), context.conversationId(), context.control());
        if (bindings.putIfAbsent(context.runId(), binding) != null) throw new IllegalStateException("Agent is already executing");
        binding.cancellation = context.onCancel(binding::cancel);
        return binding;
    }

    public final class Binding implements RunWorker {
        private final String runId;
        private final String conversationId;
        private final RunControlSignal control;
        private AutoCloseable cancellation;
        private final AtomicReference<AgentCtx> context = new AtomicReference<>();
        private Binding(RunLease lease) { this(lease.runId(), lease.run().getThreadId(), lease.run().getControlSignal()); }
        private Binding(String runId, String conversationId, RunControlSignal control) {
            this.runId = runId; this.conversationId = conversationId; this.control = control;
        }
        public AtomicReference<AgentCtx> contextRef() { return context; }
        public Consumer<AgentCtx> onContextReady() {
            return ctx -> {
                ctx.bindControlSignal(control);
                context.set(ctx);
                active.register(conversationId, ctx);
            };
        }
        @Override public void cancel() { AgentCtx ctx = context.get(); if (ctx != null) ctx.cancel(); }
        @Override public void interruptExecution() { AgentCtx ctx = context.get(); if (ctx != null) ctx.interruptExecution(); }
        @Override public boolean cancelled() { AgentCtx ctx = context.get(); return ctx != null && ctx.isCancelled(); }
        @Override public boolean pauseAcknowledged() { AgentCtx ctx = context.get(); return ctx != null && ctx.isPauseAcknowledged(); }
        @Override public boolean stepCancelAcknowledged() { AgentCtx ctx = context.get(); return ctx != null && ctx.isStepCancelAcknowledged(); }
        @Override public boolean failed() { AgentCtx ctx = context.get(); return ctx != null && ctx.hasErrored(); }
        @Override public void close() {
            AgentCtx ctx = context.get();
            if (ctx != null) active.unregister(conversationId, ctx);
            bindings.remove(runId, this);
            if (cancellation != null) try { cancellation.close(); } catch (Exception ignored) {}
        }
    }
}
