package com.harnex.tool.builtin;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.provider.ProviderRouter;
import com.harnex.skill.SkillLoader;
import com.harnex.tool.*;
import com.harnex.toolset.ToolsetRegistry;

/**
 * Lets the agent introspect its own capabilities at runtime — something Claude Code and Hermes Agent both expose.
 * Useful when the agent has to decide whether to delegate, load a skill, or give up early.
 *
 * <p>
 * Returns a compact JSON-ish summary including: loaded tools, toolset presets, provider order, skills catalogue (names
 * + descriptions only), and current budget / iteration caps. Deliberately read-only and cheap — safe to call at any
 * time.
 */
@Component
@ProjectRuntime
public class SelfInspectTool implements Tool {

    private final AgentConfig cfg;
    private final ToolRegistry tools;
    private final ToolsetRegistry toolsets;
    private final ProviderRouter router;
    private final SkillLoader skills;

    @Autowired
    public SelfInspectTool(AgentConfig cfg, @Lazy ToolRegistry tools, @Lazy ToolsetRegistry toolsets,
        ProviderRouter router, SkillLoader skills) {
        this.cfg = cfg;
        this.tools = tools;
        this.toolsets = toolsets;
        this.router = router;
        this.skills = skills;
    }

    @Override
    public String getName() {
        return "self_inspect";
    }

    @Override
    public String getDescription() {
        return "Return a summary of the agent's own capabilities: available tools, toolset presets, "
            + "provider order, skills, and limits. Use to decide whether a task is feasible, whether "
            + "to delegate, or which skill to load.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        // Pure registry walk — no FS read needed (data is in-memory beans), safe in plan-mode
        // and parallel batches alongside other research tools.
        return EnumSet.of(ToolCapability.PLAN_MODE_SAFE,
            ToolCapability.CONCURRENCY_SAFE);
    }

    /** A6: pure read-only registry walk — allow up to 8 concurrent invocations. */
    @Override
    public int maxParallel() {
        return 8;
    }

    @Override
    public int timeoutMillis() {
        return 2_000;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties", Map.of(), "required", new String[] {})).build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", tools.all().stream().map(t -> Map.of("name", t.getName(), "description", t.getDescription(),
            "capabilities", t.capabilities().stream().map(Enum::name).sorted().toList())).collect(Collectors.toList()));
        out.put("toolsets", toolsets.allPresets());
        String defaultProvider = router.defaultProviderName().orElse(null);
        out.put("providers", defaultProvider == null || defaultProvider.isBlank()
            ? List.of() : List.of(defaultProvider));
        out.put("skills", skills.list().stream()
            .map(s -> Map.of("name", s.getName(), "description", s.getDescription())).collect(Collectors.toList()));
        out.put("limits",
            Map.of("maxIterations", cfg.getMaxIterations(), "ordinaryMaxIterations",
                cfg.getOrdinaryMaxIterations(), "finalizationGraceIterations", cfg.getFinalizationGraceIterations(),
                "subagentMaxIterations",
                cfg.getSubagent().getMaxIterations(), "toolParallelism", cfg.getToolParallelism(), "perToolChars",
                cfg.getBudget().getPerToolChars(), "perTurnChars", cfg.getBudget().getPerTurnChars()));

        StringBuilder sb = new StringBuilder();
        sb.append("=== Agent self-inspection ===\n");
        for (var e : out.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        return ToolResult.builder().content(sb.toString()).build();
    }
}
