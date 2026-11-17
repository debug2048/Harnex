package com.harnex.tool.builtin;

import com.harnex.todo.TodoStore;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;

/** Read the durable per-thread checklist before updating it. */
@Component
@ProjectRuntime
public class TodoReadTool implements Tool {
    private final TodoStore todoStore;

    public TodoReadTool(TodoStore todoStore) { this.todoStore = todoStore; }

    @Override
    public String getName() {
        return "todo_read";
    }

    @Override
    public String getDescription() {
        return "Read the current per-thread todo list. Call this before updating an existing or uncertain list, "
            + "especially after context compression or an autonomous continuation.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.PLAN_MODE_SAFE, ToolCapability.CONCURRENCY_SAFE);
    }

    @Override
    public int maxParallel() {
        return 8;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties", Map.of())).build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        var snapshot = todoStore.snapshotForContext(ctx.getThread());
        if (snapshot.isEmpty()) return ToolResult.ok("No verified Todo snapshot is available for this conversation branch. "
            + "Do not treat older summaries or another branch's checklist as current work.");
        List<Map<String, Object>> todos = snapshot.orElseThrow();
        if (todos.isEmpty()) {
            return ToolResult.builder().content("no todos for this thread").build();
        }
        StringBuilder out = new StringBuilder("current todos (").append(todos.size()).append("):\n");
        for (Map<String, Object> todo : todos) {
            out.append("  ").append(todo.get("id")).append(" [")
                .append(switch (String.valueOf(todo.get("status"))) {
                    case "completed" -> "x";
                    case "in-progress" -> "~";
                    default -> " ";
                })
                .append("] ").append(todo.get("title"));
            if (todo.get("progress") != null) out.append(" — ").append(todo.get("progress"));
            out.append('\n');
        }
        return ToolResult.builder().content(out.toString()).build();
    }
}
