package com.harnex.middleware;

import com.harnex.core.AgentCtx;

/**
 * DeerFlow-inspired middleware hook. Ordered by {@link #order()} ascending for before/afterLlm/afterTools, descending
 * for after.
 */
public interface Middleware {

    /** Called once before the agent loop starts. */
    default void before(AgentCtx ctx) {}

    /** Called after each LLM response (incl. those that trigger tool calls). */
    default void afterLlm(AgentCtx ctx) {}

    /** Called after each tool execution batch completes. */
    default void afterTools(AgentCtx ctx) {}

    /** Called once at the very end (reverse order). */
    default void after(AgentCtx ctx) {}

    /**
     * Called before each individual tool is executed. Middleware can inspect/modify arguments via
     * {@code event.setParsedArguments()}, or veto execution by calling {@code event.setVetoed(true)}.
     */
    default void preToolCall(ToolCallEvent event) {}

    /**
     * Called after each individual tool execution completes (success or error). Middleware can inspect/modify the
     * result via {@code event.setResult()}.
     */
    default void postToolCall(ToolCallEvent event) {}

    /** Called when an unrecoverable error occurs in the agent loop. */
    default void onError(AgentCtx ctx, Exception error) {}

    /** Lower value runs first. */
    int order();

    /**
     * Critical middleware whose {@code before} failure must abort the agent run rather than be silently swallowed.
     * Default false — most middleware (memory injection, title generation, todo refresh) is best-effort and a failure
     * should not kill the user's request. Override and return true for middleware that establishes a required invariant
     * (e.g. system-prompt enrichment, sandbox preparation, security guardrail).
     */
    default boolean isCritical() {
        return false;
    }
}
