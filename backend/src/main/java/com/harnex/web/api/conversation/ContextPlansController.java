package com.harnex.web.api.conversation;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.conversation.ContextPlanStore;
import com.harnex.conversation.ConversationContextPlanner;

@RestController
@RequestMapping("/api/v1/runs/{runId}/context-plans")
public final class ContextPlansController {
    private final ContextPlanStore plans;
    public ContextPlansController(ContextPlanStore plans) { this.plans = plans; }
    @GetMapping public List<ConversationContextPlanner.Trace> list(@PathVariable String runId) { return plans.list(runId); }
    @GetMapping("/{planId}") public ResponseEntity<ConversationContextPlanner.Trace> get(@PathVariable String runId, @PathVariable String planId) {
        return plans.find(runId, planId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "bad_request", "message", failure.getMessage())));
    }
}
