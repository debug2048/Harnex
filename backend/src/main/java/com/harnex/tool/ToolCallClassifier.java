package com.harnex.tool;

import java.util.Collections;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.model.ToolCall;
import com.harnex.process.ShellCommandClassifier;

/** Shared capability- and argument-aware classification for runtime safety decisions. */
public final class ToolCallClassifier {

    private static final ObjectMapper M = new ObjectMapper();

    private ToolCallClassifier() {
    }

    /**
     * A call is read-only when its tool has no world-mutating capability. Bash is the deliberate exception: its
     * declaration is maximally conservative, so the command-level shell classifier decides individual invocations.
     * Thread-local bookkeeping ({@code MUTATES_THREAD_STATE}) is not a workspace/network mutation.
     */
    public static boolean isReadOnly(Tool tool, ToolCall call) {
        return isReadOnly(tool, parseArguments(call));
    }

    public static boolean isReadOnly(Tool tool, Map<String, Object> arguments) {
        if (tool == null) {
            return false;
        }
        var caps = tool.capabilities();
        boolean mutating = caps.contains(ToolCapability.WRITES_FILESYSTEM)
            || caps.contains(ToolCapability.WRITES_NETWORK)
            || caps.contains(ToolCapability.SPAWNS_PROCESS)
            || caps.contains(ToolCapability.DESTRUCTIVE_IRREVERSIBLE);
        if (!mutating) {
            return true;
        }
        if ("bash".equalsIgnoreCase(tool.getName())) {
            Object command = arguments == null ? null : arguments.get("command");
            return ShellCommandClassifier.isReadOnlyShellCommand(command);
        }
        return false;
    }

    private static Map<String, Object> parseArguments(ToolCall call) {
        String raw = call == null ? null : call.getArguments();
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = M.readValue(raw, Map.class);
            return parsed;
        } catch (Exception ignored) {
            // Invalid arguments will fail at dispatch. Classify them conservatively here.
            return Collections.emptyMap();
        }
    }
}
