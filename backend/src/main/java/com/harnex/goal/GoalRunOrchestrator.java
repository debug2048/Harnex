package com.harnex.goal;

import com.harnex.run.RunOrigin;
import com.harnex.run.RunClient;
import com.harnex.todo.TodoStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentConversationSink;
import com.harnex.core.AgentCtx;
import com.harnex.core.AgentExecutionContext;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.core.AgentExecutionRegistry;
import com.harnex.core.AgentHistoryContext;
import com.harnex.core.AgentExecutionPort;
import com.harnex.core.AgentRuntimeBudget;
import com.harnex.core.StepRequest;
import com.harnex.core.StepResult;
import com.harnex.core.SubAgentBudget;
import com.harnex.core.PlanModeService;
import com.harnex.core.PlanReviewService;
import com.harnex.goal.GoalRecord.RunDefaults;
import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.contract.goal.GoalApiRequests;
import com.harnex.model.StreamEvent;
import com.harnex.run.ActiveAgentRegistry;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.run.IdempotencyReceiptRecord;
import com.harnex.run.RunControls;
import com.harnex.run.RunCoordinator;
import com.harnex.run.RunCoordinator.BeginResult;
import com.harnex.run.RunCoordinator.RunLease;
import com.harnex.run.RunCoordinator.RunSpec;
import com.harnex.run.RunHandle;
import com.harnex.run.RunInputRecord;
import com.harnex.run.RunNotification;
import com.harnex.run.RunRecord;
import com.harnex.run.RunStatus;
import com.harnex.run.RunInputStore;
import com.harnex.run.RunInputStatus;
import com.harnex.run.RunInputMode;
import com.harnex.workspace.WorkspaceReadService;
import com.harnex.session.SessionStore;
import com.harnex.snapshot.SnapshotService;
import com.harnex.util.Ids;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Persistent one-Step-at-a-time Goal scheduler. Every AgentExecutionPort invocation is a distinct chatExec task and releases
 * its RunLease before the next Step is admitted; no long-running while loop owns a worker.
 */
@Slf4j
@Service
@ProjectRuntime
public class GoalRunOrchestrator implements RunControls {
    private final TodoStore todoStore;

    @Autowired(required = false)
    private AgentConversationSink archive;
    @Autowired(required = false)
    private AgentExecutionRegistry agentExecutions =
        new AgentExecutionRegistry(new ActiveAgentRegistry());

    private static final ObjectMapper M = new ObjectMapper();

