package com.harnex.run;

import com.harnex.core.AgentExecutionMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import com.harnex.conversation.CommitReceipt;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.core.AgentRuntimeBudget;
import com.harnex.core.StepResult;
import com.harnex.execution.AgentSpec;
import com.harnex.execution.ExecutionSpec;
import com.harnex.execution.TextTarget;
import com.harnex.util.Ids;

import lombok.Getter;

/** Mutable in-memory state for one root execution. Mutations are owned by {@link RunCoordinator}. */
@Getter
public final class RunHandle {

    private static final int SUBAGENT_DETAIL_MAX = 2_000;

    private final String runId;
    private final String threadId;
    private final RunOrigin origin;
    private volatile AgentExecutionOptions options;
    private final String requestedProvider;
    private final String requestedModel;
    private volatile String requestedReasoningEffort;
    private final long createdAt;
    private volatile long finishedAt;
    private volatile StepResult lastStepResult;
    private volatile ExecutionSpec executionSpec;
    private volatile RunRecord.SubmissionRef submission;
    private RunNotification notification;
    private volatile CommitReceipt resultReceipt;
    private volatile RunStatus status = RunStatus.QUEUED;
    private volatile String error;
    private volatile String errorCode;
    private volatile boolean terminalPersistenceFailed;
    private volatile boolean cancelRequested;
    private volatile long revision;
    private volatile String goalId;
    private volatile String lastCommittedOperationId;
    private volatile RunRecord.StepCheckpoint checkpoint;
    private volatile RunRecord.ExecutionConfig executionConfig;
    private volatile RunRecord.GoalExecution goalExecution;
    /** Process-local monotonic Step clock; timestamps in the ledger remain wall-clock values for display. */
    private long goalStepStartedNanos;
    private boolean goalStepClockRunning;
    private final RunControlSignal controlSignal;
    private final RunRecord.IdempotencyClaim creationRequest;
    private volatile RunRecord.ControlClaim lastControlClaim;
    private final boolean recovered;
    private volatile String actualProvider;
    private volatile String actualModel;
    private final List<Map<String, Object>> providerAttempts = new ArrayList<>();
    private final List<Map<String, Object>> activities = new ArrayList<>();
    /** Lifecycle records are durable facts, not entries in the lossy 500-item activity window. */
    private final LinkedHashMap<String, Map<String, Object>> subagents = new LinkedHashMap<>();
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long cachedInputTokens;
    private long cacheWriteInputTokens;
    private long reasoningTokens;
    private int messageStarts;
    private int failedMessageStarts;
    private int usageEvents;
    private int taskUsageEvents;
    private boolean estimatedUsage;
    private boolean incompleteUtilityUsage;
    private final AtomicReference<RunWorker> workerRef = new AtomicReference<>();
    private final AtomicLong seq = new AtomicLong();

    RunHandle(String runId, String threadId, RunOrigin origin, AgentExecutionOptions options, String requestedProvider,
        String requestedModel, String requestedReasoningEffort) {
        this(runId, threadId, origin, options, requestedProvider, requestedModel, requestedReasoningEffort,
            null);
    }

    RunHandle(String runId, String threadId, RunOrigin origin, AgentExecutionOptions options, String requestedProvider,
        String requestedModel, String requestedReasoningEffort, RunRecord.IdempotencyClaim creationRequest) {
        this(runId, threadId, origin, options, requestedProvider, requestedModel, requestedReasoningEffort,
            System.currentTimeMillis(), false, new RunControlSignal(), creationRequest);
    }

    private RunHandle(String runId, String threadId, RunOrigin origin, AgentExecutionOptions options, String requestedProvider,
        String requestedModel, String requestedReasoningEffort, long createdAt, boolean recovered,
        RunControlSignal controlSignal, RunRecord.IdempotencyClaim creationRequest) {
        this.runId = runId;
        this.threadId = threadId;
        this.origin = origin;
        this.options = options;
        this.requestedProvider = requestedProvider;
        this.requestedModel = requestedModel;
        this.requestedReasoningEffort = requestedReasoningEffort;
        this.createdAt = createdAt;
        this.recovered = recovered;
        this.controlSignal = controlSignal;
        this.creationRequest = creationRequest;
        this.checkpoint = RunRecord.StepCheckpoint.empty();
        this.executionConfig = RunRecord.ExecutionConfig.empty();
        if (options != null) executionSpec = AgentExecutionMapper.spec(options,
            new TextTarget(requestedProvider, requestedModel, requestedReasoningEffort), null, null);
    }

