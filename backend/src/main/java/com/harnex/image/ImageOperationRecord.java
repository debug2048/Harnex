package com.harnex.image;

import java.util.List;
import java.util.Map;
import com.harnex.execution.ImageSpec;
import com.harnex.net.HttpExchangeState.Acceptance;

import io.swagger.v3.oas.annotations.media.Schema;

/** Public operation facts contain owned asset references and bounded usage, never provider binary or temporary URLs. */
@Schema(requiredProperties = {"schemaVersion", "operationId", "scopeId", "conversationId", "runId", "requestHash", "configurationRef", "requestSnapshot", "state", "revision", "createdAt", "updatedAt", "acceptance", "artifactIds", "usage", "responseComplete"})
public record ImageOperationRecord(int schemaVersion, String operationId, String scopeId, String conversationId,
    String runId, String parentToolCallId, String requestHash, String configurationRef, ImageSpec requestSnapshot,
    State state, long revision, long createdAt, long updatedAt, Acceptance acceptance, String providerHandle,
    List<String> artifactIds, Map<String, Long> usage, boolean responseComplete, String errorCode, String detail, String parentStepId) {
    public ImageOperationRecord(int schemaVersion, String operationId, String scopeId, String conversationId,
        String runId, String parentToolCallId, String requestHash, String configurationRef, ImageSpec requestSnapshot,
        State state, long revision, long createdAt, long updatedAt, Acceptance acceptance, String providerHandle,
        List<String> artifactIds, Map<String, Long> usage, boolean responseComplete, String errorCode, String detail) {
        this(schemaVersion, operationId, scopeId, conversationId, runId, parentToolCallId, requestHash, configurationRef, requestSnapshot,
            state, revision, createdAt, updatedAt, acceptance, providerHandle, artifactIds, usage, responseComplete, errorCode, detail, null);
    }
    public ImageOperationRecord { artifactIds = List.copyOf(artifactIds); usage = Map.copyOf(usage); }
    public enum State { QUEUED, RUNNING, SAVING, SUCCEEDED, PARTIAL_SUCCESS, FAILED, CANCEL_REQUESTED, CANCELLED, UNKNOWN }
    public ImageOperationRecord update(State next, Acceptance accepted, String handle, List<String> artifacts,
        Map<String, Long> actualUsage, boolean complete, String code, String message) {
        return new ImageOperationRecord(schemaVersion, operationId, scopeId, conversationId, runId, parentToolCallId,
            requestHash, configurationRef, requestSnapshot, next, revision + 1, createdAt, System.currentTimeMillis(),
            accepted, handle, artifacts, actualUsage, complete, code, message, parentStepId);
    }
}
