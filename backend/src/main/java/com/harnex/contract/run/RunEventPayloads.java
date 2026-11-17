package com.harnex.contract.run;

import java.util.Map;
import com.harnex.conversation.CommitReceipt;
import com.harnex.image.ImageOperationRecord;
import com.harnex.provider.ModelCallRecord;

/** Named SSE payload schemas registered alongside the REST DTOs. Unknown event kinds remain ignorable. */
public final class RunEventPayloads {
    private RunEventPayloads() {}
    public record TextDelta(String text) {}
    public record MessageCommit(CommitReceipt receipt) {}
    public record ImageUpdate(String operationId, long revision, ImageOperationRecord operation) {}
    public record ModelCallUpdate(String callId, long revision, ModelCallRecord call) {}
    public record ArtifactUpdate(String artifactId, long revision) {}
    public record InteractionUpdate(String kind) {}
    public record SubmissionUpdate(String submissionId, long revision, String state) {}
    public static Map<String, Class<?>> schemas() {
        return Map.of("text_delta", TextDelta.class, "thinking_delta", TextDelta.class,
            "message_committed", MessageCommit.class, "message_checkpoint", MessageCommit.class,
            "image_operation_updated", ImageUpdate.class, "model_call_updated", ModelCallUpdate.class,
            "artifact_ready", ArtifactUpdate.class, "artifact_preview", ArtifactUpdate.class,
            "interaction_updated", InteractionUpdate.class, "submission_updated", SubmissionUpdate.class);
    }
}
