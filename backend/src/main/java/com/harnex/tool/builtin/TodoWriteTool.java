package com.harnex.tool.builtin;

import com.harnex.todo.TodoStore;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.ConversationContinuity;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;

/** Validates and replaces the current checklist through the shared Todo store. */
@Component
@ProjectRuntime
public class TodoWriteTool implements Tool {
    private final AgentConfig cfg;
    private final TodoStore todoStore;

    public TodoWriteTool(AgentConfig cfg, TodoStore todoStore) {
        this.cfg = cfg;
        this.todoStore = todoStore;
    }

    @Override
    public String getName() {
        return "todo_write";
    }

    @Override
    public String getDescription() {
        int parallel = Math.max(1, cfg.getSubagent().getMaxPerThread());
        return "Replace the per-thread todo list. Preserve existing ids, titles, and completed states; call todo_read "
            + "first when uncertain. Up to " + parallel + " items may be in-progress for parallel work. Put changing "
            + "status text in progress, not title. Use replace=true only for an intentional user-requested replan.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        // Checklist updates are allowed during planning, so retain PLAN_MODE_SAFE despite the state mutation.
        return EnumSet.of(ToolCapability.MUTATES_THREAD_STATE,
            ToolCapability.PLAN_MODE_SAFE);
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> itemProperties = new LinkedHashMap<>();
        itemProperties.put("id", Map.of("type", "integer"));
        itemProperties.put("title", Map.of("type", "string"));
        itemProperties.put("status",
            Map.of("type", "string", "enum", new String[] {"not-started", "in-progress", "completed"}));
        itemProperties.put("progress", Map.of("type", "string", "description",
            "Optional changing progress note; keep title stable."));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("todos",
            Map.of("type", "array", "description",
                "Complete todo list. Active existing items must be retained unless replace=true.", "items",
                Map.of("type", "object", "properties", itemProperties, "required",
                    new String[] {"id", "title", "status"})));
        properties.put("replace",
            Map.of("type", "boolean", "default", false, "description",
                "Intentionally replace an active list. Use only when the user changed the task or scope."));
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties", properties, "required", new String[] {"todos"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        Object raw = input.get("todos");
        if (!(raw instanceof List<?> list)) {
            return ToolResult.builder().error(true).content("'todos' must be an array").build();
        }
        List<Map<String, Object>> normalised = new ArrayList<>(list.size());
        List<Map<String, Object>> current = todoStore.snapshotForContext(ctx.getThread()).orElse(List.of());
        Map<String, Map<String, Object>> currentById = new LinkedHashMap<>();
        current.forEach(item -> currentById.put(String.valueOf(item.get("id")), item));
        int ownerTurn = ConversationContinuity.humanTurnCount(ctx.getThread());
        int inProgress = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Object id = m.get("id");
            Object title = m.get("title");
            Object status = m.get("status");
            if (title == null || status == null) {
                continue;
            }
            String st = String.valueOf(status);
            if (!st.equals("not-started") && !st.equals("in-progress") && !st.equals("completed")) {
                return ToolResult.builder().error(true).content("invalid status: " + st).build();
            }
            if ("in-progress".equals(st)) {
                inProgress++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            Object effectiveId = id == null ? normalised.size() + 1 : id;
            item.put("id", effectiveId);
            item.put("title", String.valueOf(title));
            item.put("status", st);
            Object progress = m.get("progress");
            if (progress != null) {
                if (!String.valueOf(progress).isBlank()) {
                    String note = String.valueOf(progress).strip();
                    item.put("progress", note.substring(0, Math.min(500, note.length())));
                }
            } else {
                Map<String, Object> previous = currentById.get(String.valueOf(effectiveId));
                if (previous != null && previous.get("progress") != null) {
                    item.put("progress", previous.get("progress"));
                }
            }
            // UI ownership metadata. todo_read deliberately omits this internal field from its textual result.
            item.put("_turn", ownerTurn);
            normalised.add(item);
        }
        int maxInProgress = Math.max(1, cfg.getSubagent().getMaxPerThread());
        if (inProgress > maxInProgress) {
            return ToolResult.builder().error(true)
                .content("at most " + maxInProgress + " todos may be 'in-progress' (found " + inProgress + ")"
                    + authoritativeSnapshot(current)).build();
        }
        boolean replace = Boolean.TRUE.equals(input.get("replace"))
            || "true".equalsIgnoreCase(String.valueOf(input.get("replace")));
        String continuityError = replace ? null : continuityError(current, normalised);
        if (continuityError != null) {
            return ToolResult.builder().error(true).content(continuityError + authoritativeSnapshot(current)).build();
        }
        todoStore.publish(ctx.getThread(), new TodoStore.SnapshotState(normalised, TodoStore.provenance(ctx.getThread(), ctx.getRunId())));
        // Render a compact summary for the model (and humans inspecting tool_results).
        StringBuilder sb = new StringBuilder("todos updated (").append(normalised.size()).append("):\n");
        for (var t : normalised) {
            String mark = switch (String.valueOf(t.get("status"))) {
                case "completed" -> "[x]";
                case "in-progress" -> "[~]";
                default -> "[ ]";
            };
            sb.append("  ").append(mark).append(' ').append(t.get("title"));
            if (t.get("progress") != null) sb.append(" — ").append(t.get("progress"));
            sb.append('\n');
        }
        return ToolResult.builder().content(sb.toString()).build();
    }

