package com.harnex.submission;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.harnex.execution.RunExecutor;

import io.swagger.v3.oas.annotations.media.Schema;

/** Durable accepted intent. Materialization is committed before Run admission so restart replays exactly one request. */
@Schema(requiredProperties = {"schemaVersion", "submissionId", "scopeId", "conversationId", "ordinal", "clientRequestId", "requestHash", "request", "state", "revision", "createdAt", "updatedAt"})
public record PendingSubmission(int schemaVersion, String submissionId, String scopeId, String conversationId,
    long ordinal, String clientRequestId, String requestHash, String predecessorTurnId, String predecessorSubmissionId,
    RunExecutor.Request request, State state, long revision, long createdAt, long updatedAt,
    Materialization materialization, String runId, String turnId, String reason) {
    public enum State { QUEUED, HELD, STARTING, STARTED, FAILED, CANCELLED }
    public record Materialization(long historyRevision, String parentMessageId) {}
    public PendingSubmission transition(State next, Materialization parent, String run, String turn, String detail) {
        return new PendingSubmission(schemaVersion, submissionId, scopeId, conversationId, ordinal, clientRequestId,
            requestHash, predecessorTurnId, predecessorSubmissionId, request, next, revision + 1, createdAt,
            System.currentTimeMillis(), parent, run, turn, detail);
    }
    @JsonIgnore public boolean pending() {
        return state == State.QUEUED || state == State.HELD || state == State.STARTING;
    }
}
