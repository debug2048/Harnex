package com.harnex.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.model.ToolCall;
import com.harnex.tool.ToolResult;

/**
 * Per-run no-progress detector. It recognises recurring tool work inside a short sliding window, so alternating reads
 * of the same small working set cannot evade protection. The first recurrence asks the model for a continuation
 * checkpoint and then resumes tools; only a second recurrence after that checkpoint requests no-tool finalisation.
 */
final class NoProgressGuard {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int RECURRENCE_THRESHOLD = 3;
    static final int RECURRENCE_WINDOW = 8;
    static final int READ_ONLY_CHECKPOINT_BATCHES = 12;
    static final int OVERLAPPING_READ_THRESHOLD = 3;
    private static final int DEFAULT_LINE_LIMIT = 2_000;
    private static final Set<String> CONTENT_INSENSITIVE_READS =
        Set.of("file_read", "grep", "glob", "todo_read", "memory_read");
    private static final Set<String> PROGRESS_TOOLS = Set.of("file_edit", "file_write", "multi_edit", "apply_patch",
        "todo_write", "create_goal", "update_goal", "delegate", "submit_plan");

    enum Action {
        NONE, CHECKPOINT, FINALIZE
    }

    record Observation(Action action, int repetitions) {
        static Observation none(int repetitions) {
            return new Observation(Action.NONE, repetitions);
        }
    }

    private final Deque<String> recentSignatures = new ArrayDeque<>();
    private final Map<String, ReadCoverage> readCoverage = new LinkedHashMap<>();
    private boolean checkpointCompleted;
    private int consecutiveReadOnlyBatches;
    private int overlappingReads;

    Observation observe(List<ToolCall> calls, List<ToolResult> results) {
        if (calls == null || calls.isEmpty() || results == null || results.isEmpty()) {
            return Observation.none(0);
        }
        if (containsMaterialProgress(calls, results)) {
            resetProgressEpoch();
            return Observation.none(0);
        }
        String signature = signature(calls, results);
        recentSignatures.addLast(signature);
        while (recentSignatures.size() > RECURRENCE_WINDOW) recentSignatures.removeFirst();
        int repetitions = 0;
        for (String prior : recentSignatures) {
            if (signature.equals(prior)) repetitions++;
        }
        overlappingReads += observeReadCoverage(calls, results);
        if (isReadOnlyBatch(calls)) consecutiveReadOnlyBatches++;
        else consecutiveReadOnlyBatches = 0;

        if (repetitions >= RECURRENCE_THRESHOLD || overlappingReads >= OVERLAPPING_READ_THRESHOLD) {
            int evidence = Math.max(repetitions, overlappingReads);
            clearShortTermSignals();
            return new Observation(checkpointCompleted ? Action.FINALIZE : Action.CHECKPOINT, evidence);
        }
        // Breadth-first exploration can avoid exact recurrence indefinitely by changing paths, ranges or regexes.
        // Periodically force a synthesis checkpoint, but never finalise on breadth alone: a legitimate large audit may
        // need many distinct reads. Finalisation still requires actual recurrence after a completed checkpoint.
        if (consecutiveReadOnlyBatches >= READ_ONLY_CHECKPOINT_BATCHES) {
            clearShortTermSignals();
            return new Observation(Action.CHECKPOINT, READ_ONLY_CHECKPOINT_BATCHES);
        }
        return Observation.none(Math.max(repetitions, overlappingReads));
    }

    void checkpointCompleted() {
        checkpointCompleted = true;
        clearShortTermSignals();
    }

    /** New user steering starts a fresh progress epoch while thread-level compression/file caches remain untouched. */
    void steeringApplied() {
        resetProgressEpoch();
    }

    private void clearShortTermSignals() {
        recentSignatures.clear();
        consecutiveReadOnlyBatches = 0;
        overlappingReads = 0;
    }

    private void resetProgressEpoch() {
        clearShortTermSignals();
        readCoverage.clear();
        checkpointCompleted = false;
    }

    private static boolean containsMaterialProgress(List<ToolCall> calls, List<ToolResult> results) {
        int paired = Math.min(calls.size(), results.size());
        for (int i = 0; i < paired; i++) {
            ToolCall call = calls.get(i);
            ToolResult result = results.get(i);
            String name = call == null || call.getName() == null ? "" : call.getName().toLowerCase(Locale.ROOT);
            if (PROGRESS_TOOLS.contains(name) && result != null && !result.isError()) return true;
        }
        return false;
    }

    private static boolean isReadOnlyBatch(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return false;
        for (ToolCall call : calls) {
            String name = call == null || call.getName() == null ? "" : call.getName().toLowerCase(Locale.ROOT);
            if (!CONTENT_INSENSITIVE_READS.contains(name)) return false;
        }
        return true;
    }