    private static String authoritativeSnapshot(List<Map<String, Object>> current) {
        if (current == null || current.isEmpty()) return "\nCurrent todos: none";
        StringBuilder out = new StringBuilder("\nCurrent todos (preserve id/title/completed):");
        for (Map<String, Object> item : current) {
            out.append("\n  ").append(item.get("id")).append(" [").append(item.get("status")).append("] ")
                .append(item.get("title"));
            if (item.get("progress") != null) out.append(" — ").append(item.get("progress"));
        }
        return out.toString();
    }

    /**
     * Guard an active checklist against an accidental wholesale re-plan after context compression or a long tool
     * sequence. Completed lists may naturally give way to the next user task; an active list must retain the same
     * id/title pairs and may never regress a completed item unless the caller explicitly opts into replacement.
     */
    private static String continuityError(List<Map<String, Object>> current, List<Map<String, Object>> next) {
        boolean hasOpenWork = current.stream()
            .anyMatch(item -> !"completed".equals(String.valueOf(item.get("status"))));
        if (!hasOpenWork) {
            return null;
        }
        Map<String, Map<String, Object>> nextById = new LinkedHashMap<>();
        for (Map<String, Object> item : next) {
            nextById.put(String.valueOf(item.get("id")), item);
        }
        for (Map<String, Object> old : current) {
            String id = String.valueOf(old.get("id"));
            String oldTitle = String.valueOf(old.get("title"));
            Map<String, Object> updated = nextById.get(id);
            if (updated == null) {
                return "refusing to overwrite active todo " + id + " (\"" + oldTitle + "\"): the item is missing. "
                    + "Resubmit the complete existing list with this id/title, or set replace=true only for an "
                    + "intentional user-requested replan";
            }
            String newTitle = String.valueOf(updated.get("title"));
            if (!oldTitle.equals(newTitle)) {
                return "refusing to overwrite active todo " + id + ": title changed from \"" + oldTitle + "\" to \""
                    + newTitle + "\". Preserve the existing id/title, or set replace=true only for an intentional "
                    + "user-requested replan";
            }
            if ("completed".equals(String.valueOf(old.get("status")))
                && !"completed".equals(String.valueOf(updated.get("status")))) {
                return "refusing to regress completed todo " + old.get("id")
                    + ": call todo_read and preserve completed states, or set replace=true only for an intentional replan";
            }
        }
        return null;
    }

}
