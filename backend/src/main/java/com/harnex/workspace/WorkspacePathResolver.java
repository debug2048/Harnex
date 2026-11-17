package com.harnex.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.config.UserDataPaths;
import com.harnex.process.CommandExecutor;
import com.harnex.process.ShellPaths;
import com.harnex.util.Ids;

import lombok.extern.slf4j.Slf4j;

/**
 * Workspace / path resolver.
 *
 * <p>
 * The agent always works on a single real project directory — the <b>project root</b> — resolved once at construction
 * in this order: {@code harnex.workspace.root} → {@code HARNEX_WORKSPACE} env → the JVM working directory
 * ({@code user.dir}). This mirrors mainstream coding agents (Claude Code / Cursor / Aider): bare relative paths anchor
 * at the project root, host-absolute real paths are accepted (and zone-classified), so the model edits the SAME files
 * described by {@code AGENTS.md} and seen by {@code bash}. There are no virtual path schemes — every path the model
 * uses is either a project-relative path or a real absolute path.
 *
 * <p>
 * Chat uploads are the one resource that is NOT a project file: they live under {@code <dataDir>/uploads/<threadId>/}
 * so they never pollute the user's repository. The model reaches them by their real absolute path (handed to it on the
 * attachment line); {@link #classify} grants that path GREEN for its OWNING thread and RED for every other thread, so
 * cross-thread access is blocked by the zone rules — no special URI scheme is needed. Skills are not addressed by path
 * at all: the model loads them by name through the {@code skill_read} / {@code skill_list} tools.
 *
 * <p>
 * <b>Access zones:</b>
 * <ul>
 * <li><b>GREEN</b> — inside the project root, the CURRENT thread's {@code uploads/<tid>}, or a system temporary
 * directory after private/sensitive-path checks. No additional outside-write gate.</li>
 * <li><b>YELLOW</b> — outside the project root, not sensitive. Reads are allowed and audited; writes require
 * {@code harnex.workspace.outside-write-policy}: {@code deny}, {@code ask} (default), or {@code allow}.</li>
 * <li><b>RED</b> — sensitive paths (SSH/cloud/credential stores, key material, this app's own config), the app's own
 * private {@code dataDir} state (threads/memory/snapshots/tool_results/fts/audit), AND any OTHER thread's
 * {@code uploads} slice. Reads and writes both refused regardless of the outside-write policy. This holds even when
 * {@code dataDir} sits inside the project root ({@code <projectRoot>/data}, Supervisor/desktop) or is explicitly pinned
 * to the workspace root. User Auth/config lives separately in {@code userDataDir}.</li>
 * </ul>
 *
 * <p>
 * <b>Scope of zone enforcement.</b> Zones are a guardrail for the FILE tools ({@code file_read}/{@code file_write}/
 * {@code file_edit}/{@code glob}/{@code grep}) that route through {@link #toReal}/{@link #toRealForWrite}. They are NOT
 * a global OS sandbox: the {@code bash} tool runs a real command with the workspace as its cwd, so on the LOCAL backend
 * it can read/write anything the host user can, and on the DOCKER backend the whole workspace is bind-mounted. RED/
 * YELLOW therefore bound the model's <em>file-tool</em> reach, not arbitrary shell execution — bash is gated separately
 * by the approval system and the chosen sandbox backend. Treat {@code bash} on the local backend as an explicitly
 * high-trust capability.
 */
@Slf4j
@Component
@ProjectRuntime
public class WorkspacePathResolver {

    /** Access classification for a resolved real path. See class javadoc. */
    public enum Zone {
        GREEN, YELLOW, RED
    }

    private final Path dataDir;
    private final Path sharedDataDir;
    private final Path automationData;
    /** The real project root the agent operates on. Never {@code null} — falls back to {@code user.dir}. */
    private final Path projectRoot;
    private final AgentConfig config;
    private final CommandExecutor commands;
    private final List<Pattern> sensitivePatterns;
    private final List<Path> temporaryDirectories;

