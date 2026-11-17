package com.harnex.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.config.RuntimeProfile;
import com.harnex.storage.SharedFileTransaction;
import com.harnex.config.UserDataPaths;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans skill roots for {@code <slug>/SKILL.md} and parses optional YAML front-matter.
 *
 * <p>
 * Roots are merged in increasing precedence: user-level {@code ~/.harnex/skills}, then configured
 * {@code harnex.skills-dir}. The default {@code harnex.skills-dir=./skills} is resolved against the effective workspace
 * root, so project backends naturally read {@code <project>/.harnex/skills}.
 * </p>
 *
 * Progressive-loading policy (Hermes-inspired): - At agent startup we keep only {@link Skill#name} and
 * {@link Skill#description} in memory so 200 skills and 20 skills have ~same context cost. - Full body is only loaded
 * on demand via {@link #readBody(String)} when the agent calls the `skill_read` tool.
 *
 * Front-matter is optional; plain Markdown files still work (summary = first line). Front-matter example: --- name:
 * web-research description: Structured literature scan with citation capture when_to_use: Multi-source research tasks
 * needing >3 web fetches --- # How to: ...
 */
@Slf4j
@Component
@ProjectRuntime
public class SkillLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<SkillRoot> roots;
    private final ConcurrentMap<String, Skill> byName = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Skill> allBySource = new ConcurrentHashMap<>();
    private final Path userStateFile;
    private final Path workspaceStateFile;

    public SkillLoader(AgentConfig cfg) {
        List<Path> resolved = skillRoots(cfg);
        this.roots = new ArrayList<>();
        if (!resolved.isEmpty()) {
            roots.add(new SkillRoot(resolved.get(0), "USER"));
        }
        if (resolved.size() > 1) {
            roots.add(new SkillRoot(resolved.get(resolved.size() - 1), "WORKSPACE"));
        }
        Path userData = UserDataPaths.resolve(cfg);
        Path workspaceData = Paths.get(cfg.getDataDir()).toAbsolutePath().normalize();
        this.userStateFile = userData.resolve("settings").resolve("skills.json");
        this.workspaceStateFile = workspaceData.resolve("settings").resolve("skills.json");
    }

    @PostConstruct
    public void scan() {
        byName.clear();
        allBySource.clear();
        List<Path> scanned = new ArrayList<>();
        Map<String, Boolean> userState = readState(userStateFile);
        Map<String, Boolean> workspaceState = readState(workspaceStateFile);
        for (SkillRoot skillRoot : roots) {
            Path root = skillRoot.path();
            if (!Files.isDirectory(root)) {
                continue;
            }
            scanned.add(root);
            try {
                try (var stream = Files.walk(root, 3)) {
                    stream.filter(p -> p.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                        .forEach(file -> loadHeader(file, skillRoot.source(),
                            "USER".equals(skillRoot.source()) ? userState : workspaceState));
                }
            } catch (IOException e) {
                log.warn("SkillLoader scan failed for {}: {}", root, e.getMessage());
            }
        }
        Set<String> workspaceNames = new HashSet<>();
        allBySource.values().stream().filter(s -> "WORKSPACE".equals(s.getSource()))
            .forEach(s -> workspaceNames.add(s.getName()));
        for (Skill skill : allBySource.values()) {
            skill.setOverridden("USER".equals(skill.getSource()) && workspaceNames.contains(skill.getName()));
            skill.setEffective(false);
        }
        // Roots are increasing-precedence. Selecting a disabled Workspace item is intentional and does not fall back.
        for (SkillRoot root : roots) {
            allBySource.values().stream().filter(s -> root.source().equals(s.getSource()))
                .forEach(s -> byName.put(s.getName(), s));
        }
        byName.values().forEach(skill -> skill.setEffective(skill.isEnabled()));
        log.info("SkillLoader scanned {} skills from {}", byName.size(), scanned);
    }

    /** Force re-scan (e.g. after user dropped new skills). */
    public void rescan() {
        scan();
    }

    public Collection<Skill> list() {
        return allBySource.values().stream()
            .sorted(Comparator.comparing(Skill::getName).thenComparing(Skill::getSource)).toList();
    }

    public Optional<Skill> get(String name) {
        Skill selected = byName.get(name);
        return selected != null && selected.isEnabled() ? Optional.of(selected) : Optional.empty();
    }

    /** Load + cache body. */
    public String readBody(String name) throws IOException {
        Skill s = get(name).orElse(null);
        if (s == null) {
            throw new NoSuchElementException("Unknown skill: " + name);
        }
        if (s.getBody() != null) {
            return s.getBody();
        }
        String raw = Files.readString(s.getPath(), StandardCharsets.UTF_8);
        s.setBody(stripFrontMatter(raw).body);
        return s.getBody();
    }

    /** Short multi-line summary suitable for system-prompt injection. */
    public String renderForPrompt(int max) {
        List<Skill> effective = byName.values().stream().filter(Skill::isEnabled).toList();
        if (effective.isEmpty()) {
            return "";
        }
        List<Skill> ordered = new ArrayList<>(effective);
        ordered.sort(Comparator.comparing(Skill::getName));
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== AVAILABLE SKILLS (call `skill_read` to expand) ===\n");
        int count = 0;
        for (Skill s : ordered) {
            if (count++ >= max) {
                sb.append("… ").append(ordered.size() - max).append(" more\n");
                break;
            }
            sb.append("- ").append(s.getName()).append(": ")
                .append(s.getDescription() == null ? "" : s.getDescription()).append('\n');
        }
        sb.append("=== end skills ===\n");
        return sb.toString();
    }

    public synchronized Skill setEnabled(String name, String source, boolean enabled) {
        String normalizedSource = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("USER", "WORKSPACE").contains(normalizedSource)) {
            throw new IllegalArgumentException("source must be USER or WORKSPACE");
        }
        Skill skill = allBySource.get(normalizedSource + ":" + name);
        if (skill == null) {
            throw new NoSuchElementException("Unknown " + normalizedSource + " skill: " + name);
        }
        Path stateFile = "USER".equals(normalizedSource) ? userStateFile : workspaceStateFile;
        SharedFileTransaction.withExclusiveLock(stateFile, () -> {
            Map<String, Boolean> state = new LinkedHashMap<>(readState(stateFile));
            state.put(name, enabled);
            writeState(stateFile, state);
            return null;
        });
        scan();
        return allBySource.get(normalizedSource + ":" + name);
    }

    // ---- internals ----
    /**
     * Return skill roots in increasing precedence. Later roots override earlier ones when two {@code SKILL.md} files
     * declare the same {@code name}. The project-local root is intentionally just {@code harnex.skills-dir}; with the
     * default {@code .harnex/skills}, that is {@code <project>/.harnex/skills}.
     */
    public static List<Path> skillRoots(AgentConfig cfg) {
        LinkedHashMap<String, Path> out = new LinkedHashMap<>();
        addRoot(out, UserDataPaths.resolve(cfg).resolve("skills"));

        if (cfg != null && cfg.getRuntimeProfile() == RuntimeProfile.PROJECT
            && cfg.getSkillsDir() != null && !cfg.getSkillsDir().isBlank()) {
            addRoot(out, resolveSkillsDir(cfg));
        }
        return List.copyOf(out.values());
    }

    private static Path resolveSkillsDir(AgentConfig cfg) {
        Path configured = Paths.get(cfg.getSkillsDir());
        if (configured.isAbsolute()) {
            return configured;
        }
        return resolveWorkspaceRoot(cfg).resolve(configured).normalize();
    }

    private static Path resolveWorkspaceRoot(AgentConfig cfg) {
        String root = null;
        if (cfg != null && cfg.getWorkspace() != null) {
            root = cfg.getWorkspace().getRoot();
        }
        if (root == null || root.isBlank()) {
            root = System.getenv("HARNEX_WORKSPACE");
        }
        if (root == null || root.isBlank()) {
            root = System.getProperty("user.dir", ".");
        }
        return Paths.get(root).toAbsolutePath().normalize();
    }

    private static void addRoot(Map<String, Path> out, Path root) {
        if (root == null) {
            return;
        }
        Path abs = root.toAbsolutePath().normalize();
        out.putIfAbsent(pathKey(abs), abs);
    }

    private static String pathKey(Path path) {
        Path p = path;
        try {
            p = path.toRealPath();
        } catch (IOException ignored) {
            // Missing roots are still useful as stable candidates; lexical absolute path is enough for de-dupe.
        }
        String s = p.normalize().toString();
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? s.toLowerCase(Locale.ROOT)
            : s;
    }

    private void loadHeader(Path file, String source, Map<String, Boolean> state) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            Parsed p = stripFrontMatter(raw);
            String slug = file.getParent().getFileName().toString();
            String name = (String)p.meta.getOrDefault("name", slug);
            String desc = (String)p.meta.getOrDefault("description", firstLine(p.body));
            String when = (String)p.meta.getOrDefault("when_to_use", null);
            Skill s = new Skill(name, desc == null ? "" : desc, when, file, null);
            s.setSource(source);
            s.setEnabled(state.getOrDefault(name, true));
            s.setEffective(false);
            allBySource.put(source + ":" + name, s);
        } catch (Exception e) {
            log.warn("Skip unreadable SKILL.md {}: {}", file, e.getMessage());
        }
    }

    private static Map<String, Boolean> readState(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Boolean> state = JSON.readValue(file.toFile(), Map.class);
            return state == null ? Map.of() : state;
        } catch (IOException e) {
            log.warn("Skill state is unreadable at {}: {}", file, e.getMessage());
            return Map.of();
        }
    }

    private static void writeState(Path file, Map<String, Boolean> state) {
        try {
            SharedFileTransaction.writeAtomically(file, JSON.writeValueAsBytes(state), true);
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist skill state: " + e.getMessage(), e);
        }
    }

    private static Parsed stripFrontMatter(String raw) {
        if (raw.startsWith("---")) {
            int end = raw.indexOf("\n---", 3);
            if (end > 0) {
                String fm = raw.substring(3, end).trim();
                String body = raw.substring(end + 4).replaceFirst("^\\s*\n", "");
                try {
                    // Safe deserialisation: typed to Map<String,Object>; Jackson YAML default does NOT enable
                    // polymorphic typing, so no gadget chain risk. Do NOT switch to readValue(Object.class).
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = YAML.readValue(fm, Map.class);
                    return new Parsed(meta == null ? Map.of() : meta, body);
                } catch (Exception ignore) {
                    /* fall through */ }
            }
        }
        return new Parsed(Map.of(), raw);
    }

    private static String firstLine(String body) {
        if (body == null) {
            return "";
        }
        for (String l : body.split("\n", 6)) {
            String t = l.replaceFirst("^#+\\s*", "").trim();
            if (!t.isBlank()) {
                return t.length() > 160 ? t.substring(0, 160) + "…" : t;
            }
        }
        return "";
    }

    private record Parsed(Map<String, Object> meta, String body) {
    }

    private record SkillRoot(Path path, String source) {
    }
}
