package com.harnex.contract.run;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.conversation.CommitReceipt;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.core.StepResult;
import com.harnex.execution.ExecutionSpec;
import com.harnex.run.*;

import io.swagger.v3.oas.annotations.media.Schema;

/** Public Run contract; internal admission snapshots and mutable Agent storage are never exposed. */
@JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(requiredProperties = {"schemaVersion", "runId", "conversationId", "executionSpec", "revision", "status", "terminal", "executing", "ownsConversation", "allowedActions", "origin", "createdAt", "cancelRequested", "controlState", "providerAttempts", "activities", "subagents", "usage", "lastSeq", "pendingInputCount"})
public record RunView(int schemaVersion, String runId, String conversationId, String scopeId, String turnId, String channel,
    ExecutionSpec executionSpec, long revision, RunStatus status, boolean terminal, boolean executing,
    boolean ownsConversation, List<String> allowedActions, RunOrigin origin, String goalId,
    long createdAt, Long finishedAt, boolean cancelRequested, String controlState, String currentStepId,
    String error, String errorCode, StepResult result, CommitReceipt resultReceipt, AgentExecutionOptions options,
    String requestedProvider, String requestedModel, String requestedReasoningEffort, String actualProvider, String actualModel,
    List<ProviderAttempt> providerAttempts, List<SemanticActivity> activities, List<SubagentState> subagents,
    RunRecord.Usage usage, RunRecord.ExecutionConfig executionConfig, GoalExecutionView goalExecution,
    long lastSeq, Long streamEpoch, Long firstAvailableSeq, int pendingInputCount, RunNotification notification) {
    private static final ObjectMapper JSON = new ObjectMapper();
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderAttempt(String subagentThreadId, String provider, String model, String outcome,
        String reason, Integer attempts, String circuitState) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(requiredProperties = {"seq", "eventType", "activityKind", "activityTitle", "activityOutcome"})
    public record SemanticActivity(long seq, Long ts, String eventType, String activityKind, String activityTitle,
        String activitySummary, List<String> activityTargets, String activityOutcome, String id, String callId,
        String name, String toolName, String subagentThreadId, String parentThreadId, String delegateCallId,
        String childEventType, String trigger, String outcome, String summarySource, Long beforeTokens,
        Long afterTokens, Double reductionPercent, Boolean sizeEffective, String purpose) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(requiredProperties = {"outcome"})
    public record SubagentState(String id, String eventType, String subagentThreadId, String parentThreadId,
        String delegateCallId, String goal, String model, List<String> toolsets, Integer depth, Integer maxIterations,
        String completionCriteria, String output, String terminalStatus, Boolean error, String outcome,
        Long startedSeq, Long startedAt, Long finishedSeq, Long finishedAt, String activityTitle,
        String activitySummary, List<SemanticActivity> details, Boolean detailsTruncated) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoalExecutionView(int turnsUsed, int modelCalls, int toolCalls, long activeWallClockMs,
        int retryAttempts, Long retryAt, int consecutiveNoProgress, int blockerCount, String stopReason,
        CostView cost, List<RunRecord.CriterionResult> criteriaResults, RunRecord.CompletionRequest completionRequest,
        List<RunRecord.StepAttempt> steps) {}
    public record CostView(boolean known, Double amount, String provider, String model, Long calculatedAt) {}
    public static RunView from(Map<String, Object> facts) {
        if (facts == null) return null;
        var normalized = new LinkedHashMap<>(facts);
        if (facts.get("executionSpec") instanceof ExecutionSpec execution) {
            // convertValue(Map) suppresses polymorphic type information on its Object-valued entries.
            try { normalized.put("executionSpec", JSON.readTree(JSON.writerFor(ExecutionSpec.class).writeValueAsBytes(execution))); }
            catch (IOException impossible) { throw new IllegalArgumentException("Invalid execution view", impossible); }
        }
        return JSON.convertValue(normalized, RunView.class);
    }

    public static RunView from(Map<String, Object> facts, RunClient client) {
        return from(client.publicView(facts));
    }
}
