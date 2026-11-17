package com.harnex.model;

import lombok.Data;

/**
 * Persisted, per-conversation context-compaction state.
 *
 * <p>The state belongs to a {@link ConversationThread}, so Goal continuations keep the same compaction baseline while
 * sub-agents (which own separate threads) cannot poison the parent's loop guard or read cache.</p>
 */
@Data
public class CompressionState {
    /** Incremented whenever history is actually rewritten. File-read dedupe uses this as its visibility epoch. */
    private long contextGeneration;
    /** Estimated context size immediately after the latest rewrite. */
    private int lastCompressionAfterTokens;
    /** Number of consecutive rewrites that failed to create useful headroom. */
    private int ineffectiveCompressionCount;
    /** Provider-observed prompt tokens from the latest normal task-model request, when available. */
    private int lastObservedPromptTokens;
    /** Full prepared-input estimate (legacy field name), including tools/reminders, at the observed request. */
    private int lastObservedMessageTokens;
    /** Calibration belongs to one actual provider/model; it must not carry across a model switch. */
    private String observedTarget;
    /** One-shot request for AgentLoop to end the current run with a no-tool continuation summary. */
    private boolean finalizationRequested;
}
