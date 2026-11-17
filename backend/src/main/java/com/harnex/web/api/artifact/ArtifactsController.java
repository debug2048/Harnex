package com.harnex.web.api.artifact;

import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.harnex.artifact.ArtifactException;
import com.harnex.artifact.ArtifactRecord;
import com.harnex.artifact.ArtifactStore;
import com.harnex.execution.ScopeIdentity;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.util.Ids;

/** Scope-owned content API. The authenticated Runtime selects ownership and the storage destination. */
@RestController
@RequestMapping("/api/v1/artifacts")
public final class ArtifactsController {
    private final ArtifactStore assets;
    private final ScopeIdentity scope;
    public ArtifactsController(ArtifactStore assets, ScopeIdentity scope) { this.assets = assets; this.scope = scope; }
    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) public ResponseEntity<ArtifactRecord> upload(@RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String clientRequestId) throws IOException {
        if (clientRequestId != null && !Ids.isSafeResourceId(clientRequestId)) throw new ArtifactException("invalid_artifact_request", "Invalid clientRequestId");
        String artifactId = clientRequestId == null ? null : "artifact_" + IdempotencyReceiptStore.requestHash(clientRequestId).substring(0, 32);
        try (var bytes = file.getInputStream()) {
            ArtifactRecord asset = assets.save(scope.scopeId(), new ArtifactStore.SaveRequest(artifactId, file.getOriginalFilename(), file.getContentType(),
                new ArtifactRecord.Creator("UPLOAD", null, null), List.of(), false), bytes);
            return ResponseEntity.status(201).body(asset);
        }
    }
    @GetMapping("/{id}") public ResponseEntity<ArtifactRecord> metadata(@PathVariable String id) {
        return assets.find(scope.scopeId(), id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
    @PostMapping("/{id}/exports") public Export export(@PathVariable String id) {
        ArtifactRecord asset = assets.find(scope.scopeId(), id).orElseThrow(() -> new ArtifactException("artifact_not_found", "asset is unavailable"));
        return new Export(asset.artifactId(), asset.name(), "/api/v1/artifacts/" + asset.artifactId() + "/content?download=true");
    }
    @GetMapping("/{id}/content") public ResponseEntity<InputStreamResource> content(@PathVariable String id,
        @RequestParam(defaultValue = "false") boolean download) {
        ArtifactRecord asset = assets.find(scope.scopeId(), id).orElseThrow(() -> new ArtifactException("artifact_not_found", "asset is unavailable"));
        boolean raster = List.of("image/png", "image/jpeg", "image/webp", "image/gif").contains(asset.mime());
        ContentDisposition disposition = (raster && !download ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(asset.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.mime())).contentLength(asset.bytes())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; sandbox").cacheControl(CacheControl.noCache().cachePrivate())
            .eTag("\"" + asset.checksum() + "\"").body(new InputStreamResource(() -> {
                // A conditional GET can return 304 without ever opening the persisted binary.
                ArtifactStore.ContentHandle handle = assets.open(scope.scopeId(), id);
                return new FilterInputStream(handle.stream()) {
                    @Override public void close() throws IOException { handle.close(); }
                };
            }));
    }
    @ExceptionHandler(ArtifactException.class) public ResponseEntity<?> failure(ArtifactException failure) {
        int status = switch (failure.code()) { case "artifact_not_found" -> 404; case "scope_mismatch" -> 403; case "artifact_too_large" -> 413; default -> 400; };
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", failure.code(), "message", failure.getMessage())));
    }
    public record Export(String artifactId, String filename, String downloadPath) {}
}
