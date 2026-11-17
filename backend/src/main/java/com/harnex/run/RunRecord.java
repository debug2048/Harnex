package com.harnex.run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.harnex.conversation.CommitReceipt;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.core.StepResult;
import com.harnex.execution.ExecutionSpec;
import com.harnex.execution.RunExecutor;
import com.harnex.util.Ids;

/**
 * Versioned, durable projection of one root run.
 *
 * <p>The record deliberately keeps transport replay out of the business ledger. Dynamic activity payloads remain
 * maps because their schema is event-type specific, while lifecycle, options, usage and recovery metadata are strongly
 * typed and validated before they can become persisted facts.</p>
 */
public record RunRecord(
    int schemaVersion,
    String runId,
    @JsonProperty("conversationId") String threadId,
    long revision,
    RunOrigin origin,
    RunStatus status,
    long createdAt,
    Long finishedAt,
    long lastSeq,
    String lastCommittedOperationId,
    String error,
    boolean cancelRequested,
    RunControlSignal.State controlState,
    IdempotencyClaim creationRequest,
    RequestedConfig requested,
    ActualConfig actual,
    List<Map<String, Object>> providerAttempts,
    List<Map<String, Object>> activities,
    Usage usage,
    ControlClaim lastControlClaim,
    ExecutionSpec executionSpec,
    SubmissionRef submission,
    CommitReceipt resultReceipt,
    @JsonInclude(JsonInclude.Include.NON_NULL) AgentRunRecord agent,
    @JsonInclude(JsonInclude.Include.NON_NULL) String errorCode,
    @JsonInclude(JsonInclude.Include.NON_NULL) RunNotification notification
) {

    public static final int CURRENT_SCHEMA_VERSION = 4;
    /** HTTP view version is independent of the private storage layout. */
    public static final int PUBLIC_SCHEMA_VERSION = 3;

    public RunRecord {
        providerAttempts = copyRows(providerAttempts);
        activities = compactActivities(activities, agent);
        requested = requested == null ? RequestedConfig.empty() : requested;
        actual = actual == null ? ActualConfig.empty() : actual;
        usage = usage == null ? Usage.empty() : usage;
        controlState = controlState == null ? RunControlSignal.State.NONE : controlState;
    }
    @JsonIgnore public String goalId() { return agent == null ? null : agent.goalId(); }
    @JsonIgnore public AgentExecutionOptions options() { return agent == null ? null : agent.options(); }
    @JsonIgnore public List<Map<String,Object>> subagents() { return agent == null ? List.of() : agent.subagents(); }
    @JsonIgnore public ExecutionConfig executionConfig() { return agent == null ? ExecutionConfig.empty() : agent.executionConfig(); }
    @JsonIgnore public StepCheckpoint checkpoint() { return agent == null ? StepCheckpoint.empty() : agent.checkpoint(); }
    @JsonIgnore public GoalExecution goalExecution() { return agent == null ? null : agent.goalExecution(); }
    @JsonIgnore public StepResult lastStepResult() { return agent == null ? null : agent.lastStepResult(); }

    public RunRecord validated() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported run schemaVersion: " + schemaVersion);
        }
        if (!Ids.isSafeCallId(runId)) {
            throw new IllegalArgumentException("invalid runId in run record: " + runId);
        }
        if (!Ids.isSafe(threadId)) {
            throw new IllegalArgumentException("invalid threadId in run record: " + threadId);
        }
        if (revision < 1) {
            throw new IllegalArgumentException("run revision must be positive");
        }
        if (origin == null || status == null || executionSpec == null) {
            throw new IllegalArgumentException("run origin, status and execution specification are required");
        }
        if (createdAt <= 0 || lastSeq < 0) {
            throw new IllegalArgumentException("invalid run timestamps or sequence");
        }
        if (status.isTerminal() && (finishedAt == null || finishedAt <= 0)) {
            throw new IllegalArgumentException("terminal run must have finishedAt");
        }
        boolean agentExecution = executionSpec.kind() == ExecutionSpec.Kind.AGENT;
        if (agentExecution != (agent != null)) throw new IllegalArgumentException("Agent state must match the execution kind");
        if (agent != null) agent.validated();
        else if (controlState != RunControlSignal.State.NONE && controlState != RunControlSignal.State.RUN_CANCEL_REQUESTED) {
            throw new IllegalArgumentException("Conversation execution cannot contain Agent control state");
        }
        if (creationRequest != null) creationRequest.validated();
        if (lastControlClaim != null) lastControlClaim.validated();
        return this;
    }

    /** Stable Local API projection. Persistence callers must use the typed record itself, never this map. */
    public Map<String, Object> toView() {
        String goalId = goalId(); var options = options(); var executionConfig = executionConfig();
        var checkpoint = checkpoint(); var goalExecution = goalExecution(); var lastStepResult = lastStepResult();
        var subagents = subagents();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", PUBLIC_SCHEMA_VERSION);
        result.put("revision", revision);
        result.put("runId", runId);
        result.put("threadId", threadId);
        result.put("conversationId", threadId);
        result.put("executionSpec", executionSpec);
        if (submission != null) {
            result.put("scopeId", submission.scopeId());
            result.put("turnId", submission.turnId());
            if (submission.request() != null && submission.request().channel() != null && !submission.request().channel().isBlank())
                result.put("channel", submission.request().channel());
        }
        if (resultReceipt != null) result.put("resultReceipt", resultReceipt);
        result.put("terminal", status.isTerminal());
        result.put("cancelRequested", cancelRequested);
        result.put("executing", status == RunStatus.RUNNING || status == RunStatus.PAUSE_REQUESTED);
        result.put("ownsConversation", !status.isTerminal());
        List<String> actions = new ArrayList<>();
        if (!status.isTerminal()) actions.add("cancel");
        if (executionSpec != null && executionSpec.kind() == ExecutionSpec.Kind.AGENT && !status.isTerminal()) {
            if (goalId != null && status != RunStatus.RUNNING && status != RunStatus.PAUSE_REQUESTED) actions.add("resume");
            if (status == RunStatus.RUNNING) { actions.add("steer"); actions.add("cancel-step"); if (goalId != null) actions.add("pause"); }
        }
        result.put("allowedActions", List.copyOf(actions));
        if (goalId != null && !goalId.isBlank()) result.put("goalId", goalId);
        result.put("origin", origin.toView());
        result.put("status", status.name());
        if (lastStepResult != null) result.put("result", lastStepResult);
        result.put("lastSeq", lastSeq);
        result.put("createdAt", createdAt);
        if (finishedAt != null && finishedAt > 0) result.put("finishedAt", finishedAt);
        if (lastCommittedOperationId != null && !lastCommittedOperationId.isBlank()) {
            result.put("lastCommittedOperationId", lastCommittedOperationId);
        }
        if (error != null) result.put("error", error);
        if (errorCode != null) result.put("errorCode", errorCode);
        if (notification != null) result.put("notification", notification);
        result.put("controlState", controlState.name());
        if (checkpoint.stepId() != null && !checkpoint.stepId().isBlank()) {
            result.put("currentStepId", checkpoint.stepId());
        }

        Map<String, Object> requestedView = requested.toView();
        result.put("requested", requestedView);
        result.put("requestedProvider", requestedView.get("provider"));
        result.put("requestedModel", requestedView.get("model"));
        result.put("requestedReasoningEffort", requestedView.get("reasoningEffort"));

        Map<String, Object> actualView = actual.toView();
        result.put("actual", actualView);
        result.put("actualProvider", actualView.get("provider"));
        result.put("actualModel", actualView.get("model"));
        // These rows were frozen at record construction; public views can safely share them.
        result.put("providerAttempts", providerAttempts);
        result.put("activities", expandedActivities());
        result.put("subagents", subagents);
        result.put("usage", usage.toView());
        if (options != null) result.put("options", options.toView());
        if (agent != null) result.put("executionConfig", executionConfig.toView());
        if (goalExecution != null) result.put("goalExecution", goalExecution.toView());
        return result;
    }

    /** Child steps already have a durable home in agent.subagents[].details. */
    private static List<Map<String, Object>> compactActivities(List<Map<String, Object>> rows, AgentRunRecord agent) {
        if (rows == null || rows.isEmpty() || agent == null) return copyRows(rows);
        Map<Long, Map<String, Object>> children = childActivities(agent);
        return copyRows(rows.stream().filter(row -> row == null || !row.equals(children.get(activitySeq(row)))).toList());
    }

    private static Map<Long, Map<String, Object>> childActivities(AgentRunRecord agent) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        if (agent == null) return result;
        for (var child : agent.subagents()) if (child.get("details") instanceof List<?> details) {
            for (Object detail : details) if (detail instanceof Map<?, ?> row && "subagent_event".equals(row.get("eventType"))) {
                @SuppressWarnings("unchecked") var activity = (Map<String, Object>)row;
                if (activitySeq(activity) > 0) result.put(activitySeq(activity), activity);
            }
        }
        return result;
    }

    /** Preserve the existing public/recovery window without persisting its duplicate child bodies. */
    @JsonIgnore List<Map<String, Object>> expandedActivities() {
        var indexed = childActivities(agent);
        if (indexed.isEmpty()) return activities;
        List<Map<String, Object>> unsequenced = new ArrayList<>();
        for (var activity : activities) {
            long seq = activitySeq(activity);
            if (seq > 0) indexed.put(seq, activity); else unsequenced.add(activity);
        }
        var ordered = new ArrayList<>(unsequenced);
        ordered.addAll(indexed.values().stream().sorted(Comparator.comparingLong(RunRecord::activitySeq)).toList());
        return List.copyOf(ordered.subList(Math.max(0, ordered.size() - 500), ordered.size()));
    }
    private static long activitySeq(Map<String, Object> row) {
        return row.get("seq") instanceof Number value ? value.longValue() : 0L;
    }

    static List<Map<String, Object>> copyRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<Map<String, Object>> copy = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked") Map<String,Object> frozen = row == null ? Map.of() : (Map<String,Object>)freeze(row);
            copy.add(frozen);
        }
        return List.copyOf(copy);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?,?> map) {
            Map<String,Object> copy = new LinkedHashMap<>(); map.forEach((key,item) -> copy.put(String.valueOf(key),freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(RunRecord::freeze).toList());
        return value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public record SubmissionRef(String scopeId, String turnId, String acceptOperationId,
        RunExecutor.Request request, Long expectedHistoryRevision, String requestHash) {
        public SubmissionRef {
            if (scopeId == null || !Ids.isSafeCallId(turnId) || !Ids.isSafeCallId(acceptOperationId)) {
                throw new IllegalArgumentException("invalid submission reference");
            }
        }
    }

    public record RequestedConfig(String provider, String model, String reasoningEffort) {
        static RequestedConfig empty() {
            return new RequestedConfig(null, null, null);
        }

        public Map<String, Object> toView() {
            return Map.of(
                "provider", value(provider),
                "model", value(model),
                "reasoningEffort", reasoningEffort == null ? "auto" : reasoningEffort);
        }
    }

    public record ActualConfig(String provider, String model) {
        static ActualConfig empty() {
            return new ActualConfig(null, null);
        }

        Map<String, Object> toView() {
            return Map.of("provider", value(provider), "model", value(model));
        }
    }

    /** Durable link used to reconstruct a receipt if the JVM exits after RunRecord replace but before receipt write. */
    public record IdempotencyClaim(String scope, String key, String requestHash) {
        IdempotencyClaim validated() {
            if (blank(scope) || blank(key) || blank(requestHash)) {
                throw new IllegalArgumentException("incomplete run creation idempotency claim");
            }
            return this;
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    /** Durable recovery link for the control-mutation / idempotency-receipt crash window. */
    public record ControlClaim(String operation, String key, String requestHash, String outcomeCode,
        String outcomeMessage) {
        ControlClaim validated() {
            if (operation == null || operation.isBlank() || key == null || key.isBlank()
                || requestHash == null || requestHash.isBlank()) {
                throw new IllegalArgumentException("incomplete run control idempotency claim");
            }
            return this;
        }
    }

    public record Usage(
        long prompt,
        long completion,
        long total,
        long cachedInput,
        long cacheWriteInput,
        long reasoning,
        int events,
        int taskEvents,
        int expectedEvents,
        boolean complete,
        boolean estimated,
        boolean partial
    ) {
        static Usage empty() {
            return new Usage(0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, false);
        }

        Map<String, Object> toView() {
            return Map.of(
                "prompt", prompt,
                "completion", completion,
                "total", total > 0 ? total : prompt + completion,
                "cachedInput", cachedInput,
                "cacheWriteInput", cacheWriteInput,
                "reasoning", reasoning,
                "events", events,
                "taskEvents", taskEvents,
                "expectedEvents", expectedEvents,
                "complete", complete);
        }
    }

    /** Effective mutable-environment inputs captured once for the lifetime of this Run. */
    public record ExecutionConfig(List<String> toolsets, String permissionMode) {
        public ExecutionConfig {
            toolsets = toolsets == null ? List.of() : List.copyOf(toolsets);
            permissionMode = permissionMode == null || permissionMode.isBlank() ? "ask" : permissionMode;
        }

        static ExecutionConfig empty() {
            return new ExecutionConfig(List.of(), "ask");
        }

        ExecutionConfig validated() {
            if (!List.of("ask", "accept-edits", "yolo").contains(permissionMode)) {
                throw new IllegalArgumentException("invalid persisted permissionMode");
            }
            return this;
        }

        public Map<String, Object> toView() {
            return Map.of("toolsets", toolsets, "permissionMode", permissionMode);
        }
    }

    /** Compact recovery control state; file contents are never persisted here. */
    public record StepCheckpoint(
        String stepId,
        StepPhase phase,
        long contextGeneration,
        long compressedTokenBaseline,
        long providerPromptCalibration,
        int consecutiveWeakCompressions,
        Map<String, String> fileReadFingerprints
    ) {
        public StepCheckpoint {
            phase = phase == null ? StepPhase.QUEUED : phase;
            fileReadFingerprints = fileReadFingerprints == null
                ? Map.of() : Map.copyOf(fileReadFingerprints);
        }

        static StepCheckpoint empty() {
            return new StepCheckpoint(null, StepPhase.QUEUED, 0, 0, 0, 0, Map.of());
        }

        StepCheckpoint validated() {
            if (contextGeneration < 0 || compressedTokenBaseline < 0 || providerPromptCalibration < 0
                || consecutiveWeakCompressions < 0) {
                throw new IllegalArgumentException("invalid run checkpoint counters");
            }
            return this;
        }
    }

    public enum StepPhase {
        QUEUED,
        MODEL,
        BEFORE_TOOLS,
        TOOLS,
        AFTER_TOOLS,
        CHECKPOINT,
        COMPLETED,
        SUPERSEDED_BY_STEER,
        CANCELLED,
        FAILED
    }

    /** Durable Goal-only scheduling, budget and recovery facts. Null on ordinary Runs. */
    public record GoalExecution(
        int turnsUsed,
        int modelCalls,
        int toolCalls,
        long activeWallClockMs,
        CostUsage costUsage,
        long stepStartedAt,
        int retryAttempts,
        Long retryAt,
        String retryFingerprint,
        int consecutiveNoProgress,
        String progressFingerprint,
        int blockerCount,
        String blockerFingerprint,
        List<CriterionResult> criteriaResults,
        CompletionRequest completionRequest,
        List<StepAttempt> steps,
        String stopReason
    ) {
        public GoalExecution {
            steps = steps == null ? List.of() : List.copyOf(steps);
            criteriaResults = criteriaResults == null ? List.of() : List.copyOf(criteriaResults);
            costUsage = costUsage == null ? CostUsage.unknown(null, null) : costUsage;
        }

        public static GoalExecution empty() {
            return new GoalExecution(0, 0, 0, 0, CostUsage.unknown(null, null), 0, 0, null, null, 0, null, 0, null,
                List.of(), null, List.of(),
                null);
        }

        GoalExecution validated() {
            if (turnsUsed < 0 || modelCalls < 0 || toolCalls < 0 || activeWallClockMs < 0 || stepStartedAt < 0
                || retryAttempts < 0 || retryAt != null && retryAt <= 0 || consecutiveNoProgress < 0
                || blockerCount < 0) {
                throw new IllegalArgumentException("invalid Goal execution counters");
            }
            if (completionRequest != null) completionRequest.validated();
            costUsage.validated();
            criteriaResults.forEach(CriterionResult::validated);
            steps.forEach(StepAttempt::validated);
            return this;
        }

        Map<String, Object> toView() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("turnsUsed", turnsUsed);
            out.put("modelCalls", modelCalls);
            out.put("toolCalls", toolCalls);
            out.put("activeWallClockMs", activeWallClockMs + (stepStartedAt > 0
                ? Math.max(0, System.currentTimeMillis() - stepStartedAt) : 0));
            out.put("cost", costUsage.toView());
            out.put("retryAttempts", retryAttempts);
            if (retryAt != null) out.put("retryAt", retryAt);
            if (retryFingerprint != null) out.put("retryFingerprint", retryFingerprint);
            out.put("consecutiveNoProgress", consecutiveNoProgress);
            if (progressFingerprint != null) out.put("progressFingerprint", progressFingerprint);
            out.put("blockerCount", blockerCount);
            if (blockerFingerprint != null) out.put("blockerFingerprint", blockerFingerprint);
            out.put("criteriaResults", criteriaResults.stream().map(CriterionResult::toView).toList());
            if (completionRequest != null) out.put("completionRequest", completionRequest.toView());
            out.put("steps", steps.stream().map(StepAttempt::toView).toList());
            if (stopReason != null) out.put("stopReason", stopReason);
            return out;
        }
    }

    public record CostUsage(boolean known, double amount, String provider, String model,
        double inputPerMillion, double outputPerMillion, double cachedInputPerMillion,
        double cacheWriteInputPerMillion, long calculatedAt) {
        public static CostUsage unknown(String provider, String model) {
            return new CostUsage(false, 0, provider, model, 0, 0, 0, 0, System.currentTimeMillis());
        }

        CostUsage validated() {
            if (calculatedAt <= 0 || !Double.isFinite(amount) || amount < 0
                || !Double.isFinite(inputPerMillion) || inputPerMillion < 0
                || !Double.isFinite(outputPerMillion) || outputPerMillion < 0
                || !Double.isFinite(cachedInputPerMillion) || cachedInputPerMillion < 0
                || !Double.isFinite(cacheWriteInputPerMillion) || cacheWriteInputPerMillion < 0) {
                throw new IllegalArgumentException("invalid Goal cost usage");
            }
            if (known && (provider == null || provider.isBlank() || model == null || model.isBlank())) {
                throw new IllegalArgumentException("known Goal cost usage requires provider and model");
            }
            return this;
        }

        Map<String, Object> toView() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("known", known);
            if (known) out.put("amount", amount);
            if (provider != null) out.put("provider", provider);
            if (model != null) out.put("model", model);
            out.put("calculatedAt", calculatedAt);
            return out;
        }
    }

    public enum CriterionStatus { PASS, FAIL, REVIEW_REQUIRED }

    /** Authoritative, timestamped completion evidence produced by server-owned validators. */
    public record CriterionResult(String criterionId, String type, CriterionStatus status, boolean required,
        long checkedAt, String detail, Map<String, Object> evidence) {
        public CriterionResult {
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }

        CriterionResult validated() {
            if (!Ids.isSafeCallId(criterionId) || type == null || type.isBlank() || status == null || checkedAt <= 0) {
                throw new IllegalArgumentException("invalid Goal criterion result");
            }
            return this;
        }

        Map<String, Object> toView() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("criterionId", criterionId);
            out.put("type", type);
            out.put("status", status.name());
            out.put("required", required);
            out.put("checkedAt", checkedAt);
            if (detail != null) out.put("detail", detail);
            out.put("evidence", evidence);
            return out;
        }
    }

    public record CompletionRequest(String status, String note, List<Map<String, Object>> evidence, long submittedAt) {
        public CompletionRequest {
            evidence = copyRows(evidence);
        }

        CompletionRequest validated() {
            if (!("complete".equals(status) || "blocked".equals(status)) || submittedAt <= 0) {
                throw new IllegalArgumentException("invalid Goal completion request");
            }
            return this;
        }

        Map<String, Object> toView() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", status);
            if (note != null) out.put("note", note);
            out.put("evidence", copyRows(evidence));
            out.put("submittedAt", submittedAt);
            return out;
        }
    }

    public enum StepKind { GOAL_STEP }

    public record StepAttempt(String stepId, int index, StepKind kind, String status, long startedAt, Long finishedAt,
        String error, String progressFingerprint) {
        StepAttempt validated() {
            if (!Ids.isSafeCallId(stepId) || index < 1 || kind == null || status == null || status.isBlank() || startedAt <= 0
                || finishedAt != null && finishedAt < startedAt) {
                throw new IllegalArgumentException("invalid Goal Step attempt");
            }
            return this;
        }

        Map<String, Object> toView() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stepId", stepId);
            out.put("index", index);
            out.put("kind", kind.name());
            out.put("status", status);
            out.put("startedAt", startedAt);
            if (finishedAt != null) out.put("finishedAt", finishedAt);
            if (error != null) out.put("error", error);
            if (progressFingerprint != null) out.put("progressFingerprint", progressFingerprint);
            return out;
        }
    }
}
