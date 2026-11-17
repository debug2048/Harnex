package com.harnex.web.api.automation;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.cron.AutomationExecutionService;

/** Identity-filtered, authenticated dispatch; only uses the receiving Runtime's existing authority. */
@RestController
@RequestMapping("/api/v1/automation-executions")
public final class AutomationExecutionController {
    private final AutomationExecutionService execution;
    public AutomationExecutionController(AutomationExecutionService execution) { this.execution = execution; }
    @PostMapping public AutomationExecutionService.Start create(@RequestBody AutomationExecutionService.Request request) { return execution.start(request); }
    @GetMapping("/{attemptId}") public ResponseEntity<AutomationExecutionService.Result> get(@PathVariable String attemptId) {
        var result = execution.inspect(attemptId);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }
    @PostMapping("/{attemptId}/cancel") public Map<String, Boolean> cancel(@PathVariable String attemptId) { return Map.of("cancelled", execution.cancel(attemptId)); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "automation_invalid", "message", failure.getMessage())));
    }
}
