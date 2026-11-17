package com.harnex.middleware;

import java.util.Map;

import com.harnex.core.AgentCtx;
import com.harnex.model.ToolCall;
import com.harnex.tool.ToolResult;

import lombok.Data;

/**
 * Per-tool-call context carried through preToolCall / postToolCall hooks.
 * <p>
 * In {@code preToolCall}: middleware can inspect/modify {@link #parsedArguments}, or set {@link #vetoed} to true to
 * block execution entirely.
 * <p>
 * In {@code postToolCall}: middleware can inspect/modify {@link #result}.
 */
@Data
public class ToolCallEvent {
    /** Parent agent context. */
    private final AgentCtx agentCtx;
    /** The raw tool call from the LLM. */
    private final ToolCall toolCall;
    /** Parsed arguments — mutable; preToolCall middleware can modify before execution. */
    private Map<String, Object> parsedArguments;
    /** Execution result — null during preToolCall, populated during postToolCall. */
    private ToolResult result;
    /** If set to true in preToolCall, the tool will NOT execute. */
    private boolean vetoed;
    /** Human-readable reason when vetoed. */
    private String vetoReason;
}
