package com.harnex.submission;

import com.harnex.run.RunClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import com.harnex.conversation.*;
import com.harnex.execution.ScopeIdentity;
import com.harnex.contract.run.RunCreateRequest;
import com.harnex.model.StreamEvent;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.run.RunCoordinator;
import com.harnex.application.run.RunRequestService;
import com.harnex.submission.PendingSubmission.State;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/** One queue for text, media and Agent requests; a quiescent Goal still owns its conversation. */
@Slf4j @Service
public final class PendingSubmissionService {
    private static final Set<String> TRANSIENT = Set.of("thread_busy", "runtime_draining", "busy", "run_recovery_conflict", "automation_running");
    private final PendingSubmissionStore store;
    private final ConversationRepository archive;
    private final RunCoordinator runs;
    private final RunRequestService requests;
    private final ScopeIdentity scope;
    private final ScheduledExecutorService dispatcher = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "pending-submissions"); thread.setDaemon(true); return thread;
    });
    public PendingSubmissionService(PendingSubmissionStore store, ConversationRepository archive, RunCoordinator runs,
        RunRequestService requests, ScopeIdentity scope) {
        this.store = store; this.archive = archive; this.runs = runs; this.requests = requests; this.scope = scope;
    }
    @PostConstruct public void start() {
        runs.registerFollowUpReleaseHook(run -> dispatcher.execute(() -> dispatch(run.getThreadId())));
        dispatcher.scheduleWithFixedDelay(this::recover, 1, 2, TimeUnit.SECONDS);
    }
    @PreDestroy public void close() { runs.registerFollowUpReleaseHook(null); dispatcher.shutdownNow(); }
    public synchronized PendingSubmission enqueue(String conversationId, String predecessorTurnId, RunCreateRequest request) {
        return enqueue(conversationId, predecessorTurnId, request, RunClient.INTERNAL);
    }
    public synchronized PendingSubmission enqueue(String conversationId, String predecessorTurnId, RunCreateRequest request,
        RunClient client) {
        if (request == null || request.getClientRequestId() == null || request.getClientRequestId().isBlank()) throw failure("bad_request", "clientRequestId is required");
        String id = "submission_" + IdempotencyReceiptStore.requestHash(Map.of("conversation", conversationId, "request", request.getClientRequestId())).substring(0, 40);
        Map<String, Object> hashFields = new TreeMap<>(); hashFields.put("predecessorTurnId", predecessorTurnId); hashFields.put("request", request);
        String hash = IdempotencyReceiptStore.requestHash(hashFields);
        var existing = store.find(conversationId, id);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash)) throw failure("idempotency_conflict", "clientRequestId belongs to a different submission");
            return existing.get();
        }
        var meta = conversation(conversationId);
        if (predecessorTurnId == null || archive.findTurn(scope.scopeId(), conversationId, predecessorTurnId).isEmpty()) throw failure("predecessor_required", "Select an existing predecessor turn");
        var leaf = archive.findMessage(scope.scopeId(), conversationId, meta.activeLeafId()).orElseThrow();
        if (!predecessorTurnId.equals(leaf.turnId())) throw failure("predecessor_conflict", "Queue after the current branch's latest turn");
        var records = store.list(conversationId);
        if (records.stream().filter(PendingSubmission::pending).count() >= 20) throw failure("submission_limit", "The conversation already has 20 pending submissions");
        var frozen = requests.freezeSubmission(request, client);
        String predecessorSubmission = records.stream().filter(PendingSubmission::pending).reduce((a, b) -> b).map(PendingSubmission::submissionId).orElse(null);
        long now = System.currentTimeMillis();
        var value = new PendingSubmission(1, id, scope.scopeId(), conversationId, records.stream().mapToLong(PendingSubmission::ordinal).max().orElse(0) + 1,
            request.getClientRequestId(), hash, predecessorTurnId, predecessorSubmission, frozen, State.QUEUED, 1, now, now, null, null, null, null);
        store.save(value); publish(value); return value;
    }
    public List<PendingSubmission> list(String conversationId) { conversation(conversationId); return store.list(conversationId); }
    public synchronized PendingSubmission cancel(String conversationId, String id) {
        var value = store.find(conversationId, id).orElseThrow(() -> failure("submission_not_found", "Submission not found"));
        if (value.state() == State.CANCELLED) return value;
        if (value.state() == State.STARTING || value.state() == State.STARTED) throw failure("submission_started", "Cancel the admitted Run instead");
        value = store.save(value.transition(State.CANCELLED, value.materialization(), value.runId(), value.turnId(), "cancelled"));
        publish(value); return value;
    }
    private void recover() {
        try { for (String id : store.conversations()) dispatch(id); }
        catch (RuntimeException failure) { log.warn("Pending submission recovery failed: {}", failure.toString()); }
    }
    public synchronized void dispatch(String conversationId) {
        var value = store.list(conversationId).stream().filter(PendingSubmission::pending).findFirst().orElse(null);
        if (value == null || value.state() == State.HELD) return;
        ConversationMeta meta;
        try { meta = conversation(conversationId); }
        catch (ConversationException deleted) { hold(value, "conversation_unavailable"); return; }
        if (value.state() == State.QUEUED) {
            if (runs.listViews(conversationId, null).stream().anyMatch(run -> Boolean.TRUE.equals(run.get("ownsConversation")))) return;
            String turnId = value.predecessorTurnId();
            if (value.predecessorSubmissionId() != null) {
                var predecessor = store.find(conversationId, value.predecessorSubmissionId()).orElseThrow();
                if (predecessor.state() != State.STARTED) { hold(value, "predecessor_submission_incomplete"); return; }
                turnId = predecessor.turnId();
            }
            var turn = archive.findTurn(scope.scopeId(), conversationId, turnId).orElseThrow();
            var answer = archive.findMessage(scope.scopeId(), conversationId, turn.activeAttemptId()).orElseThrow();
            var run = runs.view(turn.runId());
            if (answer.isMutable() || (run != null && Boolean.TRUE.equals(run.get("ownsConversation")))) return;
            if (answer.status() != MessageRecord.Status.COMPLETED || run == null || !"COMPLETED".equals(run.get("status"))) { hold(value, "predecessor_incomplete"); return; }
            if (!answer.messageId().equals(meta.activeLeafId())) { hold(value, "branch_changed"); return; }
            value = store.save(value.transition(State.STARTING, new PendingSubmission.Materialization(meta.historyRevision(), answer.messageId()), null, null, null));
        }
        // STARTING always reuses these exact bytes, including the previously materialized parent and revision.
        var request = new RunCreateRequest(); request.setConversationId(conversationId); request.setClientRequestId(value.submissionId());
        request.setExecution(value.request().execution()); request.setInput(value.request().input()); request.setAttachmentIds(value.request().attachmentIds());
        request.setChannel(value.request().channel()); request.setExpectedHistoryRevision(value.materialization().historyRevision()); request.setParentMessageId(value.materialization().parentMessageId());
        request.setNotificationMode(value.request().notificationMode());
        String predecessorTurn = value.predecessorSubmissionId() == null ? value.predecessorTurnId()
            : store.find(conversationId, value.predecessorSubmissionId()).orElseThrow().turnId();
        var created = requests.createQueued(request, value.request().configurationRef(), predecessorTurn);
        if (created.rejectCode() != null) {
            if (TRANSIENT.contains(created.rejectCode())) return;
            value = store.save(value.transition(State.HELD, value.materialization(), null, null, created.rejectCode()));
        } else {
            var run = created.run();
            value = store.save(value.transition(State.STARTED, value.materialization(), run.getRunId(), run.getSubmission() == null ? null : run.getSubmission().turnId(), null));
        }
        publish(value);
    }
    private void hold(PendingSubmission value, String reason) { publish(store.save(value.transition(State.HELD, value.materialization(), null, null, reason))); }
    private ConversationMeta conversation(String id) {
        var meta = archive.loadConversation(scope.scopeId(), id).orElseThrow(() -> failure("conversation_not_found", "Conversation not found"));
        if (meta.deleted()) throw failure("conversation_deleted", "Conversation was deleted");
        return meta;
    }
    private void publish(PendingSubmission value) {
        var meta = archive.loadConversation(scope.scopeId(), value.conversationId()).orElse(null);
        var run = meta == null ? null : runs.get(meta.latestRunId());
        if (run != null) runs.eventBus().publish(run, StreamEvent.of("submission_updated", Map.of("submissionId", value.submissionId(), "revision", value.revision(), "state", value.state())));
    }
    private static ConversationException failure(String code, String text) { return new ConversationException(code, text); }
}
