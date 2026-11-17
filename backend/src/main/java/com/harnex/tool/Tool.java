package com.harnex.tool;

import java.util.Map;
import java.util.Set;

/**
 * Tool SPI. Inspired by Claude Code's Tool interface plus Hermes Agent capability declarations.
 *
 * <h2>Cancellation contract</h2>
 * <p>
 * The executor enforces every tool call with two layers of timeout: an inner per-thread {@link Thread#interrupt()}
 * watchdog, and an outer {@link java.util.concurrent.Future#cancel} deadline. Implementations <strong>MUST</strong>:
 * <ul>
 * <li>Treat {@link InterruptedException} (or any {@code instanceof InterruptedException} wrapped in a checked / runtime
 * exception) as "stop now" — propagate it, do not swallow.</li>
 * <li>Honour {@link Thread#isInterrupted()} between long-running steps.</li>
 * <li>For tools that perform external I/O (HTTP, subprocess, database, MCP stdio/SSE), configure a hard
 * <em>connection-level</em> read/write deadline equal to or shorter than {@link #timeoutMillis()} — interrupt alone
 * does not unblock native or epoll-style waits.</li>
 * <li>After cancellation, <strong>stop producing externally observable side effects</strong> (writes, network calls,
 * subprocess launches). The executor will discard the result of a cancelled call but cannot stop a worker that keeps
 * running in the background; an interrupt-deaf side-effecting tool can corrupt state long after the agent has moved
 * on.</li>
 * <li>For destructive / high-risk tools, prefer process-level or connection-level kill (e.g.
 * {@code Process.destroyForcibly()}, closing the underlying socket) over relying on cooperative interrupt alone.</li>
 * </ul>
 */
public interface Tool {
    String getName();

    String getDescription();

    ToolDefinition getDefinition();

    ToolResult execute(Map<String, Object> input, ToolContext ctx);

    /**
     * Orthogonal capability declarations. Every runtime gate (plan-mode admission, concurrency batching, approval
     * prompt, UI badges, provider {@code x-capabilities}) reads this Set &mdash; there are no other tool-property
     * methods to consult. Default is the empty Set (conservative: not concurrency-safe, not plan-mode safe, no
     * recognised side effects), which forces author intent for every new tool.
     *
     * <p>
     * See {@link ToolCapability} for the full matrix and rationale for each value.
     */
    default Set<ToolCapability> capabilities() {
        return Set.of();
    }

    default int timeoutMillis() {
        return 30_000;
    }

    /**
     * Per-tool concurrency cap. The {@link ParallelToolExecutor} guarantees no
     * more than this many invocations of THIS tool will be executing simultaneously, even if the parent pool
     * ({@code harnex.tool-parallelism}) has idle workers. Returning {@link Integer#MAX_VALUE} disables the per-tool
     * limit (the worker pool size becomes the only ceiling).
     *
     * <p>
     * Defaults to {@code 1} — the conservative ChatOps choice. A single bash, sub-agent delegation or external HTTP
     * fetch racing against itself is rarely what the user wants; tools whose calls are cheap and side-effect-free (e.g.
     * {@code file_read}, {@code grep}, {@code memory_list}) should override this to a higher value (typically 8 or
     * {@link Integer#MAX_VALUE}).
     */
    default int maxParallel() {
        return 1;
    }

    /**
     * Capability gate for "external-dependency" tools. When this returns {@code false} the tool is filtered out of the
     * definitions advertised to the model (see {@link ToolRegistry#definitionsFor}) — the model never sees it, so it
     * cannot try to call a tool that is structurally unusable (no API key, disabled, provider unresolvable).
     *
     * <p>
     * Default {@code true}: ordinary tools are always available and pay zero cost. Only tools backed by an optional
     * external/paid dependency (e.g. {@code web_search} → a third-party search API) override this.
     *
     * <p>
     * <strong>Cache-stability contract:</strong> this method is consulted on every turn when building the tool table,
     * so its value MUST be a pure function of static configuration (key present / feature enabled / provider name
     * resolvable). It must NOT flip mid-session on transient runtime state (a tripped circuit breaker, a single failed
     * request) — doing so would yank the tool out of the advertised set between turns, busting the provider prompt
     * cache and making the model "forget" a tool it just saw. Transient health is handled inside {@link #execute} (fast
     * terminal-guidance return), never here.
     */
    default boolean available() {
        return true;
    }

    /**
     * Whether this tool's {@code execute} parks waiting for an EXTERNAL human response (e.g. {@code ask_user},
     * {@code submit_plan}) rather than doing bounded compute. Such tools can block for many minutes, so the executor
     * runs them on a dedicated unbounded waiter pool instead of the bounded shared tool pool — otherwise a few
     * unanswered prompts would occupy every {@code harnex.tool-parallelism} worker and stall all other tool calls with
     * "tool pool saturated". Default {@code false}: ordinary compute tools stay on the bounded pool (its back-pressure
     * is the whole point for them).
     *
     * <p>
     * Only consulted for serially-dispatched tools (these are never {@code CONCURRENCY_SAFE}).
     */
    default boolean awaitsUserInput() {
        return false;
    }

    /**
     * Per-call timeout that may inspect the parsed tool input. Defaults to {@link #timeoutMillis()}.
     *
     * <p>
     * Tools that expose a user-tunable {@code timeoutMs} parameter (e.g. {@code bash}) override this so the executor's
     * interrupt watchdog uses the same budget the tool itself will honour — otherwise the outer killer fires at the
     * static default while the inner process still has minutes left, and the model sees a confusing "timed out after
     * 120000ms" with no way to opt out. The value is clamped server-side to {@code [1_000, 600_000]} ms to bound abuse.
     */
    default long timeoutMillis(Map<String, Object> input) {
        long base = timeoutMillis();
        if (input != null) {
            Object v = input.get("timeoutMs");
            long requested = -1;
            if (v instanceof Number n) {
                requested = n.longValue();
            } else if (v instanceof String s) {
                try {
                    requested = Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    /* keep default */ }
            }
            if (requested > 0) {
                // Allow the tool's parameter to extend OR shorten the static budget, clamped to a
                // sane absolute window (max 10 minutes) so a runaway model can't pin a worker for
                // hours with timeoutMs=Long.MAX_VALUE.
                base = Math.min(600_000L, Math.max(1_000L, requested));
            }
        }
        return base;
    }
}
