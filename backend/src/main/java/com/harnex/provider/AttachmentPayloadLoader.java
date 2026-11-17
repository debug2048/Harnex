package com.harnex.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.harnex.model.MessageAttachment;
import com.harnex.util.Ids;

/** Safely resolves and bounded-reads a persisted upload owned by one conversation thread. */
final class AttachmentPayloadLoader {

    private AttachmentPayloadLoader() {}

    record Loaded(String name, String mime, byte[] bytes) {}

    /** Safe display name for prompts; never returns a persisted path or a path-separator-bearing value. */
    static String displayName(MessageAttachment attachment) {
        return attachment != null && safeBasename(attachment.getName()) ? attachment.getName() : "attachment";
    }

    /**
     * Reconstruct a tool-readable upload path from trusted ownership fields. Never echo {@code attachment.path}, which
     * may come from an old/imported transcript and is not an authorisation boundary.
     */
    static String visiblePath(MessageAttachment attachment, Path uploadsRoot) {
        String name = displayName(attachment);
        if (attachment == null || uploadsRoot == null || !safeBasename(attachment.getName())
            || !Ids.isSafe(attachment.getThreadId())) {
            return name;
        }
        try {
            Path root = uploadsRoot.toAbsolutePath().normalize();
            Path thread = root.resolve(attachment.getThreadId()).normalize();
            Path target = thread.resolve(name).normalize();
            return thread.startsWith(root) && target.startsWith(thread) ? target.toString() : name;
        } catch (RuntimeException invalidPath) {
            return name;
        }
    }

    /**
     * Return the attachment bytes, or {@code null} when ownership, containment, type-independent size, or I/O checks
     * fail. The persisted absolute {@code attachment.path} is deliberately never trusted as an authorisation boundary.
     */
    static Loaded load(MessageAttachment attachment, Path uploadsRoot, long maxBytes) {
        if (attachment == null || uploadsRoot == null || maxBytes <= 0 || maxBytes >= Integer.MAX_VALUE) {
            return null;
        }
        String name = attachment.getName();
        String threadId = attachment.getThreadId();
        if (!safeBasename(name) || threadId == null || threadId.isBlank() || !Ids.isSafe(threadId)
            || attachment.getSize() > maxBytes) {
            return null;
        }
        try {
            Path rootReal = uploadsRoot.toRealPath();
            Path threadDir = uploadsRoot.resolve(threadId).normalize();
            Path target = threadDir.resolve(name).normalize();
            if (!target.startsWith(threadDir) || Files.isSymbolicLink(threadDir) || Files.isSymbolicLink(target)
                || !Files.isDirectory(threadDir, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Path threadReal = threadDir.toRealPath();
            Path targetReal = target.toRealPath();
            if (!threadReal.startsWith(rootReal) || !targetReal.startsWith(threadReal)
                || Files.size(targetReal) > maxBytes) {
                return null;
            }
            byte[] bytes = readBounded(targetReal, (int)maxBytes);
            return bytes == null ? null : new Loaded(name, attachment.getMime(), bytes);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static byte[] readBounded(Path target, int maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(target); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = in.read(buffer)) >= 0;) {
                if (read == 0) {
                    continue;
                }
                if (total > maxBytes - read) {
                    return null;
                }
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toByteArray();
        }
    }

    static boolean safeBasename(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.contains("/") || name.contains("\\")
            || ".".equals(name) || "..".equals(name)) {
            return false;
        }
        try {
            Path path = Path.of(name);
            return path.getNameCount() == 1 && name.equals(path.getFileName().toString());
        } catch (RuntimeException invalidPath) {
            return false;
        }
    }
}
