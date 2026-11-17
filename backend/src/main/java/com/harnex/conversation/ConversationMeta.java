package com.harnex.conversation;

import com.harnex.execution.ExecutionSpec;

import io.swagger.v3.oas.annotations.media.Schema;

/** Persistent lightweight summary; loading it never loads Agent context or message bodies. */
@Schema(requiredProperties = {"schemaVersion", "conversationId", "scopeId", "historyRevision", "metadataRevision", "title", "createdAt", "updatedAt", "messageCount", "deleted"})
public record ConversationMeta(int schemaVersion, String conversationId, String scopeId, long historyRevision,
    long metadataRevision, String activeLeafId, String title, long createdAt, long updatedAt,
    ExecutionSpec defaults, String latestRunId, String latestRunStatus, String preview, long messageCount,
    boolean deleted) {
    public static final int SCHEMA_VERSION = 1;
    public ConversationMeta {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported conversation schemaVersion");
    }
}
