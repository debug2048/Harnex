package com.harnex.web.api.agent;

import com.harnex.auth.ClientIdentity;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.config.ProjectRuntime;
import com.harnex.execution.ExecutionSpec;
import com.harnex.run.RunControls;
import com.harnex.contract.goal.GoalApiRequests;
import com.harnex.contract.run.RunView;
import com.harnex.run.RunCoordinator;

@RestController
@ProjectRuntime
@RequestMapping("/api/v1/runs")
public final class AgentRunControlsController {
    private final RunControls goals;
    private final RunCoordinator runs;
    public AgentRunControlsController(RunControls goals, RunCoordinator runs) {
        this.goals = goals; this.runs = runs;
    }

    @PostMapping("/{runId}/pause")
    public ResponseEntity<?> pause(@PathVariable String runId,
        @RequestBody GoalApiRequests.Control request) {
        return control(runId, () -> goals.pause(runId, request));
    }

    @PostMapping("/{runId}/resume")
    public ResponseEntity<?> resume(@PathVariable String runId,
        @RequestBody GoalApiRequests.Control request) {
        return control(runId, () -> goals.resume(runId, request));
    }

    @PostMapping("/{runId}/review")
    public ResponseEntity<?> review(@PathVariable String runId,
        @RequestBody GoalApiRequests.Review request) {
        return control(runId, () -> goals.review(runId, request));
    }

    private ResponseEntity<?> control(String runId,
        Supplier<RunControls.ControlResult> action) {
        var run = runs.get(runId);
        if (run != null && run.getExecutionSpec().kind() != ExecutionSpec.Kind.AGENT) {
            return ResponseEntity.status(409).body(err("unsupported_control", "This control is only available for Agent executions"));
        }
        try {
            RunControls.ControlResult result = action.get();
            if (result.accepted()) return ResponseEntity.ok(RunView.from(result.view(), ClientIdentity.actor()));
            int status = switch (result.rejectCode() == null ? "" : result.rejectCode()) {
                case "run_not_found" -> 404;
                case "runtime_draining", "executor_saturated" -> 503;
                case "revision_conflict", "idempotency_conflict", "invalid_run_state", "criteria_changed", "run_busy",
                    "thread_busy", "not_goal_run" -> 409;
                default -> 400;
            };
            return ResponseEntity.status(status).body(err(result.rejectCode(), result.rejectMessage()));
        } catch (RunControls.ControlFailure failure) {
            return ResponseEntity.badRequest().body(err(failure.code(), failure.getMessage()));
        }
    }

    private static Map<String, Object> err(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message == null ? "" : message));
    }
}
