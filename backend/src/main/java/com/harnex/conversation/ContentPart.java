package com.harnex.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harnex.contract.StrictRequest;
import com.harnex.util.Ids;

/** Public content contains stable references, never binary payloads or provider-private continuation. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentPart(Type type, String text, String artifactId, String imageOperationId) implements StrictRequest {
    public ContentPart {
        if (type == null) throw new IllegalArgumentException("content part type is required");
        switch (type) {
            case TEXT -> {
                if (text == null || artifactId != null || imageOperationId != null) invalid();
            }
            case ARTIFACT_REF -> {
                if (!Ids.isSafeCallId(artifactId) || text != null || imageOperationId != null) invalid();
            }
            case IMAGE_OPERATION_REF -> {
                if (!Ids.isSafeCallId(imageOperationId) || text != null || artifactId != null) invalid();
            }
        }
    }
    private static void invalid() { throw new IllegalArgumentException("invalid content part"); }
    public static ContentPart text(String text) { return new ContentPart(Type.TEXT, text, null, null); }
    public static ContentPart artifact(String id) { return new ContentPart(Type.ARTIFACT_REF, null, id, null); }
    public static ContentPart imageOperation(String id) { return new ContentPart(Type.IMAGE_OPERATION_REF, null, null, id); }
    public enum Type { TEXT, ARTIFACT_REF, IMAGE_OPERATION_REF }
}
