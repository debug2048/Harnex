package com.harnex.tool.builtin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.EnumSet;
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
 * Simple "find and replace" edit. Matching / atomic-write logic lives in {@link TextEditCore} (shared with multi_edit).
 */
@Component
@ProjectRuntime
public class FileEditTool implements Tool {
    /** Hard cap on edit-target file size; see C5 note in execute(). */
    private static final long FILE_EDIT_MAX_BYTES = 2_000_000L;

    private final WorkspacePathResolver vp;
    private final FileAccessTracker tracker;
    private final PathProtection protection;
    private final AgentConfig.Snapshot snapshotCfg;

    @Autowired
    public FileEditTool(WorkspacePathResolver vp, FileAccessTracker tracker, PathProtection protection,
        AgentConfig cfg) {
        this.vp = vp;
        this.tracker = tracker;
        this.protection = protection;
        this.snapshotCfg = cfg.getSnapshot();
    }

    /**
     * Test-only constructor: no safety guards (legacy edit-only behaviour). The primary constructor above is marked
     * {@code @Autowired} so Spring picks it unambiguously when building the bean; without an explicit hint, two public
     * constructors caused "No default constructor found" because Spring then required (and could not find) a zero-arg
     * fallback.
     */
    public FileEditTool(WorkspacePathResolver vp) {
        this.vp = vp;
        this.tracker = null;
        this.protection = null;
        this.snapshotCfg = null;
    }

    @Override
    public String getName() {
        return "file_edit";
    }

    @Override
    public String getDescription() {
        return "Replace 'oldStr' with 'newStr' in a project or explicitly permitted external file. "
            + "By default oldStr must match exactly "
            + "and uniquely; pass replaceAll=true to replace every occurrence. If the exact match fails, "
            + "EOL differences are tolerated. A prior file_read is not required for an exact match; "
            + "broader whitespace tolerance requires a current tracked read by default. Include enough "
            + "surrounding text in oldStr to identify the intended location; conflicts require re-reading.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_FILESYSTEM,
            ToolCapability.WRITES_FILESYSTEM, ToolCapability.DESTRUCTIVE_IRREVERSIBLE);
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties",
                Map.of("path", Map.of("type", "string"), "oldStr", Map.of("type", "string"), "newStr",
                    Map.of("type", "string"), "replaceAll",
                    Map.of(
                        "type", "boolean", "description", "Replace every occurrence (default false)", "default", false),
                    "force",
                    Map.of("type", "boolean", "description",
                        "Allow whitespace-tolerant matching without a current tracked read (rare). "
                            + "Does not bypass ambiguous matches, protected paths or concurrent-change checks.",
                        "default", false)),
                "required", new String[] {"path", "oldStr", "newStr"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String displayPath = String.valueOf(input.getOrDefault("path", ""));
        String threadId = ctx.getThread() == null ? null : ctx.getThread().getId();
        Path p = vp.toRealForWrite(displayPath, threadId, ctx.isOutsideWriteApproved());
        String oldStr = String.valueOf(input.getOrDefault("oldStr", ""));
        String newStr = String.valueOf(input.getOrDefault("newStr", ""));
        boolean replaceAll = input.get("replaceAll") instanceof Boolean b ? b
            : "true".equalsIgnoreCase(String.valueOf(input.get("replaceAll")));
        boolean force =
            input.get("force") instanceof Boolean fb ? fb : "true".equalsIgnoreCase(String.valueOf(input.get("force")));
        if (oldStr.isEmpty()) {
            return ToolResult.builder().error(true).content("oldStr must not be empty").build();
        }
        // --- Safety pre-checks ------------------------------------------------
        // 1) Project-level protected paths (.harnexignore + harnex.safety.protected-globs).
        // Protection is unconditional: there is no `force` bypass for protected files because
        // that would defeat the purpose. Operators must edit such files manually.
        if (protection != null && protection.isProtected(threadId, p)) {
            return ToolResult.builder().error(true)
                .content("refusing to edit protected path: " + p
                    + " (matched .harnexignore or harnex.safety.protected-globs). "
                    + "If this is intentional, edit the file manually or update the protection list.")
                .build();
        }
        try {
            // Refuse non-regular files (FIFO / char / block / socket). Without this guard, a
            // prior `bash mkfifo x` would let Files.readString below block indefinitely waiting
            // for a writer; per-call timeouts cancel the future but leave the worker thread
            // parked, eventually starving the tool pool.
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.builder().error(true)
                    .content("refusing to edit non-regular file (FIFO/device/socket): " + p).build();
            }
            // C5 (P2): mirror FileReadTool's 2 MB default cap. Without this, a single misdirected
            // edit on a multi-hundred-megabyte file loads the whole thing into the heap and can
            // OOM the JVM. The cap is intentionally LOWER than FileReadTool's absolute max (10 MB)
            // because an edit also renders a unified diff for the WebUI, ~doubling memory pressure.
            long size = Files.size(p);
            if (size > FILE_EDIT_MAX_BYTES) {
                return ToolResult.builder().error(true)
                    .content("refusing to edit: file is " + size + " bytes (limit " + FILE_EDIT_MAX_BYTES
                        + "). Edit large files in chunks via a dedicated streaming tool, or update the"
                        + " harnex.tools.file-edit.max-bytes limit if the host has the headroom.")
                    .build();
            }
            String s = Files.readString(p, StandardCharsets.UTF_8);
            boolean allowWhitespace = force || tracker == null
                || tracker.validate(threadId, p) == FileAccessTracker.Status.OK;
            TextEditCore.HunkResult res = TextEditCore.applyHunk(s, oldStr, newStr, replaceAll, allowWhitespace);
            if (!res.ok()) {
                return ToolResult.builder().error(true).content(res.error()
                    + ". Call file_read on " + p + " and include the current surrounding text in oldStr. No changes written.").build();
            }
            String afterContent = res.text();
            if (ctx.getSnapshotContext() != null) {
                ToolResult strictError = ctx.getSnapshotContext().withPathLock(p, () -> {
                    boolean captured = ctx.getSnapshotContext().captureBeforeLocked(p);
                    if (!captured && strictSnapshot()) {
                        return ToolResult.builder().error(true)
                            .content("snapshot capture failed before write; write blocked").build();
                    }
                    TextEditCore.atomicWrite(p, afterContent, s);
                    ctx.getSnapshotContext().recordAfterHashLocked(p);
                    return null;
                });
                if (strictError != null) {
                    return strictError;
                }
            } else {
                TextEditCore.atomicWrite(p, afterContent, s);
            }
            if (tracker != null && threadId != null) {
                tracker.refreshAfterWrite(threadId, p);
            }
            FileDiff diff = FileDiffRenderer.compute(displayPath, s, afterContent);
            int rc = res.replacements();
            String suffix = " [" + rc + " replacement" + (rc == 1 ? "" : "s")
                + (res.whitespaceTolerant() ? ", whitespace-tolerant]" : "]");
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
