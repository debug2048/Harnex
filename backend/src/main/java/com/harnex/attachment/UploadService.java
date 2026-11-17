package com.harnex.attachment;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.util.Ids;

import lombok.extern.slf4j.Slf4j;

/** Workspace upload application service. HTTP adapters provide an {@link UploadSource}; this class owns all state. */
@Slf4j
@Service
@ProjectRuntime
public class UploadService {

    private static final int LIST_LIMIT = 200;
    private static final String SIDECAR_SUFFIX = ".extracted.txt";
    private static final String STAGING_SUFFIX = ".part";
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final Set<String> BLOCKED_MIME_PREFIXES = Set.of("application/x-msdownload",
        "application/x-dosexec", "application/x-executable", "application/x-mach-binary",
        "application/x-sharedlib", "application/x-msi", "application/x-bat", "application/x-shellscript",
        "application/x-perl", "application/x-php", "application/zip", "application/x-zip-compressed",
        "application/gzip", "application/x-gzip", "application/x-tar", "application/x-bzip2",
        "application/x-7z-compressed", "application/x-rar-compressed", "application/x-lzh-compressed",
        "application/x-cab");
    private static final int UPLOAD_BUCKET_CAPACITY = 20;
    private static final long UPLOAD_REFILL_MS = 3_000L;

    private final ConcurrentMap<String, long[]> uploadBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> threadQuotaLocks = new ConcurrentHashMap<>();
    private final WorkspacePathResolver paths;
    private final AgentConfig config;
    private final DocumentTextExtractor extractor;

    public UploadService(WorkspacePathResolver paths, AgentConfig config, DocumentTextExtractor extractor) {
        this.paths = paths;
        this.config = config;
        this.extractor = extractor;
    }

