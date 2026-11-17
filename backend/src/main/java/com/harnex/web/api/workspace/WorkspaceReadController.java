package com.harnex.web.api.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.harnex.config.ProjectRuntime;
import com.harnex.run.RunCoordinator;
import com.harnex.workspace.WorkspaceReadService;

/** Read-only Files/Changes surface for the active workspace. */
@RestController
@ProjectRuntime
public class WorkspaceReadController {

    private final WorkspaceReadService workspace;
    private final RunCoordinator runs;

    public WorkspaceReadController(WorkspaceReadService workspace, RunCoordinator runs) {
        this.workspace = workspace;
        this.runs = runs;
    }

    @GetMapping("/api/v1/files")
    public ResponseEntity<?> files(@RequestParam(defaultValue = "") String path,
        @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit) {
        return read(() -> workspace.listFiles(path, cursor, limit));
    }

    @GetMapping("/api/v1/files/content")
    public ResponseEntity<?> content(@RequestParam String path) {
        return read(() -> workspace.preview(path));
    }

    @GetMapping("/api/v1/files/search")
    public ResponseEntity<?> search(@RequestParam String q,
        @RequestParam(defaultValue = "false") boolean content,
        @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return read(() -> workspace.search(q, content, cursor, limit));
    }

    @GetMapping("/api/v1/files/download")
    public ResponseEntity<?> download(@RequestParam String path) {
        return respond(() -> {
            var file = workspace.download(path);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(file.input()));
        });
    }

    @PostMapping("/api/v1/files/open")
    public ResponseEntity<?> open(@RequestBody Map<String, Object> body) {
        return read(() -> workspace.openExternal(String.valueOf(body.getOrDefault("path", ""))));
    }

    @PostMapping("/api/v1/files/reveal")
    public ResponseEntity<?> reveal(@RequestBody Map<String, Object> body) {
        return read(() -> workspace.openContainingDirectory(String.valueOf(body.getOrDefault("path", ""))));
    }

    @GetMapping("/api/v1/changes")
    public ResponseEntity<?> changes(@RequestParam String runId) {
        Map<String, Object> run = runs.view(runId);
        if (run == null) {
            return ResponseEntity.status(404).body(error("run_not_found", "no such run"));
        }
        return ResponseEntity.ok(workspace.changes(runId, String.valueOf(run.getOrDefault("threadId", ""))));
    }

    @GetMapping("/api/v1/changes/diff")
    public ResponseEntity<?> diff(@RequestParam String runId, @RequestParam String path) {
        Map<String, Object> run = runs.view(runId);
        if (run == null) {
            return ResponseEntity.status(404).body(error("run_not_found", "no such run"));
        }
        return read(() -> workspace.diff(runId, String.valueOf(run.getOrDefault("threadId", "")), path));
    }

    private static ResponseEntity<?> read(ReadOperation operation) {
        return respond(() -> ResponseEntity.ok(operation.get()));
    }

    private static ResponseEntity<?> respond(ResponseOperation operation) {
        try {
            return operation.get();
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(error("path_outside_workspace", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("bad_request", e.getMessage()));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(501).body(error("open_unavailable", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(404).body(error("file_not_found", e.getMessage()));
        }
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message == null ? "" : message));
    }

    @FunctionalInterface
    private interface ResponseOperation {
        ResponseEntity<?> get() throws IOException;
    }

    @FunctionalInterface
    private interface ReadOperation {
        Object get() throws IOException;
    }
}