    private final GoalStore goalStore;
    private final GoalResourceService goals;
    private final RunCoordinator runs;
    private final AgentExecutionPort loop;
    private final SessionStore sessions;
    private final AgentHistoryContext history;
    private final SnapshotService snapshots;
    private final WorkspaceReadService workspace;
    private final PlanModeService planMode;
    private final PlanReviewService planReview;
    private final TaskExecutor executor;
    private final IdempotencyReceiptStore receipts;
    private final RunInputStore inputs;
    private final GoalCriteriaEvaluator criteria;
    private final GoalCostEstimator costs;
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "harnex-goal-retry");
        thread.setDaemon(true);
        return thread;
    });
    private static final int MAX_INFRA_RETRIES = 5;
    private final ScheduledThreadPoolExecutor budgetWatch =
        new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "harnex-goal-budget");
            thread.setDaemon(true);
            return thread;
        });

    public GoalRunOrchestrator(GoalStore goalStore, GoalResourceService goals, RunCoordinator runs, AgentExecutionPort loop,
        SessionStore sessions, SnapshotService snapshots, WorkspaceReadService workspace, PlanModeService planMode,
        PlanReviewService planReview, @Qualifier("chatExec") TaskExecutor executor,
        IdempotencyReceiptStore receipts, RunInputStore inputs, GoalCriteriaEvaluator criteria,
        GoalCostEstimator costs, AgentHistoryContext history, TodoStore todoStore) {
        this.todoStore = todoStore;
        this.goalStore = goalStore;
        this.goals = goals;
        this.runs = runs;
        this.loop = loop;
        this.sessions = sessions;
        this.history = history;
        this.snapshots = snapshots;
        this.workspace = workspace;
        this.planMode = planMode;
        this.planReview = planReview;
        this.executor = executor;
        this.receipts = receipts;
        this.inputs = inputs;
        this.criteria = criteria;
        this.costs = costs;
        budgetWatch.setRemoveOnCancelPolicy(true);
    }

    @PreDestroy
    void close() {
        retryScheduler.shutdownNow();
        budgetWatch.shutdownNow();
    }

    @PostConstruct
    void reconcile() {
        // Link any Run that crossed the durable Run-create / Goal-runIds window before the prior JVM exited.
        for (Map<String, Object> runView : runs.listViews(null, null)) {
            String goalId = text(runView.get("goalId"));
            String runId = text(runView.get("runId"));
            if (goalId.isBlank() || runId.isBlank()) continue;
            GoalRecord goal = goalStore.find(goalId).orElse(null);
            if (goal == null) continue;
            if (!goal.runIds().contains(runId)) {
                try {
                    goalStore.appendRun(goalId, runId, goal.revision(), "reconcile-run:" + runId);
                } catch (RuntimeException failure) {
                    log.error("Goal/Run association reconcile failed for {}/{}", goalId, runId, failure);
                }
            }
            RunHandle run = runs.get(runId);
            if (run != null && run.isRecovered() && !run.isTerminal()) {
                if (archive != null) archive.acceptIfMissing(run);
                runs.recoverGoalRunPaused(runId, "runtime_restart");
            }
        }
    }

    public StartResult start(String goalId, GoalApiRequests.StartRun request) {
        return start(goalId, request, RunClient.INTERNAL.origin());
    }

    public StartResult start(String goalId, GoalApiRequests.StartRun request, RunOrigin origin) {
        if (!Ids.isSafeCallId(goalId)) return StartResult.rejected("bad_request", "invalid goalId");
        if (request == null || request.revision() < 1) {
            return StartResult.rejected("bad_request", "Goal revision is required");
        }
        if (!Ids.isSafeCallId(request.clientRequestId())) {
            return StartResult.rejected("bad_request", "valid clientRequestId is required");
        }
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("goalId", goalId);
        fingerprint.put("goalRevision", request.revision());
        fingerprint.put("run", request.run());
        fingerprint.put("notificationMode", RunNotification.Mode.orOff(request.notificationMode()));
        String requestHash = IdempotencyReceiptStore.requestHash(fingerprint);
        GoalRecord goal;
        try {
            goal = goals.getRecord(goalId);
        } catch (GoalResourceService.GoalRequestException failure) {
            return StartResult.rejected(failure.code(), failure.getMessage());
        }
        if (goal.revision() != request.revision()) {
            BeginResult replay = runs.replayCreate("goal:" + goalId, request.clientRequestId(), requestHash);
            if (replay.replayed()) return StartResult.started(replay.replayedRun(), goal, true);
            if (!"idempotency_not_found".equals(replay.rejectCode())) {
                return StartResult.rejected(replay.rejectCode(), replay.rejectMessage());
            }
            return StartResult.rejected("revision_conflict", "Goal revision conflict; current=" + goal.revision());
        }
        RunDefaults config;
        try {
            config = goals.effectiveRunDefaults(goal, request.run());
        } catch (GoalResourceService.GoalRequestException failure) {
            return StartResult.rejected(failure.code(), failure.getMessage());
        }
        if (goal.budget().costBudget() > 0 && !costs.supports(config.provider(), config.model())) {
            return StartResult.rejected("cost_pricing_unavailable",
                "costBudget requires numeric input/output prices for the selected provider model");
        }
        RunSpec spec = RunSpec.goal(origin, goalId, config.options(), config.provider(), config.model(), config.reasoningEffort(),
            config.toolsets(), config.permissionMode());
        if (archive != null) spec = archive.prepare(spec, goal.threadId(), goal.objective(), "goal", requestHash);
        spec = spec.withNotification(request.notificationMode());
        BeginResult admission = runs.begin(goal.threadId(), spec, "goal:" + goalId, request.clientRequestId(), requestHash);
        if (!admission.started()) return StartResult.rejected(admission.rejectCode(), admission.rejectMessage());

        RunHandle run = admission.replayed() ? admission.replayedRun() : admission.lease().run();
        GoalRecord current = goals.getRecord(goalId);
        if (!current.runIds().contains(run.getRunId())) {
            try {
                current = goals.appendRun(goalId, run.getRunId(), current.revision(),
                    "goal-start-run:" + request.clientRequestId());
            } catch (RuntimeException conflict) {
                if (!admission.replayed()) {
                    admission.lease().cancel();
                    admission.lease().close();
                }
                return StartResult.rejected("revision_conflict", conflict.getMessage());
            }
        }
        if (admission.replayed()) return StartResult.started(run, current, true);

        RunLease lease = admission.lease();
        try {
            if (archive != null) archive.accept(run);
            ConversationThread thread = sessions.get(goal.threadId()).orElse(null);
            if (thread == null) {
                lease.fail(new IllegalStateException("Goal thread was deleted"));
                lease.close();
                return StartResult.rejected("thread_not_found", "Goal thread was deleted");
            }
            applyConfig(thread, config);
            var submission = run.getSubmission();
            if (submission != null) {
                history.prepare(thread, submission.scopeId(), submission.request().parentMessageId());
            }
            ChatMessage objective = ChatMessage.user(goal.objective());
            objective.setChannel("goal");
            thread.getMessages().add(objective);
            sessions.save(thread);
            snapshots.takeRunStart(thread, run.getRunId());
            lease.events().accept(StreamEvent.of("run_started",
                Map.of("origin", lease.run().getOrigin().toView(), "goalId", goalId)));
            lease.events().accept(StreamEvent.of("goal_created",
                Map.of("goalId", goalId, "revision", current.revision(), "runId", run.getRunId())));
            submitLease(lease, thread);
            return StartResult.started(run, current, false);
        } catch (RuntimeException failure) {
            lease.fail(failure);
            lease.close();
            return StartResult.rejected("goal_start_failed", failure.getMessage());
        }
    }

    public synchronized ControlResult resume(String runId, long expectedRevision, String feedback) {
        return resume(runId, expectedRevision, feedback, null, null, null);
    }

    private ControlResult resume(String runId, long expectedRevision, String feedback, String feedbackMarker,
        ControlInput controlInput, String interactionTarget) {
        RunHandle run = runs.get(runId);
        if (run == null) return ControlResult.rejected("run_not_found", "no such Run");
        if (run.getGoalId() == null) return ControlResult.rejected("not_goal_run", "Run is not a Goal Run");
        if (run.getRevision() != expectedRevision) {
            return ControlResult.rejected("revision_conflict", "Run revision conflict; current=" + run.getRevision());
        }
        if (!(run.getStatus() == RunStatus.PAUSED || run.getStatus() == RunStatus.BLOCKED
            || run.getStatus() == RunStatus.BUDGET_LIMITED || run.getStatus() == RunStatus.INTERRUPTED
            || run.getStatus() == RunStatus.READY_FOR_REVIEW || run.getStatus() == RunStatus.QUEUED)) {
            return ControlResult.rejected("invalid_run_state", "Run cannot resume from " + run.getStatus());
        }
        if (interactionTarget != null && !interactionTarget.isBlank()
            && !AgentExecutionOptions.LOCAL_API.equals(interactionTarget)) {
            return ControlResult.rejected("bad_request", "unknown interactionTarget: " + interactionTarget);
        }
        ConversationThread thread = sessions.get(run.getThreadId()).orElse(null);
        if (thread == null) return ControlResult.rejected("thread_not_found", "Goal thread was deleted");
        BeginResult acquired = runs.acquireExisting(runId, expectedRevision, claim(controlInput, null, null));
        if (!acquired.started()) return ControlResult.rejected(acquired.rejectCode(), acquired.rejectMessage());
        RunLease lease = acquired.lease();
        try {
            if (feedback != null && !feedback.isBlank() && !hasMessageMarker(thread, feedbackMarker)) {
                if (archive != null) archive.feedback(run, feedback,
                    feedbackMarker == null ? UUID.randomUUID().toString() : feedbackMarker);
                ChatMessage message = ChatMessage.user(feedback.trim());
                message.setChannel("goal-review");
                if (feedbackMarker != null) message.setRunInputIds(List.of(feedbackMarker));
                thread.getMessages().add(message);
            }
            if (interactionTarget != null) {
                var options = run.getOptions();
                lease.updateOptions(new AgentExecutionOptions(options.permissions(), options.limits(),
                    interactionTarget.isBlank() ? null : interactionTarget, options.planReview()));
            }
            applyConfig(thread, run);
            sessions.save(thread);
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? "Goal resume preparation failed" : failure.getMessage();
            runs.recordControlClaim(runId, claim(controlInput, "resume_preparation_failed", message));
            lease.retain(RunStatus.PAUSED, "resume preparation failed");
            lease.close();
            return commitRejected(controlInput, run, "resume_preparation_failed", message);
        }
        if (!submitLease(lease, thread, controlInput)) {
            return commitRejected(controlInput, run, "executor_saturated",
                "Goal executor is saturated; Run remains paused");
        }
        return ControlResult.accepted(acquired.lease().run());
    }

    public synchronized ControlResult pause(String runId, GoalApiRequests.Control request) {
        if (request != null && request.interactionTarget() != null) {
            return ControlResult.rejected("bad_request", "interactionTarget can only be bound when resuming");
        }
        ControlInput input = controlInput("run.pause", runId, request == null ? 0 : request.revision(),
            request == null ? null : request.clientRequestId(), Map.of());
        ControlResult replay = replayControl(input);
        if (replay != null) return replay;
        RunHandle run = goalRun(runId);
        // Pause is a monotonic safety intent on this immutable runId. The Run projection revision advances for
        // unrelated model/tool/subagent facts, so requiring an exact UI snapshot made the button race almost every
        // active event. Idempotency still protects duplicate/mismatched requests; state validation below protects
        // terminal runs. Resume/review remain strict because they can start new work or approve completion.
        RunRecord.ControlClaim controlClaim = claim(input, null, null);
        if (run.getStatus() == RunStatus.PAUSED || run.getStatus() == RunStatus.PAUSE_REQUESTED) {
            runs.recordControlClaim(runId, controlClaim);
        } else if (!runs.pause(runId, controlClaim)) {
            return ControlResult.rejected("invalid_run_state", "Run cannot be paused from " + run.getStatus());
        }
        return commitControl(input, run);
    }

    public synchronized ControlResult cancel(String runId, GoalApiRequests.Control request) {
        if (request != null && request.interactionTarget() != null) {
            return ControlResult.rejected("bad_request", "interactionTarget can only be bound when resuming");
        }
        ControlInput input = controlInput("run.cancel", runId, request == null ? 0 : request.revision(),
            request == null ? null : request.clientRequestId(), Map.of());
        ControlResult replay = replayControl(input);
        if (replay != null) return replay;
        RunHandle run = goalRun(runId);
        // Cancellation is likewise monotonic and must remain usable under a high-churn Run projection. Accept a stale
        // observed revision for the same runId; the idempotency key/hash and terminal-state checks still fail closed.
        RunRecord.ControlClaim controlClaim = claim(input, null, null);
        if (!run.isTerminal()) {
            if (!runs.cancel(runId, controlClaim)) {
                return ControlResult.rejected("invalid_run_state", "Run could not be cancelled");
            }
            return commitControl(input, run);
        }
        if (run.getStatus() != RunStatus.CANCELLED) {
            return ControlResult.rejected("invalid_run_state", "Run is already terminal: " + run.getStatus());
        }
        if (!controlClaim.equals(run.getLastControlClaim())) runs.recordControlClaim(runId, controlClaim);
        return commitControl(input, run);
    }

    public synchronized ControlResult resume(String runId, GoalApiRequests.Control request) {
        ControlInput input = controlInput("run.resume", runId, request == null ? 0 : request.revision(),
            request == null ? null : request.clientRequestId(),
            request == null || request.interactionTarget() == null ? Map.of()
                : Map.of("interactionTarget", request.interactionTarget()));
        ControlResult replay = replayControl(input);
        if (replay != null) return replay;
        ControlResult resumed = resume(runId, input.revision(), null, null, input, request == null ? null : request.interactionTarget());
        return resumed.accepted() ? commitControl(input, resumed.run()) : resumed;
    }

    public synchronized ControlResult review(String runId, GoalApiRequests.Review request) {
        if (request == null || request.decision() == null) {
            return ControlResult.rejected("bad_request", "review decision is required");
        }
        String decision = request.decision().trim().toLowerCase();
        if (!List.of("confirm", "feedback").contains(decision)) {
            return ControlResult.rejected("bad_request", "review decision must be confirm or feedback");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", decision);
        payload.put("feedback", request.feedback() == null ? "" : request.feedback());
        ControlInput input = controlInput("run.review", runId, request.revision(), request.clientRequestId(), payload);
        ControlResult replay = replayControl(input);
        if (replay != null) return replay;
        RunHandle run = goalRun(runId);
        if (run.getRevision() != input.revision()) {
            return ControlResult.rejected("revision_conflict", "Run revision conflict; current=" + run.getRevision());
        }
        if ("confirm".equals(decision)) {
            if (run.getStatus() != RunStatus.READY_FOR_REVIEW) {
                return ControlResult.rejected("invalid_run_state", "Run is not READY_FOR_REVIEW");
            }
            GoalRecord goal = goals.getRecord(run.getGoalId());
            List<RunRecord.CriterionResult> refreshed = List.of();
            if (!goal.criteria().isEmpty()) {
                GoalCriteriaEvaluator.Evaluation validation = criteria.evaluate(goal);
                refreshed = validation.results();
                if (validation.requiredFailure()) {
                    String message = "required criteria no longer pass; the Run is paused and must be resumed";
                    if (!runs.invalidateGoalReview(runId, input.revision(), refreshed,
                        "required criteria changed before review confirmation",
                        claim(input, "criteria_changed", message))) {
                        return ControlResult.rejected("invalid_run_state", "Run is not READY_FOR_REVIEW");
                    }
                    return commitRejected(input, run, "criteria_changed", message);
                }
                long reviewedAt = System.currentTimeMillis();
                refreshed = refreshed.stream().map(result -> result.status() == RunRecord.CriterionStatus.REVIEW_REQUIRED
                    ? new RunRecord.CriterionResult(result.criterionId(), result.type(),
                        RunRecord.CriterionStatus.PASS, result.required(), reviewedAt,
                        "confirmed by user review", Map.of("reviewDecision", "confirm", "reviewedAt", reviewedAt))
                    : result).toList();
            }
            if (!runs.confirmGoalReview(runId, input.revision(), refreshed, claim(input, null, null))) {
                return ControlResult.rejected("invalid_run_state", "Run is not READY_FOR_REVIEW");
            }
            return commitControl(input, run);
        }
        if (request.feedback() == null || request.feedback().isBlank()) {
            return ControlResult.rejected("bad_request", "feedback text is required");
        }
        ControlResult resumed = resume(runId, input.revision(), request.feedback(),
            feedbackMarker(input), input, null);
        return resumed.accepted() ? commitControl(input, resumed.run()) : resumed;
    }

    private RunHandle goalRun(String runId) {
        if (!Ids.isSafeCallId(runId)) throw new ControlFailure("bad_request", "invalid runId");
        RunHandle run = runs.get(runId);
        if (run == null) throw new ControlFailure("run_not_found", "no such Run");
        if (run.getGoalId() == null) throw new ControlFailure("not_goal_run", "Run is not a Goal Run");
        return run;
    }

    private ControlInput controlInput(String operation, String runId, long revision, String key,
        Map<String, Object> payload) {
        if (!Ids.isSafeCallId(runId)) throw new ControlFailure("bad_request", "invalid runId");
        if (revision < 1 || !Ids.isSafeCallId(key)) {
            throw new ControlFailure("bad_request", "revision and valid clientRequestId are required");
        }
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("runId", runId);
        fingerprint.put("revision", revision);
        fingerprint.putAll(payload);
        return new ControlInput(operation, runId, revision, key,
            IdempotencyReceiptStore.requestHash(fingerprint), Map.copyOf(payload));
    }

    private ControlResult replayControl(ControlInput input) {
        IdempotencyReceiptRecord receipt = receipts.find(input.operation(), input.runId(), input.key()).orElse(null);
        if (receipt != null) {
            if (!receipt.requestHash().equals(input.hash())) {
                return ControlResult.rejected("idempotency_conflict",
                    "clientRequestId was already used with a different payload");
            }
            String rejectedCode = text(receipt.result().get("_controlRejectCode"));
            if (!rejectedCode.isBlank()) {
                return ControlResult.rejected(rejectedCode, text(receipt.result().get("_controlRejectMessage")));
            }
            RunHandle run = runs.get(input.runId());
            if (run == null) return ControlResult.rejected("idempotency_resource_gone", "original Run was deleted");
            return ControlResult.accepted(run, receipt.result(), true);
        }
        RunHandle run = runs.get(input.runId());
        if (run == null) return null;
        RunRecord.ControlClaim claim = run.getLastControlClaim();
        if (claim == null || !input.operation().equals(claim.operation()) || !input.key().equals(claim.key())) {
            return null;
        }
        if (!input.hash().equals(claim.requestHash())) {
            return ControlResult.rejected("idempotency_conflict",
                "clientRequestId was already used with a different payload");
        }
        if (claim.outcomeCode() != null && !claim.outcomeCode().isBlank()) {
            return commitRejected(input, run, claim.outcomeCode(), claim.outcomeMessage());
        }
        if (shouldRedriveRecoveredResume(input, run)) {
            String feedback = "run.review".equals(input.operation())
                ? text(input.payload().get("feedback")) : null;
            String marker = feedback == null || feedback.isBlank() ? null : feedbackMarker(input);
            String replyTarget = input.payload().containsKey("interactionTarget")
                ? text(input.payload().get("interactionTarget")) : null;
            ControlResult resumed = resume(run.getRunId(), run.getRevision(), feedback, marker, input, replyTarget);
            return resumed.accepted() ? commitControl(input, resumed.run(), true) : resumed;
        }
        ControlResult sideEffectFailure = recoverControlSideEffects(input, run);
        if (sideEffectFailure != null) return sideEffectFailure;
        return commitControl(input, run, true);
    }

    private static boolean shouldRedriveRecoveredResume(ControlInput input, RunHandle run) {
        boolean resumeOperation = "run.resume".equals(input.operation())
            || ("run.review".equals(input.operation())
                && "feedback".equals(text(input.payload().get("decision"))));
        return resumeOperation && run.isRecovered() && run.getStatus() == RunStatus.PAUSED
            && "runtime_restart".equals(run.getError());
    }

    private ControlResult commitControl(ControlInput input, RunHandle run) {
        return commitControl(input, run, false);
    }

    private ControlResult commitControl(ControlInput input, RunHandle run, boolean replayed) {
        Map<String, Object> view = runs.view(run.getRunId());
        try {
            IdempotencyReceiptRecord receipt = receipts.commit(input.operation(), input.runId(), input.key(),
                input.hash(), view, input.runId(), run.getRevision());
            return ControlResult.accepted(run, receipt.result(), replayed);
        } catch (IdempotencyReceiptStore.IdempotencyConflictException conflict) {
            return ControlResult.rejected("idempotency_conflict",
                "clientRequestId was already used with a different payload");
        }
    }

    private ControlResult commitRejected(ControlInput input, RunHandle run, String code, String message) {
        String safeMessage = message == null ? "" : message;
        if (input == null) return ControlResult.rejected(code, safeMessage);
        try {
            receipts.commit(input.operation(), input.runId(), input.key(), input.hash(), Map.of(
                "_controlRejectCode", code,
                "_controlRejectMessage", safeMessage), input.runId(), run.getRevision());
            return ControlResult.rejected(code, safeMessage);
        } catch (IdempotencyReceiptStore.IdempotencyConflictException conflict) {
            return ControlResult.rejected("idempotency_conflict",
                "clientRequestId was already used with a different payload");
        }
    }

    private ControlResult recoverControlSideEffects(ControlInput input, RunHandle run) {
        if (!"run.review".equals(input.operation()) || !"feedback".equals(text(input.payload().get("decision")))) {
            return null;
        }
        String feedback = text(input.payload().get("feedback"));
        if (feedback.isBlank()) return null;
        ConversationThread thread = sessions.get(run.getThreadId()).orElse(null);
        if (thread == null) return ControlResult.rejected("thread_not_found", "Goal thread was deleted");
        String marker = feedbackMarker(input);
        if (hasMessageMarker(thread, marker)) return null;
        try {
            ChatMessage message = ChatMessage.user(feedback.trim());
            message.setChannel("goal-review");
            message.setRunInputIds(List.of(marker));
            thread.getMessages().add(message);
            applyConfig(thread, run);
            sessions.save(thread);
            return null;
        } catch (RuntimeException failure) {
            return ControlResult.rejected("resume_preparation_failed",
                failure.getMessage() == null ? "Goal review feedback recovery failed" : failure.getMessage());
        }
    }

    private static RunRecord.ControlClaim claim(ControlInput input, String outcomeCode, String outcomeMessage) {
        return input == null ? null : new RunRecord.ControlClaim(input.operation(), input.key(), input.hash(),
            outcomeCode, outcomeMessage);
    }

    private static String feedbackMarker(ControlInput input) {
        return "goal-review:" + input.key() + ":" + input.hash();
    }

    private boolean submitLease(RunLease lease, ConversationThread thread) {
        return submitLease(lease, thread, null);
    }

    private boolean submitLease(RunLease lease, ConversationThread thread, ControlInput controlInput) {
        try {
            executor.execute(() -> executeStep(lease, thread));
            return true;
        } catch (RejectedExecutionException saturated) {
            runs.recordControlClaim(lease.run().getRunId(), claim(controlInput, "executor_saturated",
                "Goal executor is saturated; Run remains paused"));
            lease.retain(RunStatus.PAUSED, "run executor saturated");
            lease.close();
            return false;
        }
    }

    private void executeStep(RunLease lease, ConversationThread thread) {
        boolean scheduleNext = false;
        Long scheduleRetryAt = null;
        String scheduleRetryFingerprint = null;
        StepBudgetGuard guard = null;
        try {
            RunHandle run = lease.run();
            if (!lease.startExecution()) {
                scheduleNext = run.getStatus() == RunStatus.QUEUED;
                return;
            }
            GoalRecord goal = goals.getRecord(run.getGoalId());
            RunRecord.CostUsage preflightCost = costs.estimate(run);
            runs.recordGoalCost(run.getRunId(), preflightCost);
            if (budgetReached(goal, run, preflightCost)) {
                lease.retain(RunStatus.BUDGET_LIMITED, budgetReason(goal, run, preflightCost));
                return;
            }
            String stepId = lease.startGoalStep();
            syncInstructionRevisions(run);
            goal = goals.getRecord(run.getGoalId());
            // Keep changing Goal state at the end of the transcript. In particular, Anthropic hoists system
            // messages before all history, so a per-Step system reminder would invalidate the cached history prefix.
            String contract = goalContract(goal);
            String contractHash = hash(contract);
            boolean alreadyVisible = GoalPromptContext.active(thread.getMessages()) && thread.getMessages().stream()
                .anyMatch(message -> GoalPromptContext.hasContract(message) && contractHash.equals(message.getSource()));
            ChatMessage reminder = ChatMessage.user(continuation(goal, run, alreadyVisible ? "" : contract));
            reminder.setChannel(GoalPromptContext.CHANNEL);
            reminder.setSource(contractHash);
            thread.getMessages().add(reminder);
            StepResult result;
            guard = new StepBudgetGuard(goal, run);
            StepBudgetGuard stepGuard = guard;
            result = loop.executeStep(new StepRequest(thread, run.getOptions()),
                new AgentExecutionContext(run.getRunId(), stepId, lease.events(),
                    agentExecutions.bind(lease).contextRef(), context -> {
                        agentExecutions.bind(lease).onContextReady().accept(context);
                        stepGuard.bind(context);
                    }, null, null));
            lease.recordStepResult(result);
            if (archive != null) {
                var receipt = archive.step(run, result);
                if (receipt != null) lease.events().accept(StreamEvent.of("agent_step_committed", Map.of("receipt", receipt, "stepId", result.stepId())));
            }
            syncInstructionRevisions(run);

            AgentCtx context = agentExecutions.bind(lease).contextRef().get();
            if (run.getStatus() == RunStatus.INTERRUPTED) {
                lease.finishGoalStep("INTERRUPTED", "execution interrupted by Runtime drain", null, false);
                return;
            }
            if (run.isCancelRequested()) {
                lease.finishGoalStep("CANCELLED", "Run cancelled during Goal Step", null, false);
                return;
            }
            if (!result.persisted()) {
                lease.finishGoalStep("FAILED_PERSIST", result.detail(), null, false);
                lease.fail(new IllegalStateException("Goal step output was not persisted: " + result.detail()));
                return;
            }
            if (context != null && context.getBudget() != null && context.getBudget().isExhausted()) {
                String reason = context.getBudget().getExhaustionReason();
                lease.finishGoalStep("BUDGET_LIMITED", reason, null, false);
                runs.recordGoalCost(run.getRunId(), costs.estimate(run));
                lease.retain(RunStatus.BUDGET_LIMITED, reason);
                return;
            }
            if (context != null && context.isCancelled()) {
                lease.finishGoalStep("CANCELLED", "Run cancelled during Goal Step", null, false);
                return;
            }
            if (run.getStatus() == RunStatus.PAUSE_REQUESTED || run.getStatus() == RunStatus.PAUSED
                || context != null && context.isPauseAcknowledged()) {
                lease.finishGoalStep("PAUSED", "pause acknowledged at Step boundary", null, false);
                lease.retain(RunStatus.PAUSED, "pause acknowledged at Step boundary");
                return;
            }
            if (run.getStatus() == RunStatus.QUEUED || context != null && context.isStepCancelAcknowledged()) {
                lease.finishGoalStep("SUPERSEDED_BY_STEER", "Step superseded at safe point", null, false);
                lease.retain(RunStatus.QUEUED, "Step superseded at safe point");
                scheduleNext = true;
                return;
            }

            if (result.reason() == StepResult.Reason.NEEDS_INPUT) {
                lease.finishGoalStep("NEEDS_INPUT", result.detail(), null, false);
                lease.retain(RunStatus.BLOCKED, result.detail());
                return;
            }
            StepError failure = result.reason() == StepResult.Reason.STEP_LIMIT
                ? new StepError("max_iterations", result.detail())
                : result.reason() == StepResult.Reason.FAILED
                    ? new StepError(result.errorCode(), result.detail()) : null;
            if (failure != null && !failure.iterationCap()) {
                String fingerprint = hash(failure.normalized());
                boolean sameFailure = fingerprint.equals(run.getGoalExecution().retryFingerprint());
                int nextAttempt = sameFailure ? run.getGoalExecution().retryAttempts() + 1 : 1;
                lease.finishGoalStep("FAILED_INFRA", failure.message(), null, false);
                RunRecord.CostUsage retryCost = costs.estimate(run);
                runs.recordGoalCost(run.getRunId(), retryCost);
                if (budgetReached(goal, run, retryCost)) {
                    lease.retain(RunStatus.BUDGET_LIMITED, budgetReason(goal, run, retryCost));
                    return;
                }
                if (!failure.transientFailure() || nextAttempt > MAX_INFRA_RETRIES) {
                    lease.fail(new IllegalStateException(failure.transientFailure()
                        ? "infrastructure retry limit reached: " + failure.message() : failure.message()));
                    return;
                }
                long delay = Math.min(30_000L, 1_000L << Math.min(5, nextAttempt - 1));
                long retryAt = System.currentTimeMillis() + delay;
                lease.scheduleGoalRetry(fingerprint, retryAt, failure.message());
                lease.retain(RunStatus.WAITING_RETRY, "transient infrastructure failure; retry " + nextAttempt
                    + "/" + MAX_INFRA_RETRIES);
                scheduleRetryAt = retryAt;
                scheduleRetryFingerprint = fingerprint;
                return;
            }
            String progress = progressFingerprint(run);
            GoalRecord currentGoal = goals.getRecord(run.getGoalId());
            Arbitration decision = arbitrate(currentGoal, run, guard);
            guard.close();
            if (run.isCancelRequested() || run.getStatus() == RunStatus.INTERRUPTED) {
                lease.finishGoalStep(run.isCancelRequested() ? "CANCELLED" : "INTERRUPTED",
                    "Goal validation interrupted", null, false);
                return;
            }
            if (run.getStatus() == RunStatus.PAUSE_REQUESTED || run.getStatus() == RunStatus.PAUSED) {
                lease.finishGoalStep("PAUSED", "pause acknowledged after validation", null, false);
                lease.retain(RunStatus.PAUSED, "pause acknowledged after validation");
                return;
            }
            lease.finishGoalStep(decision.status() == RunStatus.BUDGET_LIMITED ? "BUDGET_LIMITED"
                : failure != null ? "CAPPED" : "COMPLETED",
                decision.status() == RunStatus.BUDGET_LIMITED ? decision.reason()
                    : failure == null ? null : failure.message(), progress, true);
            RunStatus arbitration = decision.status();
            if (arbitration == RunStatus.COMPLETED) {
                lease.complete();
            } else if (arbitration == RunStatus.READY_FOR_REVIEW || arbitration == RunStatus.BLOCKED
                || arbitration == RunStatus.BUDGET_LIMITED) {
                lease.retain(arbitration, decision.reason());
            } else {
                lease.retain(RunStatus.QUEUED, "next Goal Step queued");
                scheduleNext = true;
            }
        } catch (RuntimeException failure) {
            log.error("Goal Step failed for Run {}", lease.runId(), failure);
            try {
                lease.finishGoalStep("FAILED", String.valueOf(failure.getMessage()), null, false);
            } catch (RuntimeException checkpointFailure) {
                failure.addSuppressed(checkpointFailure);
            }
            lease.fail(failure);
        } finally {
            if (guard != null) guard.close();
            String runId = lease.runId();
            lease.close();
            if (scheduleNext) dispatchNext(runId);
            if (scheduleRetryAt != null) scheduleRetry(runId, scheduleRetryAt, scheduleRetryFingerprint);
        }
    }

    /** One Step owns this guard. Closing it prevents timers or budget callbacks retaining a completed Run. */
    private final class StepBudgetGuard implements AutoCloseable {
        private final GoalRecord goal;
        private final RunHandle run;
        private final long startedNanos = System.nanoTime();
        private final long activeAtStart;
        private SubAgentBudget budget;
        private ScheduledFuture<?> watch;
        private boolean closed;
        private boolean validating;
        private boolean validationInterrupted;
        private final Thread worker = Thread.currentThread();

        StepBudgetGuard(GoalRecord goal, RunHandle run) {
            this.goal = goal;
            this.run = run;
            activeAtStart = run.getActiveGoalWallClockMs();
        }

        synchronized void bind(AgentCtx context) {
            budget = context.getBudget();
            if (budget == null || closed) return; // Legacy test loops may not create a family budget.
            GoalRecord.Budget limits = goal.budget();
            var used = run.getGoalExecution();
            budget.setCallLimits(limits.maxModelCalls() > 0
                    ? Math.max(0, limits.maxModelCalls() - used.modelCalls()) : -1,
                limits.maxToolCalls() > 0 ? Math.max(0, limits.maxToolCalls() - used.toolCalls()) : -1);
            budget.setLimitCheck(this::resourceReason);
            long remaining = Long.MAX_VALUE;
            if (limits.activeWallClockBudgetMs() > 0) {
                remaining = Math.max(0, limits.activeWallClockBudgetMs() - activeAtStart);
            }
            if (limits.elapsedDeadline() != null) {
                remaining = Math.min(remaining, Math.max(0, limits.elapsedDeadline() - System.currentTimeMillis()));
            }
            if (remaining != Long.MAX_VALUE) {
                context.setRuntimeBudget(new AgentRuntimeBudget(null, Math.max(1, remaining), 0));
                startWatch(context);
            }
            budget.checkLimits();
        }

        private void startWatch(AgentCtx context) {
            if (watch != null) return;
            watch = budgetWatch.scheduleWithFixedDelay(() -> {
                synchronized (StepBudgetGuard.this) {
                    if (closed) return;
                    String reason = timeReason();
                    if (reason != null) {
                        if (budget != null) budget.markExhausted(reason);
                        // Execution interruption must not persist a user Cancel intent: this Run is resumable.
                        if (!validating && context != null) context.interruptExecution();
                    }
                    if (validating && (reason != null || run.isCancelRequested()
                        || run.getStatus() == RunStatus.INTERRUPTED)) {
                        validationInterrupted = true;
                        worker.interrupt();
                    }
                }
            }, 0, 100, TimeUnit.MILLISECONDS);
        }

        synchronized void beginValidation() {
            validating = true;
            startWatch(null);
        }

        synchronized void endValidation() {
            validating = false;
            if (validationInterrupted) Thread.interrupted();
        }

        private String timeReason() {
            var limits = goal.budget();
            if (limits.elapsedDeadline() != null && System.currentTimeMillis() >= limits.elapsedDeadline()) {
                return "Goal elapsed deadline reached";
            }
            if (limits.activeWallClockBudgetMs() > 0
                && AgentRuntimeBudget.saturatedAdd(activeAtStart,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos))
                    >= limits.activeWallClockBudgetMs()) return "Goal active wall-clock budget reached";
            return null;
        }

        private String resourceReason() {
            String time = timeReason();
            if (time != null) return time;
            var limits = goal.budget();
            if (limits.tokenBudget() > 0 && number(run.view().get("usage"), "total") >= limits.tokenBudget()) {
                return "Goal token budget reached";
            }
            if (limits.costBudget() > 0) {
                RunRecord.CostUsage cost = costs.estimate(run);
                if (cost == null || !cost.known()) return "Goal cost accounting became unavailable";
                if (cost.amount() >= limits.costBudget()) return "Goal cost budget reached";
            }
            return null;
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (watch != null) watch.cancel(false);
            if (budget != null) budget.setLimitCheck(null);
            if (validationInterrupted) Thread.interrupted();
        }
    }

    private void scheduleRetry(String runId, long retryAt, String fingerprint) {
        long delay = Math.max(0, retryAt - System.currentTimeMillis());
        retryScheduler.schedule(() -> {
            RunHandle run = runs.get(runId);
            if (run == null || run.isTerminal() || run.getStatus() != RunStatus.WAITING_RETRY
                || run.getGoalExecution() == null || !fingerprint.equals(run.getGoalExecution().retryFingerprint())
                || !Long.valueOf(retryAt).equals(run.getGoalExecution().retryAt())) return;
            dispatchRetry(runId);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void dispatchRetry(String runId) {
        RunHandle run = runs.get(runId);
        if (run == null || run.isTerminal() || run.getStatus() != RunStatus.WAITING_RETRY) return;
        dispatchAcquired(run);
    }

    private void dispatchNext(String runId) {
        RunHandle run = runs.get(runId);
        if (run == null || run.isTerminal() || run.getStatus() != RunStatus.QUEUED) return;
        dispatchAcquired(run);
    }

    private void dispatchAcquired(RunHandle run) {
        String runId = run.getRunId();
        BeginResult acquired = runs.acquireExisting(runId, run.getRevision());
        if (!acquired.started()) {
            // A racing pause/cancel or Runtime drain is expected. The persisted projection is authoritative.
            if (!List.of("runtime_draining", "run_busy", "thread_busy", "revision_conflict")
                .contains(acquired.rejectCode())) {
                log.warn("Goal next-Step admission rejected for {}: {} {}", runId, acquired.rejectCode(),
                    acquired.rejectMessage());
            }
            return;
        }
        ConversationThread thread = sessions.get(run.getThreadId()).orElse(null);
        if (thread == null) {
            acquired.lease().fail(new IllegalStateException("Goal thread was deleted"));
            acquired.lease().close();
            return;
        }
        submitLease(acquired.lease(), thread);
    }

    private Arbitration arbitrate(GoalRecord goal, RunHandle run, StepBudgetGuard guard) {
        RunRecord.CostUsage cost = costs.estimate(run);
        runs.recordGoalCost(run.getRunId(), cost);
        var execution = run.getGoalExecution();
        var completion = execution.completionRequest();
        String timeLimit = guard.timeReason();
        if (timeLimit != null) return new Arbitration(RunStatus.BUDGET_LIMITED, timeLimit);
        if (completion != null && "blocked".equals(completion.status())) {
            String fingerprint = hash(normalizeBlocker(completion.note()));
            int count = fingerprint.equals(execution.blockerFingerprint()) ? execution.blockerCount() + 1 : 1;
            runs.applyGoalDecision(run.getRunId(), count, fingerprint, execution.criteriaResults(), true,
                "blocker claim " + count + "/3");
            if (count >= 3) return new Arbitration(RunStatus.BLOCKED, "same blocker persisted for 3 Goal turns");
            return new Arbitration(RunStatus.QUEUED, "blocker not yet established");
        }
        if (execution.blockerCount() > 0) {
            runs.applyGoalDecision(run.getRunId(), 0, null, execution.criteriaResults(), false,
                "blocker claim did not recur in this Step");
        }
        if (completion != null && "complete".equals(completion.status())) {
            RunRecord.StepAttempt latest = execution.steps().isEmpty() ? null
                : execution.steps().get(execution.steps().size() - 1);
            if (latest == null || completion.submittedAt() < latest.startedAt()) {
                runs.applyGoalDecision(run.getRunId(), execution.blockerCount(), execution.blockerFingerprint(),
                    execution.criteriaResults(), true, "stale completion request rejected");
                return new Arbitration(RunStatus.QUEUED, "stale completion request");
            }
            if (goal.criteria().isEmpty()) {
                runs.applyGoalDecision(run.getRunId(), 0, null, List.of(), false,
                    "no machine criteria; user review required");
                return new Arbitration(RunStatus.READY_FOR_REVIEW, "user review required");
            }
            GoalCriteriaEvaluator.Evaluation validation;
            guard.beginValidation();
            try {
                validation = criteria.evaluate(goal);
            } finally {
                guard.endValidation();
            }
            timeLimit = guard.timeReason();
            if (timeLimit != null) return new Arbitration(RunStatus.BUDGET_LIMITED, timeLimit);
            if (run.isCancelRequested() || run.getStatus() == RunStatus.INTERRUPTED) {
                return new Arbitration(RunStatus.QUEUED, "validation interrupted");
            }
            runs.applyGoalDecision(run.getRunId(), 0, null, validation.results(), validation.requiredFailure(),
                validation.requiredFailure() ? "required criteria failed" : "criteria evaluated");
            if (validation.requiredFailure()) return new Arbitration(RunStatus.QUEUED, "required criteria failed");
            if (validation.requiredReview()) {
                return new Arbitration(RunStatus.READY_FOR_REVIEW, "user review criterion required");
            }
            return new Arbitration(RunStatus.COMPLETED, "required machine criteria passed");
        }
        if (budgetReached(goal, run, cost)) {
            return new Arbitration(RunStatus.BUDGET_LIMITED, budgetReason(goal, run, cost));
        }
        // Todo/Changes are only a workspace observation, not proof that research or debugging made no progress.
        // Keep the advisory reminder; only an explicit blocker audit or configured budget may stop continuation.
        return new Arbitration(RunStatus.QUEUED, "next Goal Step queued");
    }

    private static boolean budgetReached(GoalRecord goal, RunHandle run, RunRecord.CostUsage cost) {
        GoalRecord.Budget budget = goal.budget();
        var execution = run.getGoalExecution();
        long totalTokens = number(run.view().get("usage"), "total");
        return budget.maxGoalTurns() > 0
                && execution.turnsUsed() + (execution.stepStartedAt() > 0 ? 1 : 0) >= budget.maxGoalTurns()
            || budget.maxModelCalls() > 0 && execution.modelCalls() >= budget.maxModelCalls()
            || budget.maxToolCalls() > 0 && execution.toolCalls() >= budget.maxToolCalls()
            || budget.tokenBudget() > 0 && totalTokens >= budget.tokenBudget()
            || budget.costBudget() > 0 && (cost == null || !cost.known() || cost.amount() >= budget.costBudget())
            || budget.activeWallClockBudgetMs() > 0
                && run.getActiveGoalWallClockMs() >= budget.activeWallClockBudgetMs()
            || budget.elapsedDeadline() != null && System.currentTimeMillis() >= budget.elapsedDeadline();
    }

    private static String budgetReason(GoalRecord goal, RunHandle run, RunRecord.CostUsage cost) {
        GoalRecord.Budget budget = goal.budget();
        RunRecord.GoalExecution execution = run.getGoalExecution();
        if (budget.maxGoalTurns() > 0
            && execution.turnsUsed() + (execution.stepStartedAt() > 0 ? 1 : 0) >= budget.maxGoalTurns()) {
            return "Goal turn budget reached";
        }
        if (budget.maxModelCalls() > 0 && execution.modelCalls() >= budget.maxModelCalls()) {
            return "Goal model-call budget reached";
        }
        if (budget.maxToolCalls() > 0 && execution.toolCalls() >= budget.maxToolCalls()) {
            return "Goal tool-call budget reached";
        }
        if (budget.tokenBudget() > 0 && number(run.view().get("usage"), "total") >= budget.tokenBudget()) {
            return "Goal token budget reached";
        }
        if (budget.costBudget() > 0 && (cost == null || !cost.known())) {
            return "Goal cost accounting became unavailable for the active provider/model";
        }
        if (budget.costBudget() > 0 && cost.amount() >= budget.costBudget()) return "Goal cost budget reached";
        if (budget.activeWallClockBudgetMs() > 0
            && run.getActiveGoalWallClockMs() >= budget.activeWallClockBudgetMs()) {
            return "Goal active wall-clock budget reached";
        }
        if (budget.elapsedDeadline() != null && System.currentTimeMillis() >= budget.elapsedDeadline()) {
            return "Goal elapsed deadline reached";
        }
        return "Goal Run budget reached";
    }

    private void syncInstructionRevisions(RunHandle run) {
        if (run.getGoalId() == null) return;
        inputs.list(run.getRunId(), RunInputStatus.APPLIED).stream()
            .filter(input -> input.mode() == RunInputMode.STEER)
            .sorted(Comparator.comparingLong(RunInputRecord::sequence))
            .forEach(input -> goals.appendInstruction(run.getGoalId(), input.sequence(), input.inputId(),
                input.text(), input.appliedAt() == null ? System.currentTimeMillis() : input.appliedAt()));
    }

    private String progressFingerprint(RunHandle run) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("todos", todoStore.snapshot(run.getThreadId()));
        try {
            facts.put("changes", workspace.changes(run.getRunId(), run.getThreadId()));
        } catch (RuntimeException failure) {
            facts.put("changesUnavailable", failure.getClass().getSimpleName());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(M.writeValueAsBytes(facts)));
        } catch (Exception impossible) {
            return HexFormat.of().formatHex(facts.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String goalContract(GoalRecord goal) {
        String objective = goal.objective().replace("</goal_objective>", "<\u200B/goal_objective>");
        StringBuilder out = new StringBuilder();
        out.append("<goal_objective>\n").append(objective).append("\n</goal_objective>\n");
        if (!goal.instructionRevisions().isEmpty()) {
            out.append("Applied user revisions, oldest to newest (newer overrides conflicts):\n");
            goal.instructionRevisions().forEach(revision -> out.append("- ")
                .append(revision.text().replace("</goal_revision>", "<\u200B/goal_revision>" )).append('\n'));
        }
        if (!goal.criteria().isEmpty()) {
            out.append("Acceptance criteria (required criteria must pass):\n");
            for (GoalRecord.Criterion criterion : goal.criteria()) {
                out.append("- ").append(criterion.criterionId()).append(" [").append(criterion.type())
                    .append(criterion.required() ? ", required" : ", optional").append("]");
                if (criterion.ref() != null) out.append(" ref=").append(criterion.ref());
                if (criterion.assertion() != null) out.append(" assertion=").append(criterion.assertion());
                if (criterion.expected() != null) out.append(" expected=").append(criterion.expected());
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String continuation(GoalRecord goal, RunHandle run, String contract) {
        StringBuilder out = new StringBuilder("[system_reminder] Continue the persisted Goal in a new Step.\n");
        out.append(contract.isBlank() ? "The Goal contract above remains in effect; do not restart.\n" : contract);
        if (!goal.criteria().isEmpty()) {
            for (RunRecord.CriterionResult result : run.getGoalExecution().criteriaResults()) {
                if (result.status() != RunRecord.CriterionStatus.FAIL) continue;
                out.append("Last validation failed: ").append(result.criterionId()).append(" — ")
                    .append(result.detail()).append("; evidence=").append(result.evidence()).append('\n');
            }
        }
        out.append("Step ").append(run.getGoalExecution().turnsUsed() + 1)
            .append(": inspect current Todo/Changes and prior evidence; continue from durable facts, do not restart. ")
            .append("Call update_goal to submit completion or record a concrete blocker. The server requires the same ")
            .append("blocker in three consecutive Steps before marking this Goal blocked.");
        if (run.getGoalExecution().consecutiveNoProgress() == 1) {
            out.append(" Todo/Changes statistics did not change in the prior Step; this alone is not evidence of a blocker.");
        } else if (run.getGoalExecution().consecutiveNoProgress() >= 2) {
            out.append(" Todo/Changes statistics remain unchanged. Continue useful research or debugging; if repeating failed actions, try a different approach.");
        }
        if (run.getGoalExecution().blockerCount() > 0) {
            out.append(" A blocker claim is recorded ").append(run.getGoalExecution().blockerCount())
                .append("/3; keep attempting safe alternatives unless the identical obstacle truly persists.");
        }
        return out.toString();
    }

    private static String normalizeBlocker(String note) {
        return note == null ? "unspecified" : note.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record Arbitration(RunStatus status, String reason) { }

    private record StepError(String reason, String message) {
        static StepError from(StreamEvent event) {
            Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
            String reason = text(data.get("reason"));
            String message = text(data.get("message"));
            if (message.isBlank()) message = text(data.get("detail"));
            if (reason.isBlank()) reason = "agent_error";
            if (message.isBlank()) message = reason;
            return new StepError(reason, message);
        }

        String normalized() {
            return (reason + " " + message).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        }

        boolean transientFailure() {
            if ("provider_result_unknown".equals(reason)) return false;
            String value = normalized();
            return value.matches(".*(timeout|timed out|connection|socket|dns|reset|rate.?limit|429|500|502|503|504|temporar|unavailable|eof).*" );
        }

        boolean iterationCap() {
            return "max_iterations".equals(reason);
        }
    }

    private static boolean hasMessageMarker(ConversationThread thread, String marker) {
        return marker != null && thread.getMessages().stream().anyMatch(message -> message.getRunInputIds() != null
            && message.getRunInputIds().contains(marker));
    }

    private static void applyConfig(ConversationThread thread, RunDefaults config) {
        thread.setProvider(config.provider());
        thread.setModel(config.model());
        thread.setReasoningEffort(config.reasoningEffort());
        thread.setToolsets(new ArrayList<>(config.toolsets()));
        thread.getState().setPermissionMode(config.permissionMode());
    }

    private static void applyConfig(ConversationThread thread, RunHandle run) {
        thread.setProvider(run.getRequestedProvider());
        thread.setModel(run.getRequestedModel());
        thread.setReasoningEffort(run.getRequestedReasoningEffort());
        thread.setToolsets(new ArrayList<>(run.getExecutionConfig().toolsets()));
        thread.getState().setPermissionMode(run.getExecutionConfig().permissionMode());
    }

    private static Map<String, Object> done(RunHandle run, String reason) {
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("threadId", run.getThreadId());
        done.put("goalId", run.getGoalId());
        done.put("reason", reason);
        return done;
    }

    @SuppressWarnings("unchecked")
    private static long number(Object source, String key) {
        if (!(source instanceof Map<?, ?> map)) return 0;
        Object value = ((Map<String, Object>)map).get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record StartResult(RunHandle run, GoalRecord goal, boolean replayed, String rejectCode,
        String rejectMessage) {
        static StartResult started(RunHandle run, GoalRecord goal, boolean replayed) {
            return new StartResult(run, goal, replayed, null, null);
        }
        static StartResult rejected(String code, String message) {
            return new StartResult(null, null, false, code, message == null ? "" : message);
        }
        public boolean started() { return run != null; }
    }

    private record ControlInput(String operation, String runId, long revision, String key, String hash,
        Map<String, Object> payload) { }

}
