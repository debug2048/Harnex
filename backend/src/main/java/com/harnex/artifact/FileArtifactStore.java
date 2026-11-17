package com.harnex.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.conversation.ProjectStorage;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.FileChecksums;
import com.harnex.util.Ids;

/** Checked binary storage. Publishing metadata is the commit point; callers never choose a filesystem destination. */
public class FileArtifactStore implements ArtifactStore {
    private static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;
    private static final long MAX_PIXELS = 100_000_000L;
    private final Path base;
    private final String scopeId;
    private final long maxBytes;
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectStorage storage;

    public FileArtifactStore(Path scopeDirectory, String scopeId) { this(scopeDirectory, scopeId, DEFAULT_MAX_BYTES); }
    public FileArtifactStore(Path scopeDirectory, String scopeId, long maxBytes) {
        this(scopeDirectory, scopeId, maxBytes, null);
    }
    public FileArtifactStore(ProjectStorage storage, String scopeId) {
        this(storage.root(), scopeId, DEFAULT_MAX_BYTES, storage);
    }
    private FileArtifactStore(Path scopeDirectory, String scopeId, long maxBytes, ProjectStorage storage) {
        this.storage = storage;
        this.base = scopeDirectory.toAbsolutePath().normalize().resolve("assets");
        this.scopeId = Objects.requireNonNull(scopeId);
        if (maxBytes < 1) throw new IllegalArgumentException("asset byte limit must be positive");
        this.maxBytes = maxBytes;
        try { Files.createDirectories(base.resolve(".staging")); }
        catch (IOException failure) { throw unavailable(failure); }
    }

