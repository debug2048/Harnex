package com.harnex.web.api.agent;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.config.ProjectRuntime;
import com.harnex.moa.MixtureOfAgentsService;
import com.harnex.subagent.SubAgentExecutor;
import com.harnex.subagent.SubAgentExecutor.DelegateRequest;
import com.harnex.subagent.SubAgentExecutor.SubAgentResult;

@RestController
@ProjectRuntime
@RequestMapping("/api")
public class ExtController {

    private final MixtureOfAgentsService moa;
    private final SubAgentExecutor sub;

    public ExtController(MixtureOfAgentsService moa, SubAgentExecutor sub) {
        this.moa = moa;
        this.sub = sub;
    }

    @PostMapping("/moa")
    public ResponseEntity<?> moa(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(moa.invoke(body.getOrDefault("prompt", "")));
        } catch (MixtureOfAgentsService.MoaBusyException busy) {
            // Structured back-pressure: 503 + Retry-After hint instead of a generic 500. The
            // service refuses overlapping batches by design (see MixtureOfAgentsService.invoke
            // — single-permit batch gate) so a saturated client can implement exponential
            // backoff against this status code rather than treating it as a real outage.
            return ResponseEntity.status(503).header("Retry-After", "5")
                .body(Map.of("error", "moa_busy", "message", busy.getMessage()));
        } catch (IllegalStateException cfgErr) {
            // Configuration / disabled: 400 makes the misconfiguration explicit instead of 500.
            return ResponseEntity.badRequest().body(Map.of("error", "moa_unavailable", "message", cfgErr.getMessage()));
        }
    }

    @PostMapping("/delegate")
    public SubAgentResult delegate(@RequestBody DelegateRequest req) {
        // Strip any caller-supplied parentThreadId / parentBudget so an external client cannot
        // (a) inflate the perThreadInFlight map under attacker-chosen keys for a slow leak,
        // (b) attach to or starve a server-side budget that wasn't theirs to consume. Internal
        // callers (DelegateTool.execute) populate these fields from the live ToolContext;
        // the public API has no such context. JsonIgnore on parentBudget already blocks JSON
        // ingress for it, but defence in depth is cheap.
        req.setParentThreadId(null);
        req.setParentBudget(null);
        // Public callers also do not own a trusted recursion depth. Accepting a negative depth
        // lets a request start a child at e.g. -99 and then recurse through many delegate turns
        // before maxDepth ever trips. Direct API calls are always root delegations.
        req.setDepth(0);
        return sub.delegate(req);
    }

}
