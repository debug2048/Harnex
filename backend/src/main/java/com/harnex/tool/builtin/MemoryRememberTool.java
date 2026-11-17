package com.harnex.tool.builtin;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.memory.CoreMemory;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;

/** Append a durable fact to the active workspace dataDir's memory/MEMORY.md (no silent rewrites). */
@Component
@ProjectRuntime
public class MemoryRememberTool implements Tool {
    private final CoreMemory cm;

    public MemoryRememberTool(CoreMemory cm) {
        this.cm = cm;
    }

    @Override
    public String getName() {
        return "memory_remember";
    }

    @Override
    public String getDescription() {
        return "Save a concise durable fact to this workspace's MEMORY.md. Repeated identical facts are ignored. "
            + "To correct an obsolete fact, supply replaces with its exact complete text from memory_list; retain task/scope qualifiers "
            + "and replace only a fact in the same scope. Capacity pressure retains existing facts and asks for explicit consolidation.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.WRITES_FILESYSTEM);
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties",
                Map.of("fact", Map.of("type", "string", "description", "A short declarative fact with any task/scope qualifier (<200 chars)"),
                    "replaces", Map.of("type", "string", "description", "Optional exact complete obsolete fact, without its '- ' bullet")),
                "required", new String[] {"fact"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String fact = String.valueOf(input.getOrDefault("fact", ""));
        if (fact.isBlank()) {
            return ToolResult.builder().error(true).content("empty fact").build();
        }
        try {
            cm.rememberFact(fact, input.get("replaces") instanceof String previous ? previous : null);
        } catch (Exception e) {
            return ToolResult.builder().error(true).content(e.getMessage()).build();
        }
        return ToolResult.builder().content("remembered: " + fact).build();
    }
}