    @Override public ArtifactRecord save(String scope, SaveRequest request, InputStream content) {
        checkScope(scope);
        Objects.requireNonNull(content);
        String id = request.artifactId() == null ? "artifact_" + UUID.randomUUID().toString().replace("-", "") : request.artifactId();
        validateId(id);
        for (String source : request.sourceRefs()) {
            if (find(scope, source).isEmpty()) throw error("artifact_not_found", "a source artifact is not available in this scope");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(base.resolve(".staging"), "asset-", ".pending");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[32 * 1024];
                int length;
                while ((length = content.read(buffer)) != -1) {
                    if (length == 0) continue;
                    count += length;
                    if (count > maxBytes) throw error("artifact_too_large", "asset exceeds the configured byte limit");
                    digest.update(buffer, 0, length);
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, length);
                    while (bytes.hasRemaining()) channel.write(bytes);
                }
                channel.force(true);
            }
            if (count == 0) throw error("artifact_empty", "asset content is empty");
            ImageInfo image = inspectImage(temporary);
            if (request.requireImage() && image == null) throw error("invalid_image", "the result is not a supported raster image");
            if (request.mime() != null && request.mime().startsWith("image/")
                && !"image/svg+xml".equals(request.mime()) && image == null) {
                throw error("invalid_image", "image MIME does not match the content");
            }
            String mime = image == null ? safeMime(request.mime()) : image.mime();
            ArtifactRecord record = new ArtifactRecord(1, id, scopeId, safeName(request.name()), mime, count,
                image == null ? null : image.width(), image == null ? null : image.height(), HexFormat.of().formatHex(digest.digest()),
                request.creator(), request.sourceRefs(), System.currentTimeMillis(), ArtifactRecord.Availability.LOCAL);
            Path staged = temporary;
            return storage == null ? publish(scope, record, staged) : storage.transaction(() -> publish(scope, record, staged));
        } catch (IOException failure) { throw unavailable(failure); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    /** Only final publication is serialized; reading uploads or provider responses never holds the archive lock. */
    private synchronized ArtifactRecord publish(String scope, ArtifactRecord record, Path staged) {
        Optional<ArtifactRecord> previous = find(scope, record.artifactId());
        if (previous.isPresent()) {
            ArtifactRecord saved = previous.get();
            if (!saved.checksum().equals(record.checksum()) || !Objects.equals(saved.creator(), record.creator())
                || !saved.sourceRefs().equals(record.sourceRefs())) {
                throw error("artifact_conflict", "artifactId already refers to different content or ownership");
            }
            return saved;
        }
        try {
            Path directory = directory(record.artifactId());
            Files.createDirectories(directory); checkRealPath(directory);
            Files.move(staged, directory.resolve("original"), StandardCopyOption.REPLACE_EXISTING);
            persistMetadata(directory.resolve("meta.json"), record);
            return record;
        } catch (IOException failure) { throw unavailable(failure); }
    }

    protected void persistMetadata(Path path, ArtifactRecord record) throws IOException { AtomicJsonFiles.write(json, path, record); }

    @Override public Optional<ArtifactRecord> find(String scope, String artifactId) {
        return storage == null ? readMetadata(scope, artifactId) : storage.transaction(() -> readMetadata(scope, artifactId));
    }
    private synchronized Optional<ArtifactRecord> readMetadata(String scope, String artifactId) {
        checkScope(scope);
        Path metadata = directory(artifactId).resolve("meta.json");
        if (!Files.exists(metadata)) return Optional.empty();
        try {
            checkRealPath(metadata);
            ArtifactRecord record = json.readValue(Files.readAllBytes(metadata), ArtifactRecord.class);
            if (record.schemaVersion() != 1 || !record.scopeId().equals(scopeId) || !record.artifactId().equals(artifactId)) {
                throw error("artifact_corrupt", "asset metadata identity is invalid");
            }
            return Optional.of(record);
        } catch (IOException failure) { throw unavailable(failure); }
    }

    @Override public ContentHandle open(String scope, String artifactId) {
        ArtifactRecord record = find(scope, artifactId).orElseThrow(() -> error("artifact_not_found", "asset is unavailable"));
        Path original = directory(artifactId).resolve("original");
        try {
            checkRealPath(original);
            if (!Files.isRegularFile(original) || Files.size(original) != record.bytes()) throw error("artifact_unavailable", "asset bytes are unavailable");
            if (!FileChecksums.sha256(original).equals(record.checksum())) throw error("artifact_corrupt", "asset bytes no longer match the recorded checksum");
            InputStream stream = Files.newInputStream(original);
            return new ContentHandle() {
                @Override public ArtifactRecord metadata() { return record; }
                @Override public InputStream stream() { return stream; }
                @Override public void close() throws IOException { stream.close(); }
            };
        } catch (IOException failure) { throw unavailable(failure); }
    }

    private ImageInfo inspectImage(Path path) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) return null;
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!List.of("png", "jpeg", "jpg", "gif", "webp").contains(format)) return null;
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                validateDimensions(width, height);
                // Header dimensions alone must not allow truncated or malformed encoded images into the archive.
                if (reader.read(0) == null) throw error("invalid_image", "image cannot be decoded");
                return new ImageInfo("image/" + (format.equals("jpg") ? "jpeg" : format), width, height);
            } finally { reader.dispose(); }
        } catch (IOException invalid) { throw error("invalid_image", "image bytes are invalid or truncated"); }
    }

    private static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 32768 || height > 32768 || (long) width * height > MAX_PIXELS) {
            throw error("invalid_image_dimensions", "image dimensions exceed the supported limit");
        }
    }
    private Path directory(String id) { validateId(id); return base.resolve(id); }
    private void checkRealPath(Path path) throws IOException {
        if (!path.toRealPath().startsWith(base.toRealPath())) throw error("artifact_scope_mismatch", "asset path escapes its scope");
    }
    private void checkScope(String scope) { if (!scopeId.equals(scope)) throw error("scope_mismatch", "asset belongs to another scope"); }
    private static void validateId(String id) { if (!Ids.isSafeResourceId(id)) throw error("bad_request", "invalid artifactId"); }
    private static String safeName(String name) {
        String cleaned = name == null ? "asset" : name.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        return cleaned.isEmpty() ? "asset" : cleaned.substring(0, Math.min(200, cleaned.length()));
    }
    private static String safeMime(String mime) {
        if (mime == null) return "application/octet-stream";
        String normalized = mime.split(";", 2)[0].toLowerCase(Locale.ROOT).strip();
        return List.of("text/plain", "text/markdown", "text/csv", "application/json", "application/pdf",
            "image/svg+xml", "audio/mpeg", "audio/wav", "audio/ogg", "video/mp4", "video/webm",
            "application/zip", "application/octet-stream").contains(normalized) ? normalized : "application/octet-stream";
    }
    private static ArtifactException error(String code, String message) { return new ArtifactException(code, message); }
    private static ArtifactException unavailable(Exception failure) { return new ArtifactException("artifact_storage_unavailable", "asset storage is unavailable", failure); }
    private record ImageInfo(String mime, int width, int height) {}
}
