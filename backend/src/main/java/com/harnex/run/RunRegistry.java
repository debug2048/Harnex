package com.harnex.run;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.harnex.conversation.ConversationRepository;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.execution.ExecutionSpec;
import com.harnex.execution.ScopeIdentity;

/** In-memory run index plus the durable compact projection. It contains no transport or AgentLoop logic. */
@Component
public class RunRegistry {

    private static final long IDEMPOTENCY_WINDOW_MS = 5 * 60 * 1000L;
    private static final int IDEMPOTENCY_MAX_ENTRIES = 2048;

    private final RunLedger ledger;
    private final RunJournal journal;
    private final ConcurrentMap<String, RunHandle> runs = new ConcurrentHashMap<>();
    /** Logical ownership includes persisted sleeping/recovered runs that have no JVM worker reservation. */
    private final ConcurrentMap<String, String> openRunByThread = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> recoveryConflicts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentRequestIds = new ConcurrentHashMap<>();
    private final AtomicBoolean acceptingRuns = new AtomicBoolean(true);
    private ProjectConversationLocks ownership;
    private ConversationRepository archive;
    private ScopeIdentity scope;

    @Autowired
    public RunRegistry(RunLedger ledger, RunJournal journal, ProjectConversationLocks ownership,
        ConversationRepository archive, ScopeIdentity scope) {
        this(ledger, journal);
        this.ownership = ownership; this.archive = archive; this.scope = scope;
        if (ownership.supports(ExecutionSpec.Kind.CONVERSATION)) {
            for (RunHandle run : liveRuns()) if (!run.isTerminal() && !claimRecovered(run.getThreadId(), run.getRunId())) {
                runs.remove(run.getRunId());
            }
            openRunByThread.clear(); recoveryConflicts.clear();
            for (RunHandle run : liveRuns()) if (!run.isTerminal()) openRunByThread.put(run.getThreadId(), run.getRunId());
        }
    }

    public RunRegistry(RunLedger ledger, RunJournal journal) {
        this.ledger = ledger;
        this.journal = journal;
        journal.reconcile(ledger);
        rebuildOpenRunIndex();
    }

    /** In-memory journal constructor for focused unit tests and adapter fixtures. */
    public RunRegistry(RunLedger ledger) {
        this(ledger, RunJournal.inMemory());
    }

    public boolean isAccepting() {
        return acceptingRuns.get();
    }

    public void stopAccepting() {
        acceptingRuns.set(false);
    }

    synchronized RunHandle register(String runId, String threadId, RunOrigin origin, AgentExecutionOptions options,
        String provider, String model, String reasoningEffort) {
        return register(runId, threadId, origin, options, provider, model, reasoningEffort, null);
    }

    synchronized RunHandle register(String runId, String threadId, RunOrigin origin, AgentExecutionOptions options,
        String provider, String model, String reasoningEffort, RunRecord.IdempotencyClaim creationRequest) {
        return register(runId, threadId, origin, options, provider, model, reasoningEffort, creationRequest, null, null);
    }

    synchronized RunHandle register(String runId, String threadId, RunOrigin origin, AgentExecutionOptions options,
        String provider, String model, String reasoningEffort, RunRecord.IdempotencyClaim creationRequest,
        ExecutionSpec execution, RunRecord.SubmissionRef submission) {
        return register(runId, threadId, origin, options, provider, model, reasoningEffort, creationRequest, execution, submission, null);
    }

    synchronized RunHandle register(String runId, String threadId, RunOrigin origin, AgentExecutionOptions options,
        String provider, String model, String reasoningEffort, RunRecord.IdempotencyClaim creationRequest,
        ExecutionSpec execution, RunRecord.SubmissionRef submission, RunNotification notification) {
        if (runs.containsKey(runId)) throw new IllegalStateException("duplicate runId: " + runId);
        if (recoveryConflicts.containsKey(threadId)) {
            throw new IllegalStateException("thread has conflicting persisted open runs: " + threadId);
        }
        String openRunId = openRunByThread.get(threadId);
        if (openRunId != null) {
            throw new IllegalStateException("thread already owns open run " + openRunId);
        }
        RunHandle run = new RunHandle(runId, threadId, origin, options, provider, model, reasoningEffort,
            creationRequest);
        if (execution != null) run.bindSubmission(execution, submission);
        run.bindNotification(notification);
        String operationId = operationId();
        journal.prepare(operationId, runId, RunJournalEntry.Kind.CREATE_RUN, 0L);
        synchronized (run) {
            ledger.persist(run.record(1L, operationId));
            run.markPersisted(1L, operationId);
        }
        runs.put(runId, run);
        if (ownership != null) ownership.bind(threadId, runId);
        openRunByThread.put(threadId, runId);
        journal.commit(operationId, runId, RunJournalEntry.Kind.CREATE_RUN, 0L);
        return run;
    }

