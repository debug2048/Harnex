package com.harnex.contract.run;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.run.RunInputMode;
import com.harnex.run.RunInputStatus;
import com.harnex.run.RunInputRecord;

import io.swagger.v3.oas.annotations.media.Schema;

/** Public steering facts, without storage hashes or private recovery claims. */
@JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(requiredProperties = {"schemaVersion", "inputId", "runId", "conversationId", "sequence", "revision", "mode", "text", "attachmentIds", "channel", "status", "clientRequestId", "createdAt"})
public record RunInputView(int schemaVersion, String inputId, String runId, String conversationId, long sequence,
    long revision, RunInputMode mode, String text, List<String> attachmentIds, String channel, RunInputStatus status,
    String clientRequestId, long createdAt, Long updatedAt, Long appliedAt, String appliedStepId, Long appliedAfterSeq,
    String terminalReason, Long applyingAt, String applyOperationId) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static RunInputView from(RunInputRecord record) { return from(record.toView()); }
    public static RunInputView from(Object value) { return JSON.convertValue(value, RunInputView.class); }
}