    static RunHandle restore(RunRecord record) {
        record.validated();
        RunControlSignal restoredControl = RunControlSignal.restore(record.controlState());
        if (record.cancelRequested()) restoredControl.requestRunCancel();
        RunHandle run = new RunHandle(record.runId(), record.threadId(), record.origin(), record.options(),
            record.requested().provider(), record.requested().model(), record.requested().reasoningEffort(),
            record.createdAt(), true, restoredControl, record.creationRequest());
        run.goalId = record.goalId();
        run.lastStepResult = record.lastStepResult();
        run.executionSpec = record.executionSpec();
        run.submission = record.submission();
        run.notification = record.notification();
        run.resultReceipt = record.resultReceipt();
        run.revision = record.revision();
        run.status = record.status();
        run.finishedAt = record.finishedAt() == null ? 0L : record.finishedAt();
        run.error = record.error();
        run.errorCode = record.errorCode();
        run.cancelRequested = record.cancelRequested();
        run.lastCommittedOperationId = record.lastCommittedOperationId();
        run.lastControlClaim = record.lastControlClaim();
        run.checkpoint = record.checkpoint();
        run.executionConfig = record.executionConfig();
        run.goalExecution = record.goalExecution();
        run.actualProvider = record.actual().provider();
        run.actualModel = record.actual().model();
        record.providerAttempts().forEach(row -> run.providerAttempts.add(new LinkedHashMap<>(row)));
        record.expandedActivities().forEach(row -> run.activities.add(new LinkedHashMap<>(row)));
        for (Map<String, Object> row : record.subagents()) {
            String key = text(row, "subagentThreadId", "delegateCallId", "id");
            if (!key.isBlank()) run.subagents.put(key, new LinkedHashMap<>(row));
        }
        RunRecord.Usage usage = record.usage();
        run.promptTokens = usage.prompt();
        run.completionTokens = usage.completion();
        run.totalTokens = usage.total();
        run.cachedInputTokens = usage.cachedInput();
        run.cacheWriteInputTokens = usage.cacheWriteInput();
        run.reasoningTokens = usage.reasoning();
        run.usageEvents = usage.events();
        run.taskUsageEvents = usage.taskEvents();
        run.messageStarts = usage.expectedEvents();
        run.estimatedUsage = usage.estimated();
        run.incompleteUtilityUsage = usage.partial();
        run.seq.set(record.lastSeq());
        return run;
    }

    public synchronized boolean isTerminal() {
        return status.isTerminal();
    }

    public synchronized RunStatus getStatus() { return status; }

    void recordPublishedSeq(long publishedSeq) {
        seq.accumulateAndGet(publishedSeq, Math::max);
    }

    public long getLastSeq() {
        return seq.get();
    }

    synchronized RunRecord record(long persistedRevision) {
        return record(persistedRevision, lastCommittedOperationId);
    }

    synchronized RunRecord record(long persistedRevision, String committedOperationId) {
        int expectedUsageEvents = Math.max(0, messageStarts - failedMessageStarts);
        boolean usageComplete = isTerminal() && !estimatedUsage && !incompleteUtilityUsage
            && taskUsageEvents >= expectedUsageEvents;
        AgentRunRecord agentState = executionSpec != null && executionSpec.kind() == ExecutionSpec.Kind.AGENT
            ? new AgentRunRecord(goalId,new ArrayList<>(subagents.values()),options,executionConfig,checkpoint,goalExecution,lastStepResult) : null;
        if (agentState == null && (options != null || goalId != null || goalExecution != null || lastStepResult != null || !subagents.isEmpty())) {
            throw new IllegalStateException("Conversation run contains Agent state");
        }
        return new RunRecord(
            RunRecord.CURRENT_SCHEMA_VERSION,
            runId,
            threadId,
            persistedRevision,
            origin,
            status,
            createdAt,
            finishedAt > 0 ? finishedAt : null,
            getLastSeq(),
            committedOperationId,
            error,
            cancelRequested,
            controlSignal.state(),
            creationRequest,
            new RunRecord.RequestedConfig(requestedProvider, requestedModel, requestedReasoningEffort),
            new RunRecord.ActualConfig(actualProvider, actualModel),
            providerAttempts.stream().<Map<String, Object>>map(LinkedHashMap::new).toList(),
            activities.stream().<Map<String, Object>>map(LinkedHashMap::new).toList(),
            new RunRecord.Usage(promptTokens, completionTokens,
                totalTokens > 0 ? totalTokens : promptTokens + completionTokens,
                cachedInputTokens, cacheWriteInputTokens, reasoningTokens, usageEvents, taskUsageEvents,
                expectedUsageEvents, usageComplete, estimatedUsage, incompleteUtilityUsage),
            lastControlClaim, executionSpec, submission, resultReceipt, agentState, errorCode, notification);
    }

