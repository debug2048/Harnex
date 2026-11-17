package com.harnex.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.harnex.config.ProjectRuntime;
import com.harnex.model.MessageAttachment;
import com.harnex.workspace.WorkspacePathResolver;

import lombok.extern.slf4j.Slf4j;

/** Resolves uploaded leaf names into safe, workspace-owned message attachments. */
@Slf4j
@Service
@ProjectRuntime
public class AttachmentService {

    private final WorkspacePathResolver paths;

    public AttachmentService(WorkspacePathResolver paths) {
        this.paths = paths;
    }

    public List<MessageAttachment> resolve(String threadId, List<String> names, List<String> dropped) {
        List<MessageAttachment> attachments = new ArrayList<>();
        Path directory = paths.uploadsFor(threadId);
        Path realDirectory;
        try {
            realDirectory = directory.toRealPath();
        } catch (IOException ignored) {
            realDirectory = directory.toAbsolutePath().normalize();
        }
        for (String raw : names) {
            if (raw == null || raw.isBlank()) continue;
            String safe = raw.replace('\\', '/');
            int slash = safe.lastIndexOf('/');
            if (slash >= 0) safe = safe.substring(slash + 1);
            Path file = directory.resolve(safe).normalize();
            if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
                dropped.add(safe);
                continue;
            }
            try {
                if (!file.toRealPath().startsWith(realDirectory)) {
                    log.warn("attachment {} resolves outside uploads root via symlink; dropping", safe);
                    dropped.add(safe);
                    continue;
                }
            } catch (IOException failure) {
                dropped.add(safe);
                continue;
            }
            String mime;
            try {
                mime = Files.probeContentType(file);
            } catch (IOException ignored) {
                mime = null;
            }
            if (mime == null) mime = "application/octet-stream";
            long size;
            try {
                size = Files.size(file);
            } catch (IOException ignored) {
                size = 0L;
            }
            boolean extracted = Files.exists(file.resolveSibling(file.getFileName() + ".extracted.txt"));
            attachments.add(MessageAttachment.builder().name(safe).mime(mime).size(size).path(file.toString())
                .extracted(extracted).threadId(threadId).build());
        }
        return attachments;
    }
}
