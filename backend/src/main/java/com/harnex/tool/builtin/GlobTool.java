package com.harnex.tool.builtin;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;

@Component
@ProjectRuntime
public class GlobTool implements Tool {
    private final WorkspacePathResolver vp;

    public GlobTool(WorkspacePathResolver vp) {
        this.vp = vp;
    }

    @Override
    public String getName() {
        return "glob";
    }

    @Override
    public String getDescription() {
        return "List files in the workspace matching a glob pattern (e.g. **/*.java). "
            + "Use a literal file or directory prefix to narrow the search. Includes hidden/gitignored files; "
            + "dependency/build directories are skipped unless explicitly named in the literal prefix. "
            + "Workspace search exclusions and private-data restrictions always apply.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_FILESYSTEM,
            ToolCapability.PLAN_MODE_SAFE, ToolCapability.CONCURRENCY_SAFE);
    }

    /** A6: cheap read-only walk — allow up to 8 concurrent invocations. */
    @Override
    public int maxParallel() {
        return 8;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties",
                Map.of("pattern", Map.of("type", "string", "description", "Glob pattern"), "offset",
                    Map.of("type", "integer", "description", "Skip the first N matches (default 0)."), "limit",
                    Map.of("type", "integer", "description",
                        "Max matches to return (default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ").")),
                "required", new String[] {"pattern"}))
            .build();
    }

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 5000;

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long timeoutMs = timeoutMillis(input);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        String pattern = String.valueOf(input.getOrDefault("pattern", "**/*"));
        int offset = Math.max(0, toInt(input.get("offset"), 0));
        int limit = Math.min(MAX_LIMIT, Math.max(1, toInt(input.get("limit"), DEFAULT_LIMIT)));
        Path root = vp.workspaceFor(ctx.getThread().getId());
        final String threadId = ctx.getThread().getId();
        List<String> hits = new ArrayList<>();
        int[] skipped = {0};
        boolean[] truncated = {false};
        boolean[] timedOut = {false};
        BooleanSupplier stopped = () -> {
            timedOut[0] = System.nanoTime() - deadline >= 0;
            return timedOut[0];
        };
        try {
            new WorkspaceFileSearch(vp, pattern).walk(threadId, stopped, file -> {
                if (skipped[0] < offset) { skipped[0]++; return true; }
                if (hits.size() >= limit) { truncated[0] = true; return false; }
                hits.add(root.relativize(file).toString().replace('\\', '/'));
                return true;
            });
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("bad glob pattern: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.failure(e);
        }
        StringBuilder out = new StringBuilder();
        for (String h : hits) {
            out.append(h).append('\n');
        }
        if (truncated[0]) {
            out.append("\u2026[truncated at ").append(limit).append(" matches \u2014 call again with offset=")
                .append(offset + limit).append(" to continue, or narrow the pattern]");
        }
        if (timedOut[0] || Thread.currentThread().isInterrupted()) {
            out.append(timedOut[0] || System.nanoTime() - deadline >= 0
                ? "…[search incomplete: deadline reached after " + timeoutMs + "ms]"
                : "…[search cancelled; results may be incomplete]");
        }
        return ToolResult.builder().content(out.toString()).build();
    }

    private static int toInt(Object v, int dflt) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException ignored) {
            return dflt;
        }
    }
}
