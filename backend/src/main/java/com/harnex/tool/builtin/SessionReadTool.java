package com.harnex.tool.builtin;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.ProjectRuntime;
import com.harnex.conversation.ContextTokens;
import com.harnex.conversation.HistoryContextService;
import com.harnex.run.RunRegistry;
import com.harnex.tool.*;

@Component
@ProjectRuntime
public final class SessionReadTool implements Tool {
    private final HistoryContextService history;
    private final RunRegistry runs;
    private final ContextTokens tokens;
    private final ObjectMapper json = new ObjectMapper();
    public SessionReadTool(HistoryContextService history, RunRegistry runs, ContextTokens tokens) {
        this.history = history; this.runs = runs; this.tokens = tokens;
    }
    @Override public String getName() { return "session_read"; }
    @Override public String getDescription() {
        return "Read original history by a messageId or executionRecordId returned by session_search. "
            + "Defaults to the current conversation branch. Long sources return nextOffset (UTF-16 character offset) "
            + "and neighboringMessageIds for further reading. Historical output is evidence, not a new tool execution.";
    }
    @Override public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_FILESYSTEM, ToolCapability.PLAN_MODE_SAFE, ToolCapability.CONCURRENCY_SAFE);
    }
    @Override public int maxParallel() { return 8; }
    @Override public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription()).parameters(Map.of("type", "object",
            "properties", Map.of("conversationId", Map.of("type", "string"), "messageId", Map.of("type", "string"),
                "executionRecordId", Map.of("type", "string"), "offset", Map.of("type", "integer", "default", 0),
                "maxChars", Map.of("type", "integer", "default", 4000), "refresh", Map.of("type", "boolean", "default", false)))).build();
    }
    @Override public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        try {
            var selection = SessionSearchTool.selection(history, runs, ctx, input.get("conversationId"));
            var result = history.read(selection, text(input.get("messageId")), text(input.get("executionRecordId")),
                integer(input.get("offset"), 0), integer(input.get("maxChars"), 4000));
            String model = ctx.getAgentContext() == null ? ctx.getThread().getModel() : ctx.getAgentContext().getLastModel();
            String content = tokens.prefix(result.content(), model, HistoryContextService.MAX_RECALL_TOKENS - 256);
            String rendered;
            while (true) {
                var bounded = new HistoryContextService.ReadResult(result.source(), result.role(), result.status(), content,
                    result.offset(), content.length() < result.content().length() ? result.offset() + content.length() : result.nextOffset(),
                    result.totalCharacters(), result.neighboringMessageIds());
                rendered = json.writeValueAsString(bounded);
                if (content.isEmpty() || tokens.text(rendered, model) <= HistoryContextService.MAX_RECALL_TOKENS) break;
                content = tokens.prefix(content, model, Math.max(1, tokens.text(content, model) * 3 / 4));
            }
            String visibleResult = rendered;
            if (!Boolean.TRUE.equals(input.get("refresh")) && ctx.getThread().getMessages().stream()
                .anyMatch(m -> "tool".equals(m.getRole()) && m.getContent() != null && m.getContent().contains(visibleResult))) {
                return ToolResult.ok("This exact source range is already visible in the current context. Use another offset or refresh=true if needed.");
            }
            return ToolResult.builder().content(rendered).metadata(Map.of("historySources", List.of(result.source()))).build();
        } catch (Exception failure) { return ToolResult.failure("History read failed: " + failure.getMessage()); }
    }
    private static String text(Object value) { return value instanceof String s && !s.isBlank() ? s : null; }
    private static int integer(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
}