    public Result upload(String threadId, UploadSource source) {
        if (!Ids.isSafe(threadId)) return Result.of(400, Map.of("error", "invalid threadId"));
        if (!tryAcquireUploadToken(threadId)) {
            return Result.of(429, Map.of("error", "upload rate limit exceeded; retry in a few seconds"));
        }
        if (source == null || source.empty()) return Result.of(400, Map.of("error", "empty file"));
        String safeName = sanitizeName(source.originalFilename());
        if (safeName.isBlank()) return Result.of(400, Map.of("error", "invalid filename"));

        long maxSingleFile = config.getUpload() == null ? 0L : config.getUpload().getMaxSingleFileBytes();
        if (maxSingleFile > 0 && source.size() > maxSingleFile) {
            return Result.of(413, Map.of("error", "single file exceeds size cap", "incomingBytes", source.size(),
                "limitBytes", maxSingleFile));
        }
        Path directory = paths.uploadsFor(threadId);
        long perThreadCap = config.getUpload() == null
            ? Long.MAX_VALUE : config.getUpload().getPerThreadMaxBytes();
        Path finalTarget;
        String mime;
        long size;
        synchronized (quotaLockFor(threadId)) {
            if (perThreadCap > 0 && perThreadCap != Long.MAX_VALUE) {
                long existing = currentThreadBytes(directory);
                if (existing + source.size() > perThreadCap) {
                    return Result.of(413, Map.of("error", "per-thread upload quota exceeded",
                        "existingBytes", existing, "incomingBytes", source.size(), "limitBytes", perThreadCap));
                }
            }
            Result staged = stageAndPromote(threadId, safeName, source, directory);
            if (staged.status() != 200) return staged;
            @SuppressWarnings("unchecked")
            Map<String, Object> stagedBody = (Map<String, Object>)staged.body();
            finalTarget = Path.of(String.valueOf(stagedBody.get("path")));
            mime = String.valueOf(stagedBody.get("mime"));
            size = sizeOrZero(finalTarget);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("threadId", threadId);
        response.put("name", finalTarget.getFileName().toString());
        response.put("size", size);
        response.put("mime", mime);
        response.put("path", finalTarget.toString());
        // Upload is deliberately storage-only. Native-capable models receive these original
        // bytes directly; Tika/OCR runs lazily only when file_read needs a local fallback.
        // Keeping extraction out of the multipart request makes upload latency proportional
        // to the file transfer rather than parser/OCR cost and avoids doing duplicate work for
        // models that can already consume the attachment.
        response.put("extracted", false);
        response.put("textChars", 0);
        return Result.of(200, response);
    }

    private Result stageAndPromote(String threadId, String safeName, UploadSource source, Path directory) {
        Path staging;
        try {
            Files.createDirectories(directory);
            staging = Files.createTempFile(directory, ".upload-", STAGING_SUFFIX);
        } catch (IOException failure) {
            log.warn("Upload staging create failed: thread={}: {}", threadId, failure.toString());
            return Result.of(500, Map.of("error", "write failed"));
        }
        try (InputStream input = source.openStream()) {
            Files.copy(input, staging, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            deleteQuietly(staging);
            log.warn("Upload write failed: thread={} name={}: {}", threadId, safeName, failure.toString());
            return Result.of(500, Map.of("error", "write failed"));
        }

        String mime = detectMime(staging, source.contentType());
        for (String prefix : BLOCKED_MIME_PREFIXES) {
            if (mime.startsWith(prefix)) {
                deleteQuietly(staging);
                log.warn("Upload rejected (blocked mime {}): thread={} name={}", mime, threadId, safeName);
                return Result.of(415, Map.of("error", "file type not allowed: " + mime));
            }
        }
        if (OCTET_STREAM.equals(mime) && !looksTextual(staging)) {
            deleteQuietly(staging);
            return Result.of(415, Map.of("error", "file type could not be identified"));
        }

        Path promoted = null;
        try {
            for (int attempt = 0; attempt < 16; attempt++) {
                Path candidate = uniqueTarget(directory, safeName);
                try {
                    Files.move(staging, candidate, StandardCopyOption.ATOMIC_MOVE);
                    promoted = candidate;
                    break;
                } catch (AtomicMoveNotSupportedException ignored) {
                    if (Files.exists(candidate)) continue;
                    Files.move(staging, candidate);
                    promoted = candidate;
                    break;
                } catch (FileAlreadyExistsException ignored) {
                    // Pick another visible name.
                }
            }
        } catch (IOException failure) {
            log.warn("Upload promote failed: thread={} name={}: {}", threadId, safeName, failure.toString());
        }
        if (promoted == null) {
            deleteQuietly(staging);
            return Result.of(500, Map.of("error", "promote failed"));
        }
        return Result.of(200, Map.of("path", promoted.toString(), "mime", mime));
    }

    public Result list(String threadId) {
        if (!Ids.isSafe(threadId)) return Result.of(400, List.of());
        Path directory = paths.uploadsFor(threadId);
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(directory)) return Result.of(200, result);
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> !Files.isSymbolicLink(path))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !path.getFileName().toString().endsWith(SIDECAR_SUFFIX))
                .filter(path -> !path.getFileName().toString().endsWith(STAGING_SUFFIX))
                .sorted(Comparator.comparing(path -> path.getFileName().toString())).limit(LIST_LIMIT)
                .forEach(path -> result.add(metadata(path)));
        } catch (IOException failure) {
            log.debug("List uploads failed: {}", failure.toString());
        }
        return Result.of(200, result);
    }

    public DownloadResult download(String threadId, String name) {
        if (!Ids.isSafe(threadId)) return DownloadResult.error(400);
        Path directory = paths.uploadsFor(threadId);
        Path file = directory.resolve(sanitizeName(name)).normalize();
        if (!file.startsWith(directory) || !isSafeUploadLeaf(directory, file)) return DownloadResult.error(404);
        return new DownloadResult(200, file, detectMime(file, null), file.getFileName().toString());
    }

    public Result delete(String threadId, String name) {
        if (!Ids.isSafe(threadId)) return Result.of(400, Map.of("error", "invalid threadId"));
        Path directory = paths.uploadsFor(threadId);
        Path file = directory.resolve(sanitizeName(name)).normalize();
        if (!file.startsWith(directory) || Files.isSymbolicLink(file)) {
            return Result.of(400, Map.of("error", "invalid upload path"));
        }
        boolean deleted = false;
        try {
            deleted = Files.deleteIfExists(file);
            Files.deleteIfExists(sidecarPath(file));
        } catch (IOException failure) {
            log.debug("Delete upload failed: {}", failure.toString());
        }
        return Result.of(200, Map.of("deleted", deleted));
    }

    /** Clear rate state when a thread is deleted; quota lock identity is retained to avoid an ABA race. */
    public void purge(String threadId) {
        if (threadId != null) uploadBuckets.remove(threadId);
    }

    private Object quotaLockFor(String threadId) {
        return threadQuotaLocks.computeIfAbsent(threadId, ignored -> new Object());
    }

    private boolean tryAcquireUploadToken(String threadId) {
        long now = System.nanoTime();
        long[] state = uploadBuckets.computeIfAbsent(threadId,
            ignored -> new long[] {(long)UPLOAD_BUCKET_CAPACITY * 1000L, now});
        synchronized (state) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - state[1]);
            if (elapsedMs > 0) {
                long refill = (elapsedMs * 1000L) / UPLOAD_REFILL_MS;
                if (refill > 0) {
                    state[0] = Math.min((long)UPLOAD_BUCKET_CAPACITY * 1000L, state[0] + refill);
                    state[1] = now;
                }
            }
            if (state[0] < 1000L) return false;
            state[0] -= 1000L;
            return true;
        }
    }

    private Map<String, Object> metadata(Path path) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", path.getFileName().toString());
        metadata.put("size", sizeOrZero(path));
        metadata.put("mime", detectMime(path, null));
        metadata.put("path", path.toString());
        Path sidecar = sidecarPath(path);
        metadata.put("extracted", Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS));
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            metadata.put("modified", attributes.lastModifiedTime().toString());
        } catch (IOException ignored) {
            // Optional presentation fact.
        }
        return metadata;
    }

    public static String sanitizeName(String raw) {
        if (raw == null) return "";
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder safe = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (Character.isLetterOrDigit(value) || value == '.' || value == '-' || value == '_'
                || value == ' ' || value == '(' || value == ')') safe.append(value);
            else safe.append('_');
        }
        String cleaned = safe.toString().trim();
        if (cleaned.startsWith(".")) cleaned = "_" + cleaned;
        if (cleaned.length() > 180) {
            int dot = cleaned.lastIndexOf('.');
            String extension = dot > 0 && dot > cleaned.length() - 12 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, 180 - extension.length()) + extension;
        }
        return cleaned;
    }

    private static Path uniqueTarget(Path directory, String name) {
        Path candidate = directory.resolve(name);
        if (!Files.exists(candidate)) return candidate;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 1; index < 1000; index++) {
            Path next = directory.resolve(stem + " (" + index + ")" + extension);
            if (!Files.exists(next)) return next;
        }
        return directory.resolve(stem + "-" + System.currentTimeMillis() + extension);
    }

    private String detectMime(Path file, String fallback) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            String detected = extractor.detectMime(input, file.getFileName().toString());
            if (detected != null && !detected.isBlank()) return detected;
        } catch (IOException ignored) {
            // Fall through to the declared type.
        }
        return fallback == null || fallback.isBlank() ? OCTET_STREAM : fallback;
    }

    private static boolean looksTextual(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] head = new byte[4096];
            int read = input.read(head);
            if (read <= 0) return true;
            int printable = 0;
            for (int index = 0; index < read; index++) {
                int value = head[index] & 0xFF;
                if (value == 0) return false;
                if (value == 0x09 || value == 0x0A || value == 0x0D || (value >= 0x20 && value < 0x7F)
                    || value >= 0x80) printable++;
            }
            return printable * 100 / read >= 90;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static long currentThreadBytes(Path directory) {
        if (!Files.isDirectory(directory)) return 0L;
        long[] sum = {0L};
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(file -> sum[0] += sizeOrZero(file));
        } catch (IOException ignored) {
            // Preserve the existing best-effort quota behaviour when enumeration is unavailable.
        }
        return sum[0];
    }

    private static long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static boolean isSafeUploadLeaf(Path directory, Path file) {
        try {
            return !Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && file.toRealPath().startsWith(directory.toRealPath());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path sidecarPath(Path original) {
        return original.resolveSibling(original.getFileName() + SIDECAR_SUFFIX);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Staging files are hidden and can be swept later.
        }
    }

    @FunctionalInterface
    public interface InputStreamSource {
        InputStream open() throws IOException;
    }

    public record UploadSource(String originalFilename, String contentType, long size, boolean empty,
        InputStreamSource input) {
        public InputStream openStream() throws IOException {
            return input.open();
        }
    }

    public record Result(int status, Object body) {
        static Result of(int status, Object body) {
            return new Result(status, body);
        }
    }

    public record DownloadResult(int status, Path file, String mime, String filename) {
        static DownloadResult error(int status) {
            return new DownloadResult(status, null, null, null);
        }
    }

}
