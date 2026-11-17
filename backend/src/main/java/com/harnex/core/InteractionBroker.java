package com.harnex.core;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import com.harnex.run.RunControlSignal;
import com.harnex.tool.ToolContext;

/** Reply transport and cancellable waits. It does not decide tool permissions or whether a plan needs review. */
public final class InteractionBroker {
    private InteractionBroker() { }

    public static boolean available(ToolContext context) {
        return context != null && context.getOptions() != null
            && (AgentExecutionOptions.LOCAL_API.equals(context.getOptions().interactionTarget())
                || AgentExecutionOptions.IM.equals(context.getOptions().interactionTarget()));
    }

    public static String threadId(ToolContext context) {
        if (context.getRootThreadId() != null && !context.getRootThreadId().isBlank()) return context.getRootThreadId();
        return context.getThread() == null ? null : context.getThread().getId();
    }

    public static boolean stopped(ToolContext context) {
        AgentCtx agent = context == null ? null : context.getAgentContext();
        return Thread.currentThread().isInterrupted() || agent != null
            && (agent.isCancelled() || agent.hasPendingSteering()
                || agent.controlDecision() != RunControlSignal.Decision.CONTINUE
                || agent.getBudget() != null && agent.getBudget().isExhausted());
    }

    public static long timeout(ToolContext context, long configured) {
        AgentCtx agent = context.getAgentContext();
        return Math.max(1, agent == null || agent.getRuntimeBudget() == null ? configured
            : Math.min(configured, agent.getRuntimeBudget().remainingMs()));
    }

    public static long approvalTimeout(ToolContext context, long localTimeout) {
        return timeout(context, AgentExecutionOptions.IM.equals(context.getOptions().interactionTarget())
            ? 10L * 60_000 : localTimeout);
    }

    public static <T> T await(CompletableFuture<T> future, long timeoutMs, BooleanSupplier stopped)
        throws ExecutionException, InterruptedException, TimeoutException {
        long started = System.nanoTime();
        while (!stopped.getAsBoolean()) {
            long remaining = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            try { return future.get(Math.min(remaining, 100), TimeUnit.MILLISECONDS); }
            catch (TimeoutException pending) { /* Recheck cancellation without removing the pending slot. */ }
        }
        // Cooperative pause/steering must not poison the loop thread's interrupt flag.
        throw new CancellationException("interaction superseded or execution stopped");
    }
}
