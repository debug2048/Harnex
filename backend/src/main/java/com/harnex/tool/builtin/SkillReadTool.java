package com.harnex.tool.builtin;

import java.nio.file.Path;
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

/** Expand a skill's full body (progressive-loading pattern). */
@Component
@ProjectRuntime
public class SkillReadTool implements Tool {
    private final SkillLoader loader;

    public SkillReadTool(SkillLoader loader) {
        this.loader = loader;
    }

    @Override
    public String getName() {
        return "skill_read";
    }

    @Override
    public String getDescription() {
        return "Read the full body of a named skill (Markdown how-to).";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_FILESYSTEM,
            ToolCapability.PLAN_MODE_SAFE, ToolCapability.CONCURRENCY_SAFE);
    }

    /** A6: cheap markdown read — allow up to 8 concurrent invocations. */
    @Override
    public int maxParallel() {
        return 8;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties",
                Map.of("name", Map.of("type", "string", "description", "Skill name from skill_list")), "required",
                new String[] {"name"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String name = String.valueOf(input.getOrDefault("name", ""));
        if (name.isBlank()) {
            return ToolResult.builder().error(true).content("empty name").build();
        }
        try {
            String body = loader.readBody(name);
            // Inject skill base path so agent resolves relative script references correctly
            String basePath = loader.get(name)
                .map(Skill::getPath)
                .map(Path::getParent)
                .map(Path::toString)
                .orElse(null);
            if (basePath != null) {
                body = "[skill-base-path: " + basePath + "]\n\n" + body;
            }
            return ToolResult.builder().content(body).build();
        } catch (Exception e) {
            return ToolResult.failure(e);
        }
    }
}
