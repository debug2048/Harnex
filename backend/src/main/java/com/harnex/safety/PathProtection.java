package com.harnex.safety;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.skill.SkillLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * Project-level "do not touch" guard. Two layers:
 *
 * <ol>
 * <li><b>Static globs</b> from {@code harnex.safety.protected-globs} (typed in YAML / env). Always evaluated. Defaults
 * already cover {@code .env}, secrets/, *.pem, *.key, .git/.</li>
 * <li><b>Workspace {@code .harnexignore}</b> (gitignore-style; one glob per line, {@code #} comments) reloaded
 * automatically when the file's mtime changes. Lets project owners add ad-hoc protections without restarting the
 * agent.</li>
 * </ol>
 *
 * <p>
 * Globs are compiled to regular expressions and evaluated against the path RELATIVE to the workspace root, normalised
 * to forward slashes (so the same patterns work on Windows and POSIX). Static globs and {@code .harnexignore} rules
 * are evaluated as ONE ordered list (static first, ignore-file last), so a leading {@code !} in {@code .harnexignore}
 * genuinely UN-protects an earlier static match (gitignore-style last-match-wins). Bare names without a slash match at
 * any depth ({@code *.key} = anywhere); a trailing {@code /} matches a directory and all its contents.
 *
 * <p>
 * <b>Control-plane layer.</b> Independently of the globs, the app's own behaviour-injection entry points — the loaded
 * root {@code agent-config.yml} and every skill {@code SKILL.md} — are ALWAYS write-protected (the agent rewriting them
 * would persist as a behaviour injection). This layer is absolute: it is not relative to the workspace root and cannot
 * be negated by {@code .harnexignore}. Reads are deliberately NOT blocked here (the resolver keeps these paths
 * GREEN/YELLOW) so the agent can still read the config/skills to understand the project.
 *
 * <p>
 * This is the LLM-only guard rail: real on-disk permissions / ACLs are still enforced by the OS.
 */
@Slf4j
@Component
@ProjectRuntime
public class PathProtection {

    private final AgentConfig config;
    private final WorkspacePathResolver vp;

    /** Per-thread cached {@code .harnexignore} matcher (reloaded on mtime change). */
    private final ConcurrentMap<String, IgnoreFileCache> ignoreCache = new ConcurrentHashMap<>();

    private List<Rule> staticRules() {
        return config.derivedSettings(this, c -> compile(c.getSafety() == null ? List.of() : c.getSafety().getProtectedGlobs()));
    }

    /** Real path of the loaded root {@code agent-config.yml} (behaviour-injection entry point). May be {@code null}. */
    private final Path controlPlaneConfig;
    /**
     * Real paths of the concrete control-plane config files the launcher actually injected (via
     * {@code HARNEX_CONFIG_FILES} env or {@code -Dharnex.config.files}). Only these non-root copies are write-protected
     * — NOT every {@code agent-config.yml} found anywhere under the project root, which would false-positive on a
     * user's own repo (e.g. {@code backend/src/main/resources/agent-config.yml}). Never {@code null} (may be empty).
     */
    private final Set<Path> injectedConfigFiles;
    /** Real paths of the skills roots; any {@code SKILL.md} beneath them is write-protected. */
    private final List<Path> skillsRoots;

    public PathProtection(AgentConfig cfg, WorkspacePathResolver vp) {
        this(cfg, vp, null);
    }

    @Autowired
    public PathProtection(AgentConfig cfg, WorkspacePathResolver vp, Environment env) {
        this.config = cfg;
        this.vp = vp;
        // The loaded root-level config the desktop shell points SPRING_CONFIG_ADDITIONAL_LOCATION at lives at
        // <projectRoot>/agent-config.yml in the flat layout (the data-dir copy is already RED in non-flat layouts).
        this.controlPlaneConfig = resolveDeepest(vp.getProjectRoot().resolve("agent-config.yml"));
        this.skillsRoots = SkillLoader.skillRoots(cfg).stream().map(PathProtection::resolveDeepest)
            .filter(Objects::nonNull).toList();
        this.injectedConfigFiles = parseInjectedConfigFiles(env);
    }

    /**
     * Parse the launcher-supplied list of concrete control-plane config files. Two independent sources are merged:
     * <ol>
     * <li>{@code -Dharnex.config.files} system property (tests / explicit overrides) or the {@code HARNEX_CONFIG_FILES}
     * env the Node / Tauri launchers set. Entries are separated by newline ONLY (an OS path may legitimately contain
     * {@code ,} or {@code ;}, so those must NOT be treated as separators here).</li>
     * <li>The Spring {@code spring.config.additional-location} property (resolved through the
     * {@link org.springframework.core.env.Environment} so CLI args / env / property files all count) \u2014 this
     * protects a config an operator passed MANUALLY via {@code --spring.config.additional-location} even though the
     * launcher never recorded it. Only {@code file:} locations naming a FILE are taken; directory locations are
     * skipped.</li>
     * </ol>
     * Each entry is resolved to its real path so it compares equal to a {@link WorkspacePathResolver}-resolved
     * candidate on Windows (case) and through symlinks. {@code env} may be {@code null} (non-Spring construction).
     */
    private static Set<Path> parseInjectedConfigFiles(Environment env) {
        Set<Path> out = new HashSet<>();
        // (1) Concrete OS paths the launcher recorded in HARNEX_CONFIG_FILES (newline-separated; a path
        // may legitimately contain ',' or ';').
        String raw = System.getProperty("harnex.config.files");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("HARNEX_CONFIG_FILES");
        }
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split("\\n")) {
                addOsPathConfigFile(out, part);
            }
        }
        // (2) Spring's spring.config.additional-location — covers an operator MANUALLY passing
        // --spring.config.additional-location=file:.../agent-config.yml (or setting the env), which the
        // launcher never records in HARNEX_CONFIG_FILES. Resolving it through the Environment also catches
        // CLI args / property files, not just the env var. Only FILE locations become control-plane;
        // a directory location is merely scanned by Spring for application.* and is skipped.
        if (env != null) {
            String loc = env.getProperty("spring.config.additional-location");
            if (loc != null && !loc.isBlank()) {
                for (String part : loc.split("[,;]")) {
                    addSpringLocationConfigFile(out, part);
                }
            }
        }
        return out;
    }

    /** Add a concrete OS-path config file (from HARNEX_CONFIG_FILES) to the protected set. */
    private static void addOsPathConfigFile(Set<Path> out, String osPath) {
        String t = osPath.trim();
        if (t.isEmpty()) {
            return;
        }
        Path p = resolveDeepest(Paths.get(t).toAbsolutePath());
        if (p != null) {
            out.add(p);
        }
    }

    /** Add a single {@code file:} location (from spring.config.additional-location) if it names a FILE. */
    private static void addSpringLocationConfigFile(Set<Path> out, String location) {
        String t = location.trim();
        if (t.isEmpty()) {
            return;
        }
        if (t.startsWith("optional:")) {
            t = t.substring("optional:".length());
        }
        // Only concrete file: locations. A directory location (trailing '/') is scanned for application.*
        // by Spring and is not a single control-plane file, so skip it.
        if (!t.startsWith("file:") || t.endsWith("/")) {
            return;
        }
        try {
            Path p = Paths.get(URI.create(t)); // decodes %2C / %3B back to ',' / ';'
            if (!Files.isDirectory(p)) {
                Path r = resolveDeepest(p.toAbsolutePath());
                if (r != null) {
                    out.add(r);
                }
            }
        } catch (RuntimeException e) {
            log.debug("PathProtection: ignoring unparseable additional-location entry '{}': {}", location,
                e.getMessage());
        }
    }

    /**
     * @return true if {@code realPath} (already resolved + containment-checked by {@link WorkspacePathResolver}) is
     *         under workspace AND matches a protected pattern. Paths OUTSIDE the workspace (e.g. uploads, outputs) are
     *         not protected by this layer — the workspace path resolver already restricts what can be reached.
     */
    public boolean isProtected(String threadId, Path realPath) {
        if (realPath == null) {
            return false;
        }
        // Resolve the candidate to its real path when possible so case-canonicalisation on
        // Windows + symlink resolution match the workspace root we resolve below. For paths that
        // do not yet exist (a brand-new file_write target whose dir tree may also not exist) walk
        // up to the deepest EXISTING ancestor, take its real path, then re-append the remaining
        // relative suffix.
        Path candidate = resolveDeepest(realPath);
        // Control-plane layer: absolute, layout-independent, not negatable by .harnexignore.
        if (isControlPlaneProtected(candidate)) {
            return true;
        }
        Path ws;
        try {
            ws = vp.workspaceFor(threadId).toRealPath();
        } catch (IOException e) {
            return false;
        }
        if (!candidate.startsWith(ws)) {
            return false;
        }
        String relStr = ws.relativize(candidate).toString().replace('\\', '/');
        if (relStr.isEmpty()) {
            return false;
        }

        // The workspace-root .harnexignore is itself control-plane: its `!pattern` lines can UN-protect the static
        // protected-globs (build/** convenience, etc.), so letting the file tools rewrite it would be a privilege
        // escalation (agent writes `!.env` then writes `.env`). Block writes to it unconditionally and non-negatably.
        // Humans still override protection by editing this file directly on disk \u2014 only the agent's file tools are
        // refused here. Reads are unaffected (this method is consulted only on write/edit).
        if (relStr.equals(".harnexignore")) {
            return true;
        }

        // Evaluate static globs and .harnexignore as ONE ordered rule list (static first, ignore-file last) so a
        // trailing `!pattern` in .harnexignore can genuinely UN-protect an earlier static match (last-match-wins).
        List<Rule> staticRules = staticRules();
        AgentConfig.Safety cfg = config.getSafety();
        List<Rule> rules = staticRules;
        if (cfg != null && cfg.isRespectIgnoreFile()) {
            IgnoreFileCache ic = loadIgnoreFile(threadId, ws);
            if (ic != null && !ic.rules.isEmpty()) {
                rules = new ArrayList<>(staticRules.size() + ic.rules.size());
                rules.addAll(staticRules);
                rules.addAll(ic.rules);
            }
        }
        return matchAny(rules, relStr);
    }

    /**
     * Whether {@code candidate} (an already-resolved real path) is one of the app's behaviour-injection control-plane
     * entry points that the file tools must never write: the loaded root {@code agent-config.yml}, any additional
     * config file ACTUALLY injected (via {@code spring.config.additional-location} / {@code HARNEX_CONFIG_FILES}), or
     * any {@code SKILL.md} beneath the skills root. NOT every {@code agent-config.yml} found under the project root —
     * only the ones really loaded. Absolute and layout-independent — callers cannot negate it via
     * {@code .harnexignore}. Reads are unaffected (this is consulted only on write/edit).
     */
    private boolean isControlPlaneProtected(Path candidate) {
        if (candidate == null) {
            return false;
        }
        if (controlPlaneConfig != null && candidate.equals(controlPlaneConfig)) {
            return true;
        }
        // Only the config files the launcher ACTUALLY injected (spring.config.additional-location =
        // file:.../agent-config.yml, surfaced to the backend via HARNEX_CONFIG_FILES) are control-plane. We no longer
        // blanket-protect every agent-config.yml under the project root — that false-positived on a user's own repo
        // (e.g. backend/src/main/resources/agent-config.yml), refusing legitimate edits to files we never load.
        if (injectedConfigFiles.contains(candidate)) {
            return true;
        }
        for (Path skillsRoot : skillsRoots) {
            if (candidate.startsWith(skillsRoot)) {
                Path name = candidate.getFileName();
                if (name != null && name.toString().equalsIgnoreCase("SKILL.md")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Drop cached {@code .harnexignore} on thread deletion. */
    public void purge(String threadId) {
        if (threadId != null) {
            ignoreCache.remove(threadId);
        }
    }

    // ---- internals ----

    private static boolean matchAny(List<Rule> rules, String relPath) {
        boolean hit = false;
        for (Rule r : rules) {
            if (r.regex.matcher(relPath).matches()) {
                hit = !r.negate;
            }
        }
        return hit;
    }

    private IgnoreFileCache loadIgnoreFile(String threadId, Path workspaceRoot) {
        String cacheKey = threadId == null ? workspaceRoot.toString() : threadId;
        Path f = workspaceRoot.resolve(".harnexignore");
        long mtime;
        try {
            if (!Files.isRegularFile(f)) {
                ignoreCache.remove(cacheKey);
                return null;
            }
            mtime = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return null;
        }
        IgnoreFileCache cur = ignoreCache.get(cacheKey);
        if (cur != null && cur.mtime == mtime) {
            return cur;
        }
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            List<String> patterns = new ArrayList<>(lines.size());
            for (String l : lines) {
                String t = l.trim();
                if (t.isEmpty() || t.startsWith("#"))
                    continue;
                patterns.add(t);
            }
            IgnoreFileCache fresh = new IgnoreFileCache(mtime, compile(patterns));
            ignoreCache.put(cacheKey, fresh);
            return fresh;
        } catch (IOException e) {
            log.debug(".harnexignore read failed for thread {}: {}", threadId, e.getMessage());
            return cur;
        }
    }

    private static List<Rule> compile(List<String> patterns) {
        List<Rule> out = new ArrayList<>(patterns.size() * 2);
        for (String raw : patterns) {
            String p = raw.trim();
            if (p.isEmpty() || p.startsWith("#"))
                continue;
            boolean neg = p.startsWith("!");
            if (neg) {
                p = p.substring(1);
            }
            // Gitignore-style anchoring: no slash => match at any depth; leading slash =>
            // anchored at workspace root; trailing slash => directory (also matches contents).
            boolean dirOnly = p.endsWith("/");
            if (dirOnly) {
                p = p.substring(0, p.length() - 1);
            }
            boolean anchored = p.startsWith("/");
            if (anchored) {
                p = p.substring(1);
            }
            String basePattern = anchored ? p : ("**/" + p);
            try {
                out.add(new Rule(Pattern.compile(globToRegex(basePattern)), neg));
                // A directory pattern OR a bare name without wildcards should also match
                // children: ".git/" => ".git" + ".git/**"; "secrets" (no wildcard) similarly.
                if (dirOnly || (!p.contains("*") && !p.contains("?"))) {
                    out.add(new Rule(Pattern.compile(globToRegex(basePattern + "/**")), neg));
                }
            } catch (Exception e) {
                log.warn("invalid protected-glob '{}': {}", raw, e.getMessage());
            }
        }
        return out;
    }

    /**
     * Translate a glob ({@code *}, {@code ?}, {@code **}) to a Java regex matching forward-slash paths. {@code **}
     * crosses {@code /}; {@code *} does not. Other regex metacharacters in the pattern are escaped.
     */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < n && glob.charAt(i + 1) == '*') {
                    // ** => match any chars (including /). Consume optional trailing '/'.
                    sb.append(".*");
                    i += 2;
                    if (i < n && glob.charAt(i) == '/') {
                        // "**/" => ".*" already; skip the slash so "**/foo" doesn't force a /.
                        i++;
                    }
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else if ("\\.^$+(){}|[]".indexOf(c) >= 0) {
                sb.append('\\').append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        sb.append('$');
        return sb.toString();
    }

    private record Rule(Pattern regex, boolean negate) {
    }

    private record IgnoreFileCache(long mtime, List<Rule> rules) {
    }

    /**
     * Resolve {@code p} to its canonical real path even when it (or some of its ancestors) does not yet exist on disk.
     * Walks up to the deepest existing parent, calls {@code toRealPath} there, and re-appends the missing suffix. This
     * is essential for {@code file_write} on a brand-new file (or one whose parent directory the tool will create) —
     * without this, the canonicalised workspace root and the lexically-resolved candidate disagree on Windows (case
     * folding, 8.3 short names) and the {@code startsWith} containment check false-negatives.
     */
    private static Path resolveDeepest(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path cursor = abs;
        Deque<String> trailing = new ArrayDeque<>();
        while (cursor != null && !Files.exists(cursor)) {
            Path name = cursor.getFileName();
            if (name == null)
                break;
            trailing.push(name.toString());
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            return abs;
        }
        Path real;
        try {
            real = cursor.toRealPath();
        } catch (IOException e) {
            return abs;
        }
        for (String seg : trailing) {
            real = real.resolve(seg);
        }
        return real;
    }

    // Visible for tests / debugging.
    int staticRuleCount() {
        return staticRules().size();
    }

    @Override
    public String toString() {
        return "PathProtection{static=" + staticRules().size() + ", cachedIgnores=" + ignoreCache.size() + "}";
    }
}
