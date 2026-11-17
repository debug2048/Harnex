package com.harnex.tool.builtin;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.ProjectRuntime;
import com.harnex.conversation.ConversationFactStore;
import com.harnex.conversation.HistoryContextService;
import com.harnex.conversation.HistoryReference;
import com.harnex.run.RunRegistry;
import com.harnex.tool.*;

/** Source-checked continuity state for the current conversation, separate from workspace-wide memory. */
@Component
@ProjectRuntime
public final class SessionFactTool implements Tool {
    private final ConversationFactStore facts;
    private final HistoryContextService history;
    private final RunRegistry runs;
    private final ObjectMapper json = new ObjectMapper();

    public SessionFactTool(ConversationFactStore facts, HistoryContextService history, RunRegistry runs) {
        this.facts = facts; this.history = history; this.runs = runs;
    }
    @Override public String getName() { return "session_fact"; }
    @Override public String getDescription() {
        return "Register or list durable user-source quotes for this conversation's current branch. "
            + "Use register for an important constraint, decision, parameter or correction that must survive compaction. "
            + "Supply the original USER message ID and revision from session_search/session_read and an exact quote; "
            + "retain its conditions, scope, negations and units. A hypothetical example or assistant suggestion is not user approval. "
            + "Use a stable key and qualifier for its task/environment; replace only an earlier fact in that same scope "
            + "by explicitly naming its id in supersedes. Repeating the same registration is harmless. "
            + "Use list and nextOffset to read all active facts, including those omitted from the context projection. "
            + "Entries marked summary_candidate are unreviewed extraction suggestions; verify their original context "
            + "before treating examples as decisions or relying on an inferred scope. "
            + "Quotes are returned intact; use a smaller limit for long quotes. Restart listing if facts change between pages.";
    }
    @Override public Set<ToolCapability> capabilities() {
        // Like todo_write, this persists internal conversation state rather than modifying project files.
        return EnumSet.of(ToolCapability.READS_FILESYSTEM, ToolCapability.MUTATES_THREAD_STATE, ToolCapability.PLAN_MODE_SAFE);
    }
    @Override public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription()).parameters(Map.of(
            "type", "object", "additionalProperties", false,
            "properties", Map.ofEntries(
                Map.entry("action", Map.of("type", "string", "enum", List.of("register", "list"))),
                Map.entry("sourceMessageId", Map.of("type", "string", "description", "Required for register: original USER message ID")),
                Map.entry("sourceRevision", Map.of("type", "integer", "minimum", 1, "description", "Required for register: exact source revision")),
                Map.entry("key", Map.of("type", "string", "description", "Required for register: stable subject, such as deployment_region")),
                Map.entry("qualifier", Map.of("type", "string", "description", "Task/environment scope; preserve distinctions such as production versus staging")),
                Map.entry("quote", Map.of("type", "string", "description", "Required for register: exact user quote including its conditions and negations")),
                Map.entry("supersedes", Map.of("type", "string", "description", "Optional explicit id of the earlier fact being corrected in the same scope")),
                Map.entry("offset", Map.of("type", "integer", "minimum", 0, "default", 0, "description", "For list: nextOffset returned by the previous page")),
                Map.entry("limit", Map.of("type", "integer", "minimum", 1, "maximum", 100, "default", 20))),
            "required", new String[]{"action"})).build();
    }
    @Override public ToolResult execute(Map<String, Object> input, ToolContext context) {
        try {
            if (input.containsKey("conversationId") || input.containsKey("leafId") || input.containsKey("scopeId")) {
                throw new IllegalArgumentException("session_fact only uses the current conversation branch and authenticated scope");
            }
            var selection = SessionSearchTool.selection(history, runs, context, null);
            String action = text(input, "action");
            if ("register".equals(action)) {
                long revision = integer(input.get("sourceRevision"), "sourceRevision", -1);
                if (revision < 1) throw new IllegalArgumentException("sourceRevision must be a positive integer");
                var fact = facts.register(history.scopeId(), selection.conversationId(), selection.leafId(),
                    text(input, "sourceMessageId"), revision, text(input, "key"), optionalText(input, "qualifier"),
                    text(input, "quote"), optionalText(input, "supersedes"));
                return ToolResult.builder().content(json.writeValueAsString(Map.of("fact", fact)))
                    .metadata(Map.of("historySources", List.of(reference(selection.conversationId(), fact)))).build();
            }
            if (!"list".equals(action)) throw new IllegalArgumentException("action must be register or list");
            long offset = integer(input.get("offset"), "offset", 0);
            long limit = integer(input.get("limit"), "limit", 20);
            if (offset < 0 || offset > Integer.MAX_VALUE || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("list requires a non-negative offset and limit of 1..100");
            }
            var active = facts.active(history.scopeId(), selection.conversationId(), selection.leafId());
            if (offset > active.size()) throw new IllegalArgumentException("offset exceeds active facts; restart listing after changes");
            int end = (int)Math.min(active.size(), offset + limit);
            var page = new FactPage(List.copyOf(active.subList((int)offset, end)), end < active.size() ? end : null, active.size());
            return ToolResult.builder().content(json.writeValueAsString(page))
                .metadata(Map.of("historySources", page.items().stream().map(fact -> reference(selection.conversationId(), fact)).toList())).build();
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (Exception failure) { return ToolResult.failure("Conversation fact operation failed: " + failure.getMessage()); }
    }
    public record FactPage(List<ConversationFactStore.Fact> items, Integer nextOffset, int total) {}
    private static HistoryReference reference(String conversationId, ConversationFactStore.Fact fact) {
        return new HistoryReference(conversationId, fact.messageId(), null, fact.revision());
    }
    private static String text(Map<String, Object> input, String key) {
        String value = optionalText(input, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private static String optionalText(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be text");
        return text;
    }
    private static long integer(Object value, String key, long fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        try { return new BigDecimal(number.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException(key + " must be an integer"); }
    }
}
