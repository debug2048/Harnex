package com.harnex.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A file attached to a chat message (image, PDF, doc, …).
 *
 * <p>
 * Persisted with the message so the same upload can be re-emitted to the LLM on subsequent turns of the same
 * conversation without re-uploading. Stored under {@code data/uploads/{threadId}/} — the {@code path} field is the real
 * absolute filesystem path of the stored file, which the agent passes directly to {@code file_read}.
 *
 * <p>
 * Supported MIME types can be emitted as provider-native image/file/PDF/audio/video blocks when
 * {@code harnex.vision.enabled=true} and the concrete endpoint model declares the matching
 * {@code input-modalities}. Other attachments are surfaced as a visible local-file hint so the agent can use
 * {@code file_read} (and the Tika sidecar when available).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageAttachment {
    /** Canonical asset identity for image tools; independent of the projected upload filename. */
    private String artifactId;
    private String name; // e.g. "spec.pdf"
    private String mime; // e.g. "image/png" / "application/pdf"
    private long size; // bytes
    private String path; // real absolute path, e.g. <dataDir>/uploads/<threadId>/spec.pdf
    /** True if a prior on-demand file_read generated a cached Tika/OCR sidecar. */
    private boolean extracted;
    /**
     * Owning thread id — required to resolve the file back from disk under {@code data/uploads/<threadId>/<name>}.
     * Without it, two threads that uploaded files of the same name + size could be confused by a basename-only lookup,
     * and the wrong image would be inlined into a vision request.
     */
    private String threadId;

    @JsonIgnore
    public boolean isImage() {
        return mime != null && mime.startsWith("image/");
    }
}
