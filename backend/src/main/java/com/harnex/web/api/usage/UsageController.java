package com.harnex.web.api.usage;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.usage.UsageLedger;

@RestController
@RequestMapping("/api/v1/runs/{runId}/usage")
public final class UsageController {
    private final UsageLedger ledger;
    public UsageController(UsageLedger ledger) { this.ledger = ledger; }
    @GetMapping public UsageLedger.Report report(@PathVariable String runId) { return ledger.report(runId); }
    @GetMapping("/entries") public UsageLedger.Document entries(@PathVariable String runId) { return ledger.read(runId); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "bad_request", "message", failure.getMessage())));
    }
}