    synchronized void bindNotification(RunNotification value) {
        if (revision != 0) throw new IllegalStateException("notification recipient must be frozen before admission");
        notification = value;
    }

    public synchronized RunRecord snapshot() { return record(Math.max(1, revision)); }

    synchronized void bindSubmission(ExecutionSpec spec, RunRecord.SubmissionRef value) {
        if (revision != 0) throw new IllegalStateException("submission must be frozen before admission");
        executionSpec = Objects.requireNonNull(spec);
        submission = value;
    }

    synchronized void recordResultReceipt(CommitReceipt receipt, String code) {
        if (!runId.equals(receipt.runId()) || !threadId.equals(receipt.conversationId())
            || receipt.kind() != CommitReceipt.Kind.RESULT_COMMITTED
            || submission != null && !submission.turnId().equals(receipt.turnId())) {
            throw new IllegalArgumentException("result receipt does not commit this Run");
        }
        resultReceipt = receipt;
        errorCode = code;
    }

    synchronized void markPersisted(long persistedRevision) {
        markPersisted(persistedRevision, lastCommittedOperationId);
    }

    synchronized void markPersisted(long persistedRevision, String committedOperationId) {
        revision = persistedRevision;
        lastCommittedOperationId = committedOperationId;
    }

    synchronized void markRunning() {
        if (!status.isTerminal()) {
            status = RunStatus.RUNNING;
        }
    }

    synchronized void requestCancel() {
        if (!isTerminal()) {
            cancelRequested = true;
            controlSignal.requestRunCancel();
        }
    }

    synchronized void recordControlClaim(RunRecord.ControlClaim claim) {
        if (claim != null) lastControlClaim = claim.validated();
    }

    synchronized boolean requestPause() {
        if (isTerminal() || controlSignal.isRunCancelRequested()) return false;
        if (status == RunStatus.PAUSED) return false;
        if (!controlSignal.requestPause()) return status == RunStatus.PAUSE_REQUESTED;
        status = RunStatus.PAUSE_REQUESTED;
        return true;
    }

    synchronized boolean requestStepCancel() {
        return !isTerminal() && controlSignal.requestStepCancel();
    }

    synchronized void markPaused() {
        if (isTerminal() || controlSignal.isRunCancelRequested()) return;
        controlSignal.acknowledgePause();
        status = RunStatus.PAUSED;
    }

    synchronized void markQueuedAfterStepCancel() {
        if (isTerminal() || controlSignal.isRunCancelRequested()) return;
        controlSignal.acknowledgeStepCancel();
        status = RunStatus.QUEUED;
    }

    synchronized void prepareResume() {
        if (isTerminal() || controlSignal.isRunCancelRequested()) {
            throw new IllegalStateException("terminal/cancelled Run cannot resume");
        }
        RunStatus previous = status;
        controlSignal.clearPause();
        controlSignal.acknowledgeStepCancel();
        controlSignal.beginStep();
        status = RunStatus.QUEUED;
        error = null;
        if (goalExecution != null && (previous == RunStatus.READY_FOR_REVIEW || previous == RunStatus.BLOCKED)) {
            goalExecution = new RunRecord.GoalExecution(goalExecution.turnsUsed(), goalExecution.modelCalls(),
                goalExecution.toolCalls(), goalExecution.activeWallClockMs(), goalExecution.costUsage(),
                goalExecution.stepStartedAt(),
                goalExecution.retryAttempts(), goalExecution.retryAt(), goalExecution.retryFingerprint(), 0,
                goalExecution.progressFingerprint(), 0, null,
                goalExecution.criteriaResults(), null, goalExecution.steps(), null);
        }
    }

    synchronized void transitionOpen(RunStatus next, String reason) {
        if (isTerminal() || next == null || next.isTerminal()) {
            throw new IllegalStateException("invalid open Run transition");
        }
        status = next;
        error = reason;
        if (next == RunStatus.PAUSED) controlSignal.acknowledgePause();
    }

