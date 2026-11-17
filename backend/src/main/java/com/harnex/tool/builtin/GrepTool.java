package com.harnex.tool.builtin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.ProjectRuntime;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;
import com.harnex.process.ChildProcessEnvironment;
import com.harnex.workspace.search.RipgrepExecutable;

/** Native ripgrep matching, with Harnex's access-controlled candidate selection. */
@Component
@ProjectRuntime
public class GrepTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FILE_LIMIT = 1000;
    private static final int DEFAULT_LINE_LIMIT = 1000;
    private static final int MAX_LINE_LIMIT = 5000;
    private static final int LINE_TRUNC_AT = 4000;
    private static final int MAX_REGEX_LEN = 500;
    private static final long GLOBAL_DEADLINE_MS = 8_000L;
    // Stay well below Windows' 32K UTF-16 command-line limit, including quoting.
    private static final int BATCH_CHARS = 24_000;
    private static final int MAX_JSON_RECORD = 16 * 1024 * 1024;

    private final WorkspacePathResolver vp;
    private final String executable;
    private final long timeoutMs;

    @Autowired
    public GrepTool(WorkspacePathResolver vp) {
        this(vp, RipgrepExecutable.resolve(), GLOBAL_DEADLINE_MS);
    }

    GrepTool(WorkspacePathResolver vp, String executable, long timeoutMs) {
        this.vp = vp;
        this.executable = executable;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String getName() {
        return "grep";
    }

    @Override
    public String getDescription() {
        return "Regex-search workspace files using ripgrep's default Rust regex engine "
            + "(no look-around or backreferences). Searches allowed files, including hidden/gitignored files, "
            + "except binary content. Use include with a literal file or directory prefix to narrow the search. "
            + "Dependency/build directories are skipped unless explicitly named in that prefix. "
            + "Workspace search exclusions and private-data restrictions always apply.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_FILESYSTEM,
            ToolCapability.PLAN_MODE_SAFE, ToolCapability.CONCURRENCY_SAFE);
    }

    @Override
    public int maxParallel() {
        return 8;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties",
                Map.of("regex", Map.of("type", "string"), "include",
                    Map.of("type", "string", "description", "optional glob e.g. **/*.java"), "offset",
                    Map.of("type", "integer", "description", "Skip first N matching lines (default 0)."), "limit",
                    Map.of("type", "integer", "description",
                        "Max matching lines (default " + DEFAULT_LINE_LIMIT + ", max " + MAX_LINE_LIMIT + ").")),
                "required", new String[] {"regex"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        if (input.containsKey("path")) {
            return error("Unknown argument 'path': use 'include' with a workspace-relative file or glob pattern.");
        }
        if (executable == null) {
            return error("The Runtime search component is missing. Update or rebuild the Harnex Runtime Bundle and reopen the project.");
        }
        String regex = String.valueOf(input.getOrDefault("regex", ""));
        if (regex.length() > MAX_REGEX_LEN) {
            return error("regex too long (" + regex.length() + " > " + MAX_REGEX_LEN + " chars); narrow your pattern.");
        }
        WorkspaceFileSearch files;
        try {
            String glob = String.valueOf(input.getOrDefault("include", "**/*"));
            files = new WorkspaceFileSearch(vp, glob);
        } catch (IllegalArgumentException e) {
            return error("bad include glob: " + e.getMessage());
        }
        int offset = Math.max(0, toInt(input.get("offset"), 0));
        int limit = Math.min(MAX_LINE_LIMIT, Math.max(1, toInt(input.get("limit"), DEFAULT_LINE_LIMIT)));
        Search state = new Search(offset, limit);
        ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "harnex-ripgrep");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> work = workers.submit(() -> {
            try {
                search(regex, files, ctx.getThread().getId(), state, workers);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        try {
            work.get(timeoutMs, TimeUnit.MILLISECONDS);
            return state.result(null);
        } catch (TimeoutException e) {
            state.stop();
            return state.result("…[truncated at " + timeoutMs + "ms global deadline — simplify the pattern or narrow `include`]");
        } catch (InterruptedException e) {
            state.stop();
            Thread.currentThread().interrupt();
            return state.result("…[search cancelled]");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            return error(cause.getMessage());
        } finally {
            state.stop();
            work.cancel(true);
            workers.shutdownNow();
        }
    }

    private void search(String regex, WorkspaceFileSearch files, String threadId, Search state, ExecutorService workers)
        throws Exception {
        Path root = vp.workspaceFor(threadId);
        List<String> candidates = new ArrayList<>();
        files.walk(threadId, () -> state.stopped, path -> {
            if (candidates.size() == FILE_LIMIT) { state.fileCap = true; return false; }
            candidates.add(root.relativize(path).toString().replace('\\', '/'));
            return true;
        });
        Collections.sort(candidates);
        List<String> batch = new ArrayList<>();
        int size = executable.length() + regex.length() * 2 + 512;
        for (String candidate : candidates) {
            if (state.stopped || state.lineCap) return;
            if (!batch.isEmpty() && size + candidate.length() * 2 + 4 > BATCH_CHARS) {
                runBatch(regex, root, batch, threadId, state, workers);
                batch.clear();
                size = executable.length() + regex.length() * 2 + 512;
            }
            batch.add(candidate);
            size += candidate.length() * 2 + 4;
        }
        // Even an empty workspace must validate the regex and report a missing rg dependency.
        if (!state.stopped && !state.lineCap) runBatch(regex, root, batch, threadId, state, workers);
    }

    private void runBatch(String regex, Path root, List<String> files, String threadId, Search state,
        ExecutorService workers) throws Exception {
        List<String> command = new ArrayList<>(List.of(executable, "--no-config", "--engine", "default", "--json",
            "--color", "never", "--threads", "1", "--no-mmap", "--max-count",
            Long.toString((long) state.offset + state.limit + 1), "--regexp", regex, "--"));
        for (String file : files) {
            Path path = root.resolve(file);
            // Recheck at the subprocess boundary; never hand rg directories or symlinks to traverse.
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && vp.classify(path, threadId) != WorkspacePathResolver.Zone.RED) command.add("./" + file);
        }
        if (command.get(command.size() - 1).equals("--")) command.add("-");
        Process process;
        synchronized (state) {
            if (state.stopped || state.lineCap) return;
            try {
                process = startProcess(new ProcessBuilder(command).directory(root.toFile()));
            } catch (IOException e) {
                throw new IOException("Cannot start the Runtime search component. Update or rebuild the Harnex Runtime Bundle and reopen the project. "
                    + e.getMessage(), e);
            }
            state.process = process;
        }
        try {
            process.getOutputStream().close();
            Future<String> stderr = workers.submit(() -> {
                try (Reader reader = process.errorReader(StandardCharsets.UTF_8)) {
                    StringBuilder text = new StringBuilder();
                    char[] buffer = new char[2048];
                    int count;
                    while ((count = reader.read(buffer)) >= 0) {
                        int keep = Math.min(count, Math.max(0, 8000 - text.length()));
                        text.append(buffer, 0, keep);
                    }
                    return text.toString();
                }
            });
            try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
                List<String> pending = new ArrayList<>();
                long fileMatched = 0;
                String record;
                while (!state.stopped && (record = readRecord(reader)) != null) {
                    JsonNode event = JSON.readTree(record);
                    JsonNode data = event.path("data");
                    String type = event.path("type").asText();
                    if ("end".equals(type)) {
                        // rg may emit matches before reporting that a file is binary. Publish only
                        // after the end event; --max-count bounds the work for heavily matching files.
                        if (data.path("binary_offset").isNull()) state.commit(pending, fileMatched);
                        pending.clear();
                        fileMatched = 0;
                        if (state.lineCap) break;
                        continue;
                    }
                    if (!"match".equals(type)) continue;
                    long rank = state.matched + fileMatched++;
                    if (rank < state.offset || pending.size() > state.limit - state.lines.size()) continue;
                    String path = decode(data.path("path"));
                    String line = decode(data.path("lines"));
                    if (line.indexOf('\0') >= 0) continue;
                    if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    if (line.length() > LINE_TRUNC_AT) line = line.substring(0, LINE_TRUNC_AT) + "…[line truncated]";
                    path = path.replace('\\', '/');
                    if (path.startsWith("./")) path = path.substring(2);
                    pending.add(path + ":" + data.path("line_number").asLong() + ": " + line);
                }
            }
            if (state.stopped || state.lineCap) return;
            int exit = process.waitFor();
            String diagnostics = stderr.get();
            if (exit > 1) throw new IOException("ripgrep failed (bad regex or file search error, exit " + exit + "): " + diagnostics);
        } finally {
            terminate(process);
            synchronized (state) {
                if (state.process == process) state.process = null;
            }
        }
    }

    Process startProcess(ProcessBuilder builder) throws IOException {
        return ChildProcessEnvironment.start(builder);
    }

    private static void terminate(Process process) {
        process.destroyForcibly();
        // On Windows the timed wait may return as soon as the exit code is set,
        // before the process handle is signalled and cwd/file handles are released.
        // Reap the forcibly stopped process even if cancellation interrupts cleanup.
        boolean interrupted = Thread.interrupted();
        try {
            for (;;) {
                try {
                    process.waitFor();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Bound individual JSON records, including huge lines and submatch arrays. */
    private static String readRecord(Reader reader) throws IOException {
        StringBuilder record = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1 && c != '\n') {
            if (record.length() == MAX_JSON_RECORD) throw new IOException("ripgrep match record too large; narrow `include`.");
            record.append((char) c);
        }
        return c == -1 && record.isEmpty() ? null : record.toString();
    }

    private static String decode(JsonNode value) {
        return value.has("text") ? value.get("text").asText()
            : new String(Base64.getDecoder().decode(value.path("bytes").asText()), StandardCharsets.UTF_8);
    }

    private static ToolResult error(String message) {
        return ToolResult.builder().error(true).content(message).build();
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class Search {
        final int offset;
        final int limit;
        final List<String> lines = new ArrayList<>();
        long matched;
        volatile boolean stopped;
        volatile boolean fileCap;
        volatile boolean lineCap;
        Process process;

        Search(int offset, int limit) {
            this.offset = offset;
            this.limit = limit;
        }

        synchronized void commit(List<String> pending, long fileMatched) {
            if (stopped) return;
            matched += fileMatched;
            for (String line : pending) {
                if (lines.size() == limit) {
                    lineCap = true;
                    break;
                }
                lines.add(line);
            }
        }

        synchronized void stop() {
            stopped = true;
            if (process != null) terminate(process);
        }

        synchronized ToolResult result(String footer) {
            StringBuilder out = new StringBuilder();
            for (String line : lines) out.append(line).append('\n');
            if (lineCap) out.append("…[truncated at ").append(limit)
                .append(" matching lines — call again with offset=").append((long) offset + limit).append("]\n");
            if (fileCap) out.append("…[truncated at ").append(FILE_LIMIT).append(" candidate files — narrow `include`]\n");
            if (footer != null) out.append(footer).append('\n');
            return ToolResult.builder().content(out.toString()).build();
        }
    }
}