    public RunHandle get(String runId) {
        return runId == null ? null : runs.get(runId);
    }

    synchronized RunHandle hydrate(String runId) {
        RunHandle existing = get(runId);
        if (existing != null) return existing;
        RunRecord record = ledger.find(runId).orElse(null);
        if (record == null) return null;
        if (!record.status().isTerminal() && !claimRecovered(record.threadId(), runId)) return null;
        RunHandle restored = RunHandle.restore(record);
        RunHandle raced = runs.putIfAbsent(runId, restored);
        return raced == null ? restored : raced;
    }

    synchronized RunHandle findByCreationRequest(String scope, String key) {
        if (scope == null || key == null) return null;
        Map<String, RunRecord> matches = new LinkedHashMap<>();
        for (RunHandle run : runs.values()) {
            RunRecord.IdempotencyClaim claim = run.getCreationRequest();
            if (claim != null && scope.equals(claim.scope()) && key.equals(claim.key())) {
                matches.put(run.getRunId(), run.record(Math.max(1L, run.getRevision())));
            }
        }
        for (RunRecord record : ledger.list()) {
            RunRecord.IdempotencyClaim claim = record.creationRequest();
            if (claim != null && scope.equals(claim.scope()) && key.equals(claim.key())) {
                matches.putIfAbsent(record.runId(), record);
            }
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("multiple runs claim the same idempotency request: " + scope);
        }
        return matches.isEmpty() ? null : hydrate(matches.values().iterator().next().runId());
    }

    public Map<String, Object> view(String runId) {
        RunHandle live = get(runId);
        if (live != null) return live.view();
        var local = ledger.find(runId);
        if (local.isPresent() && (local.get().status().isTerminal() || ownership == null || ownership.owns(local.get().threadId()))) return local.get().toView();
        return ledger.findPublished(runId).or(() -> local).map(this::externalView).orElse(null);
    }

    private boolean claimRecovered(String conversationId, String runId) {
        if (ownership == null) return true;
        if (ownership.owns(conversationId)) return ownership.ownsRun(conversationId, runId);
        if (!ownership.recover(conversationId, runId)) return false;
        try {
            var meta = archive.loadConversation(scope.scopeId(), conversationId).orElse(null);
            if (meta != null && runId.equals(meta.latestRunId())) return true;
        } catch (RuntimeException failure) {
            ownership.terminal(conversationId, runId); ownership.settle(conversationId, runId); throw failure;
        }
        ownership.terminal(conversationId, runId);
        ownership.settle(conversationId, runId);
        return false;
    }

    void settleOwnership(RunHandle run) {
        if (ownership != null && run.isTerminal()) ownership.settle(run.getThreadId(), run.getRunId());
    }

    private Map<String, Object> externalView(RunRecord record) {
        Map<String, Object> view = new LinkedHashMap<>(record.toView());
        boolean latest = record.status().isTerminal() || archive == null || archive.loadConversation(scope.scopeId(), record.threadId())
            .map(meta -> record.runId().equals(meta.latestRunId())).orElse(false);
        boolean occupied = !record.status().isTerminal() && latest && ownership != null && ownership.busyElsewhere(record.threadId());
        view.put("allowedActions", List.of()); view.put("controlState", "EXTERNAL");
        view.put("executing", false); view.put("ownsConversation", occupied);
        if (!record.status().isTerminal() && !occupied) view.put("status", RunStatus.INTERRUPTED.name());
        return view;
    }

    public List<Map<String, Object>> historyViews(String threadId, String status) {
        Map<String, Map<String, Object>> history = new LinkedHashMap<>();
        for (RunRecord record : ledger.published()) if (threadId == null || threadId.equals(record.threadId())) {
            history.put(record.runId(), externalView(record));
        }
        for (Map<String, Object> local : listViews(threadId, status)) {
            String id = String.valueOf(local.get("runId")); history.put(id, view(id));
        }
        return history.values().stream().filter(Objects::nonNull)
            .filter(view -> threadId == null || threadId.equals(String.valueOf(view.get("conversationId"))))
            .filter(view -> status == null || status.equalsIgnoreCase(String.valueOf(view.get("status"))))
            .sorted(Comparator.comparingLong(RunRegistry::createdAt).reversed()).toList();
    }

    public List<RunHandle> liveRuns() {
        return List.copyOf(runs.values());
    }

    /** Include child threads: an approval raised by a sub-agent still belongs to the root client's Run. */
    public boolean clientCanControlConversation(String conversationId, RunClient client) {
        for (RunHandle run : liveRuns()) {
            if (run.isTerminal()) continue;
            Map<String, Object> view = run.view();
            boolean member = conversationId.equals(run.getThreadId());
            if (!member && view.get("subagents") instanceof List<?> children) {
                member = children.stream().anyMatch(child -> child instanceof Map<?, ?> fields
                    && conversationId.equals(fields.get("subagentThreadId")));
            }
            if (member && !client.canControl(view.get("origin"))) return false;
        }
        return true;
    }

