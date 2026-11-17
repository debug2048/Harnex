package com.harnex.run;

import com.harnex.core.AgentExecutionMapper;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.harnex.conversation.CommitReceipt;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.core.StepResult;
import com.harnex.execution.AgentSpec;
import com.harnex.execution.ConversationSpec;
import com.harnex.execution.ExecutionSpec;
import com.harnex.execution.TextTarget;
import com.harnex.model.StreamEvent;
import com.harnex.util.Ids;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Transport-neutral owner of every root execution lifecycle.
 *
 * <p>Web, gateway, cron and CLI adapters all acquire a {@link RunLease} here before invoking an execution worker. The lease
 * centralises single-flight reservation, discovery, event correlation, cancellation and Supervisor drain without
 * forcing in-process adapters through HTTP.</p>
 */
@Slf4j
@Service
public class RunCoordinator {

    private static final long FINISHED_TTL_MS = 5 * 60 * 1000L;
    private static final long MAX_RUN_TTL_MS = 4 * 60 * 60 * 1000L;

    private final RunRegistry registry;
    private final RunEventBus events;
    private final RunActivity active;
    private final IdempotencyReceiptStore receipts;
    private final RunInputStore inputs;
    @Autowired(required = false)
    private RunArchiveSink archiveSink;
    @Autowired(required = false)
    private RunNotificationSupport notifications;
    private final Object admissionLock = new Object();
    private volatile Consumer<RunHandle> followUpReleaseHook = ignored -> {};
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "run-coordinator");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public RunCoordinator(RunRegistry registry, RunEventBus events, RunActivity active,
        IdempotencyReceiptStore receipts, RunInputStore inputs) {
        this.registry = registry;
        this.events = events;
        this.active = active;
        this.receipts = receipts;
        this.inputs = inputs;
        reconcileTerminalInputs();
        scheduler.scheduleWithFixedDelay(this::janitor, 60, 60, TimeUnit.SECONDS);
    }

    public BeginResult begin(String threadId, RunSpec spec) {
        return begin(threadId, spec, null, null, null);
    }

    /**
     * Atomically applies deletion, idempotency and single-flight admission. Deletion is deliberately checked before
     * either 409-class rejection so a recently deleted thread consistently returns 410 instead of being masked by a
     * stale reservation or request id.
     */
    public BeginResult begin(String threadId, RunSpec spec, String requestScope, String requestId) {
        String requestHash = requestId == null || requestId.isBlank() ? null : IdempotencyReceiptStore.requestHash(
            Map.of(
                "threadId", threadId == null ? "" : threadId,
                "origin", spec == null || spec.origin() == null ? "" : spec.origin().toView(),
                "goalId", spec == null || spec.goalId() == null ? "" : spec.goalId(),
                "provider", spec == null || spec.provider() == null ? "" : spec.provider(),
                "model", spec == null || spec.model() == null ? "" : spec.model(),
                "reasoningEffort", spec == null || spec.reasoningEffort() == null ? "" : spec.reasoningEffort(),
                "toolsets", spec == null || spec.toolsets() == null ? List.of() : spec.toolsets(),
                "permissionMode", spec == null || spec.permissionMode() == null ? "" : spec.permissionMode(),
                "notificationMode", RunNotification.Mode.orOff(spec == null ? null : spec.notificationMode())));
        return begin(threadId, spec, requestScope, requestId, requestHash);
    }

    /** Holds ownership during an explicit history/Snapshot mutation without holding a lock during file I/O. */
    public MutationLease tryReserveConversationMutation(String conversationId) {
        if (!Ids.isSafe(conversationId)) return null;
        synchronized (admissionLock) {
            if (!registry.isAccepting() || registry.openRunId(conversationId) != null
                || activeAutomation() != null
                || !registry.recoveryConflictIds(conversationId).isEmpty()
                || !active.tryReserve(conversationId, ExecutionSpec.Kind.CONVERSATION)) return null;
            return new MutationLease(conversationId);
        }
    }
    public final class MutationLease implements AutoCloseable {
        private final String conversationId;
        private final AtomicBoolean closed = new AtomicBoolean();
        private MutationLease(String id) { conversationId = id; }
        @Override public void close() { if (closed.compareAndSet(false,true)) active.releaseReservation(conversationId); }
    }

    public BeginResult begin(String threadId, RunSpec spec, String requestScope, String requestId,
        String requestHash) {
        if (threadId == null || !Ids.isSafe(threadId)) {
            return BeginResult.rejected("bad_request", "invalid threadId format");
        }
        if (spec == null || spec.origin() == null || spec.execution() == null
            || spec.execution().kind() == ExecutionSpec.Kind.AGENT && spec.options() == null) {
            return BeginResult.rejected("bad_request", "run origin and execution options are required");
        }
        if (spec.goalId() != null && !Ids.isSafeCallId(spec.goalId())) {
            return BeginResult.rejected("bad_request", "invalid goalId format");
        }
        synchronized (admissionLock) {
            if (!registry.isAccepting()) {
                return BeginResult.rejected("runtime_draining",
                    "the current Runtime is closing and no longer accepts new runs");
            }
            BeginResult deleted = deletionRejection(threadId);
            if (deleted != null) return deleted;
            boolean requestClaimed = requestId != null && !requestId.isBlank();
            ReceiptIntent receiptIntent = requestClaimed
                ? new ReceiptIntent(requestScope, requestId, requestHash) : null;
            if (requestClaimed) {
                if (requestScope == null || requestScope.isBlank() || requestHash == null || requestHash.isBlank()) {
                    return BeginResult.rejected("bad_request", "idempotent run creation requires scope and hash");
                }
                IdempotencyReceiptRecord receipt = receipts.find("run.create", requestScope, requestId).orElse(null);
                if (receipt != null) {
                    if (!receipt.requestHash().equals(requestHash)) {
                        return BeginResult.rejected("idempotency_conflict",
                            "clientRequestId was already used with a different run request");
                    }
                    RunHandle replayed = registry.hydrate(receipt.resourceId());
                    if (replayed == null) {
                        return BeginResult.rejected("idempotency_resource_gone",
                            "the original idempotent run result is no longer available");
                    }
                    return BeginResult.replayed(replayed);
                }
                RunHandle claimedRun = registry.findByCreationRequest(requestScope, requestId);
                if (claimedRun != null) {
                    RunRecord.IdempotencyClaim claim = claimedRun.getCreationRequest();
                    if (!claim.requestHash().equals(requestHash)) {
                        return BeginResult.rejected("idempotency_conflict",
                            "clientRequestId was already used with a different run request");
                    }
                    commitReceipt(claimedRun, receiptIntent);
                    return BeginResult.replayed(claimedRun);
                }
            }
            // Automation takes an idle Runtime exclusively. This check and reservation share admissionLock,
            // so a foreground submission cannot race a timer into concurrent workspace mutations.
            boolean automation = spec.origin().kind() == RunOrigin.Kind.AUTOMATION;
            RunHandle executingAutomation = activeAutomation();
            if (automation && (active.activeCount() > 0 || registry.liveRuns().stream().anyMatch(run -> !run.isTerminal()))) {
                return BeginResult.rejected("automation_runtime_busy", "The execution environment is busy; automation is waiting for it to become idle");
            }
            if (!automation && executingAutomation != null) {
                return BeginResult.rejected("automation_running", "An automation is running in this environment. Wait for it to finish or cancel it from Automations before submitting another task.");
            }
            RunNotification notification = null;
            if (RunNotification.Mode.orOff(spec.notificationMode()) != RunNotification.Mode.OFF) {
                if (notifications == null || spec.origin().kind() == RunOrigin.Kind.GATEWAY
                    || spec.execution().kind() != ExecutionSpec.Kind.AGENT) {
                    return BeginResult.rejected("notification_unavailable", "IM 提醒仅适用于项目中的 Agent、Goal 和自动化任务");
                }
                try { notification = notifications.capture(spec.notificationMode()); }
                catch (IllegalArgumentException invalid) {
                    return BeginResult.rejected("notification_recipient_required", invalid.getMessage());
                }
            }
            if (requestClaimed && !registry.claimRequestId(requestScope, requestId)) {
                return BeginResult.rejected("duplicate_request",
                    "an equivalent clientRequestId is still being committed");
            }
            Set<String> conflictingRuns = registry.recoveryConflictIds(threadId);
            if (!conflictingRuns.isEmpty()) {
                if (requestClaimed) registry.releaseRequestId(requestScope, requestId);
                return BeginResult.rejected("run_recovery_conflict",
                    "multiple persisted open runs require recovery for this thread: "
                        + String.join(",", conflictingRuns));
            }
            String openRunId = registry.openRunId(threadId);
            if (openRunId != null) {
                if (requestClaimed) registry.releaseRequestId(requestScope, requestId);
                return BeginResult.rejected("thread_busy",
                    "thread already owns non-terminal run " + openRunId);
            }
            if (!active.tryReserve(threadId, spec.execution().kind())) {
                if (requestClaimed) registry.releaseRequestId(requestScope, requestId);
                deleted = deletionRejection(threadId);
                if (deleted != null) return deleted;
                return BeginResult.rejected("thread_busy", "another run is already in progress for this thread");
            }
            try {
                deleted = deletionRejection(threadId);
                if (deleted != null) {
                    active.releaseReservation(threadId);
                    if (requestClaimed) registry.releaseRequestId(requestScope, requestId);
                    return deleted;
                }
                String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
                RunRecord.IdempotencyClaim creationRequest = requestClaimed
                    ? new RunRecord.IdempotencyClaim(requestScope, requestId, requestHash) : null;
                RunHandle handle = registry.register(runId, threadId, spec.origin(), spec.options(), spec.provider(),
                    spec.model(), spec.reasoningEffort(), creationRequest, spec.execution(), spec.submission(), notification);
                if (spec.toolsets() != null || spec.permissionMode() != null) {
                    handle.captureExecutionConfig(spec.toolsets(), spec.permissionMode());
                }
                if (spec.goalId() != null) {
                    handle.associateGoal(spec.goalId());
                }
                if (spec.goalId() != null || spec.toolsets() != null || spec.permissionMode() != null) {
                    registry.persist(handle);
                }
                try {
                    events.attach(handle);
                } catch (RuntimeException streamFailure) {
                    handle.finish(RunStatus.FAILED, "stream cursor initialisation failed");
                    registry.persist(handle);
                    registry.settleOwnership(handle);
                    throw streamFailure;
                }
                RunLease lease = new RunLease(this, handle, receiptIntent);
                // Commit the exactly-once result before the admitted Run escapes this atomic admission section. If
                // the JVM exits after the RunRecord replace but before this receipt replace, creationRequest above
                // lets the next Runtime reconstruct the missing receipt and replay the same runId.
                lease.commitIdempotency();
                if (notification != null) notifications.track(handle);
                return BeginResult.started(lease);
            } catch (RuntimeException failure) {
                active.releaseReservation(threadId);
                if (requestClaimed) registry.releaseRequestId(requestScope, requestId);
                throw failure;
            }
        }
    }

    /**
     * Resolve an already committed create request without attempting a new admission. This is used when the
     * resource revision has legitimately advanced as part of the first create transaction: an exact retry must
     * replay the original Run instead of being rejected by the newer Goal revision.
     */
    public BeginResult replayCreate(String requestScope, String requestId, String requestHash) {
        if (requestScope == null || requestScope.isBlank() || requestId == null || requestId.isBlank()
            || requestHash == null || requestHash.isBlank()) {
            return BeginResult.rejected("bad_request", "idempotent run creation requires scope, key and hash");
        }
        synchronized (admissionLock) {
            IdempotencyReceiptRecord receipt = receipts.find("run.create", requestScope, requestId).orElse(null);
            if (receipt != null) {
                if (!receipt.requestHash().equals(requestHash)) {
                    return BeginResult.rejected("idempotency_conflict",
                        "clientRequestId was already used with a different run request");
                }
                RunHandle replayed = registry.hydrate(receipt.resourceId());
                return replayed == null
                    ? BeginResult.rejected("idempotency_resource_gone",
                        "the original idempotent run result is no longer available")
                    : BeginResult.replayed(replayed);
            }
            RunHandle claimedRun = registry.findByCreationRequest(requestScope, requestId);
            if (claimedRun == null) {
                return BeginResult.rejected("idempotency_not_found", "no committed run exists for this request");
            }
            RunRecord.IdempotencyClaim claim = claimedRun.getCreationRequest();
            if (!claim.requestHash().equals(requestHash)) {
                return BeginResult.rejected("idempotency_conflict",
                    "clientRequestId was already used with a different run request");
            }
            commitReceipt(claimedRun, new ReceiptIntent(requestScope, requestId, requestHash));
            return BeginResult.replayed(claimedRun);
        }
    }

    public void releaseRequestId(String threadId, String requestId) {
        registry.releaseRequestId(threadId, requestId);
    }

    /** Acquire the single JVM worker for another Step of an existing persisted open Run. */
    public BeginResult acquireExisting(String runId, long expectedRevision) {
        return acquireExisting(runId, expectedRevision, null);
    }

    /** Acquire an existing Run while durably claiming the idempotent control request that caused the mutation. */
    public BeginResult acquireExisting(String runId, long expectedRevision, RunRecord.ControlClaim controlClaim) {
        if (!Ids.isSafeCallId(runId)) return BeginResult.rejected("bad_request", "invalid runId format");
        synchronized (admissionLock) {
            if (!registry.isAccepting()) {
                return BeginResult.rejected("runtime_draining", "the current Runtime is closing");
            }
            RunHandle run = registry.hydrate(runId);
            if (run == null) return BeginResult.rejected("run_not_found", "no such Run");
            if (expectedRevision > 0 && run.getRevision() != expectedRevision) {
                return BeginResult.rejected("revision_conflict", "Run revision conflict; current=" + run.getRevision());
            }
            if (run.isTerminal()) return BeginResult.rejected("run_terminal", "terminal Run cannot resume");
            if (run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PAUSE_REQUESTED) {
                return BeginResult.rejected("run_busy", "Run already owns an executing Step");
            }
            if (!active.tryReserve(run.getThreadId(), run.getExecutionSpec().kind())) {
                return BeginResult.rejected("thread_busy", "another execution owns this Thread");
            }
            try {
                BeginResult deleted = deletionRejection(run.getThreadId());
                if (deleted != null) {
                    active.releaseReservation(run.getThreadId());
                    return deleted;
                }
                run.prepareResume();
                run.recordControlClaim(controlClaim);
                registry.persist(run);
                return BeginResult.started(new RunLease(this, run, null));
            } catch (RuntimeException failure) {
                active.releaseReservation(run.getThreadId());
                throw failure;
            }
        }
    }

    private void commitReceipt(RunHandle run, ReceiptIntent intent) {
        if (intent == null) return;
        receipts.commit("run.create", intent.scope(), intent.requestId(), intent.requestHash(),
            Map.of("runId", run.getRunId(), "threadId", run.getThreadId(), "status", run.getStatus().name()),
            run.getRunId(), run.getRevision());
        registry.releaseRequestId(intent.scope(), intent.requestId());
    }

    private void abandonReceipt(ReceiptIntent intent) {
        if (intent != null) registry.releaseRequestId(intent.scope(), intent.requestId());
    }

    private BeginResult deletionRejection(String threadId) {
        String rejection = active.deletionRejection(threadId);
        return rejection == null ? null : BeginResult.rejected(rejection, "Conversation was deleted before this execution could start");
    }

    public RunHandle get(String runId) {
        return registry.get(runId);
    }

    /** Locate a durable submission without retrying it or granting execution authority. */
    public RunHandle findCreation(String requestScope, String requestId) {
        synchronized (admissionLock) {
            IdempotencyReceiptRecord receipt = receipts.find("run.create", requestScope, requestId).orElse(null);
            return receipt == null ? registry.findByCreationRequest(requestScope, requestId) : registry.hydrate(receipt.resourceId());
        }
    }

    /** Recover public history and the Run ledger without restarting paid work from the former process. */
    public void finishRecoveredResult(String runId, CommitReceipt receipt, RunStatus status, String detail) {
        RunHandle run = registry.get(runId);
        if (run == null || !run.isRecovered() || run.isTerminal() || run.getGoalId() != null) return;
        if (receipt != null) run.recordResultReceipt(receipt, run.getErrorCode());
        finish(run, status, detail);
    }

    public Map<String, Object> view(String runId) {
        Map<String, Object> view = registry.view(runId);
        if (view == null) return null;
        Map<String, Object> enriched = new LinkedHashMap<>(view);
        enriched.putAll(events.streamView(runId));
        enriched.put("pendingInputCount", inputs.pendingCount(runId));
        return enriched;
    }

    public List<Map<String, Object>> listViews(String threadId, String status) {
        return registry.listViews(threadId, status).stream().map(view -> {
            Map<String, Object> enriched = new LinkedHashMap<>(view);
            enriched.putAll(events.streamView(String.valueOf(view.get("runId"))));
            enriched.put("pendingInputCount", inputs.pendingCount(String.valueOf(view.get("runId"))));
            return enriched;
        }).toList();
    }

    public long activeRunCount() {
        Set<String> threads = new HashSet<>(active.activeConversationIds());
        registry.liveRuns().stream().filter(run -> !run.isTerminal()).map(RunHandle::getThreadId).forEach(threads::add);
        return threads.size();
    }

    public List<Map<String, Object>> historyViews(String conversationId, String status) {
        return registry.historyViews(conversationId, status);
    }

    private RunHandle activeAutomation() {
        return registry.liveRuns().stream().filter(run -> !run.isTerminal()
            && run.getOrigin().kind() == RunOrigin.Kind.AUTOMATION).findFirst().orElse(null);
    }
    /** Atomically refuse new work only when no Run or mutation is using this Runtime. Never cancels a task. */
    public boolean stopAcceptingIfIdle() {
        synchronized (admissionLock) {
            if (!registry.isAccepting() || activeRunCount() != 0) return false;
            registry.stopAccepting();
            return true;
        }
    }

    public boolean cancel(String runId) {
        return cancel(runId, null);
    }

    public boolean cancel(String runId, RunRecord.ControlClaim controlClaim) {
        RunHandle run = registry.get(runId);
        if (run == null || run.isTerminal()) return false;
        return signalCancel(run, true, controlClaim);
    }

    private boolean signalCancel(RunHandle run, boolean finishDetached) {
        return signalCancel(run, finishDetached, null);
    }

    private boolean signalCancel(RunHandle run, boolean finishDetached, RunRecord.ControlClaim controlClaim) {
        run.recordControlClaim(controlClaim);
        run.requestCancel();
        registry.persist(run);
        boolean signalled = active.cancel(run.getThreadId(), run.getExecutionSpec().kind());
        RunWorker context = run.getWorkerRef().get();
        if (context != null) {
            context.cancel();
            signalled = true;
        }
        // A hydrated PAUSED/INTERRUPTED/open run has no old JVM worker to signal. Cancelling it is still a durable
        // control operation and must release the thread's logical open-run ownership.
        if (finishDetached && !signalled && context == null && !active.isLive(run.getThreadId())) {
            finish(run, RunStatus.CANCELLED, null);
            signalled = true;
        }
        return signalled;
    }

    /** Internal C0 control primitive. Product REST exposure is introduced by the Goal control phase. */
    public boolean pause(String runId) {
        return pause(runId, null);
    }

    public boolean pause(String runId, RunRecord.ControlClaim controlClaim) {
        RunHandle run = registry.get(runId);
        if (run == null || run.isTerminal() || run.getExecutionSpec().kind() != ExecutionSpec.Kind.AGENT || !run.requestPause()) return false;
        run.recordControlClaim(controlClaim);
        registry.persist(run);
        RunWorker context = run.getWorkerRef().get();
        if (context == null && !active.isLive(run.getThreadId())) markPaused(run, "NO_ACTIVE_STEP");
        return true;
    }

    /** Internal safe-point StepCancel primitive; it never aliases terminal RunCancel. */
    public boolean cancelStep(String runId) {
        RunHandle run = registry.get(runId);
        if (run == null || run.isTerminal() || run.getExecutionSpec().kind() != ExecutionSpec.Kind.AGENT || !run.requestStepCancel()) return false;
        registry.persist(run);
        if (run.getWorkerRef().get() == null && !active.isLive(run.getThreadId())) {
            markQueuedAfterStepCancel(run, "NO_ACTIVE_STEP");
        }
        return true;
    }

    public boolean submitGoalCompletion(String runId, String status, String note,
        List<Map<String, Object>> evidence) {
        RunHandle run = get(runId);
        if (run == null || run.isTerminal() || run.getGoalId() == null) return false;
        run.requestGoalCompletion(status, note, evidence == null ? List.of() : evidence);
        registry.persist(run);
        events.publish(run, StreamEvent.of("goal_completion_requested", Map.of(
            "goalId", run.getGoalId(), "stepId", run.getCheckpoint().stepId() == null
                ? "" : run.getCheckpoint().stepId(), "status", status,
            "evidenceCount", evidence == null ? 0 : evidence.size())));
        return true;
    }

    /** Startup recovery never revives an old AgentCtx or automatically burns tokens. */
    public boolean recoverGoalRunPaused(String runId, String reason) {
        RunHandle run = registry.get(runId);
        if (run == null || run.isTerminal() || run.getGoalId() == null || active.isLive(run.getThreadId())) {
            return false;
        }
        if (run.isCancelRequested() || run.getControlSignal().isRunCancelRequested()) {
            if (run.getGoalExecution() != null && run.getGoalExecution().stepStartedAt() > 0) {
                finishGoalStep(run, "CANCELLED", "cancel request recovered after runtime restart", null, false);
            }
            finish(run, RunStatus.CANCELLED, null);
            return true;
        }
        if (run.getGoalExecution() != null && run.getGoalExecution().stepStartedAt() > 0) {
            finishGoalStep(run, "INTERRUPTED", reason, null, false);
        }
        run.transitionOpen(RunStatus.INTERRUPTED, reason);
        registry.persist(run);
        events.publish(run, StreamEvent.of("goal_recovered", Map.of(
            "goalId", run.getGoalId(), "status", RunStatus.INTERRUPTED.name(), "autoResume", false,
            "reason", reason == null ? "runtime_restart" : reason)));
        run.transitionOpen(RunStatus.PAUSED, reason);
        registry.persist(run);
        return true;
    }

    public boolean confirmGoalReview(String runId, long expectedRevision) {
        return confirmGoalReview(runId, expectedRevision, null);
    }

    public boolean confirmGoalReview(String runId, long expectedRevision,
        List<RunRecord.CriterionResult> refreshedResults) {
        return confirmGoalReview(runId, expectedRevision, refreshedResults, null);
    }

    public boolean confirmGoalReview(String runId, long expectedRevision,
        List<RunRecord.CriterionResult> refreshedResults, RunRecord.ControlClaim controlClaim) {
        RunHandle run = registry.get(runId);
        if (run == null || run.isTerminal() || run.getGoalId() == null
            || run.getRevision() != expectedRevision || run.getStatus() != RunStatus.READY_FOR_REVIEW) {
            return false;
        }
        List<RunRecord.CriterionResult> results = refreshedResults == null
            ? run.getGoalExecution().criteriaResults() : refreshedResults;
        run.recordControlClaim(controlClaim);
        run.applyGoalDecision(0, null, results, true, "user review confirmed");
        registry.persist(run);
        events.publish(run, StreamEvent.of("goal_completion_review", Map.of(
            "goalId", run.getGoalId(), "decision", "confirm", "revision", run.getRevision())));
        finish(run, RunStatus.COMPLETED, null);
        return true;
    }

    public boolean invalidateGoalReview(String runId, long expectedRevision,
        List<RunRecord.CriterionResult> refreshedResults, String reason) {
        return invalidateGoalReview(runId, expectedRevision, refreshedResults, reason, null);
    }

    public boolean invalidateGoalReview(String runId, long expectedRevision,
        List<RunRecord.CriterionResult> refreshedResults, String reason, RunRecord.ControlClaim controlClaim) {
        RunHandle run = registry.get(runId);
        if (run == null || run.isTerminal() || run.getGoalId() == null
            || run.getRevision() != expectedRevision || run.getStatus() != RunStatus.READY_FOR_REVIEW) return false;
        run.recordControlClaim(controlClaim);
        run.applyGoalDecision(0, null, refreshedResults, true, reason);
        run.transitionOpen(RunStatus.PAUSED, reason);
        registry.persist(run);
        events.publish(run, StreamEvent.of("goal_review_invalidated", Map.of(
            "goalId", run.getGoalId(), "status", RunStatus.PAUSED.name(), "reason", reason)));
        return true;
    }

    /** Persist a control outcome without changing business state, e.g. a converged retry or dispatch rejection. */
    public boolean recordControlClaim(String runId, RunRecord.ControlClaim controlClaim) {
        RunHandle run = registry.get(runId);
        if (run == null || controlClaim == null) return false;
        run.recordControlClaim(controlClaim);
        registry.persist(run);
        return true;
    }

    /** Thread-scoped control adapter used by delete/cron/CLI surfaces that do not hold a runId. */
    public boolean cancelThread(String threadId) {
        if (threadId == null) return false;
        boolean signalled = false;
        for (RunHandle run : registry.liveRuns()) {
            if (threadId.equals(run.getThreadId()) && !run.isTerminal()) {
                signalled |= cancel(run.getRunId());
            }
        }
        if (!signalled) signalled = active.cancel(threadId);
        return signalled;
    }

    /** Reject new roots, pause Goal Runs and cancel ordinary Runs, then wait for current workers to leave safely. */
    public Map<String, Object> drain(long timeoutMs) {
        List<RunHandle> activeRuns;
        int activeThreads;
        synchronized (admissionLock) {
            registry.stopAccepting();
            activeRuns = registry.liveRuns().stream().filter(run -> !run.isTerminal()).toList();
            activeThreads = active.activeCount();
            activeRuns.forEach(run -> {
                if (run.getGoalId() != null) pause(run.getRunId());
                else cancel(run.getRunId());
            });
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline && active.activeCount() > 0) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (active.activeCount() > 0) {
            // The old JVM cannot remain a writer after Supervisor CLOSE. Preserve Goal recoverability as INTERRUPTED,
            // then cooperatively cancel only the in-memory Step/Tool context. closeLease recognises this drain state
            // and must not collapse the persisted Goal Run into terminal CANCELLED.
            for (RunHandle run : activeRuns) {
                if (run.getGoalId() != null && active.isLive(run.getThreadId())) {
                    run.transitionOpen(RunStatus.INTERRUPTED, "workspace drain timeout");
                    registry.persist(run);
                    active.interruptExecution(run.getThreadId());
                    RunWorker context = run.getWorkerRef().get();
                    if (context != null) context.interruptExecution();
                }
            }
        }
        long remainingRuns = activeRuns.stream().filter(run -> active.isLive(run.getThreadId())).count();
        int remainingThreads = active.activeCount();
        return Map.of(
            "requested", Math.max(activeRuns.size(), activeThreads),
            "remaining", Math.max(remainingRuns, remainingThreads),
            "drained", remainingRuns == 0 && remainingThreads == 0);
    }

    public RunEventBus eventBus() {
        return events;
    }

    /** Register the single Follow-up dispatcher. It is invoked only after this lease released Thread reservation. */
    public void registerFollowUpReleaseHook(Consumer<RunHandle> hook) {
        followUpReleaseHook = hook == null ? ignored -> {} : hook;
    }

    private void updateOptions(RunHandle run, AgentExecutionOptions options) {
        run.updateOptions(options);
        registry.persist(run);
    }

    private void updateRequestedReasoning(RunHandle run, String reasoningEffort) {
        run.updateRequestedReasoning(reasoningEffort);
        registry.persist(run);
    }

    private void updateExecutionConfig(RunHandle run, List<String> toolsets, String permissionMode) {
        run.captureExecutionConfig(toolsets, permissionMode);
        registry.persist(run);
    }

    private void markRunning(RunHandle run) {
        run.markRunning();
        registry.persist(run);
        archiveStateChanged(run);
    }

    private void retain(RunHandle run, RunStatus status, String reason) {
        if (run.isTerminal() || run.getControlSignal().isRunCancelRequested()) return;
        run.transitionOpen(status, reason);
        registry.persist(run);
        archiveStateChanged(run);
        events.publish(run, StreamEvent.of("goal_state_changed",
            Map.of("goalId", run.getGoalId() == null ? "" : run.getGoalId(), "status", status.name(),
                "revision", run.getRevision(), "reason", reason == null ? "" : reason)));
    }

    private String startGoalStep(RunHandle run) {
        String stepId = run.startGoalStep();
        registry.persist(run);
        events.publish(run, StreamEvent.of("goal_step_started", Map.of(
            "goalId", run.getGoalId(), "stepId", stepId,
            "index", run.getGoalExecution().steps().size())));
        return stepId;
    }

    private void finishGoalStep(RunHandle run, String status, String error, String progressFingerprint,
        boolean consumeBusinessTurn) {
        String stepId = run.getCheckpoint().stepId();
        run.finishGoalStep(status, error, progressFingerprint, consumeBusinessTurn);
        registry.persist(run);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goalId", run.getGoalId());
        data.put("stepId", stepId == null ? "" : stepId);
        data.put("status", status);
        data.put("turnsUsed", run.getGoalExecution().turnsUsed());
        if (error != null) data.put("error", error);
        if (progressFingerprint != null) data.put("progressFingerprint", progressFingerprint);
        events.publish(run, StreamEvent.of("goal_step_finished", data));
    }

    private void scheduleGoalRetry(RunHandle run, String fingerprint, long retryAt, String reason) {
        run.scheduleGoalRetry(fingerprint, retryAt, reason);
        registry.persist(run);
        events.publish(run, StreamEvent.of("goal_retry_scheduled", Map.of(
            "goalId", run.getGoalId(), "attempt", run.getGoalExecution().retryAttempts(),
            "retryAt", retryAt, "fingerprint", fingerprint == null ? "" : fingerprint)));
    }

    public void applyGoalDecision(String runId, int blockerCount, String blockerFingerprint,
        List<RunRecord.CriterionResult> results, boolean clearCompletionRequest, String reason) {
        RunHandle run = registry.get(runId);
        if (run == null || run.isTerminal() || run.getGoalId() == null) return;
        run.applyGoalDecision(blockerCount, blockerFingerprint, results, clearCompletionRequest, reason);
        registry.persist(run);
        events.publish(run, StreamEvent.of("goal_criteria_evaluated", Map.of(
            "goalId", run.getGoalId(), "results", results == null ? List.of()
                : results.stream().map(RunRecord.CriterionResult::toView).toList(),
            "blockerCount", Math.max(0, blockerCount))));
    }

    public void recordGoalCost(String runId, RunRecord.CostUsage costUsage) {
        RunHandle run = registry.get(runId);
        if (run == null || run.isTerminal() || run.getGoalId() == null || costUsage == null) return;
        run.recordGoalCost(costUsage);
        registry.persist(run);
    }

    private void markPaused(RunHandle run, String phase) {
        if (run.isTerminal() || run.getControlSignal().isRunCancelRequested()) return;
        RunRecord.StepPhase checkpointPhase = updateCheckpointPhase(run, phase);
        run.markPaused();
        registry.persist(run);
        archiveStateChanged(run);
        events.publish(run, StreamEvent.of("run_control_checkpoint",
            Map.of("control", "PAUSE", "phase", checkpointPhase.name(), "boundary", phase,
                "status", RunStatus.PAUSED.name())));
    }

    private void markQueuedAfterStepCancel(RunHandle run, String phase) {
        if (run.isTerminal() || run.getControlSignal().isRunCancelRequested()) return;
        RunRecord.StepPhase checkpointPhase = updateCheckpointPhase(run, phase);
        run.markQueuedAfterStepCancel();
        registry.persist(run);
        archiveStateChanged(run);
        events.publish(run, StreamEvent.of("run_control_checkpoint",
            Map.of("control", "STEP_CANCEL", "phase", checkpointPhase.name(), "boundary", phase,
                "status", RunStatus.QUEUED.name())));
    }

    private static RunRecord.StepPhase updateCheckpointPhase(RunHandle run, String phase) {
        if ("AGENT_LOOP_SAFE_POINT".equals(phase)) return run.getCheckpoint().phase();
        RunRecord.StepPhase checkpointPhase;
        try {
            checkpointPhase = RunRecord.StepPhase.valueOf(phase);
        } catch (IllegalArgumentException ignored) {
            checkpointPhase = "BEFORE_STEP".equals(phase)
                ? RunRecord.StepPhase.QUEUED : RunRecord.StepPhase.CHECKPOINT;
        }
        run.updateCheckpointPhase(checkpointPhase);
        return checkpointPhase;
    }

    private void finish(RunHandle run, RunStatus status, String error) {
        synchronized (run) {
            if (run.isTerminal()) return;
            if (archiveSink != null && run.getResultReceipt() == null) {
                var receipt = archiveSink.beforeTerminal(run, run.isCancelRequested() ? RunStatus.CANCELLED : status, error);
                if (receipt != null) run.recordResultReceipt(receipt, run.getErrorCode());
            }
            RunHandle.FinishState previous = run.beforeFinish();
            long revision = run.getRevision();
            if (!run.finish(status, error)) return;
            try {
                registry.persist(run);
            } catch (RuntimeException failure) {
                if (run.getRevision() == revision) {
                    run.rollbackFinish(previous);
                    throw failure;
                }
                // The ledger replacement committed. Journal recovery can repair its missing commit marker;
                // repeating completed work would duplicate side effects.
                log.error("terminal Run {} saved but journal completion failed: {}", run.getRunId(), failure.toString());
            }
        }
        archiveStateChanged(run);
        cascadeTerminalInputs(run, run.getStatus());
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("runId", run.getRunId());
        completed.put("threadId", run.getThreadId());
        completed.put("conversationId", run.getThreadId());
        if (run.getResultReceipt() != null) completed.put("receipt", run.getResultReceipt());
        completed.put("status", run.getStatus().name());
        if (run.getLastStepResult() != null) completed.put("result", run.getLastStepResult());
        if (run.getGoalId() != null) completed.put("goalId", run.getGoalId());
        if (run.getError() != null) completed.put("message", run.getError());
        if (run.getErrorCode() != null) completed.put("errorCode", run.getErrorCode());
        events.publish(run, StreamEvent.of("done", completed));
        events.complete(run);
        registry.settleOwnership(run);
    }

    private void archiveStateChanged(RunHandle run) {
        if (archiveSink == null) return;
        try { archiveSink.stateChanged(run); }
        catch (RuntimeException failure) { log.error("Run {} state projection failed: {}", run.getRunId(), failure.toString()); }
    }

    private void reconcileTerminalInputs() {
        for (Map<String, Object> view : registry.listViews(null, null)) {
            try {
                RunStatus status = RunStatus.valueOf(String.valueOf(view.get("status")));
                if (status.isTerminal()) inputs.onRunTerminal(String.valueOf(view.get("runId")), status);
            } catch (RuntimeException failure) {
                log.warn("run input terminal reconcile failed for {}: {}", view.get("runId"), failure.toString());
            }
        }
    }

    private void cascadeTerminalInputs(RunHandle run, RunStatus status) {
        try {
            for (RunInputRecord input : inputs.onRunTerminal(run.getRunId(), status)) {
                String type = input.status() == RunInputStatus.HELD ? "run_input_held" : "run_input_cancelled";
                events.publish(run, StreamEvent.of(type, Map.of(
                    "inputId", input.inputId(), "mode", input.mode().name(), "status", input.status().name(),
                    "sequence", input.sequence(), "revision", input.revision(),
                    "reason", input.terminalReason() == null ? "source_run_terminal" : input.terminalReason())));
            }
        } catch (RuntimeException failure) {
            // The terminal RunRecord is already authoritative. Startup reconcile retries this idempotent mailbox
            // convergence rather than turning a completed user execution into an HTTP/adapter failure.
            log.error("run input terminal cascade failed for {}: {}", run.getRunId(), failure.toString());
        }
    }

    private void closeLease(RunHandle run, boolean retainedAtControlCheckpoint) {
        RunWorker context = run.getWorkerRef().get();
        if (context != null) context.close();
        if (context != null) run.getWorkerRef().compareAndSet(context, null);
        active.releaseReservation(run.getThreadId());
        if (!run.isTerminal()) {
            if (run.getGoalId() != null && run.getStatus() == RunStatus.INTERRUPTED && !registry.isAccepting()) {
                // Workspace drain timeout stopped the old in-memory Step, not the persisted Goal Run.
            } else if (retainedAtControlCheckpoint && run.getGoalId() != null
                && run.getStatus() == RunStatus.BUDGET_LIMITED && !run.isCancelRequested()
                && !run.getControlSignal().isRunCancelRequested()) {
                // The budget watchdog interrupts only the in-memory execution. Preserve its resumable checkpoint;
                // an explicit user Cancel still takes precedence over this branch.
            } else if (run.isCancelRequested() || context != null && context.cancelled()) {
                finish(run, RunStatus.CANCELLED, null);
            } else if (retainedAtControlCheckpoint || run.getStatus() == RunStatus.PAUSED) {
                // A safe control checkpoint deliberately leaves the root run open. In particular, a StepCancel
                // checkpoint returns to QUEUED; treating a context-free lease close as normal completion here would
                // collapse StepCancel back into RunCancel/Complete semantics.
            } else if (context != null && context.pauseAcknowledged()) {
                markPaused(run, "AGENT_LOOP_SAFE_POINT");
            } else if (context != null && context.stepCancelAcknowledged()) {
                markQueuedAfterStepCancel(run, "AGENT_LOOP_SAFE_POINT");
            } else if (run.isTerminalPersistenceFailed()) {
                finish(run, RunStatus.FAILED, "Could not persist the execution's terminal result");
            } else if (context != null && context.failed()) {
                finish(run, RunStatus.FAILED, "run emitted error events (see event stream)");
            } else {
                finish(run, RunStatus.COMPLETED, null);
            }
        }
        if (run.getStatus() == RunStatus.COMPLETED) {
            try {
                followUpReleaseHook.accept(run);
            } catch (RuntimeException failure) {
                log.error("follow-up release hook failed for {}: {}", run.getRunId(), failure.toString());
            }
        }
    }

    private void janitor() {
        long now = System.currentTimeMillis();
        for (RunHandle run : registry.liveRuns()) {
            if (run.isTerminal()) {
                if (now - run.getFinishedAt() > FINISHED_TTL_MS) {
                    registry.remove(run.getRunId());
                    events.remove(run.getRunId());
                }
            } else if (run.getGoalId() == null && now - run.getCreatedAt() > MAX_RUN_TTL_MS) {
                // Goal lifetimes, including retained states, are governed by their explicit budgets/deadline.
                log.warn("run {} exceeded max TTL ({} h); force-cancelling", run.getRunId(),
                    MAX_RUN_TTL_MS / 3_600_000);
                // Signal live work first, but keep the authoritative terminal classification as EXPIRED. The public
                // cancel path terminalizes detached recovered runs immediately as CANCELLED, which is correct for a
                // user request but would otherwise erase the janitor's expiry reason.
                signalCancel(run, false);
                events.publish(run, StreamEvent.of("error", Map.of(
                    "reason", "run_expired", "message", "run exceeded the maximum wall-clock TTL")));
                finish(run, RunStatus.EXPIRED, "max TTL exceeded");
            }
        }
        registry.pruneIdempotencyCache();
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
    }

    public record RunSpec(RunOrigin origin, AgentExecutionOptions options, String provider, String model, String reasoningEffort,
        String goalId, List<String> toolsets, String permissionMode,
        ExecutionSpec execution, RunRecord.SubmissionRef submission, RunNotification.Mode notificationMode) {
        public RunSpec(RunOrigin origin, AgentExecutionOptions options, String provider, String model, String reasoningEffort,
            String goalId, List<String> toolsets, String permissionMode,
            ExecutionSpec execution, RunRecord.SubmissionRef submission) {
            this(origin, options, provider, model, reasoningEffort, goalId, toolsets, permissionMode, execution, submission, RunNotification.Mode.OFF);
        }
        public RunSpec withNotification(RunNotification.Mode mode) {
            return new RunSpec(origin, options, provider, model, reasoningEffort, goalId, toolsets, permissionMode, execution, submission, mode);
        }
        public RunSpec(RunOrigin origin, AgentExecutionOptions options, String provider, String model, String reasoningEffort,
            String goalId, List<String> toolsets, String permissionMode) {
            this(origin, options, provider, model, reasoningEffort, goalId, toolsets, permissionMode,
                AgentExecutionMapper.spec(options, new TextTarget(provider, model, reasoningEffort),
                    toolsets, permissionMode), null);
        }

        public static RunSpec submission(RunOrigin origin, ExecutionSpec execution,
            RunRecord.SubmissionRef submission, AgentExecutionOptions options) {
            TextTarget target = execution instanceof AgentSpec a ? a.text()
                : ((ConversationSpec) execution).text();
            return new RunSpec(origin, options, target == null ? null : target.provider(), target == null ? null : target.model(),
                target == null ? null : target.reasoningEffort(), null,
                execution instanceof AgentSpec a ? a.toolsets() : null,
                execution instanceof AgentSpec a ? a.permissionMode() : null, execution, submission);
        }

        public RunSpec(RunOrigin origin, AgentExecutionOptions options, String provider, String model, String reasoningEffort) {
            this(origin, options, provider, model, reasoningEffort, null, null, null);
        }

        public static RunSpec of(RunOrigin origin, AgentExecutionOptions options) {
            return new RunSpec(origin, options, null, null, null, null, null, null);
        }

        public static RunSpec goal(RunOrigin origin, String goalId, AgentExecutionOptions policy, String provider, String model,
            String reasoningEffort, List<String> toolsets, String permissionMode) {
            return new RunSpec(origin, policy, provider, model, reasoningEffort, goalId, toolsets,
                permissionMode);
        }
    }

    public record BeginResult(RunLease lease, RunHandle replayedRun, String rejectCode, String rejectMessage) {
        public BeginResult(RunLease lease, String rejectCode, String rejectMessage) {
            this(lease, null, rejectCode, rejectMessage);
        }

        static BeginResult started(RunLease lease) {
            return new BeginResult(lease, null, null, null);
        }

        static BeginResult replayed(RunHandle run) {
            return new BeginResult(null, run, null, null);
        }

        static BeginResult rejected(String code, String message) {
            return new BeginResult(null, null, code, message);
        }

        public boolean started() {
            return lease != null || replayedRun != null;
        }

        public boolean replayed() {
            return replayedRun != null;
        }
    }

    private record ReceiptIntent(String scope, String requestId, String requestHash) {}

    public static final class RunLease implements AutoCloseable {
        private final RunCoordinator owner;
        private final RunHandle run;
        private final ReceiptIntent receiptIntent;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean receiptCommitted = new AtomicBoolean();
        private final AtomicBoolean retainedAtControlCheckpoint = new AtomicBoolean();

        private RunLease(RunCoordinator owner, RunHandle run, ReceiptIntent receiptIntent) {
            this.owner = owner;
            this.run = run;
            this.receiptIntent = receiptIntent;
        }

        public RunHandle run() {
            return run;
        }

        public String runId() {
            return run.getRunId();
        }

        public Consumer<StreamEvent> events() {
            return event -> owner.events.publish(run, event);
        }

        public void attachWorker(RunWorker worker) {
            if (!run.getWorkerRef().compareAndSet(null, Objects.requireNonNull(worker))) {
                throw new IllegalStateException("Run already has an attached worker");
            }
            if (run.isCancelRequested()) worker.cancel();
        }

        /**
         * Cross the final pre-execution cancellation gate.
         *
         * <p>A run may be cancelled while it is still queued or while an adapter is loading its thread. Keeping this
         * check on the lease preserves the old "cancel before AgentLoop" behaviour for every root adapter and avoids
         * constructing a provider/tool context for work the user has already stopped. A cancellation racing after
         * this method is still honoured by the attached execution worker.</p>
         *
         * @return {@code true} when the adapter may enter AgentLoop; {@code false} when the run was already cancelled
         */
        public boolean startExecution() {
            RunControlSignal.Decision control = run.getControlSignal().decision();
            if (control == RunControlSignal.Decision.RUN_CANCEL) {
                if (run.getSubmission() != null) return false;
                owner.finish(run, RunStatus.CANCELLED, null);
                return false;
            }
            if (control == RunControlSignal.Decision.PAUSE) {
                owner.markPaused(run, "BEFORE_STEP");
                retainedAtControlCheckpoint.set(true);
                return false;
            }
            if (control == RunControlSignal.Decision.STEP_CANCEL) {
                owner.markQueuedAfterStepCancel(run, "BEFORE_STEP");
                retainedAtControlCheckpoint.set(true);
                return false;
            }
            owner.markRunning(run);
            if (!run.isCancelRequested()) return true;
            owner.events.publish(run, StreamEvent.of("cancelled", Map.of(
                "reason", "user_cancelled_before_start",
                "message", "cancelled before the run started")));
            owner.finish(run, RunStatus.CANCELLED, null);
            return false;
        }

        public void updateOptions(AgentExecutionOptions options) {
            owner.updateOptions(run, options);
        }

        public void updateRequestedReasoning(String reasoningEffort) {
            owner.updateRequestedReasoning(run, reasoningEffort);
        }

        public void updateExecutionConfig(List<String> toolsets, String permissionMode) {
            owner.updateExecutionConfig(run, toolsets, permissionMode);
        }

        public String startGoalStep() {
            return owner.startGoalStep(run);
        }

        public void finishGoalStep(String status, String error, String progressFingerprint,
            boolean consumeBusinessTurn) {
            owner.finishGoalStep(run, status, error, progressFingerprint, consumeBusinessTurn);
        }

        public void scheduleGoalRetry(String fingerprint, long retryAt, String reason) {
            owner.scheduleGoalRetry(run, fingerprint, retryAt, reason);
        }

        public void recordStepResult(StepResult result) {
            if (result == null) throw new IllegalStateException("Agent returned no step result");
            if (!run.getRunId().equals(result.runId())) throw new IllegalStateException("Step result belongs to another Run");
            run.recordStepResult(result);
            owner.registry.persist(run);
        }

        public void finishStepResult(StepResult result) {
            recordStepResult(result);
            RunStatus terminal = !result.persisted() ? RunStatus.FAILED
                : result.incomplete() ? RunStatus.INCOMPLETE
                : switch (result.reason()) {
                    case RETURNED -> RunStatus.COMPLETED;
                    case CANCELLED -> RunStatus.CANCELLED;
                    default -> RunStatus.FAILED;
                };
            owner.finish(run, terminal, terminal == RunStatus.FAILED ? result.detail() : null);
        }

        public void finishResult(CommitReceipt receipt, RunStatus status, String errorCode, String detail) {
            if (status == null || !status.isTerminal()) throw new IllegalArgumentException("final Run status is required");
            run.recordResultReceipt(receipt, errorCode);
            owner.finish(run, status, detail);
        }

        public void complete() {
            owner.finish(run, RunStatus.COMPLETED, null);
        }

        /** Keep a Goal Run open while releasing this Step's worker/reservation. */
        public void retain(RunStatus status, String reason) {
            if (status == null || status.isTerminal() || status == RunStatus.RUNNING
                || status == RunStatus.PAUSE_REQUESTED) {
                throw new IllegalArgumentException("invalid retained Run status");
            }
            owner.retain(run, status, reason);
            retainedAtControlCheckpoint.set(true);
        }

        public void fail(Throwable failure) {
            if (run.isTerminal()) return;
            String message = failure == null ? "run failed" : String.valueOf(failure.getMessage());
            owner.events.publish(run, StreamEvent.of("error", Map.of("message", message)));
            owner.finish(run, RunStatus.FAILED, message);
        }

        public void cancel() {
            owner.cancel(run.getRunId());
        }

        public void commitIdempotency() {
            if (receiptIntent == null || receiptCommitted.get()) return;
            owner.commitReceipt(run, receiptIntent);
            receiptCommitted.set(true);
        }

        public void abandonIdempotency() {
            if (receiptIntent != null && !receiptCommitted.get()) owner.abandonReceipt(receiptIntent);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.closeLease(run, retainedAtControlCheckpoint.get());
            }
        }
    }
}
