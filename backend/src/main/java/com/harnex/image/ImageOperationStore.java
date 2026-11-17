package com.harnex.image;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.execution.ScopeIdentity;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;

/** Separate candidate commit markers allow recovery after the result arrived but before asset publication. */
@Component
public class ImageOperationStore {
    private final Path base;
    private final String scopeId;
    private final ObjectMapper json = new ObjectMapper();
    @Autowired public ImageOperationStore(AgentConfig config, ScopeIdentity scope) { this(Path.of(config.getDataDir()), scope.scopeId()); }
    public ImageOperationStore(Path scopeDirectory, String scopeId) {
        base = scopeDirectory.toAbsolutePath().normalize().resolve("images/operations"); this.scopeId = scopeId;
        try { Files.createDirectories(base); } catch (IOException failure) { throw unavailable(); }
    }
    public synchronized ImageOperationRecord create(ImageOperationRecord record) {
        checkScope(record.scopeId());
        var previous = find(record.scopeId(), record.operationId());
        if (previous.isPresent()) {
            if (!previous.get().requestHash().equals(record.requestHash())) throw new ImageServiceException("image_operation_conflict", "Image operation identity belongs to another request");
            return previous.get();
        }
        write(record); return record;
    }
    public synchronized ImageOperationRecord save(ImageOperationRecord record) {
        ImageOperationRecord previous = find(record.scopeId(), record.operationId()).orElseThrow();
        if (record.revision() != previous.revision() + 1) throw new ImageServiceException("image_operation_conflict", "Image operation revision changed");
        write(record); return record;
    }
    public synchronized Optional<ImageOperationRecord> find(String scope, String id) {
        checkScope(scope); Path file = directory(id).resolve("operation.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            checked(file); ImageOperationRecord record = json.readValue(Files.readAllBytes(file), ImageOperationRecord.class);
            if (record.schemaVersion() != 1 || !id.equals(record.operationId()) || !scopeId.equals(record.scopeId())) throw unavailable();
            return Optional.of(record);
        } catch (IOException failure) { throw unavailable(); }
    }
    public List<ImageOperationRecord> list(String scope, String runId) {
        checkScope(scope);
        try (var files = Files.list(base)) {
            return files.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).map(path -> find(scope, path.getFileName().toString()))
                .flatMap(Optional::stream).filter(record -> runId == null || runId.equals(record.runId())).toList();
        } catch (IOException failure) { throw unavailable(); }
    }
    List<ImageOperationRecord> all() { return list(scopeId, null); }
    public void stage(String scope, String operationId, int index, ImageProvider.BinaryWriter writer) throws IOException {
        checkScope(scope);
        if (index < 0 || index >= 16) throw new ImageServiceException("image_result_limit", "The service returned too many image candidates");
        Path dir = directory(operationId).resolve("candidates"); Files.createDirectories(dir); checked(dir);
        Path temporary = Files.createTempFile(dir, ".receiving-", ".bin");
        try {
            MessageDigest digest = digest();
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                OutputStream bytes = new DigestOutputStream(Channels.newOutputStream(channel), digest);
                OutputStream limited = new FilterOutputStream(bytes) {
                    long count;
                    @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
                    @Override public void write(byte[] buffer, int offset, int length) throws IOException {
                        count += length;
                        if (count > 64L * 1024 * 1024) throw new ImageServiceException("image_result_too_large", "An image result exceeds the storage limit");
                        out.write(buffer, offset, length);
                    }
                    @Override public void close() throws IOException { flush(); }
                };
                writer.write(limited); limited.flush(); channel.force(true);
            }
            Candidate candidate = new Candidate(index, "artifact_" + operationId + "_" + index, Files.size(temporary), HexFormat.of().formatHex(digest.digest()));
            synchronized (this) {
                Path marker = dir.resolve(index + ".json");
                if (Files.exists(marker)) {
                    Candidate previous = json.readValue(Files.readAllBytes(marker), Candidate.class);
                    if (!previous.equals(candidate)) throw new ImageServiceException("image_result_conflict", "Duplicate image result has different bytes");
                    return;
                }
                Files.move(temporary, dir.resolve(index + ".bin"), StandardCopyOption.REPLACE_EXISTING);
                // The binary is durable before publishing this marker. It can now be saved without another model call.
                AtomicJsonFiles.write(json, marker, candidate);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
    public synchronized List<Candidate> candidates(String scope, String operationId) {
        checkScope(scope); Path dir = directory(operationId).resolve("candidates");
        List<Candidate> result = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            Path marker = dir.resolve(index + ".json");
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                checked(marker); Candidate candidate = json.readValue(Files.readAllBytes(marker), Candidate.class);
                if (candidate.index() != index || !candidate.artifactId().equals("artifact_" + operationId + "_" + index)) throw unavailable();
                result.add(candidate);
            } catch (IOException failure) { throw unavailable(); }
        }
        return List.copyOf(result);
    }
    public InputStream openCandidate(String scope, String operationId, Candidate candidate) {
        checkScope(scope); Path file = directory(operationId).resolve("candidates").resolve(candidate.index() + ".bin");
        try {
            checked(file); if (Files.size(file) != candidate.bytes()) throw unavailable();
            MessageDigest digest = digest();
            try (var input = new DigestInputStream(Files.newInputStream(file), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
            if (!candidate.checksum().equals(HexFormat.of().formatHex(digest.digest()))) throw unavailable();
            return Files.newInputStream(file);
        } catch (IOException failure) { throw unavailable(); }
    }
    public boolean hasCandidateBytes(String scope, String operationId, Candidate candidate) {
        checkScope(scope);
        return Files.isRegularFile(directory(operationId).resolve("candidates").resolve(candidate.index() + ".bin"), LinkOption.NOFOLLOW_LINKS);
    }
    /** The asset and its operation reference must both be durable before releasing the temporary binary. */
    public synchronized void discardPublishedCandidate(String scope, String operationId, Candidate candidate) throws IOException {
        ImageOperationRecord record = find(scope, operationId).orElseThrow();
        if (!record.artifactIds().contains(candidate.artifactId())) throw new IOException("candidate has not been published");
        Path file = directory(operationId).resolve("candidates").resolve(candidate.index() + ".bin");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        checked(file);
        Files.deleteIfExists(file);
        // Keep the small candidate marker: recovery and image billing use its identity/count, not the binary.
    }
    protected void persist(Path target, ImageOperationRecord record) throws IOException { AtomicJsonFiles.write(json, target, record); }
    private void write(ImageOperationRecord record) {
        try { Path directory = directory(record.operationId()); Files.createDirectories(directory); checked(directory); persist(directory.resolve("operation.json"), record); }
        catch (IOException failure) { throw unavailable(); }
    }
    private Path directory(String id) { if (!Ids.isSafeResourceId(id)) throw new ImageServiceException("invalid_image_operation", "Invalid image operation identity"); return base.resolve(id); }
    private void checkScope(String scope) { if (!scopeId.equals(scope)) throw new ImageServiceException("scope_mismatch", "Image operation belongs to a different scope"); }
    private void checked(Path file) throws IOException { if (!file.toRealPath().startsWith(base.toRealPath())) throw unavailable(); }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    private static ImageServiceException unavailable() { return new ImageServiceException("image_storage_unavailable", "Image operation storage is unavailable"); }
    public record Candidate(int index, String artifactId, long bytes, String checksum) {}
}
