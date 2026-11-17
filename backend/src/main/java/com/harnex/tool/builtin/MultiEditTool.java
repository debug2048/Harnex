package com.harnex.tool.builtin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.safety.FileAccessTracker;
import com.harnex.safety.PathProtection;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;
import com.harnex.tool.builtin.support.FileDiffRenderer;
import com.harnex.tool.builtin.support.FileDiffRenderer.FileDiff;
import com.harnex.tool.builtin.support.TextEditCore;

/**
 * Multi-hunk edit of a SINGLE file: apply an ordered list of find/replace edits to one in-memory buffer, then write
 * once. Two properties that repeated {@code file_edit} calls cannot give:
 * <ul>
 * <li><b>Atomic</b> — every hunk is applied to the buffer; if ANY hunk fails to match, the whole call fails and nothing
 * is written. There is never a partially-edited file on disk.</li>
 * <li><b>Ordered / sequential semantics</b> — hunk N sees the result of hunk N-1, which is exactly what's needed when
 * an earlier edit changes text a later edit would otherwise have matched against.</li>
 * </ul>
 * Matching, EOL/whitespace tolerance and the atomic write are shared with {@code file_edit} via {@link TextEditCore}.
 */
@Component
@ProjectRuntime
public class MultiEditTool implements Tool {

    /** Mirror FileEditTool's per-file cap. */
    private static final long FILE_EDIT_MAX_BYTES = 2_000_000L;

    private final WorkspacePathResolver vp;
    private final FileAccessTracker tracker;
    private final PathProtection protection;
    private final AgentConfig.Snapshot snapshotCfg;

    @Autowired
    public MultiEditTool(WorkspacePathResolver vp, FileAccessTracker tracker, PathProtection protection,
        AgentConfig cfg) {
        this.vp = vp;
        this.tracker = tracker;
        this.protection = protection;
        this.snapshotCfg = cfg.getSnapshot();
    }

    /** Test-only constructor: no safety guards (mirrors {@link FileEditTool}). */
    public MultiEditTool(WorkspacePathResolver vp) {
        this.vp = vp;
        this.tracker = null;
        this.protection = null;
        this.snapshotCfg = null;
    }

    @Override
    public String getName() {
        return "multi_edit";
    }

