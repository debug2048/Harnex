package com.harnex.web.api.run;

import com.harnex.auth.ClientIdentity;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.harnex.contract.run.RunCreateRequest;
import com.harnex.contract.run.RunView;
import com.harnex.run.RunCoordinator;
import com.harnex.run.RunEventBus.ReplayUnavailableException;
import com.harnex.run.RunEventBus.ReplayResetException;
import com.harnex.run.RunHandle;
import com.harnex.application.run.RunRequestService;
import com.harnex.application.run.RunRequestService.CreateResult;
import com.harnex.run.RunControls;
import com.harnex.contract.goal.GoalApiRequests;
import com.harnex.util.Ids;
import com.harnex.web.RunSseAdapter;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Runs — the single execution entry point of the Local API (docs/03 §3.14).
 *
 * <ul>
 * <li>{@code POST /api/v1/runs} — create; {@code stream=true} answers with the run's SSE inline</li>
 * <li>{@code GET /api/v1/runs?threadId=&status=} — discovery (e.g. find the RUNNING run of a thread to reattach)</li>
 * <li>{@code GET /api/v1/runs/{runId}} — status/statistics</li>
 * <li>{@code GET /api/v1/runs/{runId}/events?afterSeq=} — subscribe; disconnecting only ends the subscription</li>
 * <li>{@code POST /api/v1/runs/{runId}/cancel} — the only way to stop a run besides completion</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunsController {

    private final RunRequestService requests;
    private final RunCoordinator runs;
    private final RunSseAdapter streams;
    private final List<RunControls> controls;

    public RunsController(RunRequestService requests, RunCoordinator runs, RunSseAdapter streams,
        List<RunControls> controls) {
        this.requests = requests;
        this.runs = runs;
        this.streams = streams;
        this.controls = List.copyOf(controls);
    }

    @PostMapping(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    @ApiResponse(responseCode = "202", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RunView.class)))
    public Object create(@RequestBody RunCreateRequest req) {
        CreateResult result = requests.create(req, ClientIdentity.actor());
        if (result.rejectCode() != null) {
            int status = switch (result.rejectCode()) {
                case "thread_busy", "duplicate_request", "run_recovery_conflict", "idempotency_conflict", "automation_running", "automation_runtime_busy" -> 409;
                case "thread_deleted", "thread_deleted_persisted", "idempotency_resource_gone" -> 410;
                case "runtime_draining", "busy" -> 503;
                default -> 400;
            };
            return ResponseEntity.status(status).body(err(result.rejectCode(), result.rejectMessage()));
        }
        if (result.streamRequested()) {
            return streams.open(result.run(), 0L);
        }
        return ResponseEntity.accepted().body(RunView.from(runs.view(result.run().getRunId()), ClientIdentity.actor()));
    }

    @GetMapping
    public RunPage list(@RequestParam(required = false) String conversationId,
        @RequestParam(required = false) String status) {
        return new RunPage(runs.historyViews(conversationId, status).stream().map(facts -> RunView.from(facts, ClientIdentity.actor())).toList());
    }
    public record RunPage(List<RunView> runs) {}

    @GetMapping("/{runId}")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RunView.class)))
    public ResponseEntity<?> get(@PathVariable String runId) {
        Map<String, Object> view = runs.view(runId);
        if (view == null) {
            return ResponseEntity.status(404).body(err("run_not_found", "no such run (unknown or expired)"));
        }
        return ResponseEntity.ok(RunView.from(view, ClientIdentity.actor()));
    }

    @GetMapping(value = "/{runId}/events",
        produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Object events(@PathVariable String runId, @RequestParam(defaultValue = "0") long afterSeq,
        @RequestParam(required = false) Long streamEpoch) {
        if (!Ids.isSafeCallId(runId)) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(err("bad_request", "invalid runId"));
        }
        if (afterSeq < 0) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(err("bad_request", "afterSeq must be non-negative"));
        }
        RunHandle run = runs.get(runId);
        if (run == null) {
            if (runs.view(runId) != null) {
                return ResponseEntity.status(410).contentType(MediaType.APPLICATION_JSON)
                    .body(err("event_replay_gone", "run facts are retained but the live event replay has expired"));
            }
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                .body(err("run_not_found", "no such run"));
        }
        try {
            return streams.open(run, afterSeq, streamEpoch);
        } catch (ReplayResetException reset) {
            return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(Map.of(
                "error", Map.of(
                    "code", "replay_reset",
                    "message", "runtime stream epoch changed; reload run and thread projections"),
                "streamEpoch", reset.streamEpoch(),
                "firstAvailableSeq", reset.firstAvailableSeq(),
                "runRevision", reset.runRevision()));
        } catch (ReplayUnavailableException expired) {
            // The janitor can expire the replay channel after get(runId) but before subscription. Convert that race
            // into the same stable 410 contract as the ordinary post-TTL path.
            return ResponseEntity.status(410).contentType(MediaType.APPLICATION_JSON)
                .body(err("event_replay_gone", "run facts are retained but the live event replay has expired"));
        }
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String runId,
        @RequestBody(required = false) GoalApiRequests.Control request) {
        if (!Ids.isSafeCallId(runId)) {
            return ResponseEntity.badRequest().body(err("bad_request", "invalid runId"));
        }
        RunHandle run = runs.get(runId);
        if (run == null) {
            return ResponseEntity.status(404).body(err("run_not_found", "no such run (unknown or expired)"));
        }
        if (run.getGoalId() != null && request != null) {
            if (controls.isEmpty()) return ResponseEntity.status(409).body(err("workspace_required", "This execution requires its project Runtime"));
            return control(() -> controls.get(0).cancel(runId, request));
        }
        boolean ok = runs.cancel(runId);
        return ResponseEntity.ok(Map.of("cancelled", ok, "status", run.getStatus().name()));
    }

    private ResponseEntity<?> control(
        Supplier<RunControls.ControlResult> action) {
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