    synchronized void updateCheckpointPhase(RunRecord.StepPhase phase) {
        if (phase == null) return;
        checkpoint = new RunRecord.StepCheckpoint(checkpoint.stepId(), phase, checkpoint.contextGeneration(),
            checkpoint.compressedTokenBaseline(), checkpoint.providerPromptCalibration(),
            checkpoint.consecutiveWeakCompressions(), checkpoint.fileReadFingerprints());
    }

    synchronized void steeringApplied(String stepId) {
        checkpoint = new RunRecord.StepCheckpoint(stepId, RunRecord.StepPhase.SUPERSEDED_BY_STEER,
            checkpoint.contextGeneration(), checkpoint.compressedTokenBaseline(),
            checkpoint.providerPromptCalibration(), checkpoint.consecutiveWeakCompressions(),
            checkpoint.fileReadFingerprints());
    }

    synchronized void updateOptions(AgentExecutionOptions options) {
        if (isTerminal()) {
            return;
        }
        this.options = options;
    }

    synchronized void associateGoal(String goalId) {
        if (isTerminal()) throw new IllegalStateException("cannot associate a terminal Run with a Goal");
        if (goalId == null || !Ids.isSafeCallId(goalId)) {
            throw new IllegalArgumentException("invalid goalId");
        }
        if (this.goalId != null && !this.goalId.equals(goalId)) {
            throw new IllegalStateException("Run is already associated with a different Goal");
        }
        this.goalId = goalId;
        if (goalExecution == null) goalExecution = RunRecord.GoalExecution.empty();
    }

    synchronized void captureExecutionConfig(List<String> toolsets, String permissionMode) {
        if (isTerminal()) return;
        executionConfig = new RunRecord.ExecutionConfig(toolsets,
            permissionMode == null || permissionMode.isBlank() ? "ask" : permissionMode);
    }

    synchronized String startGoalStep() {
        if (goalExecution == null) throw new IllegalStateException("Run is not associated with a Goal");
        if (goalExecution.stepStartedAt() > 0) throw new IllegalStateException("Goal Step already running");
        int index = goalExecution.steps().size() + 1;
        String stepId = "step_" + runId.substring(Math.max(0, runId.length() - 12)) + "_" + index;
        long now = System.currentTimeMillis();
        goalStepStartedNanos = System.nanoTime();
        goalStepClockRunning = true;
        List<RunRecord.StepAttempt> steps = new ArrayList<>(goalExecution.steps());
        steps.add(new RunRecord.StepAttempt(stepId, index, RunRecord.StepKind.GOAL_STEP, "RUNNING", now, null,
            null, null));
        goalExecution = new RunRecord.GoalExecution(goalExecution.turnsUsed(), goalExecution.modelCalls(),
            goalExecution.toolCalls(), goalExecution.activeWallClockMs(), goalExecution.costUsage(), now,
            goalExecution.retryAttempts(), null,
            goalExecution.retryFingerprint(), goalExecution.consecutiveNoProgress(), goalExecution.progressFingerprint(),
            goalExecution.blockerCount(), goalExecution.blockerFingerprint(), goalExecution.criteriaResults(),
            goalExecution.completionRequest(), steps, null);
        checkpoint = new RunRecord.StepCheckpoint(stepId, RunRecord.StepPhase.MODEL, checkpoint.contextGeneration(),
            checkpoint.compressedTokenBaseline(), checkpoint.providerPromptCalibration(),
            checkpoint.consecutiveWeakCompressions(), checkpoint.fileReadFingerprints());
        return stepId;
    }

