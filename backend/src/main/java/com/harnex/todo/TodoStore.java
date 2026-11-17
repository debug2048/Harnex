package com.harnex.todo;

import java.util.Objects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.model.ConversationThread;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/** Runtime-owned checklist storage shared by tools, context projection, Goals and snapshot restoration. */
@Service
@ProjectRuntime
@Slf4j
public final class TodoStore {
    private final ConcurrentMap<String, SnapshotState> states = new ConcurrentHashMap<>();
    private final Path persistDir;

    @Autowired
    public TodoStore(AgentConfig config) { this(Path.of(config.getDataDir(), "agent/todos")); }
    public TodoStore(Path persistDir) { this.persistDir = Objects.requireNonNull(persistDir); }

    public record Provenance(String scopeId, String sourceMessageId, String runId) {}
    public record SnapshotState(List<Map<String, Object>> todos, Provenance provenance) {
        public SnapshotState {
            todos = (todos == null ? List.<Map<String, Object>>of() : todos).stream()
                .map(item -> Collections.unmodifiableMap(new LinkedHashMap<>(item))).toList();
        }
    }
    private static final ObjectMapper M = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {};
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(persistDir);
            // Hydrate cache from disk so previously-saved plans survive restart.
            try (var stream = Files.list(persistDir)) {
                stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                    String fname = p.getFileName().toString();
                    String tid = fname.substring(0, fname.length() - 5);
                    try {
                        var node = M.readTree(Files.readString(p, StandardCharsets.UTF_8));
                        SnapshotState snapshot = node.isArray()
                            ? new SnapshotState(M.convertValue(node, LIST_OF_MAPS), null)
                            : M.treeToValue(node, SnapshotState.class);
                        states.put(tid, snapshot);
                    } catch (Exception e) {
                        log.warn("todo hydrate {} failed: {}", p, e.getMessage());
                    }
                });
            }
            log.info("TodoStore hydrated {} thread plans from {}", states.size(), persistDir);
        } catch (IOException e) {
            log.warn("TodoStore persist dir init failed: {}", e.getMessage());
        }
    }

    private void persist(String threadId, SnapshotState snapshot) {
        try {
            Path target = persistDir.resolve(threadId + ".json");
            Path tmp = persistDir.resolve(threadId + ".json.tmp");
            Files.writeString(tmp, M.writeValueAsString(snapshot), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("todo persist {} failed: {}", threadId, e.getMessage());
        }
    }

    /** Snapshot accessor for callers (e.g. UI endpoint). */
    public List<Map<String, Object>> snapshot(String threadId) {
        return snapshotState(threadId).map(SnapshotState::todos).orElse(List.of());
    }

    /** Distinguishes an explicitly cleared plan from unavailable state during deterministic recovery. */
    public Optional<List<Map<String, Object>>> snapshotIfPresent(String threadId) {
        return snapshotState(threadId).map(SnapshotState::todos);
    }

    public Optional<SnapshotState> snapshotState(String threadId) {
        return threadId == null ? Optional.empty() : Optional.ofNullable(states.get(threadId));
    }

    /** Archived threads require the safe point's branch authorization; standalone/subagent callers keep legacy behavior. */
    @SuppressWarnings("unchecked")
    public Optional<List<Map<String, Object>>> snapshotForContext(ConversationThread thread) {
        var extras = thread.getState() == null ? null : thread.getState().getExtras();
        boolean archived = extras != null && extras.containsKey("currentHistoryScope")
            || thread.getState() != null && thread.getState().getConversationLeafId() != null;
        if (!archived) return snapshotIfPresent(thread.getId());
        Object authorized = extras == null ? null : extras.get("currentBranchTodoSnapshot");
        return authorized instanceof List<?> list
            ? Optional.of((List<Map<String, Object>>)list) : Optional.empty();
    }

    public static Provenance provenance(ConversationThread thread, String runId) {
        var extras = thread.getState() == null ? null : thread.getState().getExtras();
        if (extras == null || !(extras.get("currentHistoryScope") instanceof String scope)
            || !(extras.get("latestHistoryUserId") instanceof String source)
            || runId == null || runId.isBlank()) return null;
        return new Provenance(scope, source, runId);
    }

    public void publish(ConversationThread thread, SnapshotState snapshot) {
        states.put(thread.getId(), snapshot);
        persist(thread.getId(), snapshot);
        var extras = thread.getState() == null ? null : thread.getState().getExtras();
        if (extras != null && extras.containsKey("currentHistoryScope")) {
            if (snapshot.provenance() != null) extras.put("currentBranchTodoSnapshot", snapshot.todos());
            else extras.remove("currentBranchTodoSnapshot");
        }
    }

    /** Restore a conversation snapshot's todo state into both in-memory and persisted stores. */
    public void restoreSnapshot(String threadId, List<Map<String, Object>> todos) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> item : todos == null ? List.<Map<String, Object>>of() : todos) {
            copy.add(new LinkedHashMap<>(item));
        }
        SnapshotState snapshot = new SnapshotState(copy, null);
        states.put(threadId, snapshot);
        persist(threadId, snapshot);
    }

    /**
     * Seed the thread's todo list from a list of step titles (e.g. when an approved {@code submit_plan} plan is turned
     * into an executable checklist). Replaces any existing list. Each title becomes a {@code not-started} item with a
     * sequential id. No-op for an empty/null list. Persisted like a normal {@code todo_write}.
     */
    public void seed(String threadId, List<String> titles) {
        seed(threadId, titles, 0);
    }

    /** Seed a plan owned by a specific compression-immune user-turn number. */
    public void seed(String threadId, List<String> titles, int ownerTurn) {
        seed(threadId, titles, ownerTurn, null, null);
    }

    /** Approved plans inherit the same current archive source as todo_write. */
    public void seed(ConversationThread thread, String runId, List<String> titles, int ownerTurn) {
        seed(thread.getId(), titles, ownerTurn, thread, runId);
    }

    private void seed(String threadId, List<String> titles, int ownerTurn, ConversationThread thread, String runId) {
        if (threadId == null || titles == null || titles.isEmpty()) {
            return;
        }
        List<Map<String, Object>> items = new ArrayList<>(titles.size());
        int id = 1;
        for (String title : titles) {
            if (title == null || title.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id++);
            item.put("title", title.strip());
            item.put("status", "not-started");
            item.put("_turn", Math.max(0, ownerTurn));
            items.add(item);
        }
        if (items.isEmpty()) {
            return;
        }
        SnapshotState snapshot = new SnapshotState(items, thread == null ? null : provenance(thread, runId));
        if (thread != null) publish(thread, snapshot);
        else { states.put(threadId, snapshot); persist(threadId, snapshot); }
    }

    /**
     * Conservative terminal clean-up for normal agent completion. If the model is producing its final answer and the
     * todo list has exactly one in-progress item with no not-started items left, that item represents the final wrap-up
     * work and should not remain visually stuck at N-1/N. We deliberately do nothing when any item is still
     * not-started, or when the list shape is ambiguous.
     */
    public boolean completeFinalInProgress(ConversationThread thread) {
        var verified = snapshotForContext(thread);
        var current = snapshotState(thread.getId());
        if (verified.isEmpty() || current.isEmpty() || !current.orElseThrow().todos().equals(verified.orElseThrow())) return false;
        return completeFinalInProgress(thread.getId());
    }

    public boolean completeFinalInProgress(String threadId) {
        SnapshotState prior = states.get(threadId);
        if (prior == null || prior.todos().isEmpty()) {
            return false;
        }
        List<Map<String, Object>> current = prior.todos();
        int inProgress = 0;
        int notStarted = 0;
        for (Map<String, Object> item : current) {
            String status = String.valueOf(item.get("status"));
            if ("in-progress".equals(status)) {
                inProgress++;
            } else if ("not-started".equals(status)) {
                notStarted++;
            }
        }
        if (inProgress != 1 || notStarted != 0) {
            return false;
        }

        List<Map<String, Object>> updated = new ArrayList<>(current.size());
        for (Map<String, Object> item : current) {
            var copy = new LinkedHashMap<String, Object>(item);
            if ("in-progress".equals(String.valueOf(copy.get("status")))) {
                copy.put("status", "completed");
            }
            updated.add(copy);
        }
        SnapshotState snapshot = new SnapshotState(updated, prior.provenance());
        states.put(threadId, snapshot);
        persist(threadId, snapshot);
        return true;
    }

    /** Drop state for a thread (called when the thread is deleted). */
    public void purge(String threadId) {
        states.remove(threadId);
        try {
            Files.deleteIfExists(persistDir.resolve(threadId + ".json"));
        } catch (IOException ignored) {
            /* file may not exist */
        }
    }
}
