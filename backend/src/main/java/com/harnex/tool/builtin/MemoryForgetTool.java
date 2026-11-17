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

/** Remove a fact line containing the given substring from MEMORY.md. */
@Component
@ProjectRuntime
public class MemoryForgetTool implements Tool {
    private final CoreMemory cm;

    public MemoryForgetTool(CoreMemory cm) {
        this.cm = cm;
    }

    @Override
    public String getName() {
        return "memory_forget";
    }

    @Override
    public String getDescription() {
        return "Delete the first MEMORY.md bullet containing the given substring (case-insensitive).";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.WRITES_FILESYSTEM,
            ToolCapability.DESTRUCTIVE_IRREVERSIBLE);
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription()).parameters(Map.of("type",
            "object", "properties", Map.of("needle", Map.of("type", "string")), "required", new String[] {"needle"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String n = String.valueOf(input.getOrDefault("needle", ""));
        if (n.isBlank()) {
            return ToolResult.builder().error(true).content("empty needle").build();
        }
        try {
            boolean r = cm.forgetContaining(n);
            return ToolResult.builder().content(r ? "forgot: matched line removed" : "no match").build();
        } catch (Exception e) {
            return ToolResult.failure(e);
        }
    }
}