    synchronized void finishGoalStep(String status, String error, String progressFingerprint,
        boolean consumeBusinessTurn) {
        if (goalExecution == null || goalExecution.stepStartedAt() <= 0) return;
        long now = System.currentTimeMillis();
        long activeWallClockMs = getActiveGoalWallClockMs();
        goalStepClockRunning = false;
        List<RunRecord.StepAttempt> steps = new ArrayList<>(goalExecution.steps());
        if (!steps.isEmpty()) {
            RunRecord.StepAttempt current = steps.get(steps.size() - 1);
            steps.set(steps.size() - 1, new RunRecord.StepAttempt(current.stepId(), current.index(), current.kind(),
                status, current.startedAt(), now, error, progressFingerprint));
        }
        int noProgress = !consumeBusinessTurn ? goalExecution.consecutiveNoProgress()
            : progressFingerprint != null && progressFingerprint.equals(goalExecution.progressFingerprint())
                ? goalExecution.consecutiveNoProgress() + 1 : 0;
        goalExecution = new RunRecord.GoalExecution(goalExecution.turnsUsed() + (consumeBusinessTurn ? 1 : 0),
            goalExecution.modelCalls(),
            goalExecution.toolCalls(), activeWallClockMs,
            goalExecution.costUsage(), 0,
            consumeBusinessTurn ? 0 : goalExecution.retryAttempts(),
            consumeBusinessTurn ? null : goalExecution.retryAt(),
            consumeBusinessTurn ? null : goalExecution.retryFingerprint(), noProgress,
            consumeBusinessTurn ? progressFingerprint : goalExecution.progressFingerprint(),
            goalExecution.blockerCount(), goalExecution.blockerFingerprint(), goalExecution.criteriaResults(),
            goalExecution.completionRequest(), steps, error);
        checkpoint = new RunRecord.StepCheckpoint(checkpoint.stepId(), RunRecord.StepPhase.COMPLETED,
            checkpoint.contextGeneration(), checkpoint.compressedTokenBaseline(),
            checkpoint.providerPromptCalibration(), checkpoint.consecutiveWeakCompressions(),
            checkpoint.fileReadFingerprints());
    }

    synchronized void goalModelStarted() {
        if (goalExecution == null) return;
        goalExecution = new RunRecord.GoalExecution(goalExecution.turnsUsed(), goalExecution.modelCalls() + 1,
            goalExecution.toolCalls(), goalExecution.activeWallClockMs(), goalExecution.costUsage(),
            goalExecution.stepStartedAt(),
            goalExecution.retryAttempts(), goalExecution.retryAt(), goalExecution.retryFingerprint(),
            goalExecution.consecutiveNoProgress(), goalExecution.progressFingerprint(), goalExecution.blockerCount(),
            goalExecution.blockerFingerprint(), goalExecution.criteriaResults(), goalExecution.completionRequest(),
            goalExecution.steps(), goalExecution.stopReason());
    }

