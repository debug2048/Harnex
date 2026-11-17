package com.harnex.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.harnex.util.Ids;

import io.swagger.v3.oas.annotations.media.Schema;

/** Strongly typed durable fact for one Agent steering input. New turns belong to PendingSubmission. */
@Schema(requiredProperties = {"schemaVersion", "inputId", "runId", "conversationId", "sequence", "revision", "mode", "text", "attachmentIds", "channel", "status", "clientRequestId", "createdAt"})
public record RunInputRecord(
    int schemaVersion,
    String inputId,
    String runId,
    @JsonProperty("conversationId") String threadId,
    long sequence,
    long revision,
    RunInputMode mode,
    String text,
    @JsonProperty("attachmentIds") List<String> attachments,
    String channel,
    RunInputStatus initialStatus,
    RunInputStatus status,
    String clientRequestId,
    String requestHash,
    String withdrawRequestId,
    String withdrawRequestHash,
    long createdAt,
    Long updatedAt,
    Long appliedAt,
    String appliedStepId,
    Long appliedAfterSeq,
    String terminalReason,
    Long applyingAt,
    String applyOperationId,
    String spawnedRunId
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_TEXT_CHARS = 32 * 1024;
    public static final int MAX_ATTACHMENTS = 20;

    public RunInputRecord {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public RunInputRecord validated() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported run input schemaVersion: " + schemaVersion);
        }
        if (!Ids.isSafeCallId(inputId) || !Ids.isSafeCallId(runId) || !Ids.isSafe(threadId)) {
            throw new IllegalArgumentException("invalid run input identity");
        }
        if (sequence < 1 || revision < 1 || createdAt <= 0 || mode == null || status == null
            || initialStatus == null) {
            throw new IllegalArgumentException("invalid run input counters or lifecycle");
        }
        if (initialStatus != RunInputStatus.QUEUED && initialStatus != RunInputStatus.HELD) {
            throw new IllegalArgumentException("invalid initial run input status");
        }
        if (((text == null || text.isBlank()) && attachments.isEmpty())
            || (text != null && text.length() > MAX_TEXT_CHARS)) {
            throw new IllegalArgumentException("run input requires text or attachments and text must not exceed 32 KiB");
        }
        if (blank(channel) || channel.length() > 64 || blank(clientRequestId) || clientRequestId.length() > 256
            || blank(requestHash)) {
            throw new IllegalArgumentException("invalid run input request metadata");
        }
        if (attachments.size() > MAX_ATTACHMENTS || attachments.stream().anyMatch(RunInputRecord::invalidAttachment)) {
            throw new IllegalArgumentException("invalid run input attachments");
        }
        if (blank(withdrawRequestId) != blank(withdrawRequestHash)) {
            throw new IllegalArgumentException("incomplete run input withdrawal claim");
        }
        if (!blank(withdrawRequestId) && withdrawRequestId.length() > 256) {
            throw new IllegalArgumentException("withdraw clientRequestId is too long");
        }
        if (appliedAfterSeq != null && appliedAfterSeq < 0) {
            throw new IllegalArgumentException("invalid appliedAfterSeq");
        }
        if (status == RunInputStatus.APPLYING && (applyingAt == null || applyingAt <= 0 || blank(applyOperationId))) {
            throw new IllegalArgumentException("applying run input requires operation metadata");
        }
        return this;
    }

    RunInputRecord withdraw(String requestId, String hash, long now) {
        return new RunInputRecord(schemaVersion, inputId, runId, threadId, sequence, revision + 1L, mode, text,
            attachments, channel, initialStatus, RunInputStatus.CANCELLED, clientRequestId, requestHash,
            requestId, hash, createdAt, now, appliedAt, appliedStepId, appliedAfterSeq, "withdrawn_by_user",
            applyingAt, applyOperationId, spawnedRunId).validated();
    }

    RunInputRecord transition(RunInputStatus next, String reason, long now) {
        return new RunInputRecord(schemaVersion, inputId, runId, threadId, sequence, revision + 1L, mode, text,
            attachments, channel, initialStatus, next, clientRequestId, requestHash, withdrawRequestId,
            withdrawRequestHash, createdAt, now, appliedAt, appliedStepId, appliedAfterSeq, reason,
            applyingAt, applyOperationId, spawnedRunId).validated();
    }

    RunInputRecord claim(String operationId, long now) {
        return new RunInputRecord(schemaVersion, inputId, runId, threadId, sequence, revision + 1L, mode, text,
            attachments, channel, initialStatus, RunInputStatus.APPLYING, clientRequestId, requestHash,
            withdrawRequestId, withdrawRequestHash, createdAt, now, appliedAt, appliedStepId, appliedAfterSeq, null,
            now, operationId, spawnedRunId).validated();
    }

    RunInputRecord applied(String stepId, Long afterSeq, String nextRunId, long now) {
        return new RunInputRecord(schemaVersion, inputId, runId, threadId, sequence, revision + 1L, mode, text,
            attachments, channel, initialStatus, RunInputStatus.APPLIED, clientRequestId, requestHash,
            withdrawRequestId, withdrawRequestHash, createdAt, now, now, stepId, afterSeq, null,
            applyingAt, applyOperationId, nextRunId).validated();
    }

    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schemaVersion", schemaVersion);
        view.put("inputId", inputId);
        view.put("runId", runId);
        view.put("conversationId", threadId);
        view.put("sequence", sequence);
        view.put("revision", revision);
        view.put("mode", mode.name());
        view.put("text", text);
        view.put("attachmentIds", attachments);
        view.put("channel", channel);
        view.put("status", status.name());
        view.put("clientRequestId", clientRequestId);
        view.put("createdAt", createdAt);
        if (updatedAt != null) view.put("updatedAt", updatedAt);
        if (appliedAt != null) view.put("appliedAt", appliedAt);
        if (!blank(appliedStepId)) view.put("appliedStepId", appliedStepId);
        if (appliedAfterSeq != null) view.put("appliedAfterSeq", appliedAfterSeq);
        if (!blank(terminalReason)) view.put("terminalReason", terminalReason);
        if (applyingAt != null) view.put("applyingAt", applyingAt);
        if (!blank(applyOperationId)) view.put("applyOperationId", applyOperationId);
        if (!blank(spawnedRunId)) view.put("spawnedRunId", spawnedRunId);
        return view;
    }

    private static boolean invalidAttachment(String value) {
        return blank(value) || value.length() > 255 || value.indexOf('\0') >= 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
