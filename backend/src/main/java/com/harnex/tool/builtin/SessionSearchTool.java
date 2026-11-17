package com.harnex.tool.builtin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.ProjectRuntime;
import com.harnex.conversation.ContextTokens;
import com.harnex.conversation.HistoryContextService;
import com.harnex.run.RunRegistry;
import com.harnex.tool.*;

/** Searches original archived messages, independent of the compressed Agent execution projection. */
@Component
@ProjectRuntime
public class SessionSearchTool implements Tool {
    private final HistoryContextService history;
    private final RunRegistry runs;
    private final ContextTokens tokens;
    private final ObjectMapper json = new ObjectMapper();
    public SessionSearchTool(HistoryContextService history, RunRegistry runs, ContextTokens tokens) {
        this.history = history; this.runs = runs; this.tokens = tokens;
    }
    @Override public String getName() { return "session_search"; }
    @Override public String getDescription() {
        return "Find original messages and recorded tool evidence from conversation history, including before compaction. "
            + "Defaults to this conversation's current branch. Specify conversationId to search another conversation in this workspace. "
            + "Results identify the exact source and matching offset; use session_read when available for original text or neighboring messages. "
            + "Pass nextCursor back as cursor with the same query and conversation to continue, even when a page has no hits. "
            + "bounded or a non-READY state means the search is incomplete; no hits does not prove the information is absent.";
    }
    @Override public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_FILESYSTEM, ToolCapability.PLAN_MODE_SAFE, ToolCapability.CONCURRENCY_SAFE);
    }
    @Override public int maxParallel() { return 8; }
    @Override public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription()).parameters(Map.of("type", "object",
            "properties", Map.of("query", Map.of("type", "string"), "limit", Map.of("type", "integer", "default", 5),
                "cursor", Map.of("type", "string", "description", "nextCursor from the previous page; omit to restart"),
                "conversationId", Map.of("type", "string", "description", "Optional explicit conversation in the same workspace")),
            "required", new String[]{"query"})).build();
    }
    @Override public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String query = String.valueOf(input.getOrDefault("query", "")).trim();
        if (query.isBlank()) return ToolResult.failure("query is required");
        try {
            var selection = selection(history, runs, ctx, input.get("conversationId"));
            int limit = input.get("limit") instanceof Number number ? number.intValue() : 5;
            String cursor = input.get("cursor") instanceof String value ? value : null;
            var result = history.search(selection, query, Math.min(8, limit), cursor);
            var hits = new ArrayList<>(result.hits());
            String model = ctx.getAgentContext() == null ? ctx.getThread().getModel() : ctx.getAgentContext().getLastModel();
            String rendered;
            do {
                rendered = json.writeValueAsString(new HistoryContextService.SearchResult(hits,
                    result.bounded() || hits.size() < result.hits().size(), result.state(), result.nextCursor()));
                if (hits.size() <= 1 || tokens.text(rendered, model) <= HistoryContextService.MAX_RECALL_TOKENS) break;
                hits.remove(hits.size() - 1);
            } while (true);
            if (hits.size() < result.hits().size()) {
                // Reposition the continuation after the actual returned prefix, not after hidden hits.
                result = history.search(selection, query, hits.size(), cursor);
                rendered = json.writeValueAsString(result);
                // A newly added continuation can cost more than the original final-page envelope.
                while (result.hits().size() > 1 && tokens.text(rendered, model) > HistoryContextService.MAX_RECALL_TOKENS) {
                    result = history.search(selection, query, result.hits().size() - 1, cursor);
                    rendered = json.writeValueAsString(result);
                }
            }
            return ToolResult.ok(rendered);
        } catch (Exception failure) { return ToolResult.failure("History search failed: " + failure.getMessage()); }
    }
    static HistoryContextService.Selection selection(HistoryContextService history, RunRegistry runs,
        ToolContext context, Object requested) {
        var run = context.getRunId() == null ? null : runs.get(context.getRunId());
        String conversation = run == null ? context.getThread().getId() : run.getThreadId();
        if (requested instanceof String target && !target.isBlank() && !target.equals(conversation)) return history.current(target, null);
        if (run != null && run.getSubmission() != null) {
            if (!history.scopeId().equals(run.getSubmission().scopeId())) throw new IllegalArgumentException("History scope mismatch");
            return history.forRun(conversation, run.getSubmission().turnId(), run.getRunId());
        }
        return history.current(conversation, context.getThread().getState().getConversationLeafId());
    }
}
