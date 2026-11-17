package com.harnex.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.conversation.ContextTokens;
import com.harnex.safety.SecretRedactor;

/** Request-only projection of the current persisted checklist, independent of historical summaries. */
final class CurrentTaskState {
    static final int MAX_TODO_TOKENS = 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String END = "\n</current_task_state>";

    private CurrentTaskState() {}

    static String render(AgentCtx ctx) {
        var snapshot = ctx.currentTodos();
        // An unavailable snapshot is not an empty list or evidence of completion.
        if (snapshot.isEmpty()) {
            if (!ctx.hasStoredTodos()) return "";
            return "<current_task_state>\nNo verified current Todo snapshot for this branch. "
                + "Historical statuses are not current; do not revive or complete work from an unverified checklist. "
                + "Reconstruct the current checklist from branch-valid history before todo_write. "
                + "Unavailable state is neither an empty plan nor proof of completion." + END;
        }
        List<Map<String, Object>> todos = snapshot.orElseThrow();
        String model = ctx.getLastModel() == null ? ctx.getThread().getModel() : ctx.getLastModel();
        ContextTokens tokens = ContextTokens.defaults();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var todo : todos) {
            String status = Objects.toString(todo.get("status"), "unknown");
            counts.merge(List.of("not-started", "in-progress", "completed").contains(status) ? status : "unknown", 1, Integer::sum);
        }
        String header = "<current_task_state>\nCurrent persisted Todo snapshot for this thread; structured state, not a new human request. "
            + "For these Todo identities and scopes, this snapshot overrides older summaries and tool-history statuses. "
            + "Do not revive completed or removed items from historical summaries. A recorded Todo status does not prove "
            + "Goal acceptance or satisfaction of a newer request. Keep the latest runtime Goal contract and user input authoritative.\n"
            + "snapshotPresent=true; total=" + todos.size() + "; statusCounts=" + encode(counts) + "\n";
        if (todos.isEmpty()) return header + "The current Todo list is explicitly empty; older entries are not current. "
            + "An empty list does not by itself prove the task complete." + END;

        List<String> rows = new ArrayList<>();
        String conservativeFooter = footer(todos.size());
        for (var todo : todos) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (String field : List.of("id", "title", "status", "progress")) {
                if (todo.containsKey(field)) safe.put(field, SecretRedactor.redact(Objects.toString(todo.get(field), "")));
            }
            if (todo.get("_turn") instanceof Number owner) safe.put("ownerTurn", owner);
            String row = encode(safe);
            String proposed = header + String.join("\n", rows) + "\n" + row + conservativeFooter + END;
            // Keep complete rows; a clipped title can lose a qualification or negation.
            if (tokens.text(proposed, model) <= MAX_TODO_TOKENS) rows.add(row);
        }
        int omitted = todos.size() - rows.size();
        return header + String.join("\n", rows) + (omitted == 0 ? "" : footer(omitted)) + END;
    }

    private static String footer(int omitted) {
        return "\nPartial snapshot projection: " + omitted + " rows remain stored but are omitted here. "
            + "Do not infer their statuses from old summaries; use todo_read for the complete current checklist.";
    }

    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value).replace("<", "\\u003c"); }
        catch (IOException impossible) { throw new IllegalStateException("Cannot serialize current Todo state", impossible); }
    }
}
