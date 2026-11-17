package com.harnex.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.harnex.config.ProjectRuntime;
import com.harnex.snapshot.SnapshotService;
import com.harnex.snapshot.SnapshotService.FileSnapshotEntry;
import com.harnex.snapshot.SnapshotService.RunFileDiff;
import com.harnex.snapshot.SnapshotService.Snapshot;
import com.harnex.tool.builtin.support.FileDiffRenderer.FileDiff;
import com.harnex.process.ChildProcessEnvironment;
import com.harnex.workspace.git.ReadOnlyGit;

import lombok.extern.slf4j.Slf4j;

/**
 * Workspace-only read model for Explorer and Changes. Unlike model-facing file tools this API never permits outside
 * paths and never follows symlinks, because every response is rendered as if it belonged to the active workspace.
 */
@Slf4j
@Service
@ProjectRuntime
public class WorkspaceReadService {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int PREVIEW_LIMIT = 256 * 1024;
    private static final int SEARCH_FILE_LIMIT = 20_000;
    private static final int SEARCH_CONTENT_LIMIT = 1024 * 1024;
    private static final int DIFF_LIMIT = 1024 * 1024;
    private static final long GIT_TIMEOUT_MS = 5_000L;

    private final Path root;
    private final Path realRoot;
    private final Path dataDir;
    private final SnapshotService snapshots;

    public WorkspaceReadService(WorkspacePathResolver paths, SnapshotService snapshots) {
        this.dataDir = paths.getDataDir().toAbsolutePath().normalize();
        try {
            this.realRoot = paths.getProjectRoot().toRealPath();
            this.root = realRoot;
        } catch (IOException e) {
            throw new IllegalStateException("workspace root cannot be resolved: " + paths.getProjectRoot(), e);
        }
        this.snapshots = snapshots;
    }

