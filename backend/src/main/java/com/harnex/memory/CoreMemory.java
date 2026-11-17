package com.harnex.memory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.config.UserDataPaths;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Hermes-inspired Core Memory: three always-on Markdown files auto-injected into the system prompt.
 *
 * <ul>
 * <li>{@code <dataDir>/memory/MEMORY.md} — durable facts the agent has chosen to remember (agent-written,
 * project-scoped).</li>
 * <li>{@code <dataDir>/memory/LESSONS.md} — failure / pitfall log appended on errors (agent-written,
 * project-scoped).</li>
 * <li>{@code <dataDir>/memory/USER.md} — user-authored profile / standing instructions (human-edited).
 * <strong>Cascade</strong>: project file wins if present, else falls back to {@code ~/.harnex/USER.md} so cross-project
 * preferences (language, commit style, safety policy) live in one place and don't have to be re-typed for every fresh
 * dataDir. The project file is NOT auto-created at startup so the global fallback can actually take effect — only
 * explicit edits (or future write tools) materialise the project override.</li>
 * </ul>
 *
 * Total injected length is HARD-CAPPED at {@link #HARD_CAP} characters per file (Hermes uses 3575; we round to 3500).
 * Over-cap content is truncated with a tail marker so the user sees that curation is needed.
 *
 * Human-editable at all times — the agent never silently rewrites these files; changes go through explicit tool calls
 * or direct file edits.
 */
@Slf4j
@Component
@ProjectRuntime
public class CoreMemory {

    /** Hard per-file cap; injection truncates beyond this. */
    public static final int HARD_CAP = 3500;

    /**
     * Process-wide counter of memory writes dropped due to lock-acquisition timeout. Exposed via
     * {@link #getDroppedWrites()} so health checks / metrics endpoints can surface a non- zero value to the operator
     * instead of relying on log scraping alone.
     */
    private static final AtomicLong droppedWrites = new AtomicLong();

    public static long getDroppedWrites() {
        return droppedWrites.get();
    }

    private final Path memoryFile;
    /** Project-scoped USER.md override. Only read when present; never auto-created at startup. */
    private final Path userFile;
    /**
     * Global fallback at {@code ~/.harnex/USER.md} — read when the project override is absent so cross-project
     * preferences survive a change of dataDir / working directory.
     */
    private final Path userFileGlobal;
    private final Path lessonsFile;

    /**
     * Short-window dedup for {@link #appendLesson}: same prefix (~80 chars) within the last hour is dropped silently.
     * Failure paths (max_iterations / agent_loop_error) tend to fire repeatedly for the same root cause across adjacent
     * threads — without dedup the file fills with near-identical bullets that crowd out genuinely distinct lessons
     * under the LRU prune. Bounded LinkedHashMap (access-ordered, cap 256) keeps the working set in memory only;
     * restarting the JVM resets the window which is acceptable since the prune cap still bounds disk size.
     */
    private static final long DEDUP_WINDOW_MS = 60L * 60L * 1000L;
    private static final int DEDUP_MAX_ENTRIES = 256;
    private static final int DEDUP_KEY_PREFIX = 80;
    private final Map<String, Long> recentLessonKeys =
        Collections.synchronizedMap(new LinkedHashMap<String, Long>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > DEDUP_MAX_ENTRIES;
            }
        });

    public CoreMemory(AgentConfig cfg) {
        Path dir = Paths.get(cfg.getDataDir(), "memory");
        this.memoryFile = dir.resolve("MEMORY.md");
        this.userFile = dir.resolve("USER.md");
        this.lessonsFile = dir.resolve("LESSONS.md");
        this.userFileGlobal = UserDataPaths.resolve(cfg).resolve("USER.md");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(memoryFile.getParent());
            if (!Files.exists(memoryFile)) {
                Files.writeString(memoryFile, "# Agent Memory\n\nDurable facts learnt across sessions. Keep concise.\n",
                    StandardCharsets.UTF_8);
            }
            // Intentionally do NOT auto-create the project-level USER.md. Creating an empty
            // placeholder here would shadow {@link #userFileGlobal} forever (cascade always sees
            // the project file first), defeating the whole point of the global fallback. The
            // project override only exists once a human (or future write tool) writes to it.
            if (!Files.exists(lessonsFile)) {
                Files.writeString(lessonsFile,
                    "# Lessons Learned\n\nMistakes / pitfalls / approaches that failed. Append-only; oldest pruned automatically.\n",
                    StandardCharsets.UTF_8);
            }
            Path effectiveUser = effectiveUserFile();
            log.info("CoreMemory ready: MEMORY={} ({} chars), USER={} ({} chars, source={}), LESSONS={} ({} chars)",
                memoryFile, size(memoryFile), effectiveUser, effectiveUser == null ? 0 : size(effectiveUser),
                effectiveUser == null ? "none" : (effectiveUser.equals(userFile) ? "project" : "global"), lessonsFile,
                size(lessonsFile));
        } catch (IOException e) {
            log.warn("CoreMemory init failed: {}", e.getMessage());
        }
    }

    /**
     * Resolve which USER.md file is actually consulted: project override → global fallback → none. Exposed so the
     * status endpoint / tests can show the operator where their preferences are coming from.
     */
    public Path effectiveUserFile() {
        if (Files.exists(userFile))
            return userFile;
        if (Files.exists(userFileGlobal))
            return userFileGlobal;
        return null;
    }

    public String readMemory() {
        return read(memoryFile);
    }

    public String readUser() {
        // Cascade: project override first, then global ~/.harnex/USER.md. Returning the empty
        // string when neither exists is correct — the prompt renderer treats blank sections as
        // absent and omits the marker entirely.
        Path effective = effectiveUserFile();
        return effective == null ? "" : read(effective);
    }

    public String readLessons() {
        return read(lessonsFile);
    }

    public synchronized Map<String, Object> sources() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("effectiveProfileSource", Files.exists(userFile) ? "WORKSPACE"
            : Files.exists(userFileGlobal) ? "USER" : "NONE");
        out.put("user", Map.of("profile", read(userFileGlobal)));
        out.put("workspace", Map.of("profile", read(userFile), "memory", read(memoryFile),
            "lessons", read(lessonsFile)));
        out.put("limits", Map.of("perFileCharacters", HARD_CAP, "storedCharacters", HARD_CAP * 2));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userProfile", documentMetadata(userFileGlobal));
        metadata.put("workspaceProfile", documentMetadata(userFile));
        metadata.put("workspaceMemory", documentMetadata(memoryFile));
        metadata.put("workspaceLessons", documentMetadata(lessonsFile));
        out.put("metadata", metadata);
        return out;
    }

    private Map<String, Object> documentMetadata(Path file) {
        Map<String, Object> out = new LinkedHashMap<>();
        String content = read(file);
        out.put("path", file.toString());
        out.put("exists", Files.exists(file));
        out.put("characters", content.length());
        out.put("tokensEstimate", (content.length() + 3) / 4);
        try {
            out.put("bytes", Files.exists(file) ? Files.size(file) : 0L);
            out.put("modifiedAt", Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0L);
        } catch (IOException ignored) {
            out.put("bytes", 0L);
            out.put("modifiedAt", 0L);
        }
        return out;
    }

    /** Explicit human-authored write with a visible scope; never silently falls back between scopes. */
    public synchronized void writeScoped(String scope, String kind, String content) throws IOException {
        String normalizedScope = scope == null ? "" : scope.trim().toUpperCase(Locale.ROOT);
        String normalizedKind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        String value = content == null ? "" : content;
        if (value.length() > HARD_CAP * 2) {
            throw new IllegalArgumentException("memory content exceeds " + (HARD_CAP * 2) + " stored characters");
        }
        Path target;
        if ("USER".equals(normalizedScope)) {
            if (!"profile".equals(normalizedKind)) {
                throw new IllegalArgumentException("user scope supports kind=profile only");
            }
            target = userFileGlobal;
        } else if ("WORKSPACE".equals(normalizedScope)) {
            target = switch (normalizedKind) {
                case "profile" -> userFile;
                case "memory" -> memoryFile;
                case "lessons" -> lessonsFile;
                default -> throw new IllegalArgumentException("kind must be profile, memory or lessons");
            };
        } else {
            throw new IllegalArgumentException("scope must be USER or WORKSPACE");
        }
        Files.createDirectories(target.getParent());
        write(target, value);
    }

    /** Append a new fact as a bullet line to MEMORY.md (atomic + cross-process file-locked). */
    public synchronized void appendFact(String fact) throws IOException {
        rememberFact(fact, null);
    }

    /** Explicit, atomic correction of one same-scope fact. Capacity pressure never silently deletes other facts. */
    public synchronized void rememberFact(String fact, String replaces) throws IOException {
        String value = fact == null ? "" : fact.replaceAll("\\s+", " ").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("empty fact");
        String previous = replaces == null ? null : replaces.replaceAll("\\s+", " ").strip();
        boolean[] saved = {false};
        withFileLock(memoryFile, () -> {
            String cur = readNoLock(memoryFile);
            String line = "- " + value;
            List<String> lines = new ArrayList<>(cur.lines().toList());
            if (previous == null || previous.isEmpty()) {
                if (lines.stream().anyMatch(existing -> existing.strip().equals(line))) { saved[0] = true; return; }
                lines.add(line);
            } else {
                String old = "- " + previous;
                long matches = lines.stream().filter(existing -> existing.strip().equals(old)).count();
                if (matches != 1) throw new IllegalArgumentException("replaces must match exactly one complete existing fact; use memory_list first");
                int at = -1;
                for (int i = 0; i < lines.size(); i++) if (lines.get(i).strip().equals(old)) { at = i; break; }
                if (!old.equals(line) && lines.stream().anyMatch(existing -> existing.strip().equals(line))) lines.remove(at);
                else lines.set(at, line);
            }
            String next = String.join("\n", lines) + "\n";
            int capacity = previous == null || previous.isEmpty() ? HARD_CAP : Math.max(HARD_CAP, cur.length());
            if (next.length() > capacity) throw new IllegalArgumentException("MEMORY.md is full (prompt limit " + HARD_CAP
                + " characters). Consolidate with replaces or explicitly forget obsolete facts; existing facts were retained.");
            write(memoryFile, next);
            saved[0] = true;
        });
        if (!saved[0]) throw new IOException("Memory write did not complete; no fact was saved");
    }

    /** Drop oldest bullet lines (from top) until {@code text} fits within {@code budget} chars. */
    private static String pruneOldestBullets(String text, int budget) {
        while (text.length() > budget) {
            int firstBullet = text.indexOf("\n- ");
            if (firstBullet < 0)
                break;
            int nextBullet = text.indexOf("\n- ", firstBullet + 1);
            if (nextBullet < 0) {
                // Only one bullet remains but text still over budget — drop it too so we honour
                // the cap. Without this, a single oversized fact / lesson would silently keep
                // the file growing past 2 * HARD_CAP across appends.
                text = text.substring(0, firstBullet + 1);
                break;
            }
            text = text.substring(0, firstBullet + 1) + text.substring(nextBullet + 1);
        }
        return text;
    }

    /** Remove the first bullet line containing `needle` (case-insensitive). */
    public synchronized boolean forgetContaining(String needle) throws IOException {
        boolean[] removed = {false};
        String lc = needle.toLowerCase();
        withFileLock(memoryFile, () -> {
            String cur = readNoLock(memoryFile);
            String[] lines = cur.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                if (!removed[0] && l.startsWith("- ") && l.toLowerCase().contains(lc)) {
                    removed[0] = true;
                    continue;
                }
                sb.append(l).append('\n');
            }
            if (removed[0]) {
                write(memoryFile, sb.toString().replaceAll("\n+$", "\n"));
            }
        });
        return removed[0];
    }

    /** System-prompt block (both files + truncation markers). */
    public String renderForPrompt() {
        String mem = truncate(readMemory(), HARD_CAP);
        String user = truncate(readUser(), HARD_CAP);
        String lessons = truncate(readLessons(), HARD_CAP);
        if (mem.isBlank() && user.isBlank() && lessons.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== CORE MEMORY (auto-injected, max ").append(HARD_CAP).append(" chars each) ===\n");
        if (!user.isBlank()) {
            sb.append("-- USER.md --\n").append(user).append('\n');
        }
        if (!mem.isBlank()) {
            sb.append("-- MEMORY.md --\n").append(mem).append('\n');
        }
        if (!lessons.isBlank()) {
            sb.append("-- LESSONS.md (failures to avoid) --\n").append(lessons).append('\n');
        }
        sb.append("=== end core memory ===\n");
        return sb.toString();
    }

    /** Append a timestamped lesson line to LESSONS.md, pruning to keep file under cap. */
    public synchronized void appendLesson(String lesson) throws IOException {
        // Short-window dedup: same lesson prefix within DEDUP_WINDOW_MS is silently dropped so
        // identical failures across adjacent threads / retries don't crowd out genuinely
        // distinct lessons under the LRU prune. Trim+normalise whitespace before keying so
        // cosmetic differences (extra spaces, newlines) don't defeat the check.
        String normalised = lesson == null ? "" : lesson.replace('\n', ' ').strip();
        if (normalised.isEmpty()) {
            return;
        }
        String key = normalised.length() > DEDUP_KEY_PREFIX ? normalised.substring(0, DEDUP_KEY_PREFIX) : normalised;
        long now = System.currentTimeMillis();
        Long last = recentLessonKeys.get(key);
        if (last != null && now - last < DEDUP_WINDOW_MS) {
            log.debug("appendLesson: dropping duplicate within {}ms window (key=`{}`)", DEDUP_WINDOW_MS,
                key.length() > 40 ? key.substring(0, 40) + "…" : key);
            return;
        }
        recentLessonKeys.put(key, now);
        // UTC for cross-subsystem consistency (LlmAuditLog already uses UTC). A JVM-default
        // zone here would mean LESSONS.md timestamps could disagree with the audit log by a
        // calendar day when the operator runs in a non-UTC tz.
        String ts = LocalDate.now(ZoneOffset.UTC).toString();
        String line = "- [" + ts + "] " + normalised + "\n";
        withFileLock(lessonsFile, () -> {
            String cur = readNoLock(lessonsFile);
            String next = cur + line;
            // Prune oldest bullets from the top until under 2x HARD_CAP (keeps prompt slot bounded).
            // Shares pruneOldestBullets with appendFact so a single oversized lesson is dropped too.
            next = pruneOldestBullets(next, HARD_CAP * 2);
            write(lessonsFile, next);
        });
    }

    /**
     * B3: cross-process file locking. `synchronized` only blocks JVM-internal threads; if multiple JVMs (or future
     * workers) share the data dir they would race. FileLock makes RMW atomic at the OS level.
     */
    @FunctionalInterface
    private interface IoOp {
        void run() throws IOException;
    }

    private static void withFileLock(Path p, IoOp op) throws IOException {
        // Lock a sibling .lock file so we don't have to keep the data file open exclusively.
        Path lockPath = p.resolveSibling(p.getFileName() + ".lock");
        try (RandomAccessFile raf = new RandomAccessFile(lockPath.toFile(), "rw"); FileChannel ch = raf.getChannel()) {
            FileLock fl = null;
            try {
                // Bounded tryLock loop instead of indefinite ch.lock(): a stale lock from a
                // crashed sibling JVM (or another process holding the file) would otherwise
                // hang every memory-mutating tool call forever. 5s is generous for a write
                // that's just a few KB of markdown.
                long deadline = System.currentTimeMillis() + 5_000L;
                while (fl == null) {
                    fl = ch.tryLock();
                    if (fl != null)
                        break;
                    if (System.currentTimeMillis() >= deadline) {
                        // Graceful degradation: the contended write is forensic memory (a fact /
                        // lesson). Failing the whole agent turn because we couldn't acquire a
                        // file lock for 5 s is hostile to the user — the conversation result
                        // matters far more than a single missed memory entry. Log at ERROR (not
                        // WARN) so operators actually see the dropped write in production logs;
                        // a 5-second contended file lock is never normal and indicates either a
                        // crashed sibling JVM holding stale lock state or a runaway writer.
                        droppedWrites.incrementAndGet();
                        LoggerFactory.getLogger(CoreMemory.class).error(
                            "CoreMemory: DROPPED write to {} — lock held by another writer for 5s "
                                + "(total dropped this JVM: {}). Investigate stuck/crashed memory writer.",
                            lockPath.getFileName(), droppedWrites.get());
                        return;
                    }
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        // Same logic on interrupt: log and skip rather than propagate.
                        LoggerFactory.getLogger(CoreMemory.class).debug(
                            "CoreMemory: interrupted while awaiting lock on {} — skipping write",
                            lockPath.getFileName());
                        return;
                    }
                }
                op.run();
            } finally {
                if (fl != null) {
                    try {
                        fl.release();
                    } catch (IOException ignored) {
                        /* lock cleanup best-effort */ }
                }
            }
        }
    }

    /** Internal read used inside an existing file-lock; avoid double-locking. */
    private static String readNoLock(Path p) {
        return read(p);
    }

    // --- helpers ---
    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(Path p, String s) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, s, StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static int size(Path p) {
        return read(p).length();
    }

    private static String truncate(String s, int cap) {
        if (s == null) {
            return "";
        }
        if (s.length() <= cap) {
            return s;
        }
        return s.substring(0, cap) + "\n…[TRUNCATED — curate this file]\n";
    }

    public Path memoryFile() {
        return memoryFile;
    }

    public Path userFile() {
        return userFile;
    }

    /** Exposed for tests / status endpoints \u2014 the LESSONS.md path lives in dataDir alongside MEMORY.md. */
    public Path lessonsFile() {
        return lessonsFile;
    }
}
