package com.harnex.provider;

import java.util.LinkedHashMap;
import java.util.Map;

import com.harnex.observability.HarnexMetrics;

/** Provider-reported token usage shared by streaming and one-shot calls. */
public record ProviderTokenUsage(long prompt, long completion, long cachedInput, long cacheWriteInput,
    long reasoning) {

    public ProviderTokenUsage {
        prompt = Math.max(0, prompt);
        completion = Math.max(0, completion);
        cachedInput = Math.max(0, cachedInput);
        cacheWriteInput = Math.max(0, cacheWriteInput);
        reasoning = Math.max(0, reasoning);
    }

    public long total() {
        return prompt + completion;
    }

    public Map<String, Object> eventData() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prompt", prompt);
        out.put("completion", completion);
        out.put("total", total());
        out.put("cachedInput", cachedInput);
        out.put("cacheWriteInput", cacheWriteInput);
        out.put("reasoning", reasoning);
        return out;
    }

    public void record(HarnexMetrics metrics) {
        if (metrics == null) return;
        metrics.promptTokens.increment(prompt);
        metrics.completionTokens.increment(completion);
        metrics.cachedInputTokens.increment(cachedInput);
        metrics.cacheWriteInputTokens.increment(cacheWriteInput);
        metrics.reasoningTokens.increment(reasoning);
    }
}
