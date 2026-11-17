package com.harnex.web.api.run;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import com.harnex.run.RunToolDetails;
import com.harnex.run.RunToolStore;

@RestController
public class RunToolsController {
    private final RunToolDetails details;
    public RunToolsController(RunToolDetails details) { this.details = details; }
    @GetMapping("/api/v1/runs/{runId}/tools/{seq}")
    public RunToolStore.ToolDetail get(@PathVariable String runId, @PathVariable long seq) { return details.get(runId, seq); }
    @GetMapping("/api/v1/runs/{runId}/subagents/{threadId}/result")
    public RunToolDetails.SubagentResult subagentResult(@PathVariable String runId, @PathVariable String threadId) {
        return details.subagentResult(runId, threadId);
    }
}