    public WorkspacePathResolver(AgentConfig cfg) { this(cfg, null); }

    @Autowired
    public WorkspacePathResolver(AgentConfig cfg, CommandExecutor commands) {
        this(cfg, commands, systemTemporaryDirectories());
    }

    /** Explicit roots keep policy tests independent of the machine's temporary directory. */
    public WorkspacePathResolver(AgentConfig cfg, CommandExecutor commands, List<Path> temporaryDirectories) {
        this.commands = commands;
        this.temporaryDirectories = temporaryDirectories.stream().map(WorkspacePathResolver::safeRealPath).distinct().toList();
        this.dataDir = Paths.get(cfg.getDataDir()).toAbsolutePath();
        this.sharedDataDir = Paths.get(cfg.getSharedDataDir()).toAbsolutePath();
        this.automationData = UserDataPaths.resolve(cfg).resolve("automation").toAbsolutePath();

        AgentConfig.Workspace ws = cfg.getWorkspace();
        String cfgRoot = ws == null ? null : ws.getRoot();
        String root = (cfgRoot != null && !cfgRoot.isBlank()) ? cfgRoot : System.getenv("HARNEX_WORKSPACE");
        if (root == null || root.isBlank()) {
            // No explicit project root: anchor on the JVM working directory. For the interactive CLI / TUI this is the
            // directory the user opened ("open a folder = work on it"); for the server it is the launch dir. There is
            // intentionally no per-thread scratch fallback — the agent always operates on a real directory.
            root = System.getProperty("user.dir", ".");
        }
        this.projectRoot = Paths.get(root).toAbsolutePath().normalize();
        this.config = cfg;
        List<Pattern> compiled = new ArrayList<>();
        List<String> globs = ws == null ? null : ws.getSensitiveGlobs();
        if (globs != null) {
            for (String g : globs) {
                if (g != null && !g.isBlank()) {
                    compiled.add(globToRegex(g));
                }
            }
        }
        this.sensitivePatterns = compiled;

        // Fail fast on a mis-configured project root BEFORE touching the filesystem: a typo'd
        // harnex.workspace.root / HARNEX_WORKSPACE must NOT be silently mkdir'd into an empty
        // directory (that would mask the misconfig and leave the agent editing the wrong place).
        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalStateException("configured workspace root does not exist or is not a directory: "
                + projectRoot + " (point harnex.workspace.root / HARNEX_WORKSPACE at an existing project folder)");
        }
        try {
            Files.createDirectories(dataDir.resolve("uploads"));
            log.info("WorkspacePathResolver: project root = {}", projectRoot);
        } catch (IOException e) {
            log.warn("Init dirs failed: {}", e.getMessage());
        }
    }

    /** The project root the agent operates on (never {@code null}). */
    public Path getProjectRoot() {
        return projectRoot;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public Path workspaceFor(String threadId) {
        ensureDir(projectRoot, "project workspace");
        return projectRoot; // shared real project root; threadId is not a slice key
    }

    public Path uploadsFor(String threadId) {
        Ids.requireSafe(threadId, "threadId");
        Path u = dataDir.resolve("uploads").resolve(threadId);
        ensureDir(u, "uploads");
        return u;
    }

    /**
     * Whether {@code dir} is the current thread's own uploads slice ({@code <dataDir>/uploads/<threadId>}) or one of
     * its ancestors ({@code <dataDir>}, {@code <dataDir>/uploads}). The file-walk tools ({@code glob}/{@code grep})
     * classify {@code <dataDir>} and {@code <dataDir>/uploads} as RED and would prune them — and, with them, the GREEN
     * own-uploads slice underneath. This predicate lets the walk descend THROUGH those RED ancestors so a thread can
     * search its OWN attachments, while every sibling under them (other threads' slices, the app's private state) is
     * still pruned by its own RED classification one level down. No side effects (does not create directories), so it
     * is safe to call on every directory visit.
     */
    public boolean isOwnUploadsAncestor(Path dir, String threadId) {
        if (dir == null || threadId == null || threadId.isBlank()) {
            return false;
        }
        Path own = safeRealPath(dataDir.resolve("uploads").resolve(threadId));
        Path candidate = safeRealPath(dir);
        // true when candidate is the own-uploads slice itself or an ancestor of it.
        return own.startsWith(candidate);
    }

    /**
     * Idempotent mkdir-p that swallows transient races (parallel callers, dir already exists) but surfaces real
     * failures (disk full, permission revoked, sandbox root unmounted) by throwing once we observe that the directory
     * STILL does not exist after the attempt. The previous "catch (IOException ignored)" left a non-existent path
     * returned to the caller, which then surfaced downstream as a confusing NoSuchFileException at the first tool call
     * with no hint that the actual root cause was a sandbox-creation failure.
     */
    private static void ensureDir(Path p, String label) {
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            if (!Files.isDirectory(p)) {
                throw new IllegalStateException("Cannot create " + label + " directory " + p + ": " + e.getMessage(),
                    e);
            }
            // Directory exists — the IOException was a benign race / replayable hiccup.
        }
    }

    /**
     * Convert a path to a real absolute path for a WRITE operation, then apply {@link Zone} enforcement for WRITE.
     * Identical to {@link #toReal} except the resolved target is checked against the write policy (RED is always
     * refused; YELLOW follows {@code outside-write-policy}).
     */
    public Path toRealForWrite(String pathOrReal, String threadId) {
        return toRealForWrite(pathOrReal, threadId, false);
    }

    /** Resolve a write after the approval layer has explicitly admitted this exact tool call. */
    public Path toRealForWrite(String pathOrReal, String threadId, boolean outsideWriteApproved) {
        Path p = resolveRaw(pathOrReal, threadId);
        if (p == null) {
            return null;
        }
        enforce(p, threadId, true, outsideWriteApproved);
        return p;
    }

    /** Convert a relative or real path to a real absolute path for a READ operation, applying zone enforcement. */
    public Path toReal(String pathOrReal, String threadId) {
        Path p = resolveRaw(pathOrReal, threadId);
        if (p == null) {
            return null;
        }
        enforce(p, threadId, false, false);
        return p;
    }

    /** Classify a tool argument without granting access; used by ApprovalService before execution. */
    public Zone classifyInputPath(String pathOrReal, String threadId) {
        Path path = resolveRaw(pathOrReal, threadId);
        return path == null ? Zone.RED : classify(path, threadId);
    }

    /** Shell cwd is independent of file-tool paths and must be accounted for before applying the same zone policy. */
    public Zone classifyCommandPath(String input, String cwd, String threadId) {
        Path base = projectRoot;
        if (cwd != null && !cwd.isBlank()) {
            base = commands == null ? ShellPaths.resolve(cwd, projectRoot, null)
                : commands.resolveCommandDirectory(cwd, projectRoot);
            if (!Files.isDirectory(base) || !safeRealPath(base).startsWith(safeRealPath(projectRoot))) {
                throw new IllegalArgumentException("cwd must be an existing directory inside the workspace: " + cwd);
            }
        }
        if (commands != null && commands.getEnvironment() == CommandExecutor.Environment.DOCKER) {
            String containerPath = input;
            if (input.startsWith("/")) {
                var segments = new ArrayDeque<String>();
                for (String segment : input.split("/")) {
                    if (segment.isEmpty() || segment.equals(".")) continue;
                    if (segment.equals("..")) { if (!segments.isEmpty()) segments.removeLast(); }
                    else segments.addLast(segment);
                }
                containerPath = "/" + String.join("/", segments);
            }
            if (containerPath.equals("/tmp") || containerPath.startsWith("/tmp/")) {
                for (Pattern sensitive : sensitivePatterns) if (sensitive.matcher(containerPath).matches()) return Zone.RED;
                return Zone.GREEN;
            }
            if (containerPath.equals("/work") || containerPath.startsWith("/work/")) {
                return classify(projectRoot.resolve(containerPath.length() == 5 ? "" : containerPath.substring(6)).normalize(), threadId);
            }
        }
        Path path = commands == null ? ShellPaths.resolve(input, base, null)
            : commands.resolveHostPath(input, base);
        return path == null ? Zone.RED : classify(path, threadId);
    }

    public String getOutsideWritePolicy() {
        return normalizeOutsideWritePolicy(config.getWorkspace() == null ? null : config.getWorkspace().getOutsideWritePolicy());
    }

    private static List<Path> systemTemporaryDirectories() {
        List<Path> roots = new ArrayList<>();
        Path home = safeRealPath(Path.of(System.getProperty("user.home", ".")));
        for (String value : new String[] { System.getProperty("java.io.tmpdir"), System.getenv("TMPDIR"),
            System.getenv("TEMP"), System.getenv("TMP"), ShellPaths.isWindows() ? null : "/tmp" }) {
            if (value == null || value.isBlank()) continue;
            try {
                Path path = Path.of(value);
                if (!path.isAbsolute() || !Files.isDirectory(path)) continue;
                Path real = path.toRealPath();
                // A malformed environment variable must not turn the filesystem root or entire home into temp space.
                if (real.getParent() != null && !real.equals(home)) roots.add(real);
            } catch (IOException | RuntimeException ignored) { }
        }
        return roots.stream().distinct().toList();
    }

    /**
     * Pure path resolution (no read/write zone policy). Handles relative paths (anchored at the project root) and
     * host-absolute real paths. Relative paths may intentionally resolve outside the project (for example an adjacent
     * repository via {@code ../other-repo}); both forms are left to {@link #classify}/{@link #enforce} for the same
     * GREEN/YELLOW/RED decision. This is also how an upload's absolute path under
     * {@code dataDir/uploads/<tid>} is reached and granted GREEN for its owning thread.
     */
    private Path resolveRaw(String pathOrReal, String threadId) {
        try {
            return commands == null ? ShellPaths.resolve(pathOrReal, projectRoot, null)
                : commands.resolveHostPath(pathOrReal, projectRoot);
        } catch (IllegalArgumentException invalid) { throw new SecurityException(invalid.getMessage(), invalid); }
    }

    public Zone classify(Path real) { return classify(real, null); }

    /** Current-thread uploads are accessible; all other dataDir contents remain private. */
    public Zone classify(Path real, String threadId) {
        return classify(real, threadId, WorkspacePathResolver::safeRealPath);
    }

    /**
     * Resolve fixed policy roots once per traversal; candidate paths are always resolved afresh. This scope is
     * single-threaded and only prunes directories. Recheck with classify(path, threadId) before publishing/reading
     * a file so policy roots moved during a search cannot grant stale access.
     */
    public SearchAccess searchAccess(String threadId) {
        return new SearchAccess(threadId);
    }

    public List<String> searchExcludeGlobs() {
        var workspace = config.getWorkspace();
        return workspace == null || workspace.getSearchExcludeGlobs() == null ? List.of()
            : new ArrayList<>(workspace.getSearchExcludeGlobs());
    }

    public final class SearchAccess {
        private final String threadId;
        private final Map<Path, Path> roots = new HashMap<>();

        private SearchAccess(String threadId) { this.threadId = threadId; }

        public Zone classify(Path path) {
            return WorkspacePathResolver.this.classify(path, threadId, this::root);
        }

        public boolean isOwnUploadsAncestor(Path dir) {
            return threadId != null && !threadId.isBlank()
                && root(dataDir.resolve("uploads").resolve(threadId)).startsWith(safeRealPath(dir));
        }

        private Path root(Path path) { return roots.computeIfAbsent(path, WorkspacePathResolver::safeRealPath); }
    }

    private Zone classify(Path real, String threadId, Function<Path, Path> resolveRoot) {
        Path candidate = safeRealPath(real);
        Path data = resolveRoot.apply(dataDir);

        // User-level schedules grant future execution authority; project tools cannot read or rewrite them.
        if (candidate.startsWith(resolveRoot.apply(automationData))) return Zone.RED;

        if (candidate.startsWith(data)) {
            // The current thread's uploads slice is the only agent-facing part of dataDir.
            if (threadId != null) {
                Path myUploads = resolveRoot.apply(data.resolve("uploads").resolve(threadId));
                if (candidate.startsWith(myUploads)) {
                    return Zone.GREEN;
                }
            }
            return Zone.RED;
        }

        if (candidate.startsWith(resolveRoot.apply(sharedDataDir))) return Zone.RED;

        if (candidate.startsWith(resolveRoot.apply(projectRoot))) {
            return Zone.GREEN;
        }
        String s = candidate.toString().replace('\\', '/');
        for (Pattern p : sensitivePatterns) {
            if (p.matcher(s).matches()) {
                return Zone.RED;
            }
        }
        if (temporaryDirectories.stream().anyMatch(candidate::startsWith)) return Zone.GREEN;
        return Zone.YELLOW;
    }

    /**
     * Apply zone policy to a resolved real path. No-op for GREEN; hard-deny for RED; YELLOW gated by the
     * configured outside-write policy. Yellow accesses are audited.
     */
    private void enforce(Path real, String threadId, boolean write, boolean outsideWriteApproved) {
        Zone z = classify(real, threadId);
        switch (z) {
            case GREEN:
                return;
            case RED:
                throw new SecurityException("access to sensitive path denied (red zone): " + real);
            case YELLOW:
            default:
                if (write) {
                    if ("deny".equals(getOutsideWritePolicy())) {
                        throw new SecurityException("write outside the project root is denied by policy: " + real);
                    }
                    if ("ask".equals(getOutsideWritePolicy()) && !outsideWriteApproved) {
                        throw new SecurityException("write outside the project root requires explicit approval: " + real);
                    }
                    log.warn("workspace: YELLOW-zone WRITE permitted to {} (outside project root)", real);
                } else {
                    log.info("workspace: YELLOW-zone read of {} (outside project root)", real);
                }
        }
    }

    private static String normalizeOutsideWritePolicy(String value) {
        String normalized = value == null || value.isBlank() ? "ask" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("deny", "ask", "allow").contains(normalized)) {
            throw new IllegalStateException("harnex.workspace.outside-write-policy must be deny, ask or allow");
        }
        return normalized;
    }

    /**
     * Translate a forward-slash glob ({@code **}, {@code *}, {@code ?}) into a full-match regex. {@code **} spans path
     * separators; {@code *} does not. Used only for the sensitive-path (RED zone) denylist.
     */
    static Pattern globToRegex(String glob) {
        String g = glob.replace('\\', '/');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append("[^/]*");
                    }
                    break;
                case '?':
                    sb.append("[^/]");
                    break;
                case '.':
                    sb.append("\\.");
                    break;
                case '/':
                    sb.append('/');
                    break;
                default:
                    if ("\\+()^$|{}[]".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
            }
        }
        return Pattern.compile(sb.toString(), ShellPaths.isWindows() ? Pattern.CASE_INSENSITIVE : 0);
    }

    /**
     * Resolve a path through symlinks where possible. For not-yet-existing paths we resolve the deepest existing
     * ancestor and re-append the tail so writes to new files still get robust containment checks.
     */
    private static Path safeRealPath(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException ignored) {
            Path parent = abs.getParent();
            while (parent != null) {
                try {
                    Path real = parent.toRealPath();
                    return real.resolve(parent.relativize(abs)).normalize();
                } catch (IOException e) {
                    parent = parent.getParent();
                }
            }
            return abs;
        }
    }
}
