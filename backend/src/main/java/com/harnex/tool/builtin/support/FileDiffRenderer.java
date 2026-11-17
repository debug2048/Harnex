package com.harnex.tool.builtin.support;

import java.util.*;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;

/**
 * Computes a structured + human-readable diff for file_edit / file_write tool results.
 *
 * <p>
 * Design notes:
 * <ul>
 * <li>Pure utility — no Spring, no IO. Callers pass the {@code before} / {@code after} text.</li>
 * <li>Bounded: refuses to diff content that looks binary or minified (lines too long, or NUL bytes present). Falls back
 * to a {@code "+N -M bytes"} summary so the LLM still gets a signal without 50 KB of unreadable hex re-entering the
 * context window.</li>
 * <li>Output size is capped: total diff body trimmed to {@link #MAX_DIFF_LINES} lines, plus an overall char cap of
 * {@link #MAX_DIFF_CHARS}. The {@code truncated} flag tells the UI to render a "view full diff" affordance (which can
 * fetch from the persisted tool_result blob when {@link com.harnex.tool.budget.BudgetEnforcer} kicks in).</li>
 * <li>The {@code content} string handed to the LLM is intentionally short: a one-line header (path + counts) followed
 * by the (possibly trimmed) unified diff. The full structured hunk list lives in
 * {@link com.harnex.tool.ToolResult#getMetadata()} and is forwarded over SSE, NOT into thread.messages — so snapshot
 * blobs stay small.</li>
 * </ul>
 */
public final class FileDiffRenderer {

    /** Lines longer than this make the diff useless for humans/LLMs (minified JS, base64, ...). */
    public static final int MAX_LINE_LENGTH = 500;
    /** Hard cap on emitted diff body lines (across all hunks). */
    public static final int MAX_DIFF_LINES = 200;
    /** Hard cap on emitted diff body chars (defensive — long lines still get truncated). */
    public static final int MAX_DIFF_CHARS = 8_000;
    /** Context lines around each hunk in unified diff output. */
    public static final int CONTEXT_LINES = 3;
    /**
     * Skip the Myers diff for inputs above this many lines on either side. DiffUtils is O(N*M) in line count; a
     * file_edit on a 200k-line file would burn minutes of CPU and hundreds of MB of heap for an output the renderer
     * caps at 200 lines anyway. Above this threshold we fall back to the byte-delta summary (same path as binary
     * files).
     */
    public static final int MAX_DIFF_INPUT_LINES = 20_000;

    private FileDiffRenderer() {}

