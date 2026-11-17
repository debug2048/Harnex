package com.harnex.provider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.harnex.model.ChatMessage;
import com.harnex.model.StreamEvent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Model provider abstraction. Supports streaming chat (SSE) and one-shot non-stream. */
public interface ModelProvider {
    String getName();

    /** Returns the configured default model for this provider (may be null if unconfigured). */
    default String getDefaultModel() {
        return null;
    }

    /** Stream a chat completion, invoking callback for each incremental event. */
    void streamChat(ChatRequest request, Consumer<StreamEvent> onEvent);

    /**
     * Stream variant with an explicit TTFT (time-to-first-content) cap in ms. Implementations SHOULD use
     * {@code min(ttftBudgetMs, internalDefault)} to bound the wait for the first content chunk. {@code 0} or negative
     * means "use the implementation's default" — preserves the original 2-arg behaviour for callers that don't pass the
     * budget explicitly. Required so {@link com.harnex.provider.ProviderRouter} can shrink the per-attempt cap when the
     * global {@code total-budget-ms} is smaller than the provider's default TTFT — without this, an operator who
     * configures a tight 10s router budget would still wait 90s for a stalled first provider before failover fires.
     *
     * <p>
     * Default delegates to {@link #streamChat(ChatRequest, Consumer)} ignoring the budget so custom providers stay
     * backward compatible (they remain susceptible to the original wedge but at least keep working).
     * </p>
     */
    default void streamChat(ChatRequest request, Consumer<StreamEvent> onEvent, long ttftBudgetMs) {
        streamChat(request, onEvent);
    }

    /** One-shot (non-streaming) for utility calls, including upstream-reported token usage. */
    OneShotResult oneShot(String model, List<ChatMessage> messages, Float temperature);

    /**
     * One-shot with a hard wall-clock budget. Implementations MUST actually cancel the underlying HTTP call on timeout
     * so the worker thread is freed — wrapping {@link #oneShot} in {@code CompletableFuture.orTimeout} (the previous
     * MoA approach) only marks the future complete and leaves the OkHttp call attached to the worker until
     * provider-side readTimeout (10 min) finally fires, which trivially exhausts a small bounded pool. On timeout /
     * cancellation, throw {@link ProviderException} with code {@code "timeout"}.
     *
     * <p>
     * Default implementation delegates to {@link #oneShot} ignoring the budget.
     * </p>
     */
    default OneShotResult oneShot(String model, List<ChatMessage> messages, Float temperature, long timeoutMs) {
        return oneShot(model, messages, temperature);
    }

    /**
     * One-shot with independent wall-clock and output-token budgets. Utility workloads such as history compression
     * should not inherit a reasoning model's very large interactive output allowance merely because they share the
     * same provider. Implementations that support request-level limits should override this method; the default keeps
     * custom providers source-compatible and delegates to the timeout-aware overload.
     */
    default OneShotResult oneShot(String model, List<ChatMessage> messages, Float temperature, long timeoutMs,
        Integer maxOutputTokens) {
        return oneShot(model, messages, temperature, timeoutMs);
    }

    /** Preserve a prepared request's prefix for a utility call. No client tool calls are ever executed here. */
    default OneShotResult oneShot(ChatRequest request, long timeoutMs) {
        throw new ProviderException("summary_prefix_unsupported", "Provider does not support request-preserving summaries",
            com.harnex.net.HttpExchangeState.Acceptance.NOT_SENT, null, 0);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder(toBuilder = true)
    class ChatRequest {
        private String model;
        /**
         * Preferred provider name (Provider Affinity). When non-null, the router places this provider first in the
         * failover sequence and nulls the model on failover so the fallback provider uses its own defaultModel.
         */
        private String provider;
        private List<ChatMessage> messages;
        private List<Map<String, Object>> tools;
        /**
         * Ordinary conversation opt-in: accept nonblank text followed by [DONE] when a Chat-compatible
         * service omits finish_reason. Never permits tool calls or overrides an explicit failure.
         * Agent, probe and utility requests retain strict completion checks by default.
         */
        @JsonIgnore
        private boolean allowTextOnlyDone;
        /** Search permission before local availability filtering; hosted search needs no local backend/key. */
        @Builder.Default
        @JsonIgnore
        private boolean webSearchAllowed = true;
        /** Selected by the actual provider adapter on each attempt, never sent upstream. */
        @JsonIgnore
        private boolean nativeWebSearchActive;

        /** Local functions actually offered on this attempt, excluding a hosted-search replacement. */
        @JsonIgnore
        public Set<String> localToolNames() {
            Set<String> names = new LinkedHashSet<>();
            if (tools != null) for (Map<String, Object> tool : tools) {
                Object function = tool.get("function");
                Object name = function instanceof Map<?, ?> fn ? fn.get("name") : tool.get("name");
                if (name instanceof String n && !(nativeWebSearchActive && "web_search".equals(n))) names.add(n);
            }
            return Set.copyOf(names);
        }
        private Float temperature;
        private Integer maxTokens;
        /** Per-conversation reasoning override; null means use the selected provider's configured default. */
        private String reasoningEffort;
        /**
         * Stable conversation/task scope for provider prompt-cache routing. Adapters hash this value with their
         * provider/model identity before emitting a vendor field; callers should pass a thread/session id, never a
         * per-request id. Null falls back to a stable reusable-prefix fingerprint.
         */
        private String promptCacheScope;
        /**
         * Optional idempotency key sent to upstream providers as the {@code Idempotency-Key} (OpenAI) or
         * {@code anthropic-idempotency-key} header. Lets the provider de-duplicate on its side when our failover layer
         * retries a non-idempotent POST whose response was lost in flight, preventing duplicate billing / duplicate
         * tool execution. Null/blank disables the header.
         */
        private String idempotencyKey;
        /** Internal per-target projection hook. Never serialized into an upstream request. */
        @JsonIgnore
        private TargetPreparation targetPreparation;
        @JsonIgnore
        private String contextPlanId;
    }

    @FunctionalInterface
    interface TargetPreparation {
        void prepare(String provider, String model, ChatRequest request);
    }
}
