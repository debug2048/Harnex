package com.harnex.tool;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** OpenAI-format tool definition (function-call schema). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolDefinition {
    private String name;
    private String description;
    /** JSON-schema object describing input parameters. */
    private Map<String, Object> parameters;
    /**
     * Optional capability hints. Auto-populated from {@link Tool#capabilities()} by {@link ToolRegistry#definitionsFor}
     * when not explicitly set. Serialised as {@code x-capabilities: ["READS_FILESYSTEM", ...]} alongside the tool
     * schema so downstream consumers (UI badges, audit logs, third-party governance proxies) can route tool calls
     * without round-tripping through the agent's executor.
     */
    private Set<ToolCapability> capabilities;
    private Integer timeoutMs;

    /** Serialize into OpenAI tools[] element. */
    public Map<String, Object> toApi() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description == null ? "" : description);
        fn.put("parameters", canonicalSchema(ToolPurpose.schema(parameters)));
        // x-* extension fields are ignored by OpenAI but useful to UI / approval / governance
        // layers that round-trip the schema.
        if (capabilities != null && !capabilities.isEmpty()) {
            // Stable, sorted ordering so equality / diffs across runs are deterministic.
            fn.put("x-capabilities", capabilities.stream().map(Enum::name).sorted().toList());
        }
        if (timeoutMs != null) {
            fn.put("x-timeoutMs", timeoutMs);
        }
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("type", "function"); api.put("function", fn);
        return api;
    }

    /** JSON object order is not part of a schema's meaning, but must not randomly churn a cached prompt. */
    private static Object canonicalSchema(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalSchema(item)));
            return new LinkedHashMap<>(sorted);
        }
        if (value instanceof List<?> list) return list.stream().map(ToolDefinition::canonicalSchema).toList();
        if (value != null && value.getClass().isArray()) {
            var ordered = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                ordered.add(canonicalSchema(Array.get(value, i)));
            }
            return ordered;
        }
        return value;
    }
}