    @Override
    public String getDescription() {
        return "Apply several ordered find/replace edits to ONE file in a single atomic write. Each edit is "
            + "{oldStr, newStr, replaceAll?}; edits apply in order (later edits see earlier results). If ANY edit "
            + "fails to match, the whole call fails and the file is left unchanged. Prefer this over multiple "
            + "file_edit calls when changing several places in the same file. A prior file_read is not required "
            + "when each oldStr matches the current contents exactly (EOL differences tolerated). Include enough "
            + "surrounding text to identify each location; re-read on conflicting or ambiguous matches.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_FILESYSTEM,
            ToolCapability.WRITES_FILESYSTEM, ToolCapability.DESTRUCTIVE_IRREVERSIBLE);
    }

    @Override
    public int maxParallel() {
        return 1;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("type", "object");
        edit.put("properties",
            Map.of("oldStr", Map.of("type", "string"), "newStr", Map.of("type", "string"), "replaceAll",
                Map.of("type", "boolean", "description", "Replace every occurrence within this hunk (default false)")));
        edit.put("required", new String[] {"oldStr", "newStr"});

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string", "description", "Workspace file to edit."));
        props.put("edits", Map.of("type", "array", "description",
            "Ordered list of {oldStr, newStr, replaceAll?} edits applied to one buffer.", "items", edit));
        props.put("force",
            Map.of("type", "boolean", "description",
                "Allow whitespace-tolerant matching without a current tracked read (rare). "
                    + "Does not bypass ambiguous matches, protected paths or concurrent-change checks.",
                "default", false));
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties", props, "required", new String[] {"path", "edits"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String displayPath = String.valueOf(input.getOrDefault("path", ""));
        String threadId = ctx.getThread() == null ? null : ctx.getThread().getId();
        Path p = vp.toRealForWrite(displayPath, threadId, ctx.isOutsideWriteApproved());
        boolean force =
            input.get("force") instanceof Boolean fb ? fb : "true".equalsIgnoreCase(String.valueOf(input.get("force")));

        Object editsObj = input.get("edits");
        if (!(editsObj instanceof List<?> editsList) || editsList.isEmpty()) {
            return ToolResult.builder().error(true)
                .content("edits must be a non-empty array of {oldStr, newStr, replaceAll?}").build();
        }

        // 1) Protected paths (unconditional — no force bypass).
        if (protection != null && protection.isProtected(threadId, p)) {
            return ToolResult.builder().error(true).content(
                "refusing to edit protected path: " + p + " (matched .harnexignore or harnex.safety.protected-globs).")
                .build();
        }
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.builder().error(true)
                    .content("refusing to edit non-regular file (FIFO/device/socket): " + p).build();
            }
            long size = Files.size(p);
            if (size > FILE_EDIT_MAX_BYTES) {
                return ToolResult.builder().error(true)
                    .content("refusing to edit: file is " + size + " bytes (limit " + FILE_EDIT_MAX_BYTES + ").")
                    .build();
            }
            String before = Files.readString(p, StandardCharsets.UTF_8);
            boolean allowWhitespace = force || tracker == null
                || tracker.validate(threadId, p) == FileAccessTracker.Status.OK;
            String buffer = before;
            int totalReplacements = 0;
            boolean anyWhitespaceTolerant = false;
            // Apply each hunk to the running buffer. Any mismatch aborts the WHOLE call — nothing
            // is written, so the on-disk file stays as it was (atomicity).
            for (int i = 0; i < editsList.size(); i++) {
                Object e = editsList.get(i);
                if (!(e instanceof Map<?, ?> em)) {
                    return ToolResult.builder().error(true)
                        .content("edit #" + (i + 1) + " is not an object {oldStr, newStr, replaceAll?}").build();
                }
                String oldStr = em.get("oldStr") == null ? "" : String.valueOf(em.get("oldStr"));
                String newStr = em.get("newStr") == null ? "" : String.valueOf(em.get("newStr"));
                boolean replaceAll = em.get("replaceAll") instanceof Boolean rb ? rb
                    : "true".equalsIgnoreCase(String.valueOf(em.get("replaceAll")));
                if (oldStr.isEmpty()) {
                    return ToolResult.builder().error(true).content("edit #" + (i + 1) + " has empty oldStr").build();
                }
                TextEditCore.HunkResult res = TextEditCore.applyHunk(buffer, oldStr, newStr, replaceAll, allowWhitespace);
                if (!res.ok()) {
                    return ToolResult.builder().error(true)
                        .content("edit #" + (i + 1) + " failed: " + res.error() + ". Call file_read on " + p
                            + " and include the current surrounding text in oldStr. No changes written.").build();
                }
                buffer = res.text();
                totalReplacements += res.replacements();
                anyWhitespaceTolerant |= res.whitespaceTolerant();
            }
            if (buffer.equals(before)) {
                // All hunks were no-ops (e.g. newStr == oldStr). Avoid a pointless write + empty diff.
                return ToolResult.ok("no changes (all " + editsList.size() + " edits left the file identical)");
            }
            if (ctx.getSnapshotContext() != null) {
                final String afterBuffer = buffer;
                ToolResult strictError = ctx.getSnapshotContext().withPathLock(p, () -> {
                    boolean captured = ctx.getSnapshotContext().captureBeforeLocked(p);
                    if (!captured && strictSnapshot()) {
                        return ToolResult.builder().error(true)
                            .content("snapshot capture failed before write; write blocked").build();
                    }
                    TextEditCore.atomicWrite(p, afterBuffer, before);
                    ctx.getSnapshotContext().recordAfterHashLocked(p);
                    return null;
                });
                if (strictError != null) {
                    return strictError;
                }
            } else {
                TextEditCore.atomicWrite(p, buffer, before);
            }
            if (tracker != null && threadId != null) {
                tracker.refreshAfterWrite(threadId, p);
            }
            FileDiff diff = FileDiffRenderer.compute(displayPath, before, buffer);
            String suffix = " [" + editsList.size() + " edit" + (editsList.size() == 1 ? "" : "s") + ", "
                + totalReplacements + " replacement" + (totalReplacements == 1 ? "" : "s")
                + (anyWhitespaceTolerant ? ", whitespace-tolerant]" : "]");
            return ToolResult.builder().content(FileDiffRenderer.renderForLlm("edited", diff) + suffix)
                .metadata(FileDiffRenderer.toMetadata(diff)).build();
        } catch (Exception e) {
            return ToolResult.failure(e);
        }
    }

    private boolean strictSnapshot() {
        return snapshotCfg != null && snapshotCfg.isStrictCaptureForWrites();
    }
}