    private int observeReadCoverage(List<ToolCall> calls, List<ToolResult> results) {
        int repeats = 0;
        int paired = Math.min(calls.size(), results.size());
        for (int index = 0; index < paired; index++) {
            ToolCall call = calls.get(index);
            ToolResult result = results.get(index);
            if (call == null || !"file_read".equalsIgnoreCase(call.getName()) || result == null || result.isError()) {
                continue;
            }
            ReadRequest request = readRequest(call.getArguments());
            if (request == null) continue;
            ReadCoverage coverage = readCoverage.computeIfAbsent(request.path(), ignored -> new ReadCoverage());
            String content = result.getLlmContent() != null ? result.getLlmContent() : result.getContent();
            if (coverage.observe(request, content)) repeats++;
        }
        return repeats;
    }

    private static ReadRequest readRequest(String arguments) {
        if (arguments == null || arguments.isBlank()) return null;
        try {
            var node = JSON.readTree(arguments);
            var pathNode = node.get("path");
            if (pathNode == null || !pathNode.isTextual() || pathNode.asText().isBlank()) return null;
            String path = pathNode.asText().replace('\\', '/');
            try {
                path = Path.of(path).normalize().toString().replace('\\', '/');
            } catch (RuntimeException ignored) {
                // The tool will report malformed paths; retaining the textual form is enough for loop detection.
            }
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                path = path.toLowerCase(Locale.ROOT);
            }
            boolean lineMode = node.has("offset") || node.has("limit");
            if (!lineMode) return new ReadRequest(path, 0, 0, true);
            long offset = node.has("offset") && node.get("offset").canConvertToLong()
                ? Math.max(1L, node.get("offset").asLong()) : 1L;
            long limit = node.has("limit") && node.get("limit").canConvertToLong()
                ? Math.max(1L, Math.min(DEFAULT_LINE_LIMIT, node.get("limit").asLong())) : DEFAULT_LINE_LIMIT;
            return new ReadRequest(path, offset, offset + limit, false);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ReadRequest(String path, long start, long endExclusive, boolean wholeFile) {}

    private static final class ReadCoverage {
        private final List<long[]> intervals = new ArrayList<>();
        private boolean wholeFile;

        boolean observe(ReadRequest request, String content) {
            if (request.wholeFile()) {
                boolean repeated = wholeFile;
                // A default byte read is a whole-file observation unless the tool explicitly reports truncation.
                if (content != null && !content.contains("[... truncated ")) wholeFile = true;
                return repeated;
            }
            if (wholeFile) return true;
            long length = Math.max(1L, request.endExclusive() - request.start());
            long covered = coveredLength(request.start(), request.endExclusive());
            add(request.start(), request.endExclusive());
            return covered * 4L >= length * 3L;
        }

        private long coveredLength(long start, long end) {
            long covered = 0L;
            for (long[] interval : intervals) {
                long overlapStart = Math.max(start, interval[0]);
                long overlapEnd = Math.min(end, interval[1]);
                if (overlapEnd > overlapStart) covered += overlapEnd - overlapStart;
            }
            return Math.min(end - start, covered);
        }

        private void add(long start, long end) {
            intervals.add(new long[] {start, end});
            intervals.sort(Comparator.comparingLong(interval -> interval[0]));
            List<long[]> merged = new ArrayList<>();
            for (long[] interval : intervals) {
                if (merged.isEmpty() || interval[0] > merged.get(merged.size() - 1)[1]) {
                    merged.add(new long[] {interval[0], interval[1]});
                } else {
                    long[] previous = merged.get(merged.size() - 1);
                    previous[1] = Math.max(previous[1], interval[1]);
                }
            }
            intervals.clear();
            intervals.addAll(merged);
        }
    }

    private static String signature(List<ToolCall> calls, List<ToolResult> results) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (call == null) continue;
            String name = call.getName() == null ? "" : call.getName().toLowerCase(Locale.ROOT);
            StringBuilder entry = new StringBuilder(name).append('\n').append(canonicalJson(call.getArguments()));
            ToolResult result = i < results.size() ? results.get(i) : null;
            entry.append('\n').append(result == null || result.isError() ? "error" : "ok");
            // Repeated reads/searches are the loop pattern we want to catch, even when the first result contains raw
            // content and later results are compact dedup markers. For polling/commands, changed output is progress
            // evidence and remains part of the signature.
            if (!CONTENT_INSENSITIVE_READS.contains(name) && result != null) {
                String content = result.getLlmContent() != null ? result.getLlmContent() : result.getContent();
                entry.append('\n').append(content == null ? "" : content);
            }
            entries.add(entry.toString());
        }
        Collections.sort(entries);
        String value = String.join("\n--call--\n", entries);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String canonicalJson(String arguments) {
        if (arguments == null || arguments.isBlank()) return "{}";
        try {
            return JSON.readTree(arguments).toString();
        } catch (Exception ignored) {
            return arguments.replaceAll("\\s+", " ").strip();
        }
    }
}
