package com.harnex.web.api.goal;

import com.harnex.auth.ClientIdentity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.harnex.config.ProjectRuntime;
import com.harnex.goal.GoalResourceService;
import com.harnex.goal.GoalResourceService.GoalRequestException;
import com.harnex.goal.GoalRunOrchestrator;
import com.harnex.contract.goal.GoalApiRequests;
import com.harnex.contract.goal.GoalView;
import com.harnex.contract.run.RunView;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/** Structured Goal resources; slash commands are adapters to this same service. */
@RestController
@ProjectRuntime
@RequestMapping("/api/v1/goals")
public class GoalsController {
    private final GoalResourceService goals;
    private final GoalRunOrchestrator orchestrator;

    public GoalsController(GoalResourceService goals, GoalRunOrchestrator orchestrator) {
        this.goals = goals;
        this.orchestrator = orchestrator;
    }

    @PostMapping
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = GoalView.class)))
    public ResponseEntity<?> create(@RequestBody GoalApiRequests.Create request) {
        try {
            GoalResourceService.Operation created = goals.create(request);
            boolean start = request.start() == null || request.start();
            if (!start || created.replayed() && !created.record().runIds().isEmpty()) {
                return ResponseEntity.status(created.replayed() ? 200 : 201).body(GoalView.from(goals.view(created.record().goalId())));
            }
            GoalRunOrchestrator.StartResult run = orchestrator.start(created.record().goalId(),
                new GoalApiRequests.StartRun(created.record().revision(), request.run(), request.clientRequestId(), request.notificationMode()), ClientIdentity.actor().origin());
            if (!run.started()) {
                return error(run.rejectCode(), run.rejectMessage());
            }
            return ResponseEntity.status(created.replayed() ? 200 : 201).body(GoalView.from(goals.view(created.record().goalId())));
        } catch (GoalRequestException failure) {
            return error(failure.code(), failure.getMessage());
        }
    }

    @GetMapping
    public GoalPage list(@RequestParam(required = false) String conversationId,
        @RequestParam(required = false) String status) {
        return new GoalPage(goals.list(conversationId, status).stream().map(GoalView::from).toList());
    }

    public record GoalPage(List<GoalView> goals) {}
    public record GoalHistory(String goalId, List<RunView> runs) {}

    @GetMapping("/{goalId}")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = GoalView.class)))
    public ResponseEntity<?> get(@PathVariable String goalId) {
        try {
            return ResponseEntity.ok(GoalView.from(goals.view(goalId)));
        } catch (GoalRequestException failure) {
            return error(failure.code(), failure.getMessage());
        }
    }

    @PatchMapping("/{goalId}")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = GoalView.class)))
    public ResponseEntity<?> patch(@PathVariable String goalId,
        @RequestBody GoalApiRequests.Patch request) {
        try {
            GoalResourceService.Operation changed = goals.patch(goalId, request);
            return ResponseEntity.ok(GoalView.from(changed.view()));
        } catch (GoalRequestException failure) {
            return error(failure.code(), failure.getMessage());
        }
    }

    @PostMapping("/{goalId}/runs")
    @ApiResponse(responseCode = "202", content = @Content(schema = @Schema(implementation = RunView.class)))
    public ResponseEntity<?> startRun(@PathVariable String goalId,
        @RequestBody GoalApiRequests.StartRun request) {
        GoalRunOrchestrator.StartResult result = orchestrator.start(goalId, request, ClientIdentity.actor().origin());
        if (!result.started()) {
            return ResponseEntity.status(status(result.rejectCode()))
                .body(errorBody(result.rejectCode(), result.rejectMessage()));
        }
        return ResponseEntity.accepted().body(RunView.from(result.run().view(), ClientIdentity.actor()));
    }

    @GetMapping("/{goalId}/history")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = GoalHistory.class)))
    public ResponseEntity<?> history(@PathVariable String goalId) {
        try {
            return ResponseEntity.ok(new GoalHistory(goalId, goals.history(goalId).stream().map(facts -> RunView.from(facts, ClientIdentity.actor())).toList()));
        } catch (GoalRequestException failure) {
            return error(failure.code(), failure.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> error(String code, String message) {
        return ResponseEntity.status(status(code)).body(errorBody(code, message));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message == null ? "" : message));
    }

    private static int status(String code) {
        return switch (code == null ? "" : code) {
            case "goal_not_found", "run_not_found", "thread_not_found" -> 404;
            case "idempotency_resource_gone" -> 410;
            case "revision_conflict", "idempotency_conflict", "thread_busy", "run_busy", "run_terminal",
                "invalid_run_state", "criteria_changed" -> 409;
            case "runtime_draining", "busy" -> 503;
            default -> 400;
        };
    }
}
