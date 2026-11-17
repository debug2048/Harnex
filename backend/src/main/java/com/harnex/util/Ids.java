package com.harnex.util;

import java.util.regex.Pattern;

/**
 * Strict identifier validation. Used for threadId / jobId / callId to prevent path traversal when IDs are used as
 * filesystem path components or Docker bind-mount sources.
 */
public final class Ids {

    // Alphanumerics + dash + underscore + colon, 1..128 chars. No dots, no slashes.
    // Bumped from 64 to 128: third-party MCP tool callIds frequently take the form
    // `mcp__<server>__<tool>__<uuid>` which can exceed 64 characters in legitimate use.
    // Colon added: some OpenAI-compatible providers (e.g., Kimi) use `<tool_name>:<index>`
    // format for tool call IDs (e.g., "glob:1", "file_read:2").
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_:-]{1,128}");
    /**
     * Relaxed validation for provider-originated callIds (e.g., Kimi's {@code "functions.skill_read:0"}). Dots are
     * permitted here because callIds are NEVER used as filesystem path components.
     */
    private static final Pattern SAFE_CALL_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private Ids() {}

    public static boolean isSafe(String id) {
        return id != null && SAFE.matcher(id).matches();
    }

    public static boolean isSafeCallId(String id) {
        return id != null && SAFE_CALL_ID.matcher(id).matches();
    }
    /** Portable application-owned file resource identity; excludes dot components and Windows alternate streams. */
    public static boolean isSafeResourceId(String id) { return id != null && RESOURCE_ID.matcher(id).matches(); }

    /** Throws IllegalArgumentException if {@code id} is not a safe identifier. */
    public static String requireSafe(String id, String what) {
        if (!isSafe(id)) {
            throw new IllegalArgumentException("Invalid " + what + " (must match [A-Za-z0-9_:-]{1,128})");
        }
        return id;
    }
}
