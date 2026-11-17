package com.harnex.conversation;

import java.util.List;
import com.harnex.execution.ExecutionSpec;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"turnId", "conversationId", "userMessageId", "assistantAttemptIds", "requestId", "executionSnapshot", "runId", "acceptedOperationId"})
public record TurnRecord(String turnId, String conversationId, String userMessageId, List<String> assistantAttemptIds,
    String requestId, String requestHash, String predecessorTurnId, ExecutionSpec executionSnapshot,
    String runId, String acceptedOperationId) {
    public TurnRecord { assistantAttemptIds = List.copyOf(assistantAttemptIds); }
    public String activeAttemptId() { return assistantAttemptIds.isEmpty() ? null : assistantAttemptIds.get(assistantAttemptIds.size() - 1); }
}
