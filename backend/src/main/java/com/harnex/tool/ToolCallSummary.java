package com.harnex.tool;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.model.ToolCall;
import com.harnex.safety.SecretRedactor;

/** Builds short, redacted tool descriptions suitable for restore-point metadata and UI labels. */
public final class ToolCallSummary {

    private static final int MAX_LENGTH = 180;
    private static final ObjectMapper M = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private ToolCallSummary() {
    }

    public static List<String> forRestorePoints(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        return calls.stream().map(ToolCallSummary::forRestorePoint).filter(s -> !s.isBlank()).toList();
    }

    public static String forRestorePoint(ToolCall call) {
        String name = call == null || call.getName() == null ? "" : call.getName().trim();
        Map<String, Object> args = parseArguments(call);
        String lower = name.toLowerCase(Locale.ROOT);
        String summary;
        if ("bash".equals(lower)) {
            summary = "bash: " + stringArg(args, "command", "cmd", "script");
        } else if ("file_write".equals(lower)) {
            summary = "write " + stringArg(args, "path", "file");
        } else if ("file_edit".equals(lower)) {
            summary = "edit " + stringArg(args, "path", "file");
        } else {
            String target = stringArg(args, "path", "file", "url", "name");
            summary = target.isBlank() ? name : name + ": " + target;
        }
        return compact(SecretRedactor.redact(summary), MAX_LENGTH);
    }

    private static Map<String, Object> parseArguments(ToolCall call) {
        if (call == null || call.getArguments() == null || call.getArguments().isBlank()) {
            return Map.of();
        }
        try {
            return M.readValue(call.getArguments(), MAP);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String stringArg(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object value = args.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String compact(String text, int maxLength) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3).trim() + "...";
    }
}
