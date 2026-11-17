package com.harnex.web.api.agent;

import com.harnex.todo.TodoStore;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.config.ProjectRuntime;
import com.harnex.run.ActiveAgentRegistry;
import com.harnex.run.ActiveAgentRegistry.PermissionModeUpdate;
import com.harnex.session.SessionStore;
import com.harnex.util.Ids;

/** Agent controls. Complete history and branches belong to ConversationsController. */
@RestController
@ProjectRuntime
@RequestMapping("/api/v1/agent/conversations")
public class AgentConversationController {
    private final TodoStore todoStore;

    private final SessionStore store;
    private final ActiveAgentRegistry active;
    public AgentConversationController(SessionStore store, ActiveAgentRegistry active, TodoStore todoStore) {
        this.todoStore = todoStore;
        this.store = store; this.active = active;
    }

    @GetMapping("/{id}/todos")
    public ResponseEntity<List<Map<String, Object>>> todos(@PathVariable String id) {
        if (!Ids.isSafe(id)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(todoStore.snapshot(id));
    }

    /**
     * Set the per-thread permission mode. Accepted values: {@code ask | accept-edits | yolo}. Anything else is
     * normalised to {@code ask}. The dangerous-pattern denylist still applies under all modes.
     */
    @PutMapping("/{id}/mode")
    public ResponseEntity<Map<String, Object>> setMode(@PathVariable String id, @RequestParam("mode") String mode) {
        if (!Ids.isSafe(id)) {
            return ResponseEntity.badRequest().build();
        }
        String normalised = switch (mode == null ? "" : mode.toLowerCase()) {
            case "yolo", "accept-edits", "ask" -> mode.toLowerCase();
            default -> "ask";
        };
        PermissionModeUpdate runtime = active.setPermissionMode(id, normalised);
        boolean live = runtime == PermissionModeUpdate.LIVE;
        boolean pending = runtime == PermissionModeUpdate.PENDING;
        boolean runtimeAccepted = live || pending;
        // Runtime updates are either already applied to AgentLoop's in-memory thread (LIVE) or queued for the
        // reservation-only window (PENDING). Do not also save store.get(id): FtsSessionStore may return the same
        // mutable object from fullCache while AgentLoop is appending messages/tool results. The terminal AgentLoop
        // save,
        // or RunRequestService's slash RESPOND save, will persist the new mode with a consistent transcript. Idle threads can
        // be updated directly.
        boolean persisted = false;
        if (runtime == PermissionModeUpdate.NONE) {
            persisted = store.get(id).map(t -> {
                t.getState().setPermissionMode(normalised);
                store.save(t);
                return true;
            }).orElse(false);
        }
        if (!runtimeAccepted && !persisted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("permissionMode", normalised, "live", live, "pending", pending, "runtime",
            runtimeAccepted, "persisted", persisted));
    }

}
