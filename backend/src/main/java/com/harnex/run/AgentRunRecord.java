package com.harnex.run;

import java.util.List;
import java.util.Map;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.core.StepResult;
import com.harnex.util.Ids;

/** Durable state which exists only for Agent execution, including Goal orchestration. */
public record AgentRunRecord(String goalId, List<Map<String,Object>> subagents, AgentExecutionOptions options,
    RunRecord.ExecutionConfig executionConfig, RunRecord.StepCheckpoint checkpoint,
    RunRecord.GoalExecution goalExecution, StepResult lastStepResult) {
    public AgentRunRecord {
        subagents = RunRecord.copyRows(subagents);
        executionConfig = executionConfig == null ? RunRecord.ExecutionConfig.empty() : executionConfig;
        checkpoint = checkpoint == null ? RunRecord.StepCheckpoint.empty() : checkpoint;
    }
    public AgentRunRecord validated() {
        if (options == null) throw new IllegalArgumentException("Agent execution options are required");
        if (goalId != null && !goalId.isBlank() && !Ids.isSafeCallId(goalId)) throw new IllegalArgumentException("invalid Agent goalId");
        executionConfig.validated(); checkpoint.validated();
        if (goalExecution != null) {
            if (goalId == null || goalId.isBlank()) throw new IllegalArgumentException("Goal execution requires goalId");
            goalExecution.validated();
        }
        return this;
    }
}
