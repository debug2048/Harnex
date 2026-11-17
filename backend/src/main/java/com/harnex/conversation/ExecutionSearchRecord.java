package com.harnex.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

/** Retained, bounded tool output is searchable without entering conversational model input. */
public record ExecutionSearchRecord(String recordId, String conversationId, String runId, String toolCallId,
    long sourceRevision, String title, String text, boolean deleted,
    @Schema(nullable = true) String sourceMessageId) {
    public ExecutionSearchRecord(String recordId, String conversationId, String runId, String toolCallId,
        long sourceRevision, String title, String text, boolean deleted) {
        this(recordId, conversationId, runId, toolCallId, sourceRevision, title, text, deleted, null);
    }
}
