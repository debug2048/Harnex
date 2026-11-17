package com.harnex.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Shared cost / observability tracker that flows from a root agent run down through every sub-agent it delegates to.
 *
 * <p>
 * <b>Iterations are NOT capped here.</b> Each {@link AgentLoop#run} bounds its own loop locally with
 * {@code cfg.subagent.maxIterations} (optionally lowered per sub-agent via
 * {@link com.harnex.model.ConversationThread#getMaxIterations()}),
 * so every agent — root or sub-agent — gets its own independent turn budget.
 *
 * <p>
 * Two things ARE shared family-wide, because per-agent caps alone do not bound them:
 * <ul>
 * <li><b>Total sub-agent runs</b> ({@code maxSubagentRuns}). {@code maxPerThread} only caps <em>concurrent</em>
 * in-flight delegations — once a sub-agent finishes its permit is released, so a parent (and each descendant) can issue
 * delegations sequentially across its iterations without bound. This counter is the family-wide ceiling on how many
 * sub-agents one user turn may spawn in total, regardless of concurrency or depth.</li>
 * <li><b>Token / cost</b> ({@code maxTotalTokens}). When {@code > 0} the loop honours a single shared counter across
 * all descendants. When zero, tokens are still accumulated for observability via {@link #getTokensUsed()} but never
 * block.</li>
 * </ul>
 * {@link #iterationsUsed} is kept purely for observability (it never rejects).
 */
public final class SubAgentBudget {

    private final int maxSubagentRuns;
    private final long maxTotalTokens;
    /** Observability only — total model iterations across the family this turn. Never gates. */
    private final AtomicInteger iterationsUsed = new AtomicInteger();
    /** Family-wide count of sub-agent delegations spawned this turn. Gated by {@link #maxSubagentRuns}. */
    private final AtomicInteger subagentRunsUsed = new AtomicInteger();
    private final AtomicLong tokensUsed = new AtomicLong();
    /**
     * ask_user calls raised in THIS user turn — the per-turn anti-nag cap reads this. Resets with each fresh budget.
     */
    private final AtomicInteger asksUsed = new AtomicInteger();

    /** Set true when the token cap is hit; root + sub-agents observe via {@link #isExhausted()}. */
    private volatile boolean exhausted;
    /** Human-readable reason for the budget rejection (rendered into error events). */
    private volatile String exhaustionReason;
    /** Optional live Goal limits, checked at model/tool admission boundaries by every member of the family. */
    private volatile Supplier<String> limitCheck;
    private long remainingModelCalls = -1;
    private long remainingToolCalls = -1;

    public SubAgentBudget(int maxSubagentRuns, long maxTotalTokens) {
        // 0 / negative is treated as "unlimited" so callers can opt in selectively.
        this.maxSubagentRuns = Math.max(0, maxSubagentRuns);
        this.maxTotalTokens = Math.max(0L, maxTotalTokens);
    }

    /** Record one model iteration for observability. Never rejects — iteration caps are enforced per-agent locally. */
    public void recordIteration() {
        iterationsUsed.incrementAndGet();
    }

    /**
     * Reserve one sub-agent run from the family-wide pool. Returns {@code false} when the cap is exhausted — the caller
     * ({@code delegate} tool / {@link com.harnex.subagent.SubAgentExecutor}) must refuse the delegation so a runaway
     * agent cannot spawn an unbounded number of sub-agents by delegating sequentially across its iterations. Does NOT
     * mark the whole budget exhausted: the parent loop keeps running, only further delegation is denied.
     */
    public boolean tryAcquireSubagentRun() {
        if (!checkLimits()) return false;
        if (maxSubagentRuns <= 0) {
            subagentRunsUsed.incrementAndGet();
            return true;
        }
        int next = subagentRunsUsed.incrementAndGet();
        if (next > maxSubagentRuns) {
            subagentRunsUsed.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Hand a previously-acquired run back to the family pool. Called when a delegation is refused AFTER acquiring (queue
     * full, or an exception before the sub-agent runner starts) so the cap counts sub-agents that actually spawn, not
     * refused attempts. Floors at zero so a stray double-release cannot drive the counter negative.
     */
    public void releaseSubagentRun() {
        subagentRunsUsed.updateAndGet(n -> n > 0 ? n - 1 : 0);
    }

    public int getMaxSubagentRuns() {
        return maxSubagentRuns;
    }

    public int getSubagentRunsUsed() {
        return subagentRunsUsed.get();
    }

    /** Account for token usage reported by a provider. Marks the budget exhausted when over the shared cost cap. */
    public void addTokens(long tokens) {
        if (tokens <= 0) {
            return;
        }
        long total = tokensUsed.addAndGet(tokens);
        if (maxTotalTokens > 0 && total > maxTotalTokens && !exhausted) {
            markExhausted("max_total_tokens(" + maxTotalTokens + ") shared across parent + sub-agents");
        }
    }

    public boolean isExhausted() {
        return exhausted;
    }

    public void setLimitCheck(Supplier<String> check) {
        this.limitCheck = check;
    }

    /** Remaining Step allowances after historical Goal usage. Negative means no configured cap. */
    public synchronized void setCallLimits(long models, long tools) {
        remainingModelCalls = models;
        remainingToolCalls = tools;
    }

    public synchronized boolean tryAcquireModelCall() {
        if (!checkLimits()) return false;
        if (remainingModelCalls == 0) {
            markExhausted("Goal model-call budget reached");
            return false;
        }
        if (remainingModelCalls > 0) remainingModelCalls--;
        return true;
    }

    public synchronized boolean tryAcquireToolCall() {
        if (!checkLimits()) return false;
        if (remainingToolCalls == 0) {
            markExhausted("Goal tool-call budget reached");
            return false;
        }
        if (remainingToolCalls > 0) remainingToolCalls--;
        return true;
    }

    public boolean checkLimits() {
        Supplier<String> check = limitCheck;
        if (!exhausted && check != null) {
            String reason = check.get();
            if (reason != null) markExhausted(reason);
        }
        return !exhausted;
    }

    public String getExhaustionReason() {
        return exhaustionReason;
    }

    public long getMaxTotalTokens() {
        return maxTotalTokens;
    }

    public int getIterationsUsed() {
        return iterationsUsed.get();
    }

    public long getTokensUsed() {
        return tokensUsed.get();
    }

    /** Increment the per-turn ask_user counter and return the new total (used by ask_user's anti-nag cap). */
    public int incrementAsks() {
        return asksUsed.incrementAndGet();
    }

    /** ask_user calls raised so far this turn. */
    public int getAsksUsed() {
        return asksUsed.get();
    }

    public synchronized void markExhausted(String reason) {
        // First-writer-wins: keep the original reason rather than overwriting with later detail.
        // synchronized makes the check-then-act atomic so two threads racing past the volatile
        // read in addTokens cannot both win and clobber each other's reason.
        if (!exhausted) {
            this.exhaustionReason = reason;
            this.exhausted = true;
        }
    }
}