    public Map<String, Object> listFiles(String relativePath, String cursor, int requestedLimit) throws IOException {
        String normalized = normalizeRelative(relativePath);
        Path directory = resolveExisting(normalized);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path is not a directory");
        }
        int limit = Math.max(1, Math.min(MAX_PAGE_SIZE,
            requestedLimit <= 0 ? DEFAULT_PAGE_SIZE : requestedLimit));
        int offset = decodeCursor(cursor, normalized);
        Map<String, String> git = gitStatuses();
        List<Map<String, Object>> all = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> !Files.isSymbolicLink(path) && !isExcluded(path)).forEach(path -> {
                try {
                    BasicFileAttributes attrs =
                        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    Map<String, Object> node = new LinkedHashMap<>();
                    String rel = relative(path);
                    node.put("path", rel);
                    node.put("name", path.getFileName().toString());
                    node.put("type", attrs.isDirectory() ? "directory" : "file");
                    if (attrs.isRegularFile()) {
                        node.put("size", attrs.size());
                    }
                    node.put("modifiedAt", attrs.lastModifiedTime().toMillis());
                    if (git.containsKey(rel)) {
                        node.put("gitStatus", git.get(rel));
                    } else if (attrs.isDirectory()) {
                        String prefix = rel.isBlank() ? "" : rel + "/";
                        git.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).findFirst()
                            .ifPresent(e -> node.put("gitStatus", "changed"));
                    }
                    all.add(node);
                } catch (IOException ignored) {
                    // A file may disappear during listing; the next refresh observes the stable state.
                }
            });
        }
        all.sort(Comparator
            .comparing((Map<String, Object> n) -> !"directory".equals(n.get("type")))
            .thenComparing(n -> String.valueOf(n.get("name")).toLowerCase(Locale.ROOT)));
        int from = Math.min(offset, all.size());
        int to = Math.min(all.size(), from + limit);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("path", normalized);
        page.put("entries", List.copyOf(all.subList(from, to)));
        if (to < all.size()) {
            page.put("nextCursor", encodeCursor(normalized, to));
        }
        return page;
    }

    public record FileDownload(String name, long size, InputStream input) implements AutoCloseable {
        @Override public void close() throws IOException { input.close(); }
    }

    /** Full original bytes, under the same workspace boundary as listing and preview. */
    public FileDownload download(String relativePath) throws IOException {
        Path file = resolveExisting(relativePath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path is not a regular file");
        }
        long size = Files.size(file);
        return new FileDownload(file.getFileName().toString(), size,
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS));
    }

    public Map<String, Object> preview(String relativePath) throws IOException {
        Path file = resolveExisting(relativePath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path is not a regular file");
        }
        long size = Files.size(file);
        byte[] bytes;
        try (var in = Files.newInputStream(file)) {
            bytes = in.readNBytes(PREVIEW_LIMIT + 1);
        }
        boolean truncated = bytes.length > PREVIEW_LIMIT;
        if (truncated) {
            bytes = Arrays.copyOf(bytes, PREVIEW_LIMIT);
        }
        boolean binary = isBinary(bytes);
        Map<String, Object> out = new LinkedHashMap<>();
        String relative = relative(file);
        out.put("path", relative);
        out.put("mime", contentType(file));
        out.put("size", size);
        out.put("modifiedAt", Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis());
        String gitStatus = gitStatuses().get(relative);
        if (gitStatus != null) {
            out.put("gitStatus", gitStatus);
        }
        out.put("binary", binary);
        out.put("truncated", truncated);
        if (!binary) {
            out.put("content", decodeUtf8(bytes));
        }
        return out;
    }

    public Map<String, Object> search(String query, boolean includeContent, String cursor, int requestedLimit)
        throws IOException {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isBlank()) {
            throw new IllegalArgumentException("q is required");
        }
        int limit = Math.max(1, Math.min(MAX_PAGE_SIZE,
            requestedLimit <= 0 ? 50 : requestedLimit));
        int offset = decodeCursor(cursor, "search:" + needle + ":" + includeContent);
        List<Map<String, Object>> matches = new ArrayList<>();
        int[] visited = {0};
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 32,
            new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (!directory.equals(root) && (Files.isSymbolicLink(directory) || isExcluded(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                if (visited[0]++ >= SEARCH_FILE_LIMIT) {
                    return FileVisitResult.TERMINATE;
                }
                if (!attrs.isRegularFile() || Files.isSymbolicLink(path) || isExcluded(path)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = relative(path);
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                String snippet = includeContent ? contentMatchSnippet(path, needle) : "";
                boolean nameMatch = !includeContent && (name.contains(needle) || rel.toLowerCase(Locale.ROOT).contains(needle));
                if (nameMatch || includeContent && !snippet.isBlank()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", rel);
                    item.put("name", path.getFileName().toString());
                    item.put("match", nameMatch ? "name" : "content");
                    if (!snippet.isBlank()) {
                        item.put("snippet", snippet);
                    }
                    matches.add(item);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        matches.sort(Comparator.comparing(m -> String.valueOf(m.get("path"))));
        int from = Math.min(offset, matches.size());
        int to = Math.min(matches.size(), from + limit);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("query", query.trim());
        page.put("entries", List.copyOf(matches.subList(from, to)));
        page.put("scannedFiles", visited[0]);
        page.put("truncatedScan", visited[0] >= SEARCH_FILE_LIMIT);
        if (to < matches.size()) {
            page.put("nextCursor", encodeCursor("search:" + needle + ":" + includeContent, to));
        }
        return page;
    }

    public Map<String, Object> changes(String runId, String threadId) {
        Map<String, FileSnapshotEntry> baselines = new LinkedHashMap<>();
        Map<String, Long> baselineSeq = new HashMap<>();
        Snapshot baseline = null;
        if (threadId != null && !threadId.isBlank()) {
            List<Snapshot> runSnapshots = snapshots.list(threadId);
            for (Snapshot snapshot : runSnapshots == null ? List.<Snapshot>of() : runSnapshots) {
                if (!String.valueOf(runId).equals(snapshot.runId)) {
                    continue;
                }
                if ("run_start".equals(snapshot.kind)) {
                    baseline = snapshot;
                }
                for (FileSnapshotEntry entry : snapshot.fileEntries == null
                    ? List.<FileSnapshotEntry>of() : snapshot.fileEntries) {
                    if ("workspace".equals(entry.scope) && entry.path != null && !entry.path.isBlank()) {
                        String path = entry.path.replace('\\', '/');
                        long seq = snapshot.seq;
                        if (!baselineSeq.containsKey(path) || seq < baselineSeq.get(path)) {
                            baselineSeq.put(path, seq);
                            baselines.put(path, entry);
                        }
                    }
                }
            }
        }
        boolean gitManaged = isGitManaged();
        Map<String, String> status = gitStatuses();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, FileSnapshotEntry> candidate : baselines.entrySet()) {
            String path = candidate.getKey();
            String gitStatus = status.get(path);
            FileSnapshotEntry snapshotEntry = candidate.getValue();
            String snapshotStatus = snapshotEntry == null ? null : snapshotEntry.changeType;
            RunFileDiff runDiff = null;
            try {
                runDiff = snapshots.diffFromRunBaseline(threadId, snapshotEntry, resolvePotential(path));
            } catch (IOException | RuntimeException ignored) {
                // A skipped/oversized capture remains visible with an unavailable diff.
            }
            if (runDiff != null && runDiff.beforeExists() == runDiff.afterExists() && runDiff.diff().unchanged) {
                continue;
            }
            if (runDiff == null && (snapshotEntry == null
                || "unchanged".equals(snapshotStatus) || "skipped".equals(snapshotStatus))) continue;

            Map<String, Object> change = new LinkedHashMap<>();
            change.put("path", path);
            String taskStatus = runDiff == null ? displaySnapshotStatus(snapshotStatus)
                : !runDiff.beforeExists() ? "added" : !runDiff.afterExists() ? "deleted" : "modified";
            change.put("status", taskStatus);
            if (gitStatus != null) {
                change.put("gitStatus", gitStatus);
            }
            change.put("origin", "this_run");
            FileDiff rendered = runDiff == null ? null : runDiff.diff();
            change.put("diffAvailable", rendered != null ? !rendered.binary
                : snapshotEntry != null && snapshotEntry.diffAvailable);
            long[] counts = rendered != null
                ? new long[] {rendered.added, rendered.removed}
                : new long[] {
                    snapshotEntry.linesAdded == null ? 0 : snapshotEntry.linesAdded,
                    snapshotEntry.linesDeleted == null ? 0 : snapshotEntry.linesDeleted,
                };
            change.put("added", counts[0]);
            change.put("removed", counts[1]);
            entries.add(change);
        }
        entries.sort(Comparator.comparing(e -> String.valueOf(e.get("path"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId == null ? "" : runId);
        out.put("entries", entries);
        out.put("thisRun", entries);
        // Kept as an empty compatibility field for older clients. The review surface is
        // intentionally task-scoped and never exposes unrelated pre-existing changes.
        out.put("existing", List.of());
        out.put("repositoryType", gitManaged ? "git" : "filesystem");
        if (baseline != null) {
            out.put("baseline", Map.of("snapshotSeq", baseline.seq, "takenAt",
                baseline.takenAt == null ? "" : baseline.takenAt, "kind", baseline.kind));
        }
        return out;
    }

    public Map<String, Object> diff(String runId, String threadId, String relativePath) throws IOException {
        Path file = resolvePotential(relativePath);
        String rel = relative(file);
        FileSnapshotEntry baseline = null;
        long baselineSeq = Long.MAX_VALUE;
        for (Snapshot snapshot : snapshots.list(threadId)) {
            if (!String.valueOf(runId).equals(snapshot.runId)) continue;
            for (FileSnapshotEntry entry : snapshot.fileEntries == null
                ? List.<FileSnapshotEntry>of() : snapshot.fileEntries) {
                if ("workspace".equals(entry.scope) && rel.equals(String.valueOf(entry.path).replace('\\', '/'))
                    && snapshot.seq < baselineSeq) {
                    baseline = entry;
                    baselineSeq = snapshot.seq;
                }
            }
        }
        if (baseline == null) throw new IllegalArgumentException("path was not changed by this run");
        RunFileDiff runDiff = snapshots.diffFromRunBaseline(threadId, baseline, file);
        FileDiff rendered = runDiff.diff();
        String unified = rendered.unifiedDiff == null ? "" : rendered.unifiedDiff;
        boolean truncated = unified.length() > DIFF_LIMIT;
        if (truncated) unified = unified.substring(0, DIFF_LIMIT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("path", rel);
        out.put("gitManaged", isGitManaged());
        out.put("available", !rendered.binary);
        out.put("binary", rendered.binary);
        out.put("truncated", truncated);
        out.put("unifiedDiff", unified);
        out.put("added", rendered.added);
        out.put("removed", rendered.removed);
        return out;
    }

    public Map<String, Object> openExternal(String relativePath) throws IOException {
        Path target = resolveExisting(relativePath);
        openWithSystem(target, "external open");
        return Map.of("opened", true, "path", relative(target));
    }

    /** Open the directory containing a workspace file (or the directory itself when one is selected). */
    public Map<String, Object> openContainingDirectory(String relativePath) throws IOException {
        Path target = containingDirectory(relativePath);
        openWithSystem(target, "opening the containing directory");
        return Map.of("opened", true, "path", relativePath, "directory", relative(target));
    }

    public static void openWithSystem(Path target, String action) {
        List<String> command = systemOpenCommand(target, System.getProperty("os.name", ""));
        try {
            ChildProcessEnvironment.start(new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD));
        } catch (IOException e) {
            throw new UnsupportedOperationException(action + " is unavailable in this runtime", e);
        }
    }

    static List<String> systemOpenCommand(Path target, String osName) {
        String platform = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (platform.contains("win")) {
            return List.of("explorer.exe", target.toString());
        }
        if (platform.contains("mac")) {
            return List.of("open", target.toString());
        }
        if (platform.contains("linux") || platform.contains("unix") || platform.contains("bsd")) {
            return List.of("xdg-open", target.toString());
        }
        throw new UnsupportedOperationException("opening paths is unavailable on " + osName);
    }

    Path containingDirectory(String relativePath) throws IOException {
        Path target = resolveExisting(relativePath);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return target;
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("containing directory is unavailable");
        }
        return parent;
    }

    private Path resolveExisting(String value) throws IOException {
        Path candidate = resolvePotential(value);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("path not found");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(realRoot)) {
            throw new SecurityException("path resolves outside the active workspace");
        }
        return candidate;
    }

    private Path resolvePotential(String value) {
        String normalized = normalizeRelative(value);
        Path relative = Path.of(normalized);
        if (relative.isAbsolute()) {
            throw new SecurityException("absolute paths are not allowed");
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new SecurityException("path escapes the active workspace");
        }
        if (!candidate.equals(root) && isExcluded(candidate)) {
            throw new SecurityException("path is private or excluded from the workspace read API");
        }
        Path cursor = root;
        for (Path segment : root.relativize(candidate)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new SecurityException("symlinks are not exposed by the workspace read API");
            }
        }
        return candidate;
    }

    private static String normalizeRelative(String value) {
        if (value == null || value.isBlank() || ".".equals(value)) {
            return "";
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("absolute paths are not allowed");
        }
        return normalized;
    }

    private String relative(Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private Map<String, String> gitStatuses() {
        GitResult result = git("status", "--porcelain=v1", "-z", "--untracked-files=all");
        Map<String, String> out = new HashMap<>();
        if (!result.ok) {
            return out;
        }
        List<String> records = splitNul(result.stdout);
        for (int i = 0; i < records.size(); i++) {
            String record = records.get(i);
            if (record.length() < 4) {
                continue;
            }
            String code = record.substring(0, 2);
            String path = record.substring(3).replace('\\', '/');
            if ((code.indexOf('R') >= 0 || code.indexOf('C') >= 0) && i + 1 < records.size()) {
                i++;
            }
            out.put(path, code);
        }
        return out;
    }

    private boolean isGitManaged() {
        GitResult result = git("rev-parse", "--is-inside-work-tree");
        return result.ok && "true".equalsIgnoreCase(result.text().trim());
    }

    private GitResult git(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(root.toString());
            command.addAll(List.of(args));
            Process process = ChildProcessEnvironment.start(
                ReadOnlyGit.prepare(new ProcessBuilder(command).redirectErrorStream(true)));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Thread drainer = new Thread(() -> {
                try (var in = process.getInputStream()) {
                    in.transferTo(bytes);
                } catch (IOException ignored) {
                }
            }, "workspace-read-git");
            drainer.setDaemon(true);
            drainer.start();
            if (!process.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new GitResult(false, new byte[0]);
            }
            drainer.join(250L);
            return new GitResult(process.exitValue() == 0, bytes.toByteArray());
        } catch (Exception e) {
            log.debug("workspace git read failed: {}", e.toString());
            return new GitResult(false, new byte[0]);
        }
    }

    private String contentMatchSnippet(Path path, String needle) {
        try {
            if (Files.size(path) > SEARCH_CONTENT_LIMIT) {
                return "";
            }
            byte[] bytes = Files.readAllBytes(path);
            if (isBinary(bytes)) {
                return "";
            }
            String text = decodeUtf8(bytes);
            int index = text.toLowerCase(Locale.ROOT).indexOf(needle);
            if (index < 0) {
                return "";
            }
            int start = Math.max(0, index - 80);
            int end = Math.min(text.length(), index + needle.length() + 120);
            return text.substring(start, end).replaceAll("\\s+", " ").trim();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private boolean isExcluded(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(dataDir)) {
            return true;
        }
        Path rel = root.relativize(normalized);
        if (rel.getNameCount() == 0) {
            return false;
        }
        String first = rel.getName(0).toString();
        if (".harnex".equals(first)) {
            return rel.getNameCount() == 1 ? !Files.isDirectory(normalized.resolve("skills"))
                : !"skills".equals(rel.getName(1).toString());
        }
        return Set.of(".git", "node_modules", "target", "dist", ".idea").contains(first);
    }

    private static int decodeCursor(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int split = decoded.lastIndexOf(':');
            if (split < 0 || !scope.equals(decoded.substring(0, split))) {
                throw new IllegalArgumentException("cursor does not belong to this query");
            }
            return Math.max(0, Integer.parseInt(decoded.substring(split + 1)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid cursor", e);
        }
    }

    private static String encodeCursor(String scope, int offset) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((scope + ":" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBinary(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        try {
            decodeUtf8(bytes);
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("content is not UTF-8 text", e);
        }
    }

    private static String contentType(Path file) {
        try {
            String type = Files.probeContentType(file);
            return type == null ? "application/octet-stream" : type;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static String displaySnapshotStatus(String status) {
        if ("created".equals(status)) {
            return "added";
        }
        if ("deleted".equals(status)) {
            return "deleted";
        }
        return "modified";
    }

    private static List<String> splitNul(byte[] bytes) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                out.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        return out;
    }

    private record GitResult(boolean ok, byte[] stdout) {
        String text() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }
}
