package com.harnex.web.api.run;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.harnex.config.ProjectRuntime;
import com.harnex.contract.run.RunInputCreateRequest;
import com.harnex.contract.run.RunInputView;
import com.harnex.application.run.RunInputService;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/** HTTP adapter for the persistent S1 Run Input Mailbox. */
@RestController
@ProjectRuntime
@RequestMapping("/api/v1/runs/{runId}/inputs")
public class RunInputsController {

    private final RunInputService inputs;

    public RunInputsController(RunInputService inputs) {
        this.inputs = inputs;
    }

    public record RunInputPage(List<RunInputView> inputs) {}
    public record RunInputResponse(RunInputView input) {}

    @PostMapping
    @ApiResponse(responseCode = "202", content = @Content(schema = @Schema(implementation = RunInputResponse.class)))
    public ResponseEntity<?> enqueue(@PathVariable String runId,
        @RequestBody RunInputCreateRequest request) {
        return response(inputs.enqueue(runId, request));
    }

    @GetMapping
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RunInputPage.class)))
    public ResponseEntity<?> list(@PathVariable String runId,
        @RequestParam(required = false) String status) {
        RunInputService.ListResult result = inputs.list(runId, status);
        if (result.rejectCode() != null) {
            int http = "run_not_found".equals(result.rejectCode()) ? 404 : 400;
            return ResponseEntity.status(http).body(error(result.rejectCode(), result.rejectMessage()));
        }
        return ResponseEntity.ok(new RunInputPage(result.inputs().stream().map(RunInputView::from).toList()));
    }

    @DeleteMapping("/{inputId}")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RunInputResponse.class)))
    public ResponseEntity<?> withdraw(@PathVariable String runId, @PathVariable String inputId,
        @RequestParam(required = false) String clientRequestId) {
        return response(inputs.withdraw(runId, inputId, clientRequestId));
    }

    private static ResponseEntity<?> response(RunInputService.OperationResult result) {
        if (result.rejected()) {
            return ResponseEntity.status(result.httpStatus()).body(error(result.rejectCode(), result.rejectMessage()));
        }
        return ResponseEntity.status(result.httpStatus()).body(new RunInputResponse(RunInputView.from(result.body().get("input"))));
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message == null ? "" : message));
    }
}
