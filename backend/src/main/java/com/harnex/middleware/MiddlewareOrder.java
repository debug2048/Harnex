package com.harnex.middleware;

/**
 * Canonical ordering constants for built-in {@link Middleware} implementations. Centralising the magic numbers keeps
 * the chain layout discoverable and prevents accidental collisions when a new middleware is added — pick one of the
 * named slots below, or insert a new constant in sorted position with a deliberate gap (steps of 100 leave room for
 * runtime extensions).
 *
 * <p>
 * Lower values run earlier in {@code before/afterLlm/afterTools}; the chain reverses the order for {@code after} so a
 * low-numbered middleware brackets the call (early before, late after).
 */
public final class MiddlewareOrder {

    private MiddlewareOrder() {}

    /** Loads thread metadata into the prompt context. Must run first so others see populated state. */
    public static final int THREAD_DATA = 100;

    /** Queues request-only runtime instructions. Right after thread-data so reminders see fresh state. */
    public static final int SYSTEM_REMINDER = 150;

    /** Configurable user hooks (matched by tool name / event). Runs after framework setup. */
    public static final int CONFIGURABLE_HOOK = 200;

    /**
     * Scrub secrets / PII out of tool results before they re-enter the LLM context. Sits AFTER
     * {@link #CONFIGURABLE_HOOK} so user hooks see raw tool output (for logging / vetoing on actual values) but BEFORE
     * summarisation so the redacted form is what gets compressed into the rolling summary.
     */
    public static final int SENSITIVE_DATA_REDACTION = 300;

    /** Compresses long histories. Mid-pipeline so it sees the final prompt but before observability. */
    public static final int SUMMARIZATION = 600;

    /** Generates a thread title once enough turns exist. Late stage — needs settled history. */
    public static final int TITLE = 900;

    /** Enforces sub-agent recursion / count limits. Runs near the end of the chain. */
    public static final int SUBAGENT_LIMIT = 1100;

    /** Discover external tools once a task has selected its presets, before AgentLoop resolves tool definitions. */
    public static final int MCP_PREPARATION = 1200;
}
