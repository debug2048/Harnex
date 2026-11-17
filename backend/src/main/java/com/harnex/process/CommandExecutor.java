package com.harnex.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.tool.ToolResult;

import lombok.extern.slf4j.Slf4j;

/** Executes commands on this Runtime's host or in its configured container. Isolation is opt-in. */
@Slf4j
@Component
@ProjectRuntime
public class CommandExecutor {

    public enum Environment {
        HOST, DOCKER
    }

    /**
     * Environment variables the agent is NEVER allowed to override, regardless of backend. Letting the model rewrite
     * PATH/LD_PRELOAD/JAVA_TOOL_OPTIONS/etc would be a code-injection primitive (host or jvm-level). Keys containing
     * '=' are also rejected to prevent shell injection through the variable name itself.
     */
    public static final Set<String> RESERVED_ENV_KEYS = Set.of("PATH", "HOME", "LD_PRELOAD", "LD_LIBRARY_PATH",
        "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "BASH_ENV", "ENV",
        "PYTHONSTARTUP", "NODE_OPTIONS", "SHELL", "IFS", "PROMPT_COMMAND", "USER", "LOGNAME",
        // GIT_* hooks let the model substitute the credential helper / SSH command — both
        // are documented Git auth-handler injection vectors. ASKPASS variants similarly let
        // anyone overwrite the password prompt with an arbitrary script.
        "GIT_SSH", "GIT_SSH_COMMAND", "GIT_ASKPASS", "SSH_ASKPASS", "SUDO_ASKPASS",
        // TMPDIR / TMP / TEMP redirect tempfile creation; many libraries use them to derive
        // the path for downloaded artefacts and caches — pointing them at an attacker-writable
        // location enables write-then-link races.
        "TMPDIR", "TMP", "TEMP", "HARNEX_WORKSPACE",
        // PERL5OPT mirrors the role of NODE_OPTIONS for Perl interpreters; RUBYOPT same for
        // Ruby. Neither is in common manual-set use; safer to deny.
        "PERL5OPT", "RUBYOPT");

    /** True if the env key is safe to forward to the sandboxed shell (allow-list pattern + denylist check). */
    public static boolean isSafeEnvKey(String k) {
        if (k == null || k.isEmpty() || k.contains("=")) {
            return false;
        }
        if (RESERVED_ENV_KEYS.contains(k.toUpperCase(Locale.ROOT))) {
            return false;
        }
        // Conservative POSIX-style allow-list: starts with a letter or underscore, then alnum/underscore.
        return k.matches("[A-Za-z_][A-Za-z0-9_]{0,63}");
    }

    private final AgentConfig cfg;

    /**
     * Cached path to bash.exe on Windows (Git Bash / MSYS2). Null if not available or not Windows. Volatile to handle
     * runtime changes (e.g., user uninstalls Git Bash mid-session).
     */
    private volatile String windowsBashPath;

    /**
     * Timestamp of last bash availability check (milliseconds). Used to periodically re-validate that bash.exe still
     * exists, in case the user uninstalls Git Bash while the app is running.
     */
    private volatile long lastBashCheckMillis;

    /** Re-check bash availability every 5 minutes in case it gets uninstalled. */
    private static final long BASH_RECHECK_INTERVAL_MS = 5 * 60 * 1000L;

    public CommandExecutor(AgentConfig cfg) {
        this.cfg = cfg;
        cfg.validateConfiguration();
        String retired = System.getenv("HARNEX_SANDBOX_BACKEND");
        if (retired != null && !retired.isBlank()) {
            throw new IllegalArgumentException("HARNEX_SANDBOX_BACKEND has been removed. Use HARNEX_EXECUTION_ENVIRONMENT=host|docker; connect remote workspaces through SSH Runtime connections.");
        }
        log.info("CommandExecutor environment={}, sandbox={} (isolation is opt-in)", getEnvironment(),
            cfg.getExecution().getSandboxMode());
        // Prepare the host shell even when starting in Docker mode; a later task may select host execution.
        if (ShellPaths.isWindows()) {
            String detected = detectGitBash();
            if (detected != null) {
                this.windowsBashPath = detected;
                this.lastBashCheckMillis = System.currentTimeMillis();
                log.info("CommandExecutor: detected Git Bash at {} — will use for bash tool execution", detected);
            } else {
                log.info("CommandExecutor: Git Bash not found — falling back to PowerShell+cmd.exe for bash tool");
            }
        }
    }

