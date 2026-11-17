package com.harnex.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.middleware.Middleware;
import com.harnex.middleware.MiddlewareOrder;
import com.harnex.model.StreamEvent;

/** Ordinary tasks, Goal steps and sub-agents share the same lazy MCP preparation boundary. */
@Component
@ProjectRuntime
public final class McpToolsetMiddleware implements Middleware {
    private final McpRegistry registry;

    public McpToolsetMiddleware(McpRegistry registry) { this.registry = registry; }

    @Override public int order() { return MiddlewareOrder.MCP_PREPARATION; }

    @Override public void before(AgentCtx context) {
        if (context.isCancelled() || Thread.currentThread().isInterrupted()) return;
        List<String> unavailable = registry.prepareToolsets(context.getThread().getToolsets(), context::registerCancellation);
        if (!unavailable.isEmpty() && !context.isCancelled() && !Thread.currentThread().isInterrupted()) {
            String message = "MCP servers unavailable: " + String.join(", ", unavailable) + ". Continuing with available tools.";
            context.emit(StreamEvent.of("notice", Map.of("kind", "mcp_unavailable", "servers", unavailable,
                "message", message, "activityKind", "mcp", "activityTitle", "MCP",
                "activitySummary", message, "activityOutcome", "failed")));
        }
    }
}