    public List<Map<String, Object>> listViews(String threadId, String status) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (RunRecord record : ledger.list()) {
            merged.put(record.runId(), record.toView());
        }
        for (RunHandle run : runs.values()) merged.put(run.getRunId(), run.view());
        return merged.values().stream()
            .filter(view -> threadId == null || threadId.equals(String.valueOf(view.get("threadId"))))
            .filter(view -> status == null || status.equalsIgnoreCase(String.valueOf(view.get("status"))))
            .sorted(Comparator.comparingLong(RunRegistry::createdAt).reversed()).toList();
    }

    boolean claimRequestId(String threadId, String requestId) {
        pruneIdempotencyCache();
        String key = threadId + ":" + requestId;
        return recentRequestIds.putIfAbsent(key, System.currentTimeMillis()) == null;
    }

    void releaseRequestId(String threadId, String requestId) {
        if (threadId != null && requestId != null) recentRequestIds.remove(threadId + ":" + requestId);
    }

    void persist(RunHandle run) {
        if (run == null) return;
        synchronized (run) {
            long expectedRevision = run.getRevision();
            long nextRevision = expectedRevision + 1L;
            String operationId = operationId();
            journal.prepare(operationId, run.getRunId(), RunJournalEntry.Kind.UPDATE_RUN, expectedRevision);
            ledger.persist(run.record(nextRevision, operationId));
            run.markPersisted(nextRevision, operationId);
            updateOpenRunIndex(run);
            if (ownership != null && run.isTerminal()) ownership.terminal(run.getThreadId(), run.getRunId());
            journal.commit(operationId, run.getRunId(), RunJournalEntry.Kind.UPDATE_RUN, expectedRevision);
            if (run.isTerminal()) journal.compact(run.getRunId());
        }
    }

    void remove(String runId) {
        runs.remove(runId);
    }

    String openRunId(String threadId) {
        return threadId == null ? null : openRunByThread.get(threadId);
    }

    Set<String> recoveryConflictIds(String threadId) {
        Set<String> ids = threadId == null ? null : recoveryConflicts.get(threadId);
        return ids == null ? Set.of() : ids;
    }

    void pruneIdempotencyCache() {
        long cutoff = System.currentTimeMillis() - IDEMPOTENCY_WINDOW_MS;
        recentRequestIds.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        if (recentRequestIds.size() >= IDEMPOTENCY_MAX_ENTRIES) {
            int target = IDEMPOTENCY_MAX_ENTRIES * 9 / 10;
            recentRequestIds.entrySet().stream().sorted(Comparator.comparingLong(Map.Entry::getValue))
                .limit(Math.max(0, recentRequestIds.size() - target)).map(Map.Entry::getKey).toList()
                .forEach(recentRequestIds::remove);
        }
    }

    private static long createdAt(Map<String, Object> view) {
        Object value = view.get("createdAt");
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String operationId() {
        return "op_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void rebuildOpenRunIndex() {
        List<RunRecord> records = ledger.list();
        if (records == null || records.isEmpty()) return;
        Map<String, List<RunRecord>> openByThread = records.stream().filter(record -> !record.status().isTerminal())
            .collect(Collectors.groupingBy(RunRecord::threadId));
        for (Map.Entry<String, List<RunRecord>> entry : openByThread.entrySet()) {
            List<RunRecord> open = entry.getValue();
            for (RunRecord record : open) runs.put(record.runId(), RunHandle.restore(record));
            if (open.size() == 1) {
                openRunByThread.put(entry.getKey(), open.get(0).runId());
            } else {
                recoveryConflicts.put(entry.getKey(), Set.copyOf(new HashSet<>(
                    open.stream().map(RunRecord::runId).toList())));
            }
        }
    }

    private void updateOpenRunIndex(RunHandle run) {
        if (run.isTerminal()) {
            openRunByThread.remove(run.getThreadId(), run.getRunId());
            Set<String> conflicts = recoveryConflicts.get(run.getThreadId());
            if (conflicts != null) {
                Set<String> remaining = new HashSet<>(conflicts);
                remaining.remove(run.getRunId());
                if (remaining.size() == 1) {
                    recoveryConflicts.remove(run.getThreadId());
                    openRunByThread.put(run.getThreadId(), remaining.iterator().next());
                } else if (remaining.isEmpty()) {
                    recoveryConflicts.remove(run.getThreadId());
                } else {
                    recoveryConflicts.put(run.getThreadId(), Set.copyOf(remaining));
                }
            }
        } else if (!recoveryConflicts.containsKey(run.getThreadId())) {
            openRunByThread.put(run.getThreadId(), run.getRunId());
        }
    }
}