    private static boolean dockerAvailable() {
        Process p = null;
        try {
            p = ChildProcessEnvironment.start(new ProcessBuilder("docker", "version", "--format", "{{.Server.Os}}").redirectErrorStream(true));
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            return done && p.exitValue() == 0 && "linux".equals(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return false;
        } finally {
            // Always ensure the probe child is gone — a 3 s timeout, an InterruptedException, or a
            // pre-`waitFor` throw all used to leak the child process (and its open fds).
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    /**
     * Detect Git Bash (bash.exe) on Windows. Searches in priority order: 1. Environment variable
     * HARNEX_SANDBOX_WINDOWS_BASH (explicit override) 2. Via git.exe --exec-path (most reliable for Git for Windows) 3.
     * Common installation paths 4. PATH environment variable (via 'where bash.exe')
     *
     * @return absolute path to bash.exe, or null if not found
     */
    private static String detectGitBash() {
        // Priority 1: explicit override
        String forced = System.getenv("HARNEX_SANDBOX_WINDOWS_BASH");
        if (forced != null && !forced.isBlank()) {
            Path bashPath = Path.of(forced);
            if (Files.isExecutable(bashPath)) {
                return bashPath.toAbsolutePath().toString();
            }
            log.warn("HARNEX_SANDBOX_WINDOWS_BASH set to '{}' but not executable", forced);
        }

        // Priority 2: via git.exe --exec-path (most reliable)
        Process p = null;
        try {
            p = ChildProcessEnvironment.start(new ProcessBuilder("git", "--exec-path").redirectErrorStream(true));
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                try (BufferedReader br =
                    new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String gitExecPath = br.readLine();
                    if (gitExecPath != null && !gitExecPath.isBlank()) {
                        // Git for Windows: bash.exe is typically in the same dir as git.exe or in ../bin/
                        Path gitExecDir = Path.of(gitExecPath.trim());
                        Path bashPath = gitExecDir.resolve("bash.exe");
                        if (Files.isExecutable(bashPath)) {
                            return bashPath.toAbsolutePath().toString();
                        }
                        // Try ../bin/bash.exe (usr/bin structure)
                        bashPath = gitExecDir.getParent().resolve("bin").resolve("bash.exe");
                        if (Files.isExecutable(bashPath)) {
                            return bashPath.toAbsolutePath().toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }

        // Priority 3: common installation paths
        for (String path : List.of("C:\\Program Files\\Git\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\bin\\bash.exe", "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\usr\\bin\\bash.exe")) {
            Path bashPath = Path.of(path);
            if (Files.isExecutable(bashPath)) {
                return bashPath.toAbsolutePath().toString();
            }
        }

        // Priority 4: search PATH
        p = null;
        try {
            p = ChildProcessEnvironment.start(new ProcessBuilder("where", "bash.exe").redirectErrorStream(true));
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                try (BufferedReader br =
                    new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String firstResult = br.readLine();
                    if (firstResult != null && !firstResult.isBlank()) {
                        Path bashPath = Path.of(firstResult.trim());
                        if (Files.isExecutable(bashPath)) {
                            return bashPath.toAbsolutePath().toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }

        return null; // not found
    }

    /**
     * Re-validate that the cached bash.exe still exists. Returns the current bash path if valid, null if it has been
     * uninstalled. Caches the result for BASH_RECHECK_INTERVAL_MS to avoid excessive file system checks.
     */
    private String getValidBashPath() {
        String cached = this.windowsBashPath;
        if (cached == null) {
            return null; // never had bash
        }

        long now = System.currentTimeMillis();
        if (now - this.lastBashCheckMillis < BASH_RECHECK_INTERVAL_MS) {
            return cached; // recently checked, assume still valid
        }

        // Time to re-check
        Path bashPath = Path.of(cached);
        if (Files.isExecutable(bashPath)) {
            this.lastBashCheckMillis = now;
            return cached; // still valid
        } else {
            // Bash was uninstalled
            log.warn("CommandExecutor: previously detected Git Bash at {} is no longer available — "
                + "falling back to PowerShell+cmd.exe", cached);
            this.windowsBashPath = null; // clear cache
            this.lastBashCheckMillis = now;
            return null;
        }
    }

    public Environment getEnvironment() {
        return Environment.valueOf(cfg.getExecution().getEnvironment().toUpperCase(Locale.ROOT));
    }

    /** File-tool paths remain host paths; Git Bash mount aliases are resolved by that installation's own utility. */
    public Path resolveHostPath(String path, Path base) {
        String bash = ShellPaths.isWindows() ? getValidBashPath() : null;
        return ShellPaths.resolve(path, base, bash == null ? null : value -> convertMsysPath(bash, value));
    }

    public Path resolveCommandDirectory(String path, Path workspace) {
        if (getEnvironment() == Environment.DOCKER && path != null && (path.equals("/work") || path.startsWith("/work/"))) {
            return workspace.resolve(path.length() == 5 ? "" : path.substring(6)).normalize();
        }
        return resolveHostPath(path, workspace);
    }

    private static Path convertMsysPath(String bash, String input) {
        Path directory = Path.of(bash).toAbsolutePath().getParent();
        Path converter = List.of(directory.resolve("cygpath.exe"), directory.resolve("../usr/bin/cygpath.exe"),
            directory.resolve("../../usr/bin/cygpath.exe")).stream().map(Path::normalize)
            .filter(Files::isRegularFile).findFirst().orElseThrow(() -> new IllegalArgumentException(
                "Git Bash cannot translate path " + input + ": cygpath.exe is unavailable. Use a project-relative or native Windows path."));
        Process process = null;
        try {
            ProcessBuilder command = new ProcessBuilder(converter.toString(), "-aw", "--", input).redirectErrorStream(true);
            ChildProcessEnvironment.sanitizeCommand(command);
            process = ChildProcessEnvironment.start(command);
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IllegalArgumentException("Git Bash path translation timed out. Use a project-relative or native Windows path.");
            String output = new String(process.getInputStream().readNBytes(16_384), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0 || output.isEmpty() || output.contains("\n") || output.contains("\r")) {
                throw new IllegalArgumentException("Git Bash could not translate path: " + input + ". Use a project-relative or native Windows path.");
            }
            Path translated = Path.of(output);
            if (!translated.isAbsolute()) throw new IllegalArgumentException("Git Bash returned a non-absolute path for " + input);
            return translated.normalize();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Git Bash path translation was interrupted", interrupted);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Git Bash path translation failed; use a project-relative or native Windows path", failure);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    public Map<String, Object> describe() {
        return Map.of("environment", getEnvironment().name().toLowerCase(Locale.ROOT),
            "sandbox", cfg.getExecution().getSandboxMode(),
            "network", getEnvironment() == Environment.HOST && "none".equals(cfg.getExecution().getSandboxMode())
                ? "host" : cfg.getExecution().getSandboxNetwork(),
            "nativeSandbox", NativeSandbox.capabilities(cfg), "isolationOptIn", true,
            "platform", System.getProperty("os.name"));
    }

    /** Describe the same shell selection used by exec so models can generate the correct syntax. */
    public String getShellDescription() {
        if (getEnvironment() == Environment.DOCKER) {
            return "Commands execute in /bin/sh inside Docker. The workspace is mounted at /work; "
                + "use POSIX shell syntax, /work paths or $HARNEX_WORKSPACE instead of Runtime-host absolute paths.";
        }
        if (!ShellPaths.isWindows()) {
            return "Commands execute in /bin/sh on this Runtime; use POSIX shell syntax and forward-slash paths. "
                + "Use cwd for a subdirectory and project-relative paths for outputs. The system temporary directory is also available for temporary files; sensitive paths remain protected.";
        }
        if (getValidBashPath() != null) {
            return "Commands execute in Git Bash on Windows. Use Bash syntax and quoted forward-slash paths "
                + "(e.g. 'backend/src/main/java/File.java'); backslashes are escape characters. "
                + "Prefer the grep tool or rg over findstr. Native Windows /flags may be converted to paths by MSYS; "
                + "when necessary, disable conversion for that command only with MSYS_NO_PATHCONV=1 "
                + "and supply Windows-compatible paths (findstr requires backslash paths in single quotes). "
                + "Use cwd for a subdirectory and project-relative output paths, including screenshots. /tmp maps to Windows TEMP, "
                + "which is available for temporary files; sensitive paths remain protected. Do not splice Windows paths with $(pwd). "
                + "PowerShell scripts must be invoked explicitly with powershell.exe -NoProfile -NonInteractive -Command '...'; "
                + "single-quote that script so Bash does not expand PowerShell variables.";
        }
        return "Commands execute in cmd.exe on Windows (wrapped by PowerShell for UTF-8 output). "
            + "Use CMD syntax, not Bash or PowerShell syntax; quote paths containing spaces.";
    }

    public ExecResult exec(String cmd, Path workspace, long timeoutMs) {
        return exec(cmd, workspace, timeoutMs, null, null, null);
    }

    /**
     * Extended exec: allows extra environment variables, stdin payload, and a working directory relative to the
     * workspace.
     *
     * @param env extra environment vars (merged into the inherited env). May be null.
     * @param stdin text to feed to the process's stdin. May be null.
     * @param cwdRel cwd relative to {@code workspace}. Must NOT escape it. May be null.
     */
    public ExecResult exec(String cmd, Path workspace, long timeoutMs, Map<String, String> env, String stdin,
        String cwdRel) {
        return exec(cmd, workspace, timeoutMs, env, stdin, cwdRel, null);
    }

    /** A tool may request tighter isolation, never weaken an operator's configured boundary. */
    public ExecResult exec(String cmd, Path workspace, long timeoutMs, Map<String, String> env, String stdin,
        String cwdRel, String requestedSandbox) {
        String mode;
        try {
            mode = NativeSandbox.effectiveMode(cfg.getExecution().getSandboxMode(), requestedSandbox);
        } catch (IllegalArgumentException invalid) {
            return new ExecResult(true, -1, invalid.getMessage());
        }
        Path effectiveCwd = workspace;
        if (cwdRel != null && !cwdRel.isBlank()) {
            // Literal startsWith was bypassable by an in-workspace symlink: e.g. workspace/out
            // -> /etc, then cwd="out" would normalise to {workspace}/out which trivially passes
            // the startsWith check, but ProcessBuilder.directory then chdirs into /etc and the
            // backend (HOST especially) executes the agent's command outside the workspace.
            // Use real-path containment so symlink ancestors cannot escape — matches the
            // "realpath containment" rule documented in 03_详细设计.md.
            Path resolved;
            try { resolved = resolveCommandDirectory(cwdRel, workspace); }
            catch (IllegalArgumentException invalid) { return new ExecResult(true, -1, "Invalid cwd: " + invalid.getMessage()); }
            Path workspaceReal;
            Path resolvedReal;
            try {
                workspaceReal = workspace.toRealPath();
                resolvedReal = resolved.toRealPath();
            } catch (NoSuchFileException nsfe) {
                // ProcessBuilder would also reject a non-existent cwd; surface a clearer error
                // here so the agent gets actionable feedback instead of a vendor-specific
                // IOException buried inside the runProcess catch block.
                return new ExecResult(true, -1, "cwd does not exist: " + cwdRel);
            } catch (IOException e) {
                return new ExecResult(true, -1, "cwd resolve failed: " + e.getMessage());
            }
            if (!resolvedReal.startsWith(workspaceReal)) {
                return new ExecResult(true, -1, "cwd escapes workspace (real path): " + cwdRel);
            }
            // Validation passes — the real-path of `resolved` lives inside the real-path of
            // workspace, so following the logical path at chdir time cannot escape the workspace
            // either. Keep the LOGICAL path so the DOCKER backend's `workspace.relativize(cwd)`
            // still yields the in-mount /work/<rel> the container expects (the realpath may
            // legitimately point at a host location outside the bind mount).
            effectiveCwd = resolved;
        }
        ExecResult result;
        try {
            result = switch (getEnvironment()) {
                case DOCKER -> runDocker(cmd, workspace, effectiveCwd, timeoutMs, env, stdin, mode);
                case HOST -> runHost(cmd, workspace, effectiveCwd, timeoutMs, env, stdin, mode);
            };
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            result = new ExecResult(true, -1, unavailable.getMessage());
        }
        return new ExecResult(result.error(), result.exitCode(), result.output(), Map.of(
            "environment", getEnvironment().name().toLowerCase(Locale.ROOT), "sandbox", mode,
            "network", "none".equals(mode) && getEnvironment() == Environment.HOST ? "host" : cfg.getExecution().getSandboxNetwork(),
            "platform", System.getProperty("os.name")));
    }
    // ---- HOST ----
    private ExecResult runHost(String cmd, Path workspace, Path cwd, long timeoutMs, Map<String, String> env,
        String stdin, String mode) {
        boolean windows = ShellPaths.isWindows();

        ProcessBuilder pb;
        Charset outputFallback = null;
        String effectiveStdin = stdin;  // effective stdin may include cmd for bash -s

        if (windows) {
            // On Windows, prefer Git Bash if available for better Unix command compatibility
            String bashPath = getValidBashPath();
            if (bashPath != null) {
                // Use bash -s (read script from stdin) to avoid Windows ProcessBuilder
                // quoting conflicts with Git Bash's argument parsing.
                pb = new ProcessBuilder(bashPath, "-s");
                // Git Bash can mix UTF-8 tools with native Windows programs using the local code page.
                outputFallback = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
                // Merge: command goes first via stdin, then any user-provided stdin follows.
                effectiveStdin = stdin != null && !stdin.isEmpty()
                    ? cmd + "\n" + stdin
                    : cmd;
            } else {
                // Fallback to PowerShell + cmd.exe
                pb = windowsCmdProcess(cmd);
            }
        } else {
            pb = new ProcessBuilder("/bin/sh", "-c", cmd);
        }

        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        // HARNEX_WORKSPACE is the workspace ROOT, not the (possibly nested) cwd — it mirrors the DOCKER/SSH branches,
        // which pass `workspace` separately from `cwd`. Setting it from `cwd` leaked a subdirectory into the child
        // whenever cwdRel was supplied, so workspace-relative tooling inside the command resolved against the wrong
        // base.
        pb.environment().put("HARNEX_WORKSPACE", workspace.toAbsolutePath().toString());
        if (windows) {
            pb.environment().putIfAbsent("PYTHONUTF8", "1");
            pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");
        }
        ChildProcessEnvironment.sanitizeCommand(pb);
        if (env != null) {
            for (var e : env.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && isSafeEnvKey(e.getKey())) {
                    pb.environment().put(e.getKey(), e.getValue());
                } else if (e.getKey() != null) {
                    log.warn("sandbox(HOST): rejected env key '{}' (reserved or invalid)", e.getKey());
                }
            }
        }
        if (!"none".equals(mode)) {
            try (NativeSandbox.Prepared prepared = NativeSandbox.wrap(pb, workspace, cfg, mode, env)) {
                return runProcess(prepared.builder(), timeoutMs, cmd, effectiveStdin, null, outputFallback);
            }
        }
        return runProcess(pb, timeoutMs, cmd, effectiveStdin, null, outputFallback);
    }

    // ---- DOCKER ----
    private ExecResult runDocker(String cmd, Path workspace, Path cwd, long timeoutMs, Map<String, String> env,
        String stdin, String mode) {
        AgentConfig.Execution execution = cfg.getExecution();
        String image = execution.getImage();
        String memory = execution.getMemory();
        String cpus = execution.getCpus();
        String pids = Integer.toString(execution.getPidsLimit());
        boolean netAllow = "allow".equals(execution.getSandboxNetwork());
        if (!dockerAvailable()) {
            throw new IllegalStateException("EXECUTION_UNAVAILABLE: a reachable Linux Docker daemon is required; host fallback is disabled.");
        }
        // Translate host cwd → container cwd: workspace mounts at /work, so the
        // container-side path is /work + (cwd relative to workspace).
        String containerCwd = "/work";
        if (!cwd.equals(workspace)) {
            Path rel = workspace.relativize(cwd);
            if (!rel.toString().isEmpty()) {
                containerCwd = "/work/" + rel.toString().replace('\\', '/');
            }
        }

        // Container name so we can `docker kill` the container itself on timeout/cancel.
        // Killing the local `docker run` client (the parent process) does NOT reliably stop
        // the actual container: it's a sibling managed by the docker daemon, not a child of
        // the CLI. Without an explicit name we'd have no handle to reap it; the LLM-issued
        // command would keep burning CPU / writing the bind-mount workspace after timeout.
        // UUID-suffixed so concurrent batches cannot collide.
        String containerName = "harnex-sb-" + UUID.randomUUID();

        List<String> args = new ArrayList<>(List.of("docker", "run", "--rm", "-i", "--name", containerName,
            "--read-only", "--tmpfs", "/tmp:rw,size=64m", "--memory", memory, "--cpus", cpus, "--pids-limit", pids,
            "--cap-drop=ALL", "--security-opt=no-new-privileges", "--mount", dockerMount(workspace, "read-only".equals(mode)), "-w",
            containerCwd, "-e", "HARNEX_WORKSPACE=/work"));
        Path realWorkspace;
        try { realWorkspace = workspace.toRealPath(); }
        catch (IOException failure) { throw new IllegalStateException("EXECUTION_UNAVAILABLE: workspace is unavailable", failure); }
        for (Path hidden : NativeSandbox.hidden(cfg)) {
            if (realWorkspace.startsWith(hidden)) throw new IllegalStateException("EXECUTION_DENIED: workspace is inside private Runtime storage");
            if (hidden.startsWith(realWorkspace) && Files.isDirectory(hidden)) {
                String target = "/work/" + realWorkspace.relativize(hidden).toString().replace('\\', '/');
                args.addAll(List.of("--tmpfs", target + ":ro,noexec,nosuid,size=64k"));
            }
        }
        if (env != null) {
            for (var e : env.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                if (!isSafeEnvKey(e.getKey())) {
                    log.warn("sandbox(DOCKER): rejected env key '{}' (reserved or invalid)", e.getKey());
                    continue;
                }
                args.add("-e");
                args.add(e.getKey() + "=" + e.getValue());
            }
        }
        if (!netAllow) {
            args.addAll(List.of("--network", "none"));
        }
        args.add(image);
        args.addAll(List.of("/bin/sh", "-c", cmd));

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        ChildProcessEnvironment.sanitizeCommand(pb);
        return runProcess(pb, timeoutMs, cmd, stdin, () -> dockerKillBestEffort(containerName));
    }

    /** Best-effort {@code docker kill}; bounded so a hung docker daemon can't keep us blocked. */
    private static String dockerMount(Path source, boolean readOnly) {
        // Docker --mount is CSV. Quoting the entire source field preserves commas, colons, spaces and drive letters.
        String field = "source=" + source.toAbsolutePath();
        return "type=bind,\"" + field.replace("\"", "\"\"") + "\",target=/work" + (readOnly ? ",readonly" : "");
    }

    private static void dockerKillBestEffort(String containerName) {
        Process p = null;
        try {
            p = ChildProcessEnvironment.start(
                new ProcessBuilder("docker", "kill", containerName).redirectErrorStream(true));
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("sandbox(DOCKER): `docker kill {}` itself timed out after 5s", containerName);
                p.destroyForcibly();
            } else if (p.exitValue() != 0) {
                // Non-zero is expected when the container already exited cleanly within the
                // race window between waitFor() expiring and docker-kill running. Log at
                // debug to avoid noisy WARNs for the common case.
                log.debug("sandbox(DOCKER): `docker kill {}` exit={}", containerName, p.exitValue());
            }
        } catch (Exception e) {
            log.warn("sandbox(DOCKER): `docker kill {}` failed: {}", containerName, e.getMessage());
        } finally {
            if (p != null && p.isAlive())
                p.destroyForcibly();
        }
    }

    private static ProcessBuilder windowsCmdProcess(String cmd) {
        String inner = "chcp 65001 >NUL & " + cmd;
        // PowerShell preserves Unicode output from cmd built-ins like dir, then emits UTF-8
        // bytes for runProcess' reader.
        String psScript = "[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false);"
            + "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);"
            + "$OutputEncoding=[Console]::OutputEncoding;" + "cmd.exe /d /c " + psSingleQuote(inner) + ";"
            + "exit $LASTEXITCODE";
        String encoded = Base64.getEncoder().encodeToString(psScript.getBytes(StandardCharsets.UTF_16LE));
        return new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
            "Bypass", "-EncodedCommand", encoded);
    }

    private static String psSingleQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static ExecResult runProcess(ProcessBuilder pb, long timeoutMs, String display, String stdin) {
        return runProcess(pb, timeoutMs, display, stdin, null);
    }

    /**
     * Run a process under a wall-clock deadline. On timeout / exception / waitFor failure the full process tree is
     * killed (parent shell + every descendant), and the optional {@code onForceKill} hook is invoked to handle
     * out-of-tree cleanup that JDK {@link ProcessHandle#descendants()} cannot see — currently {@code docker kill} for
     * the named container, because the container's PID 1 is a child of the docker daemon, not of the local docker CLI
     * we spawned.
     */
    private static ExecResult runProcess(ProcessBuilder pb, long timeoutMs, String display, String stdin,
        Runnable onForceKill) {
        return runProcess(pb, timeoutMs, display, stdin, onForceKill, null);
    }

    private static ExecResult runProcess(ProcessBuilder pb, long timeoutMs, String display, String stdin,
        Runnable onForceKill, Charset outputFallback) {
        Process p = null;
        final StringBuilder sb = new StringBuilder();
        final long startedAt = System.nanoTime();
        try {
            // Keychain material is injected only so Spring can resolve provider configuration.
            // Never propagate it into agent-controlled local commands or transport helpers.
            ChildProcessEnvironment.sanitize(pb);
            // Defence-in-depth: Process.waitFor(timeoutMs, ...) throws IllegalArgumentException
            // when timeoutMs <= 0. That happens AFTER pb.start() and is NOT caught by the
            // IO/Interrupted handler below, so without this clamp a malformed bash.timeoutMs
            // would leak the spawned child. BashTool also clamps at the tool boundary; this is
            // the inner safety net for any other call site.
            if (timeoutMs <= 0) {
                timeoutMs = 1L;
            }
            p = ChildProcessEnvironment.start(pb);
            final Process proc = p;
            // Drain stdout on a dedicated daemon thread so the main thread can enforce the deadline.
            // IMPORTANT: start the reader BEFORE writing stdin. If the child writes more than the
            // pipe buffer (~64 KiB on Linux) before consuming stdin, a stdin-first ordering blocks
            // the parent in os.write(...) before waitFor(timeout) is even reached, leaking the
            // child past the deadline and stalling the calling tool worker indefinitely.
            Thread reader = new Thread(() -> {
                try (BufferedReader br =
                    new BufferedReader(new InputStreamReader(proc.getInputStream(),
                        outputFallback == null ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1))) {
                    String line;
                    boolean truncated = false;
                    while ((line = br.readLine()) != null) {
                        // After hitting the 200k cap we keep draining to EOF and discard. The
                        // earlier `break` closed the pipe under the child, which gave commands
                        // like `mvn test` / `npm test` / `tail -f ... | grep -v` an EPIPE on
                        // their next stdout write — they would either crash with a non-zero
                        // exit (false-positive failure) or block on subsequent IO until our
                        // outer waitFor timeout fired (false-positive timeout). Continuing to
                        // drain costs nothing (we throw away the bytes), keeps the child's
                        // stdout writable, and lets the command finish naturally with the
                        // truncation-marker note appended exactly once.
                        if (truncated) {
                            continue;
                        }
                        if (outputFallback != null) {
                            // ISO-8859-1 above preserves bytes until a complete line is available. Decode each
                            // line independently so native stderr does not corrupt adjacent UTF-8 tool output.
                            line = decodeOutputLine(line.getBytes(StandardCharsets.ISO_8859_1), outputFallback);
                        }
                        synchronized (sb) {
                            sb.append(line).append('\n');
                            if (sb.length() > 200_000) {
                                sb.append("…[truncated at 200k chars; further output discarded]");
                                truncated = true;
                            }
                        }
                    }
                } catch (IOException ignored) {
                    /* process terminated */ }
            }, "sandbox-reader");
            reader.setDaemon(true);
            reader.start();

            // Feed stdin on a separate daemon so a child that ignores stdin (or whose stdin pipe
            // fills before it drains) cannot block this thread past the timeout. The writer
            // thread will unblock either when the child consumes the bytes or when destroyForcibly
            // below closes the pipe.
            if (stdin != null && !stdin.isEmpty()) {
                final byte[] stdinBytes = stdin.getBytes(StandardCharsets.UTF_8);
                Thread writer = new Thread(() -> {
                    try (var os = proc.getOutputStream()) {
                        os.write(stdinBytes);
                        os.flush();
                    } catch (IOException ignored) {
                        /* stdin already closed or process terminated */ }
                }, "sandbox-stdin");
                writer.setDaemon(true);
                writer.start();
            } else {
                try {
                    p.getOutputStream().close();
                } catch (IOException ignored) {
                }
            }

            boolean ok = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                killProcessTree(p);
                if (onForceKill != null) {
                    try {
                        onForceKill.run();
                    } catch (Exception kx) {
                        log.warn("sandbox: onForceKill hook failed: {}", kx.getMessage());
                    }
                }
                reader.interrupt();
                String dump;
                synchronized (sb) {
                    dump = sb.toString();
                }
                return new ExecResult(true, -1, "timeout after " + timeoutMs + "ms\n" + dump);
            }
            reader.join(500);
            if (reader.isAlive()) {
                // B11: reader thread blocked draining the closed stream; do not leak it.
                reader.interrupt();
                try {
                    reader.join(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            int ec = p.exitValue();
            String dump;
            synchronized (sb) {
                dump = sb.toString();
            }
            return new ExecResult(ec != 0, ec, "$ " + display + "\n" + dump + "\n[exit " + ec + "]");
        } catch (IOException | InterruptedException e) {
            if (p != null && p.isAlive()) {
                killProcessTree(p);
                if (onForceKill != null) {
                    try {
                        onForceKill.run();
                    } catch (Exception kx) {
                        log.warn("sandbox: onForceKill hook failed: {}", kx.getMessage());
                    }
                }
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String reason = e instanceof InterruptedException
                ? "command interrupted after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    + "ms (timeout budget: " + timeoutMs + "ms)"
                : e.getClass().getSimpleName() + ": " + e.getMessage();
            synchronized (sb) {
                return new ExecResult(true, -1, "$ " + display + "\n" + sb + "\n[" + reason + "]");
            }
        } catch (RuntimeException re) {
            // Catch-all for unchecked failures AFTER pb.start() (e.g. IllegalArgumentException
            // from waitFor with non-positive timeout, OOM, etc.). Without this the spawned
            // child would survive past the JVM-side error and run unattended.
            if (p != null && p.isAlive()) {
                killProcessTree(p);
                if (onForceKill != null) {
                    try {
                        onForceKill.run();
                    } catch (Exception kx) {
                        log.warn("sandbox: onForceKill hook failed: {}", kx.getMessage());
                    }
                }
            }
            return new ExecResult(true, -1, re.getClass().getSimpleName() + ": " + re.getMessage());
        }
    }

    public static String decodeOutputLine(byte[] bytes, Charset fallback) {
        try {
            // REPORT is the decoder default: valid UTF-8 always wins, including literal replacement characters.
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, fallback);
        }
    }

    /**
     * Destroy the entire descendant tree of {@code p}, then {@code p} itself.
     *
     * <p>
     * Why this is necessary: {@link #runHost} wraps every command in a shell ({@code bash.exe -c} / {@code cmd.exe /c}
     * on Windows, {@code /bin/sh -c} on POSIX). {@link Process#destroyForcibly()} on the shell only signals the shell —
     * long-running children it spawned (the actual compiler / interpreter / background daemon the LLM started) keep
     * running and continue to write to the workspace and burn CPU after the bash tool reports {@code timeout}.
     *
     * <p>
     * On Windows, Git Bash (MSYS2) processes may create new process sessions that escape
     * {@link ProcessHandle#descendants()}, so we use {@code taskkill /T /F} as the primary method, which reliably
     * terminates the entire process tree. On Unix/Linux, {@link ProcessHandle#descendants()} is sufficient.
     *
     * <p>
     * Descendant kill is best-effort and silent: a child that already exited or that we lack permission to signal is
     * fine.
     */
    private static void killProcessTree(Process p) {
        if (p == null) {
            return;
        }

        long pid = p.pid();
        boolean windows = ShellPaths.isWindows();

        if (windows) {
            // On Windows, use taskkill /T for reliable process tree termination, especially for
            // Git Bash processes that may create new sessions invisible to ProcessHandle.descendants()
            killProcessTreeWindows(pid);
        } else {
            // On Unix/Linux, ProcessHandle.descendants() is reliable
            killProcessTreeUnix(p);
        }
    }

    /**
     * Kill process tree on Windows using {@code taskkill /T /F}. Falls back to Java API if taskkill fails.
     */
    private static void killProcessTreeWindows(long pid) {
        Process killer = null;
        try {
            // /F = force termination
            // /T = terminate all child processes (process tree)
            // /PID = specific process ID
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid));
            pb.redirectErrorStream(true);
            killer = ChildProcessEnvironment.start(pb);

            boolean done = killer.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                log.warn("sandbox: taskkill timed out for PID {}, attempting fallback kill", pid);
                killer.destroyForcibly();
                fallbackKillJavaApi(pid);
            } else {
                int exitCode = killer.exitValue();
                // Exit code 128: "The process not found" (already gone, which is fine)
                // Exit code 0: success
                if (exitCode != 0 && exitCode != 128) {
                    log.debug("sandbox: taskkill for PID {} exited with code {}, attempting fallback", pid, exitCode);
                    fallbackKillJavaApi(pid);
                }
            }
        } catch (Exception e) {
            log.warn("sandbox: taskkill failed for PID {}: {}, attempting fallback", pid, e.getMessage());
            fallbackKillJavaApi(pid);
        } finally {
            if (killer != null && killer.isAlive()) {
                killer.destroyForcibly();
            }
        }
    }

    /**
     * Kill process tree on Unix/Linux using Java ProcessHandle API.
     */
    private static void killProcessTreeUnix(Process p) {
        // Snapshot descendants BEFORE killing the parent because once the parent is reaped, on
        // some OSes the parent-pid linkage is lost and descendants() returns an empty stream.
        List<ProcessHandle> descendants = new ArrayList<>();
        try {
            p.descendants().forEach(descendants::add);
        } catch (Exception ignored) {
            /* descendants() can throw on exotic OSes; tolerate */
        }

        try {
            p.destroyForcibly();
        } catch (Exception ignored) {
            /* already gone */
        }

        for (ProcessHandle d : descendants) {
            try {
                if (d.isAlive()) {
                    d.destroyForcibly();
                }
            } catch (Exception ignored) {
                /* race with natural exit; harmless */
            }
        }
    }

    /**
     * Fallback kill using Java ProcessHandle API when taskkill fails on Windows.
     */
    private static void fallbackKillJavaApi(long pid) {
        try {
            ProcessHandle.of(pid).ifPresent(ph -> {
                // Kill descendants first
                ph.descendants().forEach(d -> {
                    try {
                        d.destroyForcibly();
                    } catch (Exception ignored) {
                    }
                });
                // Then kill the parent
                try {
                    ph.destroyForcibly();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            log.debug("sandbox: fallback kill failed for PID {}: {}", pid, e.getMessage());
        }
    }

    private static String env(String key, String dflt) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? dflt : v;
    }

    public static ToolResult toToolResult(ExecResult r) {
        return ToolResult.builder().error(r.error).content(r.output).metadata(Map.of("execution", r.execution())).build();
    }

    public record ExecResult(boolean error, int exitCode, String output, Map<String, Object> execution) {
        public ExecResult(boolean error, int exitCode, String output) { this(error, exitCode, output, Map.of()); }
    }
}
