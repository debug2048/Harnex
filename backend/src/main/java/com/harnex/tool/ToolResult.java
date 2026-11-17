package com.harnex.tool;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolResult {
    private String callId;
    private String toolName;
    private boolean error;
    /** A missing required answer/review, distinct from a tool or infrastructure failure. */
    private boolean requiresInput;
    private String content;
    /**
     * LLM-facing / persisted-thread version of the result, decoupled from {@link #content} (which is the small
     * UI-preview version). {@link com.harnex.tool.budget.BudgetEnforcer} fills this with the FULL scrubbed content
     * when it fits inside the (deliberately large) LLM budget, or with a larger-threshold truncation carrying a
     * model-actionable {@code ref:{sha}} hint when it does not. {@code null} means "no separate LLM version" — callers
     * MUST fall back to {@link #content} for backwards compatibility (a tool result that never entered the budget
     * path, or one that stayed within the small per-tool UI cap and is therefore already complete).
     */
    private String llmContent;
    /** If truncated by BudgetEnforcer, reference to on-disk full payload. */
    private String persistRef;
    private long durationMs;
    /**
     * Set to {@code true} by {@link com.harnex.tool.budget.BudgetEnforcer} once it has run
     * {@link com.harnex.safety.SecretRedactor} over the FULL content BEFORE splitting it into the small UI
     * {@link #content} preview and the (larger) {@link #llmContent} model version. Both fields therefore derive from a
     * single already-scrubbed source, so the main {@link com.harnex.core.AgentLoop} path can safely SKIP its otherwise
     * idempotent re-scrub instead of re-scanning multi-KB payloads 2–3 times per turn.
     * <p>
     * Stays {@code false} for any result that BYPASSED the budget path (e.g. the MCP side-channel or an
     * executor-level error stub) so {@link com.harnex.middleware.SensitiveDataMiddleware} / AgentLoop still redact it as
     * the primary pass. It is ONLY ever set after content has genuinely passed through SecretRedactor — never as a
     * shortcut that would let un-scrubbed bytes reach the LLM, disk, or the UI.
     */
    private boolean scrubbed;
    /**
     * Optional structured side-channel for rich UI rendering. Distinct from {@link #content} (which is what the LLM and
     * persisted thread message see). Only forwarded over SSE as the {@code tool_use_end.metadata} field — never written
     * into {@code thread.messages}, so snapshot blobs stay lean. Typical key: {@code "diff"} for file_edit / file_write
     * results.
     */
    private Map<String, Object> metadata;

    /**
     * Hard cap for any error message handed back to the LLM. Some providers serialise the entire request body into the
     * exception, which then re-feeds into every subsequent prompt.
     */
    private static final int MAX_ERROR_MESSAGE_CHARS = 512;

    /** Convenience: a successful result with just content. Equivalent to {@code builder().content(c).build()}. */
    public static ToolResult ok(String content) {
        return ToolResult.builder().content(content == null ? "" : content).build();
    }

    /**
     * Convenience: a failed result whose message is sanitised to a single line and clipped so an exception message
     * bloated by a multi-KB JSON body or a stack trace doesn't pollute the conversation history. The {@code error=true}
     * flag is mandatory so middleware (audit / approval / budget) can route on it without parsing content.
     */
    public static ToolResult failure(String message) {
        return ToolResult.builder().error(true).content(sanitiseErrorMessage(message)).build();
    }

    /** Same as {@link #failure(String)} but pulls the message off a Throwable. */
    public static ToolResult failure(Throwable t) {
        if (t == null)
            return failure("unknown error");
        String msg = t.getMessage();
        // Some exceptions have null message but a useful class name (e.g. NullPointerException at L42).
        if (msg == null || msg.isBlank())
            msg = t.getClass().getSimpleName();
        return failure(msg);
    }

    /**
     * Strip newlines + clip to {@link #MAX_ERROR_MESSAGE_CHARS} so a verbose provider/JDK exception cannot tank the
     * next prompt. Visible for tests.
     */
    static String sanitiseErrorMessage(String raw) {
        if (raw == null || raw.isBlank())
            return "unknown error";
        // Collapse newlines/tabs into spaces — multi-line stack-frame messages are unreadable
        // for the LLM and tend to derail it onto irrelevant frames.
        String oneLine = raw.replaceAll("\\s+", " ").strip();
        if (oneLine.length() > MAX_ERROR_MESSAGE_CHARS) {
            oneLine = oneLine.substring(0, MAX_ERROR_MESSAGE_CHARS) + "\u2026(truncated)";
        }
        return oneLine;
    }
}
