package com.harnex.web.api.provider;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.provider.ModelCallRecord;
import com.harnex.provider.ModelCallStore;

@RestController
@RequestMapping("/api/v1/runs/{runId}/model-calls")
public final class ModelCallsController {
    private final ModelCallStore calls;
    public ModelCallsController(ModelCallStore calls) { this.calls = calls; }
    @GetMapping public List<ModelCallRecord> list(@PathVariable String runId) { return calls.list(runId); }
    public record DiagnosticStatus(long droppedWrites) {}
    @GetMapping("/status") public DiagnosticStatus status() { return new DiagnosticStatus(calls.droppedWrites()); }
    @GetMapping("/{callId}") public ResponseEntity<ModelCallRecord> get(@PathVariable String runId, @PathVariable String callId) {
        return calls.find(runId, callId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "bad_request", "message", failure.getMessage())));
    }
}