    public synchronized long getActiveGoalWallClockMs() {
        if (goalExecution == null) return 0;
        long currentStep = goalStepClockRunning
            ? Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - goalStepStartedNanos)) : 0;
        return AgentRuntimeBudget.saturatedAdd(goalExecution.activeWallClockMs(), currentStep);
    }

    synchronized void goalToolStarted() {
        if (goalExecution == null) return;
        goalExecution = new RunRecord.GoalExecution(goalExecution.turnsUsed(), goalExecution.modelCalls(),
            goalExecution.toolCalls() + 1, goalExecution.activeWallClockMs(), goalExecution.costUsage(),
            goalExecution.stepStartedAt(),
            goalExecution.retryAttempts(), goalExecution.retryAt(), goalExecution.retryFingerprint(),
            goalExecution.consecutiveNoProgress(), goalExecution.progressFingerprint(), goalExecution.blockerCount(),
            goalExecution.blockerFingerprint(), goalExecution.criteriaResults(), goalExecution.completionRequest(),
            goalExecution.steps(), goalExecution.stopReason());
    }

    synchronized void requestGoalCompletion(String status, String note, List<Map<String, Object>> evidence) {
        if (goalExecution == null) throw new IllegalStateException("Run has no Goal execution state");
        goalExecution = new RunRecord.GoalExecution(goalExecution.turnsUsed(), goalExecution.modelCalls(),
            goalExecution.toolCalls(), goalExecution.activeWallClockMs(), goalExecution.costUsage(),
            goalExecution.stepStartedAt(),
            goalExecution.retryAttempts(), goalExecution.retryAt(), goalExecution.retryFingerprint(),
            goalExecution.consecutiveNoProgress(), goalExecution.progressFingerprint(), goalExecution.blockerCount(),
            goalExecution.blockerFingerprint(), goalExecution.criteriaResults(),
            new RunRecord.CompletionRequest(status, note, evidence, System.currentTimeMillis()), goalExecution.steps(),
            goalExecution.stopReason());
    }

    synchronized void scheduleGoalRetry(String fingerprint, long retryAt, String reason) {
        if (goalExecution == null) return;
        int attempts = fingerprint != null && fingerprint.equals(goalExecution.retryFingerprint())
            ? goalExecution.retryAttempts() + 1 : 1;
        goalExecution = new RunRecord.GoalExecution(goalExecution.turnsUsed(), goalExecution.modelCalls(),
            goalExecution.toolCalls(), goalExecution.activeWallClockMs(), goalExecution.costUsage(),
            goalExecution.stepStartedAt(), attempts,
            retryAt, fingerprint, goalExecution.consecutiveNoProgress(), goalExecution.progressFingerprint(),
            goalExecution.blockerCount(), goalExecution.blockerFingerprint(), goalExecution.criteriaResults(),
            goalExecution.completionRequest(), goalExecution.steps(), reason);
    }

    synchronized void applyGoalDecision(int blockerCount, String blockerFingerprint,
        List<RunRecord.CriterionResult> results, boolean clearCompletionRequest, String reason) {
        if (goalExecution == null) return;
        goalExecution = new RunRecord.GoalExecution(goalExecution.turnsUsed(), goalExecution.modelCalls(),
            goalExecution.toolCalls(), goalExecution.activeWallClockMs(), goalExecution.costUsage(),
            goalExecution.stepStartedAt(),
            goalExecution.retryAttempts(), goalExecution.retryAt(), goalExecution.retryFingerprint(),
            goalExecution.consecutiveNoProgress(), goalExecution.progressFingerprint(), Math.max(0, blockerCount),
            blockerFingerprint, results == null ? List.of() : results,
            clearCompletionRequest ? null : goalExecution.completionRequest(), goalExecution.steps(), reason);
    }

    synchronized void recordGoalCost(RunRecord.CostUsage costUsage) {
        if (goalExecution == null || costUsage == null) return;
        goalExecution = new RunRecord.GoalExecution(goalExecution.turnsUsed(), goalExecution.modelCalls(),
            goalExecution.toolCalls(), goalExecution.activeWallClockMs(), costUsage, goalExecution.stepStartedAt(),
            goalExecution.retryAttempts(), goalExecution.retryAt(), goalExecution.retryFingerprint(),
            goalExecution.consecutiveNoProgress(), goalExecution.progressFingerprint(), goalExecution.blockerCount(),
            goalExecution.blockerFingerprint(), goalExecution.criteriaResults(), goalExecution.completionRequest(),
            goalExecution.steps(), goalExecution.stopReason());
    }

    synchronized void updateRequestedReasoning(String reasoningEffort) {
        if (!isTerminal()) requestedReasoningEffort = reasoningEffort;
    }

    synchronized void recordStepResult(StepResult result) {
        if (!isTerminal()) this.lastStepResult = result;
    }

    record FinishState(RunStatus status, String error, long finishedAt,
        Map<String, Map<String, Object>> subagents) { }

    synchronized FinishState beforeFinish() {
        Map<String, Map<String, Object>> children = new LinkedHashMap<>();
        subagents.forEach((key, value) -> children.put(key, new LinkedHashMap<>(value)));
        return new FinishState(status, error, finishedAt, children);
    }

    synchronized void rollbackFinish(FinishState previous) {
        status = previous.status();
        error = previous.error();
        finishedAt = previous.finishedAt();
        subagents.clear();
        subagents.putAll(previous.subagents());
        terminalPersistenceFailed = true;
        // Control signals are independent user intent; never roll back cancellation.
    }

    synchronized boolean finish(RunStatus status, String error) {
        if (isTerminal()) {
            return false;
        }
        if (controlSignal.isRunCancelRequested()) status = RunStatus.CANCELLED;
        this.status = status;
        if (status == RunStatus.CANCELLED) controlSignal.requestRunCancel();
        this.error = error;
        this.finishedAt = System.currentTimeMillis();
        for (Map<String, Object> subagent : subagents.values()) {
            if ("running".equals(subagent.get("outcome"))) {
                subagent.put("outcome", status == RunStatus.CANCELLED ? "cancelled" : "failed");
                subagent.put("error", true);
                subagent.put("terminalStatus", status.name());
                subagent.put("finishedAt", this.finishedAt);
                subagent.put("activitySummary", "Parent run ended before the subagent reported a terminal event");
            }
        }
        return true;
    }

    synchronized void addActivity(Map<String, Object> activity) {
        activities.add(activity);
        if (activities.size() > 500) {
            activities.remove(0);
        }
    }

    synchronized void updateSubagent(String eventType, Map<String, Object> data, long eventSeq, long ts) {
        String thread = text(data, "subagentThreadId");
        String delegate = text(data, "delegateCallId", "callId");
        String key = !thread.isBlank() ? thread : delegate;
        if (key.isBlank()) return;

        Map<String, Object> state = subagents.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        copy(data, state, "subagentThreadId", "delegateCallId", "parentThreadId", "goal", "model",
            "toolsets", "depth", "maxIterations", "completionCriteria");
        // A child step carries its own tool id/title/output. Keep those on the detail
        // row; replacing the lifecycle identity breaks clients merging live snapshots.
        if (!"subagent_event".equals(eventType)) {
            copy(data, state, "id", "output", "terminalStatus", "error", "activityTitle", "activitySummary");
        }
        state.put("eventType", eventType);
        if ("subagent_started".equals(eventType)) {
            state.put("outcome", "running");
            state.put("startedSeq", eventSeq);
            state.put("startedAt", ts);
        } else if ("subagent_event".equals(eventType)) {
            List<Map<String, Object>> details = new ArrayList<>();
            Object existing = state.get("details");
            if (existing instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> map) {
                        // Step rows never change after append. Copy the list for rollback,
                        // while immutable/published snapshots already own their deep copy.
                        @SuppressWarnings("unchecked") var previousStep = (Map<String, Object>)map;
                        details.add(previousStep);
                    }
                }
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("seq", eventSeq);
            detail.put("ts", ts);
            detail.put("eventType", eventType);
            copy(data, detail, "activityKind", "activityTitle", "activitySummary", "activityTargets",
                "activityOutcome", "id", "callId", "name", "toolName", "subagentThreadId",
                "parentThreadId", "delegateCallId", "childEventType", "from", "to", "reason",
                "selectedAttempts", "circuitState", "actualProvider", "actualModel", "trigger", "outcome",
                "summarySource", "beforeTokens", "afterTokens", "reductionPercent", "sizeEffective", "purpose");
            details.add(detail);
            if (details.size() > SUBAGENT_DETAIL_MAX) {
                int remove = details.size() - SUBAGENT_DETAIL_MAX;
                details.subList(0, remove).clear();
                state.put("detailsTruncated", true);
            }
            state.put("details", details);
        } else if ("subagent_finished".equals(eventType)) {
            boolean failed = Boolean.TRUE.equals(data.get("error"));
            state.put("outcome", failed ? "failed" : "completed");
            state.put("finishedSeq", eventSeq);
            state.put("finishedAt", ts);
        }
    }

    synchronized void messageStarted(String provider, String model, long seq, long ts) {
        actualProvider = nonBlank(provider, actualProvider);
        actualModel = nonBlank(model, actualModel);
        recordMessageStart(provider, model, seq, ts, "");
    }

    synchronized void subagentMessageStarted(String provider, String model, long seq, long ts, String childId) {
        recordMessageStart(provider, model, seq, ts, childId);
    }

    synchronized void subagentProviderSwitched(String childId, String from, String reason, long ts) {
        for (int i = providerAttempts.size() - 1; i >= 0; i--) {
            Map<String, Object> attempt = providerAttempts.get(i);
            if (childId.equals(attempt.get("subagentThreadId"))
                && (from.isBlank() || from.equals(attempt.get("provider")))
                && "streaming".equals(attempt.get("outcome"))) {
                attempt.put("outcome", "failed");
                attempt.put("reason", reason);
                attempt.put("finishedAt", ts);
                failedMessageStarts++;
                return;
            }
        }
    }

    private void recordMessageStart(String provider, String model, long seq, long ts, String childId) {
        goalModelStarted();
        messageStarts++;
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("provider", provider);
        attempt.put("model", model);
        attempt.put("seq", seq);
        attempt.put("startedAt", ts);
        attempt.put("outcome", "streaming");
        if (!childId.isBlank()) attempt.put("subagentThreadId", childId);
        providerAttempts.add(attempt);
    }

    synchronized void providerSwitched(String from, String to, String model, String reason, int selectedAttempts,
        String circuitState, long ts) {
        actualProvider = nonBlank(to, actualProvider);
        actualModel = nonBlank(model, actualModel);
        boolean marked = false;
        for (int i = providerAttempts.size() - 1; i >= 0; i--) {
            Map<String, Object> attempt = providerAttempts.get(i);
            if (attempt.containsKey("subagentThreadId")) continue;
            if (!marked && (from.isBlank() || from.equals(String.valueOf(attempt.get("provider"))))
                && !"failed".equals(attempt.get("outcome"))) {
                attempt.put("outcome", "failed");
                attempt.put("reason", reason);
                attempt.put("attempts", selectedAttempts);
                if (!circuitState.isBlank()) attempt.put("circuitState", circuitState);
                attempt.put("finishedAt", ts);
                failedMessageStarts++;
                marked = true;
            }
        }
        if (!marked && !from.isBlank()) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("provider", from);
            failed.put("model", requestedModel == null ? "" : requestedModel);
            failed.put("outcome", "failed");
            failed.put("reason", reason);
            failed.put("attempts", selectedAttempts);
            if (!circuitState.isBlank()) failed.put("circuitState", circuitState);
            failed.put("finishedAt", ts);
            providerAttempts.add(failed);
        }
    }

    synchronized void addUsage(long prompt, long completion, long total, long cachedInput, long cacheWriteInput,
        long reasoning, String provider, String model, String kind, boolean estimated, boolean reportedIncomplete,
        long ts) {
        addUsage(prompt, completion, total, cachedInput, cacheWriteInput, reasoning, provider, model, kind,
            estimated, reportedIncomplete, ts, "");
    }

    synchronized void addUsage(long prompt, long completion, long total, long cachedInput, long cacheWriteInput,
        long reasoning, String provider, String model, String kind, boolean estimated, boolean reportedIncomplete,
        long ts, String childId) {
        promptTokens += prompt;
        completionTokens += completion;
        totalTokens += total > 0 ? total : prompt + completion;
        cachedInputTokens += cachedInput;
        cacheWriteInputTokens += cacheWriteInput;
        reasoningTokens += reasoning;
        usageEvents++;
        estimatedUsage |= estimated;
        incompleteUtilityUsage |= reportedIncomplete;
        if (!childId.isBlank()) recordChildUsage(childId, provider, model, prompt, completion, cachedInput, cacheWriteInput);
        boolean taskUsage = kind == null || kind.isBlank() || "task".equals(kind) || "model".equals(kind);
        if (!taskUsage) return;
        taskUsageEvents++;
        if (childId.isBlank()) {
            actualProvider = nonBlank(provider, actualProvider);
            actualModel = nonBlank(model, actualModel);
        }
        for (int i = providerAttempts.size() - 1; i >= 0; i--) {
            Map<String, Object> attempt = providerAttempts.get(i);
            if (!childId.equals(String.valueOf(attempt.getOrDefault("subagentThreadId", "")))) continue;
            if (!attempt.containsKey("usage")) {
                attempt.put("usage", Map.of("prompt", prompt, "completion", completion,
                    "total", total > 0 ? total : prompt + completion, "cachedInput", cachedInput,
                    "cacheWriteInput", cacheWriteInput, "reasoning", reasoning));
                attempt.put("outcome", "completed");
                attempt.put("finishedAt", ts);
                break;
            }
        }
    }

    public synchronized Map<String, Object> view() {
        return record(Math.max(1L, revision)).toView();
    }

    /** Durable per-child billing identity, including utility calls, without changing the root routing identity. */
    private void recordChildUsage(String childId, String provider, String model, long prompt, long completion,
        long cachedInput, long cacheWriteInput) {
        Map<String, Object> child = subagents.computeIfAbsent(childId, id -> new LinkedHashMap<>(Map.of("subagentThreadId", id)));
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> prior = Map.of();
        if (child.get("usageByTarget") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> raw) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    raw.forEach((key, value) -> entry.put(String.valueOf(key), value));
                    if (provider.equals(entry.get("provider")) && model.equals(entry.get("model"))) prior = entry;
                    else entries.add(entry);
                }
            }
        }
        entries.add(Map.of("provider", provider, "model", model,
            "prompt", usageNumber(prior, "prompt") + prompt,
            "completion", usageNumber(prior, "completion") + completion,
            "cachedInput", usageNumber(prior, "cachedInput") + cachedInput,
            "cacheWriteInput", usageNumber(prior, "cacheWriteInput") + cacheWriteInput));
        child.put("usageByTarget", entries);
    }

    public synchronized List<Map<String, Object>> childUsage() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> child : subagents.values()) {
            if (child.get("usageByTarget") instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> raw) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        raw.forEach((key, value) -> entry.put(String.valueOf(key), value));
                        entries.add(entry);
                    }
                }
            }
        }
        return entries;
    }

    private static long usageNumber(Map<String, Object> values, String key) {
        return values.get(key) instanceof Number number ? number.longValue() : 0;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) target.put(key, source.get(key));
        }
    }
}
