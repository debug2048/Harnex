package com.harnex.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.execution.ScopeIdentity;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.safety.PathProtection;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;
import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

import io.swagger.v3.oas.annotations.media.Schema;

/** Explicit user export in the destination Runtime. Source paths never cross scopes. */
@Service @ProjectRuntime
public final class ProjectArtifactExporter {
    private final ArtifactStore assets;
    private final ScopeIdentity scope;
    private final WorkspacePathResolver paths;
    private final PathProtection protection;
    private final Path receipts;
    private final ObjectMapper json = new ObjectMapper();
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    public ProjectArtifactExporter(ArtifactStore assets, ScopeIdentity scope, WorkspacePathResolver paths,
            PathProtection protection, AgentConfig config) {
        this.assets = assets; this.scope = scope; this.paths = paths; this.protection = protection;
        this.receipts = Path.of(config.getDataDir()).resolve("artifact-exports");
    }
    @Schema(name = "ProjectArtifactExportRequest")
    public record Request(String clientRequestId, String relativePath, String expectedChecksum) {}
    @Schema(name = "ProjectArtifactExportResult")
    public record Result(String exportId, String scopeId, String artifactId, String relativePath, String checksum, long bytes, long exportedAt) {}
    public record Receipt(String requestHash, Result result, boolean committed) {}
    public static final class Failure extends RuntimeException {
        private final String code;
        private final String existingChecksum;
        public Failure(String code, String message, String existingChecksum) { super(message); this.code = code; this.existingChecksum = existingChecksum; }
        public String code() { return code; }
        public String existingChecksum() { return existingChecksum; }
    }
    public synchronized Result export(String artifactId, Request request) {
        if (request == null || !Ids.isSafeResourceId(request.clientRequestId())) throw failure("invalid_export_request", "A valid clientRequestId is required");
        String relative = normalize(request.relativePath());
        if (request.expectedChecksum() != null && !request.expectedChecksum().matches("[a-f0-9]{64}")) throw failure("invalid_export_request", "Invalid expected checksum");
        String exportId = "export_" + IdempotencyReceiptStore.requestHash(request.clientRequestId()).substring(0, 32);
        var source = assets.find(scope.scopeId(), artifactId).orElseThrow(() -> failure("artifact_not_found", "The source asset is unavailable in this space"));
        if (source.bytes() > MAX_BYTES) throw failure("artifact_too_large", "The asset exceeds the export byte limit");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("artifactId", artifactId); input.put("relativePath", relative); input.put("expectedChecksum", request.expectedChecksum()); input.put("checksum", source.checksum());
        String hash = IdempotencyReceiptStore.requestHash(input);
        Path receiptPath = receipts.resolve(exportId + ".json");
        Path temporary = null;
        try {
            Receipt previous = Files.isRegularFile(receiptPath) ? json.readValue(receiptPath.toFile(), Receipt.class) : null;
            if (previous != null && !hash.equals(previous.requestHash())) throw failure("export_idempotency_conflict", "This export ID was already used for a different request");
            if (previous != null && previous.committed()) return previous.result();
            Path destination = destination(relative);
            String existing = checksum(destination);
            // A persisted intent plus matching bytes recovers a lost acknowledgement without writing again.
            if (previous != null && source.checksum().equals(existing)) {
                AtomicJsonFiles.write(json, receiptPath, new Receipt(hash, previous.result(), true));
                return previous.result();
            }
            checkConflict(existing, request.expectedChecksum());
            Result result = new Result(exportId, scope.scopeId(), artifactId, relative, source.checksum(), source.bytes(), System.currentTimeMillis());
            AtomicJsonFiles.write(json, receiptPath, new Receipt(hash, result, false));
            Files.createDirectories(destination.getParent());
            destination = destination(relative);
            temporary = Files.createTempFile(destination.getParent(), ".harnex-export-", ".pending");
            try (var handle = assets.open(scope.scopeId(), artifactId)) {
                Files.copy(handle.stream(), temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (!source.checksum().equals(checksum(temporary))) throw failure("artifact_corrupt", "The source asset checksum does not match");
            destination = destination(relative);
            checkConflict(checksum(destination), request.expectedChecksum());
            if (request.expectedChecksum() == null) {
                // No REPLACE_EXISTING: a concurrent create is always a conflict.
                try { Files.move(temporary, destination); }
                catch (FileAlreadyExistsException conflict) {
                    throw new Failure("export_conflict", "The destination was created while the export was being saved; review it again", checksum(destination));
                }
            } else {
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            }
            temporary = null;
            AtomicJsonFiles.write(json, receiptPath, new Receipt(hash, result, true));
            return result;
        } catch (IOException failure) {
            throw failure("export_storage_unavailable", "The export could not be saved. Retry the same export to recover its acknowledgement");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }
    private static String normalize(String path) {
        if (path == null || path.isBlank() || path.length() > 2048 || path.indexOf('\0') >= 0) throw failure("invalid_export_path", "Choose a project-relative file path");
        String value = path.replace('\\', '/');
        if (value.startsWith("/") || value.startsWith("~") || value.contains(":")) throw failure("invalid_export_path", "The destination must be relative to the selected project");
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw failure("invalid_export_path", "The destination cannot contain empty, dot or parent segments");
        }
        return value;
    }
    private Path destination(String relative) throws IOException {
        Path root = paths.getProjectRoot().toRealPath();
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) throw failure("invalid_export_path", "The destination is outside the selected project");
        Path cursor = root;
        for (Path segment : root.relativize(candidate)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) throw failure("export_path_denied", "Export destinations cannot pass through symbolic links");
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().startsWith(root)) throw failure("export_path_denied", "The destination resolves outside the selected project");
        }
        try {
            Path resolved = paths.toRealForWrite(relative, null, false);
            if (protection.isProtected(null, resolved)) throw failure("export_path_denied", "The destination is protected by project policy");
        } catch (SecurityException denied) { throw failure("export_path_denied", "The destination is protected by project policy"); }
        return candidate;
    }
    private static void checkConflict(String existing, String expected) {
        if (!Objects.equals(existing, expected)) throw new Failure("export_conflict", "The destination already exists or changed; review it before replacing", existing);
    }
    private static String checksum(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw failure("export_path_denied", "The destination is not a regular file");
        if (Files.size(path) > MAX_BYTES) throw failure("export_path_denied", "The existing file exceeds the export comparison limit");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[32768]; int n; long count = 0;
                while ((n = input.read(buffer)) >= 0) {
                    if ((count += n) > MAX_BYTES) throw failure("export_path_denied", "The file changed beyond the export byte limit");
                    digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Failure failure(String code, String message) { return new Failure(code, message, null); }
}
