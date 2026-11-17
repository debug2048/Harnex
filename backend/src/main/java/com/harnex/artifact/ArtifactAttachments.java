package com.harnex.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.harnex.config.AgentConfig;
import com.harnex.execution.ScopeIdentity;
import com.harnex.model.ChatMessage;
import com.harnex.model.MessageAttachment;
import com.harnex.util.FileChecksums;
import com.harnex.util.Ids;

/** Trusted provider/Agent input projection. The archive always keeps the canonical artifact reference. */
@Component
public final class ArtifactAttachments {
    private final ArtifactStore artifacts;
    private final ScopeIdentity scope;
    private final Path uploads;
    public ArtifactAttachments(ArtifactStore artifacts, ScopeIdentity scope, AgentConfig config) {
        this.artifacts = artifacts; this.scope = scope;
        uploads = Path.of(config.getDataDir()).toAbsolutePath().normalize().resolve("uploads");
    }
    public void validate(List<String> ids) {
        for (String id : ids) require(id);
    }
    private ArtifactRecord require(String id) {
        return artifacts.find(scope.scopeId(), id).filter(record -> record.availability() == ArtifactRecord.Availability.LOCAL)
            .orElseThrow(() -> new ArtifactException("artifact_not_found", "attachment is unavailable in this scope: " + id));
    }
    public List<MessageAttachment> resolve(String conversationId, List<String> ids) {
        if (!Ids.isSafe(conversationId)) throw new IllegalArgumentException("invalid conversationId");
        List<MessageAttachment> result = new ArrayList<>();
        for (String id : ids) {
            ArtifactRecord record = require(id);
            String name = id + extension(record.mime());
            Path directory = uploads.resolve(conversationId);
            Path temporary = null;
            try {
                Files.createDirectories(directory);
                if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(uploads.toRealPath())) {
                    throw new IOException("attachment projection escapes uploads");
                }
                Path target = directory.resolve(name);
                if (Files.isSymbolicLink(target)) throw new IOException("invalid attachment projection");
                if (!Files.isRegularFile(target) || Files.size(target) != record.bytes()
                    || !FileChecksums.sha256(target).equals(record.checksum())) {
                    temporary = Files.createTempFile(directory, "asset-", ".pending");
                    try (ArtifactStore.ContentHandle content = artifacts.open(scope.scopeId(), id)) {
                        Files.copy(content.stream(), temporary, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                result.add(MessageAttachment.builder().artifactId(id).name(name).mime(record.mime()).size(record.bytes())
                    .threadId(conversationId).path(target.toString()).build());
            } catch (IOException failure) { throw new ArtifactException("artifact_unavailable", "could not prepare attachment", failure); }
            finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
        }
        return List.copyOf(result);
    }

    /** Request-only references keep image tool arguments tied to the images in this particular message. */
    public ChatMessage withImageReferences(ChatMessage message) {
        if (message == null || !("user".equals(message.getRole()) || "assistant".equals(message.getRole()))
            || message.getAttachments() == null || message.getAttachments().isEmpty()) return message;
        StringBuilder references = new StringBuilder();
        for (int index = 0; index < message.getAttachments().size(); index++) {
            MessageAttachment attachment = message.getAttachments().get(index);
            if (attachment == null || !attachment.isImage()) continue;
            String id = imageReferenceId(attachment);
            if (id != null) references.append("- Attachment ").append(index + 1).append(": artifactId=").append(id).append('\n');
        }
        if (references.isEmpty()) return message;
        String content = message.getContent() == null ? "" : message.getContent();
        return message.toBuilder().content(content + "\n\n<image_attachments>\n"
            + "Image references for this message, in attachment order. Use these exact IDs for sourceArtifactIds or artifactIds "
            + "when an image tool requires them. A reference alone does not mean the image contents have been inspected.\n"
            + references + "</image_attachments>").build();
    }

    private String imageReferenceId(MessageAttachment attachment) {
        String id = attachment.getArtifactId();
        // Sessions saved before artifactId was carried explicitly used the canonical ID as the upload basename.
        // Recover only a matching asset in this scope; arbitrary filenames and absolute paths are never references.
        boolean legacy = id == null;
        if (legacy) {
            String name = attachment.getName(), suffix = extension(attachment.getMime());
            if (name == null || !name.endsWith(suffix)) return null;
            id = name.substring(0, name.length() - suffix.length());
        }
        if (!Ids.isSafeResourceId(id)) return null;
        ArtifactRecord asset = artifacts.find(scope.scopeId(), id).orElse(null);
        if (asset == null || asset.availability() != ArtifactRecord.Availability.LOCAL
            || !asset.mime().equals(attachment.getMime()) || legacy && asset.bytes() != attachment.getSize()) return null;
        return id;
    }

    private static String extension(String mime) {
        return switch (mime) { case "image/png" -> ".png"; case "image/jpeg" -> ".jpg"; case "image/webp" -> ".webp";
            case "image/gif" -> ".gif"; case "application/pdf" -> ".pdf"; case "text/plain" -> ".txt"; default -> ".bin"; };
    }
}
