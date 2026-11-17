package com.harnex.preview;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.harnex.artifact.ArtifactStore;
import com.harnex.execution.ScopeIdentity;
import com.harnex.contract.StrictRequest;
import com.harnex.workspace.WorkspaceReadService;

import io.swagger.v3.oas.annotations.media.Schema;

/** Short-lived read grants for browser previews. Never expose a Runtime session token or arbitrary filesystem path. */
@Service
public final class PreviewService {
    public static final String CONTENT_PREFIX = "/api/v1/preview-content/";
    private static final long TTL = 30 * 60_000L;
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_SESSION_BYTES = 48 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private final ScopeIdentity scope;
    private final ArtifactStore artifacts;
    private final ObjectProvider<WorkspaceReadService> workspaces;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public PreviewService(ScopeIdentity scope, ArtifactStore artifacts, ObjectProvider<WorkspaceReadService> workspaces) {
        this.scope = scope; this.artifacts = artifacts; this.workspaces = workspaces;
    }
    @Schema(description = "Choose exactly one source: project path, owned artifact, or inline HTML.")
    public record PreviewRequest(String path, String artifactId, String html) implements StrictRequest {}
    @Schema(requiredProperties = {"id", "url", "name", "mime", "expiresAt"})
    public record PreviewSession(String id, String url, String name, String mime, long expiresAt) {}
    public record Resource(String mime, byte[] bytes) {}
    private static final class Session {
        String id, root, entry, name;
        long expiresAt;
        int bytes;
        final Map<String, Resource> resources = new LinkedHashMap<>();
    }

    public synchronized PreviewSession create(PreviewRequest request) throws IOException {
        if (request == null || (request.path() != null ? 1 : 0) + (request.artifactId() != null ? 1 : 0)
            + (request.html() != null ? 1 : 0) != 1) throw new IllegalArgumentException("Choose exactly one preview source");
        Session session = new Session();
        Resource entry;
        if (request.path() != null) {
            String path = safePath(request.path());
            int slash = path.lastIndexOf('/');
            session.root = slash < 0 ? "" : path.substring(0, slash + 1);
            session.entry = session.name = path.substring(slash + 1);
            entry = file(path);
        } else if (request.artifactId() != null) {
            try (var handle = artifacts.open(scope.scopeId(), request.artifactId())) {
                session.name = handle.metadata().name();
                session.entry = "content" + extension(session.name);
                entry = new Resource(mime(session.name, handle.metadata().mime()), bounded(handle.stream()));
            }
        } else {
            session.entry = session.name = "result.html";
            byte[] bytes = request.html().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("HTML preview exceeds 2 MiB");
            entry = new Resource("text/html", bytes);
        }
        byte[] key = new byte[32]; random.nextBytes(key);
        session.id = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        session.expiresAt = System.currentTimeMillis() + TTL;
        session.resources.put(session.entry, entry); session.bytes = entry.bytes().length;
        prune();
        while (!sessions.isEmpty() && (sessions.size() >= 32 || totalBytes() + session.bytes > MAX_TOTAL_BYTES)) {
            sessions.remove(sessions.keySet().iterator().next());
        }
        sessions.put(session.id, session);
        String url = CONTENT_PREFIX + encode(scope.scopeId()) + "/" + encode(scope.instanceId()) + "/"
            + session.id + "/" + encode(session.entry);
        return new PreviewSession(session.id, url, session.name, entry.mime(), session.expiresAt);
    }

    public synchronized Resource read(String scopeId, String instanceId, String id, String path) throws IOException {
        prune();
        Session session = sessions.get(id);
        if (!scope.scopeId().equals(scopeId) || !scope.instanceId().equals(instanceId) || session == null) {
            throw new IOException("Preview expired or belongs to another space; reopen it");
        }
        String relative = safePath(path);
        Resource value = session.resources.get(relative);
        if (value == null) {
            if (session.root == null) throw new IOException("This preview has no local resource bundle");
            value = file(session.root + relative);
            if (session.resources.size() >= 128 || session.bytes + value.bytes().length > MAX_SESSION_BYTES
                || totalBytes() + value.bytes().length > MAX_TOTAL_BYTES) throw new IOException("Preview resource limit exceeded");
            session.resources.put(relative, value); session.bytes += value.bytes().length;
        }
        return value;
    }
    public synchronized void remove(String id) { sessions.remove(id); }
    private void prune() { sessions.values().removeIf(value -> value.expiresAt < System.currentTimeMillis()); }
    private long totalBytes() { return sessions.values().stream().mapToLong(value -> value.bytes).sum(); }
    private Resource file(String path) throws IOException {
        String mime = mime(path, null);
        if ("application/octet-stream".equals(mime)) throw new IllegalArgumentException("This file type cannot be previewed");
        WorkspaceReadService workspace = workspaces.getIfAvailable();
        if (workspace == null) throw new IllegalArgumentException("Project file preview requires a project; use a conversation attachment instead");
        try (var file = workspace.download(path)) { return new Resource(mime, bounded(file.input())); }
    }
    private static byte[] bounded(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Preview exceeds 32 MiB; download the original instead");
        return bytes;
    }
    static String safePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
            throw new IllegalArgumentException("Invalid preview path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.startsWith(".") || segment.chars().anyMatch(c -> c < 32)) {
                throw new IllegalArgumentException("Preview paths cannot contain hidden files or parent segments");
            }
        }
        return path;
    }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT); }
    public static String mime(String name, String fallback) {
        return switch (extension(name)) {
            case ".html", ".htm" -> "text/html";
            case ".css" -> "text/css";
            case ".js", ".mjs" -> "text/javascript";
            case ".json" -> "application/json";
            case ".svg" -> "image/svg+xml";
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".ico" -> "image/x-icon";
            case ".woff" -> "font/woff";
            case ".woff2" -> "font/woff2";
            case ".ttf" -> "font/ttf";
            case ".pdf" -> "application/pdf";
            case ".mp3" -> "audio/mpeg";
            case ".wav" -> "audio/wav";
            case ".ogg" -> "audio/ogg";
            case ".mp4" -> "video/mp4";
            case ".webm" -> "video/webm";
            case ".md", ".markdown" -> "text/markdown";
            case ".csv" -> "text/csv";
            case ".txt" -> "text/plain";
            default -> fallback == null ? "application/octet-stream" : fallback;
        };
    }
}
