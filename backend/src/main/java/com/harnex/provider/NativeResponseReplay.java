package com.harnex.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.model.ToolCall;
import lombok.extern.slf4j.Slf4j;

/** Provider-native replay validation after the execution layer has applied its tool policy. */
@Slf4j
public final class NativeResponseReplay {
    private static final ObjectMapper M = new ObjectMapper();
    private NativeResponseReplay() {}

    /**
     * Verify that provider-native output still describes exactly the function calls the middleware-approved loop will
     * execute. Non-function item types are deliberately ignored here and remain untouched in the raw replay payload.
     */
    public static boolean matchesTools(String provider, String rawItems, List<ToolCall> effectiveCalls) {
        if (rawItems == null || rawItems.isBlank()) {
            return true;
        }
        // Opaque continuation data is private to the adapter instance that produced it. Unscoped protocol-only
        // provenance is deliberately rejected: it cannot prove which endpoint/account owns encrypted signatures.
        boolean openAiResponses = provider != null && provider.startsWith("openai-responses:");
        boolean anthropic = provider != null && provider.startsWith("anthropic:");
        boolean gemini = provider != null && provider.startsWith("gemini:");
        if (!openAiResponses && !anthropic && !gemini) {
            return false;
        }
        try {
            var root = M.readTree(rawItems);
            if (!root.isArray()) {
                return false;
            }
            List<ToolCall> rawCalls = new ArrayList<>();
            for (var item : root) {
                String type = item.path("type").asText();
                if (openAiResponses && "function_call".equals(type)) {
                    rawCalls.add(ToolCall.builder().id(item.path("call_id").asText())
                        .name(item.path("name").asText()).arguments(item.path("arguments").asText("{}")).build());
                } else if (anthropic && "tool_use".equals(type)) {
                    var input = item.get("input");
                    rawCalls.add(ToolCall.builder().id(item.path("id").asText())
                        .name(item.path("name").asText())
                        .arguments(input == null || input.isNull() ? "{}" : M.writeValueAsString(input)).build());
                } else if (gemini && "function_call".equals(type)) {
                    var arguments = item.get("arguments");
                    rawCalls.add(ToolCall.builder().id(item.path("id").asText())
                        .name(item.path("name").asText())
                        .arguments(arguments == null || arguments.isNull()
                            ? "{}" : M.writeValueAsString(arguments)).build());
                }
            }
            List<ToolCall> expected = effectiveCalls == null ? List.of() : effectiveCalls;
            if (rawCalls.size() != expected.size()) {
                return false;
            }
            for (int i = 0; i < rawCalls.size(); i++) {
                ToolCall raw = rawCalls.get(i);
                ToolCall effective = expected.get(i);
                if (effective == null || !Objects.equals(raw.getId(), effective.getId())
                    || !Objects.equals(raw.getName(), effective.getName())
                    || !sameJson(raw.getArguments(), effective.getArguments())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Discarding malformed provider response output metadata: {}", e.getMessage());
            return false;
        }
    }

    private static boolean sameJson(String left, String right) {
        String a = left == null || left.isBlank() ? "{}" : left;
        String b = right == null || right.isBlank() ? "{}" : right;
        try {
            return Objects.equals(M.readTree(a), M.readTree(b));
        } catch (Exception ignored) {
            return Objects.equals(a, b);
        }
    }

}