    /**
     * Compute a {@link FileDiff} for the given before/after text. Never throws — degrades to a "binary or oversized"
     * placeholder when content is not suitable for textual diff.
     *
     * @param path project-relative or absolute path shown to the user / LLM
     * @param before previous content (use {@code ""} for new-file create)
     * @param after new content (use {@code ""} for delete, though no tool does that today)
     */
    public static FileDiff compute(String path, String before, String after) {
        if (before == null)
            before = "";
        if (after == null)
            after = "";
        FileDiff d = new FileDiff();
        d.path = path == null ? "" : path;
        d.beforeBytes = before.length();
        d.afterBytes = after.length();

        if (before.equals(after)) {
            d.unchanged = true;
            d.unifiedDiff = "";
            return d;
        }

        if (looksBinary(before) || looksBinary(after)) {
            d.binary = true;
            // Without a line-level diff, fall back to byte delta. Sign is informative even for
            // binary: a sudden -90% size on a known binary asset is usually a bug.
            int delta = after.length() - before.length();
            d.added = Math.max(0, delta);
            d.removed = Math.max(0, -delta);
            d.unifiedDiff = "[binary or minified content; diff suppressed]";
            return d;
        }

        List<String> beforeLines = splitLines(before);
        List<String> afterLines = splitLines(after);

        // Algorithmic-DoS guard: Myers diff is O(N*M). looksBinary() catches long-single-line
        // minified bundles via the line-length probe but does NOT catch "lots of normal-length
        // lines" — a file_edit on a 200k-line CSV would block the worker for minutes computing
        // hunks we'd then truncate to 200 lines anyway. Skip straight to the byte-delta summary
        // (same path as binary), preserving the +N/-M signal without the CPU/memory cost.
        if (beforeLines.size() > MAX_DIFF_INPUT_LINES || afterLines.size() > MAX_DIFF_INPUT_LINES) {
            d.binary = true;
            int delta = after.length() - before.length();
            d.added = Math.max(0, delta);
            d.removed = Math.max(0, -delta);
            d.unifiedDiff = "[file too large for line diff: " + beforeLines.size() + " \u2192 " + afterLines.size()
                + " lines; reporting byte delta only]";
            return d;
        }

        Patch<String> patch;
        try {
            patch = DiffUtils.diff(beforeLines, afterLines);
        } catch (Exception e) {
            // Myers diff is O(N*M) and can OOM on pathological inputs. Be defensive.
            d.binary = true;
            d.unifiedDiff = "[diff computation failed: " + e.getClass().getSimpleName() + "]";
            return d;
        }

        int added = 0;
        int removed = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            DeltaType type = delta.getType();
            int src = delta.getSource().getLines().size();
            int tgt = delta.getTarget().getLines().size();
            switch (type) {
                case INSERT -> added += tgt;
                case DELETE -> removed += src;
                case CHANGE -> {
                    added += tgt;
                    removed += src;
                }
                default -> {
                    /* EQUAL never appears in deltas */ }
            }
        }
        d.added = added;
        d.removed = removed;

        // Whitespace-only edits: avoid feeding the LLM a noisy hunk that says nothing semantic.
        // The structured added/removed still tells the UI what happened.
        if (isWhitespaceOnly(before, after)) {
            d.whitespaceOnly = true;
            d.unifiedDiff = "[whitespace-only changes: +" + added + " -" + removed + " lines]";
            return d;
        }

        List<String> ud;
        try {
            ud = UnifiedDiffUtils.generateUnifiedDiff(d.path, d.path, beforeLines, patch, CONTEXT_LINES);
        } catch (Exception e) {
            d.unifiedDiff = "[unified diff render failed: " + e.getClass().getSimpleName() + "]";
            return d;
        }
        // Drop the leading "--- a" / "+++ b" file headers — they duplicate info the caller
        // already shows in the per-tool card header and waste vertical space.
        if (ud.size() >= 2 && ud.get(0).startsWith("--- ") && ud.get(1).startsWith("+++ ")) {
            ud = ud.subList(2, ud.size());
        }

