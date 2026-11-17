package com.harnex.contract.goal;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.harnex.execution.PlanReviewPolicy;
import com.harnex.run.RunNotification;

import io.swagger.v3.oas.annotations.media.Schema;

/** JSON contracts for the structured Goal resource and Run controls. */
public final class GoalApiRequests {
    private GoalApiRequests() { }

    @Schema(name = "GoalCreateRequest")
    public record Create(
        @JsonProperty("conversationId") String threadId,
        String objective,
        List<Criterion> criteria,
        Budget budget,
        RunConfig run,
        Boolean start,
        String clientRequestId,
        RunNotification.Mode notificationMode
    ) {
        public Create(String threadId, String objective, List<Criterion> criteria, Budget budget, RunConfig run, Boolean start, String clientRequestId) {
            this(threadId, objective, criteria, budget, run, start, clientRequestId, null);
        }
    }

    @Schema(name = "GoalPatchRequest")
    public record Patch(
        long revision,
        String objective,
        List<Criterion> criteria,
        Budget budget,
        RunConfig run,
        String clientRequestId
    ) { }

    @Schema(name = "GoalStartRunRequest")
    public record StartRun(
        long revision,
        RunConfig run,
        String clientRequestId,
        RunNotification.Mode notificationMode
    ) {
        public StartRun(long revision, RunConfig run, String clientRequestId) { this(revision, run, clientRequestId, null); }
    }

    @Schema(name = "GoalCriterionRequest")
    public record Criterion(
        String criterionId,
        String type,
        String ref,
        Boolean required,
        String assertion,
        String expected
    ) { }

    @Schema(name = "GoalBudgetRequest")
    public record Budget(
        Integer maxGoalTurns,
        Integer maxModelCalls,
        Integer maxToolCalls,
        Long tokenBudget,
        Double costBudget,
        Long activeWallClockBudgetMs,
        Long elapsedDeadline
    ) { }

    public record RunConfig(
        String provider,
        String model,
        String reasoningEffort,
        List<String> toolsets,
        String permissionMode,
        Integer maxWorkIterations,
        String interactionTarget,
        PlanReviewPolicy planReview,
        Boolean readOnly
    ) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown Goal execution field: " + name);
        }
    }

    public record Control(long revision, String clientRequestId, String interactionTarget) {
        public Control(long revision, String clientRequestId) { this(revision, clientRequestId, null); }
    }

    public record Review(long revision, String decision, String feedback, String clientRequestId) { }
}
