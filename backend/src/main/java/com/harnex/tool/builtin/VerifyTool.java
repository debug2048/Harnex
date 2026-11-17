package com.harnex.tool.builtin;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.process.CommandExecutor;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;

/**
 * Hard-validation tool. Reads {@code harnex.verify.checks} (name -> shell command), runs them in the workspace sandbox,
 * and returns a structured PASS/FAIL summary suitable for an agent to consume before claiming a task is done.
 *
 * <p>
 * Inspired by Claude Code's "verifier loop": instead of letting the model self-report completion, the operator
 * pre-defines acceptance gates (compile / typecheck / lint / test). The model is expected to call {@code verify} before
 * answering "done".
 * </p>
 */
@Component
@ProjectRuntime
public class VerifyTool implements Tool {

    private final AgentConfig cfg;
    private final CommandExecutor runner;
    private final WorkspacePathResolver vp;

    public VerifyTool(AgentConfig cfg, CommandExecutor runner, WorkspacePathResolver vp) {
        this.cfg = cfg;
        this.runner = runner;
        this.vp = vp;
    }

    @Override
    public String getName() {
        return "verify";
    }

    @Override
    public String getDescription() {
        return "Run pre-configured acceptance checks (compile / typecheck / lint / test) defined under "
            + "harnex.verify.checks. Returns per-check pass/fail and exit codes. Call this BEFORE "
            + "reporting a task complete.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        // Verify spawns external processes (compile/test/lint) that read and may write the
        // workspace. Not plan-mode safe (executing tests defeats the purpose of "plan only")
        // and not concurrency-safe (two test runners stepping on each other corrupt output).
        return EnumSet.of(ToolCapability.READS_FILESYSTEM,
            ToolCapability.WRITES_FILESYSTEM, ToolCapability.SPAWNS_PROCESS);
    }

    @Override
    public int timeoutMillis() {
        return (int)Math.min(Integer.MAX_VALUE, Math.max(60_000L, cfg.getVerify().getTimeoutMs() * 4 + 30_000L));
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, String> available = cfg.getVerify().getChecks();
        String desc = getDescription()
            + (available.isEmpty() ? " (No checks configured — operator must populate harnex.verify.checks.)"
                : " Available checks: " + String.join(", ", available.keySet()));
        return ToolDefinition.builder().name(getName()).description(desc)
            .parameters(Map.of("type", "object", "properties",
                Map.of("checks",
                    Map.of("type", "array", "description",
                        "Names of checks to run; omit or pass [] to run every configured check.", "items",
                        Map.of("type", "string")))))
            .timeoutMs(timeoutMillis()).build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        Map<String, String> all = cfg.getVerify().getChecks();
        if (all == null || all.isEmpty()) {
            return ToolResult.builder().error(true)
                .content("No verify checks configured. Operator: set harnex.verify.checks.<name>: <command>").build();
        }
        @SuppressWarnings("unchecked")
        List<String> requested = input.get("checks") instanceof List<?> l ? (List<String>)(List<?>)l : List.of();

        LinkedHashMap<String, String> toRun = new LinkedHashMap<>();
        if (requested.isEmpty()) {
            toRun.putAll(all);
        } else {
            for (String name : requested) {
                String cmd = all.get(name);
                if (cmd == null) {
                    return ToolResult.builder().error(true)
                        .content("unknown check: " + name + ". Available: " + all.keySet()).build();
                }
                toRun.put(name, cmd);
            }
        }

        long perCheck = cfg.getVerify().getTimeoutMs();
        var workspace = vp.workspaceFor(ctx.getThread().getId());
        StringBuilder sb = new StringBuilder();
        int passed = 0, failed = 0;
        for (var e : toRun.entrySet()) {
            CommandExecutor.ExecResult r;
            try {
                r = runner.exec(e.getValue(), workspace, perCheck, null, null, null);
            } catch (Exception ex) {
                r = new CommandExecutor.ExecResult(true, -1, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            boolean ok = r.exitCode() == 0 && !r.error();
            if (ok) {
                passed++;
            } else {
                failed++;
            }
            sb.append(ok ? "[PASS] " : "[FAIL] ").append(e.getKey()).append("  exit=").append(r.exitCode())
                .append("  cmd=").append(e.getValue()).append('\n');
            if (!ok && r.output() != null && !r.output().isBlank()) {
                sb.append("       └─ ").append(snippet(r.output(), 800)).append('\n');
            }
        }
        sb.insert(0, String.format("verify: %d passed, %d failed%n", passed, failed));
        return ToolResult.builder().error(failed > 0).content(sb.toString()).build();
    }

    private static String snippet(String s, int max) {
        s = s.replace("\r", "");
        // Last `max` chars are usually the most informative (compiler tail).
        if (s.length() <= max) {
            return s.replace("\n", "\n       │  ");
        }
        return "…" + s.substring(s.length() - max).replace("\n", "\n       │  ");
    }
}
