package com.harnex.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.safety.SecretRedactor;

/** Model-authored, user-facing intent. This reserved argument is never sent to a tool implementation. */
public final class ToolPurpose {
    public static final String ARGUMENT = "_harnex_purpose";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolPurpose() {}

    public static Map<String, Object> schema(Map<String, Object> parameters) {
        var schema = new LinkedHashMap<String, Object>(parameters == null
            ? Map.of("type", "object") : parameters);
        var properties = new LinkedHashMap<String, Object>();
        if (schema.get("properties") instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> properties.put(String.valueOf(key), value));
        }
        properties.put(ARGUMENT, Map.of("type", "string", "description",
            "For each call, provide one short user-facing sentence in the user's language explaining what this "
                + "specific step aims to accomplish, e.g. 'Read the build configuration to find the test command'. "
                + "Describe the intended action, not a claimed result or internal reasoning. Do not include secrets. "
                + "This is display metadata, removed before execution; keep it under 160 characters."));
        schema.put("properties", properties);
        return schema;
    }

    public static String fromArguments(Object arguments) {
        if (arguments instanceof Map<?, ?> map) return normalize(map.get(ARGUMENT));
        if (!(arguments instanceof String raw) || raw.isBlank()) return null;
        try {
            var value = JSON.readTree(raw).get(ARGUMENT);
            return value != null && value.isTextual() ? normalize(value.textValue()) : null;
        } catch (Exception ignored) { return null; }
    }

    public static String normalize(Object value) {
        if (!(value instanceof String text)) return null;
        String compact = SecretRedactor.redact(text).replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        // Clients truncate visually; hover and detail views need the complete text.
        return compact.isEmpty() ? null : compact;
    }
}
