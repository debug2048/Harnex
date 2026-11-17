package com.harnex.web.api.artifact;

import com.harnex.artifact.ProjectArtifactExporter;
import com.harnex.config.ProjectRuntime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @ProjectRuntime
@RequestMapping("/api/v1/artifacts")
public final class ProjectArtifactExportsController {
    private final ProjectArtifactExporter exports;
    public ProjectArtifactExportsController(ProjectArtifactExporter exports) { this.exports = exports; }
    @PostMapping("/{id}/project-exports")
    public ProjectArtifactExporter.Result export(@PathVariable String id, @RequestBody ProjectArtifactExporter.Request request) { return exports.export(id, request); }
    public record ExportError(String code, String message, String existingChecksum) {}
    public record ExportFailure(ExportError error) {}
    @ExceptionHandler(ProjectArtifactExporter.Failure.class)
    public ResponseEntity<ExportFailure> failure(ProjectArtifactExporter.Failure failure) {
        int status = switch (failure.code()) {
            case "export_conflict", "export_idempotency_conflict" -> 409;
            case "export_path_denied" -> 403;
            case "artifact_not_found" -> 404;
            case "artifact_too_large" -> 413;
            case "export_storage_unavailable" -> 503;
            default -> 400;
        };
        return ResponseEntity.status(status).body(new ExportFailure(new ExportError(failure.code(), failure.getMessage(), failure.existingChecksum())));
    }
}
