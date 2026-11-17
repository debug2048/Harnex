package com.harnex.tool.builtin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.process.CommandExecutor;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.workspace.git.GitWorkspaceInspector;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;

@Component
@ProjectRuntime
public class BashTool implements Tool {

    private final WorkspacePathResolver vp;
    private final CommandExecutor runner;
    private final GitWorkspaceInspector git;

    @Autowired
    public BashTool(WorkspacePathResolver vp, CommandExecutor runner, GitWorkspaceInspector git) {
        this.vp = vp;
        this.runner = runner;
        this.git = git;
    }

    public BashTool(WorkspacePathResolver vp, CommandExecutor runner) {
        this(vp, runner, null);
    }

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return "Execute a shell command in this Runtime's workspace using its configured host or container toolchain. "
            + "Supports custom env vars, stdin payload, and a working directory relative to the workspace. "
            + "Ordinary commands use the existing environment. Request sandbox only for a task that needs isolation; "
            + "isolation failures never retry with weaker permissions. "
            + "Prefer the grep and glob tools for file searches. " + runner.getShellDescription();
    }

    @Override
    public Set<ToolCapability> capabilities() {
        // Shell scripts can do absolutely anything — declare the maximal capability set so
        // every safety gate (approval, plan-mode block, sequential dispatch) engages.
        return EnumSet.of(ToolCapability.READS_FILESYSTEM,
            ToolCapability.WRITES_FILESYSTEM, ToolCapability.READS_NETWORK,
            ToolCapability.WRITES_NETWORK, ToolCapability.SPAWNS_PROCESS,
            ToolCapability.DESTRUCTIVE_IRREVERSIBLE);
    }

    @Override
    public int timeoutMillis() {
        return 120_000;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription()).parameters(Map.of("type",
            "object", "properties",
            Map.of("command", Map.of("type", "string", "description", "Command line to execute"), "timeoutMs",
                Map.of("type", "integer", "description", "Timeout in ms", "default", 120000), "cwd",
                Map.of("type", "string", "description", "Working directory: project-relative or an absolute native/Git Bash drive path inside the workspace. Prefer this over embedding cd in command."),
                "stdin", Map.of("type", "string", "description", "Optional payload piped to the process's stdin"),
                "sandbox",
                Map.of("type", "string", "enum", List.of("read-only", "workspace-write"), "description",
                    "Optional stricter isolation for this command only. Omit for normal development. "
                        + "Requires a supported native sandbox or configured container; never disables operator policy."),
                "env",
                Map.of("type", "object", "description",
                    "Extra environment variables (string→string). PATH/HOME/LD_PRELOAD/JAVA_TOOL_OPTIONS and other runtime-altering keys are reserved and rejected.",
                    "additionalProperties", Map.of("type", "string"))),
            "required", new String[] {"command"})).build();
    }

    /** Hard cap on stdin payload size — protects against an LLM-driven OOM via a multi-megabyte stdin. */
    private static final int MAX_STDIN_BYTES = 1 * 1024 * 1024; // 1 MiB

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String cmd = String.valueOf(input.getOrDefault("command", "")).trim();
        if (cmd.isEmpty()) {
            return ToolResult.builder().error(true).content("empty command").build();
        }
        long timeout = toLong(input.get("timeoutMs"), 120_000L);
        // Clamp to the same window Tool.timeoutMillis(input) enforces so a model-supplied
        // timeoutMs <= 0 (or > 10 min) cannot reach CommandExecutor.runProcess where
        // Process.waitFor(<=0, ...) throws IllegalArgumentException AFTER pb.start() —
        // a path the catch block doesn't cover, leaking the spawned child.
        if (timeout < 1_000L || timeout > 600_000L) {
            timeout = Math.min(600_000L, Math.max(1_000L, timeout <= 0 ? 120_000L : timeout));
        }
        String cwd = input.get("cwd") == null ? null : String.valueOf(input.get("cwd"));
        String stdin = input.get("stdin") == null ? null : String.valueOf(input.get("stdin"));
        if (input.containsKey("sync_workdir")) return ToolResult.failure(
            "sync_workdir has been removed. Open the remote workspace through a Runtime connection.");
        String requestedSandbox = input.get("sandbox") == null ? null : String.valueOf(input.get("sandbox"));
        // Reject stdin payloads that would exceed the cap once UTF-8 encoded. Cheap fast-path:
        // if char-count × 4 (max bytes-per-char in UTF-8) is already under the cap there's no
        // point in eagerly encoding. Only when the conservative estimate exceeds the cap do we
        // pay for a precise byte-count — which keeps legit CJK / emoji payloads from being
        // rejected under the old `length() * 4` heuristic alone.
        if (stdin != null) {
            long approxBytes = (long)stdin.length() * 4;
            if (approxBytes > MAX_STDIN_BYTES) {
                int actualBytes = stdin.getBytes(StandardCharsets.UTF_8).length;
                if (actualBytes > MAX_STDIN_BYTES) {
                    return ToolResult.builder().error(true).content(
                        "bash rejected: stdin (" + actualBytes + " bytes) exceeds " + MAX_STDIN_BYTES + " byte cap.")
                        .build();
                }
            }
        }
        Map<String, String> env = null;
        if (input.get("env") instanceof Map<?, ?> m) {
            env = new HashMap<>();
            List<String> rejected = new ArrayList<>();
            for (var e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                String k = String.valueOf(e.getKey());
                // Defence-in-depth: reject reserved/dangerous env names at the tool boundary too.
                // CommandExecutor enforces the same denylist, but failing fast here yields a clearer
                // error message back to the model.
                if (!CommandExecutor.isSafeEnvKey(k)) {
                    rejected.add(k);
                    continue;
                }
                String val = String.valueOf(e.getValue());
                // Reject NUL / newline / carriage return / ESC / other C0 control chars in env values
                // — these (a) break ProcessBuilder on POSIX (NUL terminates the env var),
                // (b) let an attacker smuggle additional shell commands when the value is later
                // interpolated into a script,
                // (c) inject ANSI escape sequences into the TUI when the env var is later echoed,
                // which can clear the user's screen or hijack the cursor.
                // Allow everything ≥ 0x20 and tab (0x09); deny ESC (0x1B) and the rest of C0.
                if (val.length() > 8_192 || hasUnsafeEnvChars(val)) {
                    rejected.add(k + "=<unsafe value>");
                    continue;
                }
                env.put(k, val);
            }
            if (!rejected.isEmpty()) {
                return ToolResult.builder().error(true)
                    .content("rejected reserved/invalid env keys: " + rejected
                        + " (denylist: PATH, HOME, LD_PRELOAD, JAVA_TOOL_OPTIONS, BASH_ENV, NODE_OPTIONS, ...)")
                    .build();
            }
        }
        Path workspace = vp.workspaceFor(ctx.getThread().getId());
        GitWorkspaceInspector.GitSnapshot gitBefore =
            git != null && ctx.getSnapshotContext() != null && git.isGitRepo(workspace) ? git.inspectBefore(workspace)
                : null;
        if (gitBefore != null && gitBefore.isTimedOut()) {
            ctx.getSnapshotContext().warn("git_status_timeout");
        }
        List<Path> captured = captureObviousPaths(cmd, cwd, ctx);
        CommandExecutor.ExecResult r =
            runner.exec(cmd, workspace, timeout, env, stdin, cwd, requestedSandbox);
        if (ctx.getSnapshotContext() != null) {
            captureGitTrackedChanges(workspace, gitBefore, ctx);
            for (Path p : captured) {
                ctx.getSnapshotContext().recordAfterHash(p);
            }
        }
        return CommandExecutor.toToolResult(r);
    }

    private void captureGitTrackedChanges(Path workspace, GitWorkspaceInspector.GitSnapshot before, ToolContext ctx) {
        if (git == null || before == null || before.isTimedOut()) {
            return;
        }
        GitWorkspaceInspector.GitSnapshot after = git.inspectAfter(workspace);
        if (after.isTimedOut()) {
            ctx.getSnapshotContext().warn("git_diff_timeout");
            return;
        }
        if (!Objects.equals(before.getHead(), after.getHead())) {
            ctx.getSnapshotContext().warn("git_head_changed");
        }
        for (Path relPath : git.changedTrackedPaths(before, after)) {
            String rel = relPath.toString().replace('\\', '/');
            git.readHeadBlob(workspace, rel).ifPresent(bytes -> {
                ctx.getSnapshotContext().captureGitBefore(rel, bytes);
                ctx.getSnapshotContext().recordAfterHash(workspace.resolve(rel).normalize());
            });
        }
        for (Path relPath : git.newUntrackedPaths(before, after)) {
            String rel = relPath.toString().replace('\\', '/');
            ctx.getSnapshotContext().recordGitCreated(rel, workspace.resolve(rel).normalize());
        }
    }

    private List<Path> captureObviousPaths(String cmd, String cwd, ToolContext ctx) {
        if (ctx.getSnapshotContext() == null || cmd == null || cmd.isBlank()) {
            return List.of();
        }
        if (cmd.contains("$") || cmd.contains("$(") || cmd.contains("`") || cmd.matches("(?s).*\\b(ssh|scp|docker|kubectl)\\b.*")) {
            return List.of();
        }
        List<String> recursiveDeleteTargets = recursiveDeleteTargets(cmd);
        Set<String> recursiveDeleteSet = new HashSet<>(recursiveDeleteTargets);
        List<String> raw = new ArrayList<>();
        Matcher redir = Pattern.compile("(?:^|\\s)(?:>>|>)\\s*(['\"]?)([^'\"\\s|;&]+)\\1")
            .matcher(cmd);
        while (redir.find()) {
            raw.add(redir.group(2));
        }
        Matcher tee = Pattern.compile("(?:^|\\s)tee\\s+(?:-[a-zA-Z]+\\s+)*(['\"]?)([^'\"\\s|;&]+)\\1")
            .matcher(cmd);
        while (tee.find()) {
            raw.add(tee.group(2));
        }
        Matcher simple = Pattern
            .compile("^\\s*(?:touch|rm|mv|cp)\\s+(?:-[a-zA-Z]+\\s+)*(.+?)\\s*$").matcher(cmd);
        if (simple.find()) {
            for (String part : simple.group(1).split("\\s+")) {
                String stripped = stripQuotes(part);
                if (!stripped.isBlank() && !stripped.startsWith("-") && !stripped.contains("*")
                    && !recursiveDeleteSet.contains(stripped)) {
                    raw.add(stripped);
                }
            }
        }
        Matcher sed = Pattern.compile("^\\s*sed\\s+.*\\s-i\\S*\\s+.*\\s+(['\"]?)([^'\"\\s|;&]+)\\1\\s*$")
            .matcher(cmd);
        if (sed.find()) {
            raw.add(sed.group(2));
        }
        if (raw.isEmpty() && recursiveDeleteTargets.isEmpty()) {
            return List.of();
        }
        Path base = vp.workspaceFor(ctx.getThread().getId()).toAbsolutePath().normalize();
        if (cwd != null && !cwd.isBlank()) {
            try { base = runner.resolveCommandDirectory(cwd, base); }
            catch (IllegalArgumentException invalid) { return List.of(); } // exec returns the actionable cwd error
        }
        List<Path> out = new ArrayList<>();
        for (String r : recursiveDeleteTargets) {
            Path resolved = resolveStaticPath(base, r);
            if (resolved != null) {
                out.addAll(ctx.getSnapshotContext().captureDirectoryBefore(resolved));
            }
        }
        for (String r : raw) {
            Path resolved = resolveStaticPath(base, r);
            if (resolved != null) {
                if (ctx.getSnapshotContext().captureBefore(resolved)) {
                    out.add(resolved);
                }
            }
        }
        return out;
    }

    private static List<String> recursiveDeleteTargets(String cmd) {
        if (cmd == null || cmd.matches("(?s).*[|;&].*")) {
            return List.of();
        }
        List<String> tokens = tokenizeSimple(cmd);
        if (tokens.isEmpty()) {
            return List.of();
        }
        String verb = tokens.get(0);
        if (!"rm".equals(verb) && !"rmdir".equals(verb)) {
            return List.of();
        }
        boolean recursive = "rmdir".equals(verb);
        List<String> targets = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token == null || token.isBlank()) {
                continue;
            }
            if (token.startsWith("--")) {
                if ("--recursive".equals(token) || "--dir".equals(token)) {
                    recursive = true;
                }
                continue;
            }
            if (token.startsWith("-")) {
                if (token.indexOf('r') >= 0 || token.indexOf('R') >= 0) {
                    recursive = true;
                }
                continue;
            }
            if (!token.startsWith("~") && !token.contains("*")) {
                targets.add(token);
            }
        }
        return recursive ? targets : List.of();
    }

    private static List<String> tokenizeSimple(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private Path resolveStaticPath(Path base, String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("~")) {
            return null;
        }
        try {
            return runner.resolveHostPath(raw, base);
        } catch (Exception ignored) {
            // Static bash parsing is best-effort; failures just leave the snapshot partial/unverified.
            return null;
        }
    }

    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) {
            return s;
        }
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        return (first == last && (first == '\'' || first == '"')) ? s.substring(1, s.length() - 1) : s;
    }

    private static long toLong(Object v, long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    /**
     * True if {@code v} contains any of: NUL, ESC (0x1B), or any other C0 control char other than tab (0x09). Newline /
     * carriage return / ANSI escape would all break ProcessBuilder semantics or inject control codes into the TUI when
     * the value is later echoed; non-ASCII (UTF-8 ≥ 0x80) is allowed so legitimate i18n values pass through.
     */
    private static boolean hasUnsafeEnvChars(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\t')
                continue; // explicit allow
            if (c < 0x20 || c == 0x7F)
                return true;
        }
        return false;
    }
}