        StringBuilder body = new StringBuilder();
        int linesEmitted = 0;
        boolean truncated = false;
        for (String line : ud) {
            // Truncate individual very-long lines (minified content slipped past looksBinary).
            String safe =
                line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) + " …(line truncated)" : line;
            if (linesEmitted >= MAX_DIFF_LINES || body.length() + safe.length() + 1 > MAX_DIFF_CHARS) {
                truncated = true;
                break;
            }
            if (body.length() > 0)
                body.append('\n');
            body.append(safe);
            linesEmitted++;
        }
        if (truncated) {
            int remaining = ud.size() - linesEmitted;
            body.append("\n… (").append(remaining).append(" more diff lines truncated)");
        }
        d.unifiedDiff = body.toString();
        d.truncated = truncated;
        return d;
    }

    /**
     * Build the human/LLM-facing {@code content} string for a tool result. Pattern matches what Claude Code / Aider
     * emit so the model recognises it as a patch citation.
     *
     * @param verb e.g. {@code "edited"} or {@code "wrote"}
     */
    public static String renderForLlm(String verb, FileDiff d) {
        StringBuilder sb = new StringBuilder();
        sb.append(verb).append(' ').append(d.path);
        if (d.unchanged) {
            sb.append("  (no changes)");
            return sb.toString();
        }
        sb.append("  (+").append(d.added).append(" -").append(d.removed).append(')');
        if (d.binary) {
            sb.append(" [binary]");
            return sb.toString();
        }
        if (d.whitespaceOnly) {
            sb.append(" [whitespace-only]");
            return sb.toString();
        }
        if (d.unifiedDiff != null && !d.unifiedDiff.isEmpty()) {
            sb.append('\n').append(d.unifiedDiff);
        }
        return sb.toString();
    }

    /** Convert the POJO to a {@code Map} suitable for {@code ToolResult.metadata}. */
    public static Map<String, Object> toMetadata(FileDiff d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", d.path);
        m.put("added", d.added);
        m.put("removed", d.removed);
        m.put("beforeBytes", d.beforeBytes);
        m.put("afterBytes", d.afterBytes);
        if (d.unchanged)
            m.put("unchanged", true);
        if (d.binary)
            m.put("binary", true);
        if (d.whitespaceOnly)
            m.put("whitespaceOnly", true);
        if (d.truncated)
            m.put("truncated", true);
        m.put("unifiedDiff", d.unifiedDiff == null ? "" : d.unifiedDiff);
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("diff", m);
        return wrap;
    }

    // ---- helpers --------------------------------------------------------------------------

    private static List<String> splitLines(String s) {
        if (s.isEmpty())
            return new ArrayList<>();
        // -1 to preserve trailing empty lines so a single appended "\n" shows as one added line,
        // matching git/unified-diff conventions.
        return new ArrayList<>(Arrays.asList(s.split("\r\n|\r|\n", -1)));
    }

    private static boolean looksBinary(String s) {
        if (s.isEmpty())
            return false;
        // NUL byte is the canonical "this is binary" signal (same heuristic git uses).
        if (s.indexOf('\0') >= 0)
            return true;
        // Cheap line-length check: if the first 4 KB contains no line break AND the file is
        // larger than 4 KB, treat as minified. Avoids walking entire huge buffers.
        int probe = Math.min(s.length(), 4096);
        boolean sawNewline = false;
        int maxRun = 0;
        int run = 0;
        for (int i = 0; i < probe; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                sawNewline = true;
                if (run > maxRun)
                    maxRun = run;
                run = 0;
            } else {
                run++;
            }
        }
        if (run > maxRun)
            maxRun = run;
        if (!sawNewline && s.length() > 4096)
            return true;
        // A single line longer than 2× MAX_LINE_LENGTH inside the probe window is enough to
        // suppress: line-level diff on minified bundles produces unreadable mega-hunks.
        return maxRun > MAX_LINE_LENGTH * 2;
    }

    private static boolean isWhitespaceOnly(String before, String after) {
        // Cheap pre-filter — comparing collapsed-whitespace strings is O(n) and avoids the
        // common "agent rewrote indentation" false-noisy-diff case.
        return collapseWs(before).equals(collapseWs(after));
    }

    private static String collapseWs(String s) {
        // Remove all whitespace; sufficient for "are the non-WS tokens identical?".
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n')
                sb.append(c);
        }
        return sb.toString();
    }

    /** Structured diff result. Public for tests and metadata serialisation. */
    public static final class FileDiff {
        public String path;
        public int added;
        public int removed;
        public long beforeBytes;
        public long afterBytes;
        public boolean unchanged;
        public boolean binary;
        public boolean whitespaceOnly;
        public boolean truncated;
        public String unifiedDiff;

        public int getAdded() {
            return added;
        }

        public int getRemoved() {
            return removed;
        }

        public String getPath() {
            return path;
        }

        public String getUnifiedDiff() {
            return unifiedDiff;
        }

        public boolean isUnchanged() {
            return unchanged;
        }

        public boolean isBinary() {
            return binary;
        }

        public boolean isWhitespaceOnly() {
            return whitespaceOnly;
        }

        public boolean isTruncated() {
            return truncated;
        }
    }
}
