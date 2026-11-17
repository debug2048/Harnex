package com.harnex.web.api.run;

import com.harnex.auth.ClientIdentity;
import org.springframework.web.bind.annotation.*;
import com.harnex.run.RunPresentationProjector;

@RestController
public final class RunPresentationController {
    private final RunPresentationProjector projector;
    public RunPresentationController(RunPresentationProjector projector) { this.projector = projector; }
    @GetMapping("/api/v1/runs/{runId}/presentation") public RunPresentationProjector.Presentation get(@PathVariable String runId) { return projector.capture(runId, ClientIdentity.actor()); }
}
