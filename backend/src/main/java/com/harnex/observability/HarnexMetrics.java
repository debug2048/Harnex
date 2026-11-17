package com.harnex.observability;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Domain counters / timers. Published via /actuator/prometheus when {@code micrometer-registry-prometheus} is on the
 * classpath.
 */
@Component
public class HarnexMetrics {
    public final Counter chatRequests;
    public final Counter chatErrors;
    public final Counter toolCalls;
    public final Counter toolErrors;
    public final Counter providerFailovers;
    public final Counter approvalBlocks;
    public final Timer llmStreamTimer;
    public final Timer toolTimer;
    public final Timer subagentQueueTimer;
    public final Timer subagentRunTimer;
    public final Counter subagentTimeouts;
    public final Counter subagentNoProgress;
    public final Counter subagentModelCalls;
    public final Counter subagentToolCalls;
    public final Counter subagentFileReads;
    public final Counter delegateLimitExceeded;
    /**
     * Cumulative token counters across the whole process lifetime. Bumped from any provider (stream OR oneShot)
     * whenever upstream returns a usage object. Surfaced in the {@code /api/statusline} payload so the cockpit-bar can
     * render lifetime totals even for non-stream call paths (MoA aggregator, summariser) that never emit a per-stream
     * {@code usage} SSE event.
     */
    public final Counter promptTokens;
    public final Counter completionTokens;
    /** Input tokens served from a provider prompt cache (a subset of {@link #promptTokens}). */
    public final Counter cachedInputTokens;
    /** Input tokens written to a prompt cache; GPT-5.6+ bills these separately from ordinary input. */
    public final Counter cacheWriteInputTokens;
    /** Hidden reasoning tokens reported inside the provider's output-token detail object. */
    public final Counter reasoningTokens;

    public HarnexMetrics(MeterRegistry reg) {
        chatRequests = Counter.builder("harnex.chat.requests").register(reg);
        chatErrors = Counter.builder("harnex.chat.errors").register(reg);
        toolCalls = Counter.builder("harnex.tool.calls").register(reg);
        toolErrors = Counter.builder("harnex.tool.errors").register(reg);
        providerFailovers = Counter.builder("harnex.provider.failovers").register(reg);
        approvalBlocks = Counter.builder("harnex.approval.blocks").register(reg);
        llmStreamTimer = Timer.builder("harnex.llm.stream").register(reg);
        toolTimer = Timer.builder("harnex.tool.duration").register(reg);
        subagentQueueTimer = Timer.builder("harnex.subagent.queue.duration").register(reg);
        subagentRunTimer = Timer.builder("harnex.subagent.run.duration").register(reg);
        subagentTimeouts = Counter.builder("harnex.subagent.timeouts").register(reg);
        subagentNoProgress = Counter.builder("harnex.subagent.no_progress").register(reg);
        subagentModelCalls = Counter.builder("harnex.subagent.model.calls").register(reg);
        subagentToolCalls = Counter.builder("harnex.subagent.tool.calls").register(reg);
        subagentFileReads = Counter.builder("harnex.subagent.file_reads").register(reg);
        delegateLimitExceeded = Counter.builder("harnex.delegate.limit_exceeded").register(reg);
        promptTokens = Counter.builder("harnex.tokens.prompt").register(reg);
        completionTokens = Counter.builder("harnex.tokens.completion").register(reg);
        cachedInputTokens = Counter.builder("harnex.tokens.cached_input").register(reg);
        cacheWriteInputTokens = Counter.builder("harnex.tokens.cache_write_input").register(reg);
        reasoningTokens = Counter.builder("harnex.tokens.reasoning").register(reg);
    }
}
