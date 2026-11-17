package com.harnex.contract.goal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.goal.GoalRecord;
import com.harnex.run.RunStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** Public Goal projection, independent from private continuation and receipt storage. */
@JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(requiredProperties = {"schemaVersion", "goalId", "conversationId", "revision", "objective", "criteria", "budget", "runDefaults", "instructionRevisions", "runIds", "status", "createdAt", "updatedAt"})
public record GoalView(int schemaVersion, String goalId, String conversationId, long revision, String objective,
    List<GoalRecord.Criterion> criteria, GoalRecord.Budget budget, GoalRecord.RunDefaults runDefaults,
    List<GoalRecord.InstructionRevision> instructionRevisions, List<String> runIds, RunStatus status,
    String activeRunId, Long activeRunRevision, String latestRunId, Long latestRunRevision, RunStatus latestRunStatus,
    long createdAt, long updatedAt) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static GoalView from(Map<String, Object> facts) {
        var data = new LinkedHashMap<>(facts);
        data.put("conversationId", data.remove("threadId"));
        data.put("runDefaults", data.remove("run"));
        return JSON.convertValue(data, GoalView.class);
    }
}
