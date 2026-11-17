package com.harnex.tool.builtin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.harnex.config.ProjectRuntime;
import com.harnex.tool.*;
import com.harnex.run.RunCoordinator;
import com.harnex.run.RunHandle;

/**
 * Submits a completion or blocker claim for the structured Goal validator. This tool never writes a terminal status
 * directly:
 *
 * <ul>
 * <li>{@code complete} — every requirement is verified by authoritative evidence and no work remains.</li>
 * <li>{@code blocked} — record a concrete obstacle; the server requires three consecutive matching claims.</li>
 * </ul>
 *
 * pause / resume / budget changes are user/system controlled and deliberately NOT routed through this tool.
 */
@Component
@ProjectRuntime
public class UpdateGoalTool implements Tool {
    private final RunCoordinator runs;

    public UpdateGoalTool() {
        this.runs = null;
    }

    @Autowired
    public UpdateGoalTool(RunCoordinator runs) {
        this.runs = runs;
    }

    @Override
    public String getName() {
        return "update_goal";
    }

    @Override
    public String getDescription() {
        return "Update the status of the current autonomous goal. status=\"complete\" ONLY when every requirement is "
            + "verified by authoritative evidence (build/tests pass, files checked) and nothing remains — report final "
            + "token usage. status=\"blocked\" records a concrete obstacle that requires external input. The server "
            + "requires the same blocker in three consecutive Steps before stopping; reuse the same concise note while "
            + "the obstacle is unchanged and keep trying useful alternatives. "
            + "Do not mark complete just because a budget is running low.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.MUTATES_THREAD_STATE, ToolCapability.PLAN_MODE_SAFE,
            ToolCapability.APPROVAL_FREE);
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties",
                Map.of("status",
                    Map.of("type", "string", "enum", new String[] {"complete", "blocked"}, "description",
                        "complete = objective verified done; blocked = record current obstacle for server audit"),
                    "note",
                    Map.of("type", "string", "description",
                        "Brief justification: the evidence of completion, or the nature of the blocker."),
                    "evidence", Map.of("type", "array", "description", "Structured evidence references",
                        "items", Map.of("type", "object"))),
                "required", new String[] {"status"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String status = String.valueOf(input.getOrDefault("status", "")).trim().toLowerCase(Locale.ROOT);
        String note = input.get("note") == null ? null : String.valueOf(input.get("note")).trim();
        if (runs != null && ctx.getRunId() != null) {
            RunHandle run = runs.get(ctx.getRunId());
            if (run != null && run.getGoalId() != null && run.getGoalExecution() != null) {
                if (!"complete".equals(status) && !"blocked".equals(status)) {
                    return ToolResult.failure("status must be \"complete\" or \"blocked\"");
                }
                if ("blocked".equals(status) && (note == null || note.isBlank())) {
                    return ToolResult.failure("blocked requires a note identifying the concrete obstacle");
                }
                List<Map<String, Object>> evidence = new ArrayList<>();
                Object suppliedEvidence = input.get("evidence");
                if (suppliedEvidence instanceof List<?> rows) {
                    for (Object row : rows) {
                        if (row instanceof Map<?, ?> map) {
                            Map<String, Object> copy = new LinkedHashMap<>();
                            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                            evidence.add(copy);
                        }
                    }
                }
                if (!runs.submitGoalCompletion(ctx.getRunId(), status, note, evidence)) {
                    return ToolResult.failure("Goal completion request could not be persisted");
                }
                return ToolResult.ok("Goal " + status.toUpperCase(Locale.ROOT)
                    + " request persisted for server-side criteria validation after this Step.");
            }
        }
        return ToolResult.failure("no active structured Goal Run to update");
    }
}
