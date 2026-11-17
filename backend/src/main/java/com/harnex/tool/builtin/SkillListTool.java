package com.harnex.tool.builtin;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.skill.Skill;
import com.harnex.skill.SkillLoader;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;

/** List available skills (name + description). Body is NOT loaded here. */
@Component
@ProjectRuntime
public class SkillListTool implements Tool {
    private final SkillLoader loader;

    public SkillListTool(SkillLoader loader) {
        this.loader = loader;
    }

    @Override
    public String getName() {
        return "skill_list";
    }

    @Override
    public String getDescription() {
        return "List every available skill with its one-line summary. Call skill_read to expand one.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_FILESYSTEM,
            ToolCapability.PLAN_MODE_SAFE, ToolCapability.CONCURRENCY_SAFE);
    }

    /** A6: cheap directory scan — allow up to 8 concurrent invocations. */
    @Override
    public int maxParallel() {
        return 8;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties", Map.of())).build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (Skill s : loader.list()) {
            sb.append("- ").append(s.getName()).append(": ").append(s.getDescription());
            if (s.getWhenToUse() != null) {
                sb.append("  (when: ").append(s.getWhenToUse()).append(")");
            }
            sb.append('\n');
        }
        if (sb.length() == 0) {
            sb.append("(no skills installed — drop SKILL.md files into ./skills/<slug>/)");
        }
        return ToolResult.builder().content(sb.toString()).build();
    }
}
