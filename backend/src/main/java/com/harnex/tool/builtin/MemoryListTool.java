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

/** Read MEMORY.md + USER.md verbatim (already injected, but useful for audit). */
@Component
@ProjectRuntime
public class MemoryListTool implements Tool {
    private final CoreMemory cm;

    public MemoryListTool(CoreMemory cm) {
        this.cm = cm;
    }

    @Override
    public String getName() {
        return "memory_list";
    }

    @Override
    public String getDescription() {
        return "Return the current MEMORY.md and USER.md contents.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_FILESYSTEM,
            ToolCapability.PLAN_MODE_SAFE, ToolCapability.CONCURRENCY_SAFE);
    }

    /** A6: cheap in-memory list — allow up to 8 concurrent invocations. */
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
        String out = "-- USER.md --\n" + cm.readUser() + "\n-- MEMORY.md --\n" + cm.readMemory();
        return ToolResult.builder().content(out).build();
    }
}
