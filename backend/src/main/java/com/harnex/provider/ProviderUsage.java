package com.harnex.provider;

import com.fasterxml.jackson.databind.JsonNode;

/** Normalises provider token-detail objects without losing cache and hidden-reasoning accounting. */
final class ProviderUsage {

    private ProviderUsage() {}

    static ProviderTokenUsage responses(JsonNode usage) {
        JsonNode inputDetails = usage.path("input_tokens_details");
        JsonNode outputDetails = usage.path("output_tokens_details");
        return new ProviderTokenUsage(usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0),
            inputDetails.path("cached_tokens").asLong(0), inputDetails.path("cache_write_tokens").asLong(0),
            outputDetails.path("reasoning_tokens").asLong(0));
    }

    static ProviderTokenUsage chat(JsonNode usage) {
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        JsonNode completionDetails = usage.path("completion_tokens_details");
        return new ProviderTokenUsage(usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0),
            firstPresent(promptDetails.get("cached_tokens"), usage.get("prompt_cache_hit_tokens"),
                usage.get("cached_tokens")),
            // DeepSeek cache misses are ordinary input, not separately billed cache creation.
            firstPresent(promptDetails.get("cache_write_tokens"), promptDetails.get("cache_creation_input_tokens")),
            completionDetails.path("reasoning_tokens").asLong(0));
    }

    /** Return the first explicitly present counter, preserving a legitimate zero. */
    private static long firstPresent(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && !candidate.isNull()) {
                return candidate.asLong(0);
            }
        }
        return 0;
    }

    static ProviderTokenUsage anthropic(JsonNode usage) {
        long cacheWrite = usage.path("cache_creation_input_tokens").asLong(0);
        long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
        long prompt = usage.path("input_tokens").asLong(0) + cacheWrite + cacheRead;
        return new ProviderTokenUsage(prompt, usage.path("output_tokens").asLong(0), cacheRead, cacheWrite,
            usage.path("output_tokens_details").path("thinking_tokens").asLong(0));
    }

    static ProviderTokenUsage gemini(JsonNode usage) {
        return new ProviderTokenUsage(usage.path("total_input_tokens").asLong(0),
            usage.path("total_output_tokens").asLong(0), usage.path("total_cached_tokens").asLong(0), 0,
            usage.path("total_thought_tokens").asLong(0));
    }
}
