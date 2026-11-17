package com.harnex.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * Cache annotations on an already-rendered request. Never edits prompt text, roles, ordering, tools or native
 * assistant/thinking items. A changed reminder, continuity envelope or summary is allowed to miss the cache.
 *
 * The endpoint's existing cache protocol capability selects the history strategy automatically for callers with
 * a stable conversation scope. One-shot utility requests cache only the instruction prefix. This does not predict
 * cache hits: minimum lengths, expiry and exact rendered-prefix matching remain the provider's responsibility.
 */
@Slf4j
final class PromptCachePlan {
    private static final int MAX_BREAKPOINTS = 4;
    private static final Set<String> ANTHROPIC_INPUT_BLOCKS = Set.of("text", "image", "document", "tool_result");
    private static final Set<String> CHAT_INPUT_BLOCKS = Set.of("text", "image_url");

    private PromptCachePlan() {}

    static void apply(Map<String, Object> body, ModelProvider.ChatRequest request,
        ProviderModelCapabilities.Profile profile, boolean enabled) {
        if (!enabled || request.getPromptCacheScope() == null
            || request.getPromptCacheScope().isBlank()) return;

        if (profile.promptCacheExplicit()) {
            // Keep the existing instruction representation and explicit prefix marker, but permit the provider
            // to reuse user/tool history. Do not move instructions between top-level and message fields here.
            body.put("prompt_cache_options", Map.of("mode", "implicit"));
            log.debug("prompt cache plan: protocol={}, history=implicit", profile.api());
        } else if (profile.promptCacheMode() == ProviderModelCapabilities.PromptCacheMode.CONTENT_CACHE_CONTROL) {
            int marked = markHistory(body, profile.anthropicApi());
            log.debug("prompt cache plan: protocol={}, history=message_breakpoints, added={}", profile.api(), marked);
        }
        // AUTO_PREFIX / KEY / NONE require no extra protocol fields.
    }

    @SuppressWarnings("unchecked")
    private static int markHistory(Map<String, Object> body, boolean anthropic) {
        if (!(body.get("messages") instanceof List<?> raw)) return 0;
        List<Map<String, Object>> messages = (List<Map<String, Object>>) raw;
        int existingHistory = messages.stream().filter(message -> "user".equals(message.get("role"))
            || "tool".equals(message.get("role"))).mapToInt(message -> markerCount(message.get("content"))).sum();
        int remaining = Math.min(3 - existingHistory, MAX_BREAKPOINTS - markerCount(body.get("system"))
            - (body.containsKey("cache_control") ? 1 : 0)
            - messages.stream().mapToInt(message -> markerCount(message.get("content"))).sum());
        if (remaining <= 0) return 0;

        // Keep recent input endpoints as explicit anchors as well as the newest one. This also retains a previous
        // write boundary when a large tool batch would exceed a vendor's automatic prefix-lookback window.
        // Only annotate input messages: signed/opaque assistant blocks must be replayed untouched.
        List<Map<String, Object>> annotated = new ArrayList<>(messages);
        int marked = 0;
        for (int i = messages.size() - 1; i >= 0 && marked < remaining; i--) {
            Map<String, Object> message = messages.get(i);
            Object role = message.get("role");
            if (!"user".equals(role) && !("tool".equals(role) && !anthropic)) continue;
            // One boundary per completed parallel tool batch; do not consume all slots inside that batch.
            if ("tool".equals(role) && i + 1 < messages.size()
                && "tool".equals(messages.get(i + 1).get("role"))) continue;
            List<Map<String, Object>> blocks;
            if (message.get("content") instanceof String text) {
                if (text.isEmpty()) continue;
                blocks = new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("type", "text", "text", text))));
            } else if (message.get("content") instanceof List<?> content && !content.isEmpty()
                && content.stream().allMatch(Map.class::isInstance)) {
                blocks = new ArrayList<>((List<Map<String, Object>>) content);
            } else continue;

            Map<String, Object> last = blocks.get(blocks.size() - 1);
            if (!(last.get("type") instanceof String type)
                || !(anthropic ? ANTHROPIC_INPUT_BLOCKS : CHAT_INPUT_BLOCKS).contains(type)
                || last.containsKey("cache_control")) continue;
            // Copy on write, including maps sourced from immutable/native replay data.
            Map<String, Object> block = new LinkedHashMap<>(last);
            block.put("cache_control", Map.of("type", "ephemeral"));
            blocks.set(blocks.size() - 1, block);
            Map<String, Object> copy = new LinkedHashMap<>(message);
            copy.put("content", blocks);
            annotated.set(i, copy);
            marked++;
        }
        if (marked > 0) body.put("messages", annotated);
        return marked;
    }

    private static int markerCount(Object content) {
        if (!(content instanceof List<?> blocks)) return 0;
        return (int) blocks.stream().filter(block -> block instanceof Map<?, ?> map && map.containsKey("cache_control"))
            .count();
    }
}
