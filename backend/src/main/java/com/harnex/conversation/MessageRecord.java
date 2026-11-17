package com.harnex.conversation;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

/** Immutable public history value. Only an unfinished assistant attempt can receive another revision. */
@Schema(requiredProperties = {"schemaVersion", "messageId", "turnId", "runId", "role", "contentParts", "status", "revision", "createdAt", "updatedAt"})
public record MessageRecord(int schemaVersion, String messageId,
    @Schema(nullable = true) String parentMessageId,
    @Schema(nullable = true) String revisesMessageId,
    String turnId, @Schema(nullable = true) String runId, Role role, List<ContentPart> contentParts, Status status, long revision,
    long createdAt, long updatedAt) {
    public static final int SCHEMA_VERSION = 1;
    public MessageRecord {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported message schemaVersion");
        contentParts = contentParts == null ? List.of() : List.copyOf(contentParts);
        if (messageId == null || role == null || status == null || revision < 1) {
            throw new IllegalArgumentException("invalid message record");
        }
    }
    public String plainText() {
        return contentParts.stream().filter(part -> part.type() == ContentPart.Type.TEXT)
            .map(ContentPart::text).collect(Collectors.joining("\n"));
    }
    @JsonIgnore
    public boolean isMutable() { return status == Status.PENDING || status == Status.STREAMING; }
    public enum Role { USER, ASSISTANT }
    public enum Status { PENDING, STREAMING, COMPLETED, PROGRESS, INCOMPLETE, INTERRUPTED, FAILED, CANCELLED }
}
