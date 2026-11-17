package com.harnex.tool.builtin;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
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

@Component
@ProjectRuntime
public class FileWriteTool implements Tool {
    private final WorkspacePathResolver vp;
    private final AgentConfig cfg;
    private final FileAccessTracker tracker;
    private final PathProtection protection;

    @Autowired
    public FileWriteTool(WorkspacePathResolver vp, AgentConfig cfg, FileAccessTracker tracker,
        PathProtection protection) {
        this.vp = vp;
        this.cfg = cfg;
        this.tracker = tracker;
        this.protection = protection;
    }

    /** Test-only constructor: no safety guards. */
    public FileWriteTool(WorkspacePathResolver vp, AgentConfig cfg) {
        this(vp, cfg, null, null);
    }

    @Override
    public String getName() {
        return "file_write";
    }

    @Override
    public String getDescription() {
        return "Write (overwrite) a text file in the project or an explicitly permitted external path.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.WRITES_FILESYSTEM,
            ToolCapability.DESTRUCTIVE_IRREVERSIBLE);
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription()).parameters(Map.of("type",
            "object", "properties",
            Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string"), "overwrite", Map.of("type",
                "boolean", "description",
                "Required when the target file already exists AND has not been read in this "
                    + "session. Set true to acknowledge that the existing contents will be " + "replaced wholesale.",
                "default", false)),
            "required", new String[] {"path", "content"})).build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String path = String.valueOf(input.getOrDefault("path", ""));
        String content = String.valueOf(input.getOrDefault("content", ""));
        boolean overwrite = input.get("overwrite") instanceof Boolean ob ? ob
            : "true".equalsIgnoreCase(String.valueOf(input.get("overwrite")));
        // Reject obviously oversized writes BEFORE touching the filesystem so a runaway agent
        // cannot fill the disk by looping with growing content.
        //
        // Two-stage size check:
        // 1) Cheap upper-bound: chars \u00d7 4 (max UTF-8 expansion). If even this is BELOW
        // the cap, the write is provably safe \u2014 skip the encode.
        // 2) For ASCII-heavy text the chars\u00d74 estimate is 4\u00d7 too pessimistic and
        // previously rejected legitimate writes (a 10 MB cap allowed only ~2.5 M ASCII
        // chars). Only when the cheap estimate trips do we actually encode and compare
        // the precise byte length \u2014 the temporary allocation is at most one extra
        // copy of the content string, which is acceptable on the rare \"close-to-cap\"
        // path and avoided on the common \"well under cap\" path.
        long maxBytes = cfg.getBudget() == null ? 10L * 1024 * 1024 : cfg.getBudget().getPerFileWriteBytes();
        if (maxBytes > 0 && (long)content.length() * 4 > maxBytes) {
            long actualBytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (actualBytes > maxBytes) {
                return ToolResult.builder().error(true)
                    .content("file_write rejected: content (" + actualBytes + " bytes) exceeds per-write byte cap ("
                        + maxBytes + " bytes). Tune harnex.budget.per-file-write-bytes if intentional.")
                    .build();
            }
        }
        Path p = vp.toRealForWrite(path, ctx.getThread().getId(), ctx.isOutsideWriteApproved());
        String threadId = ctx.getThread() == null ? null : ctx.getThread().getId();
        // --- Safety pre-checks --------------------------------------------------
        if (protection != null && protection.isProtected(threadId, p)) {
            return ToolResult.builder().error(true).content("refusing to write protected path: " + p
                + " (matched .harnexignore or harnex.safety.protected-globs).").build();
        }
        // For an EXISTING file, require either (a) a recent matching file_read, OR (b) explicit
        // overwrite=true. New-file creates always pass \u2014 there is nothing to clobber.
        try {
            if (Files.isRegularFile(p) && !overwrite && tracker != null) {
                FileAccessTracker.Status st = tracker.validate(threadId, p);
                if (st == FileAccessTracker.Status.NEVER_READ) {
                    return ToolResult.builder().error(true)
                        .content("refusing write: target exists and has not been read in this session. "
                            + "Either call file_read on " + p + " first (preferred for partial "
                            + "changes \u2014 use file_edit), or pass overwrite=true to acknowledge a "
                            + "wholesale replace.")
                        .build();
                }
                if (st == FileAccessTracker.Status.STALE) {
                    return ToolResult.builder().error(true)
                        .content("refusing write: file changed on disk since you last read it. Re-read " + p
                            + " or pass overwrite=true to clobber.")
                        .build();
                }
            }
        } catch (Exception ignored) {
            // stat failure is benign \u2014 fall through and let the I/O below surface it.
        }
        try {
            if (Files.isDirectory(p)) {
                return ToolResult.builder().error(true).content("path is a directory: " + p).build();
            }
            // Refuse to overwrite a non-regular file (FIFO / char / block / socket). Without
            // this, an LLM-planted `bash mkfifo x` would make Files.writeString block until a
            // reader appears (POSIX FIFO open-for-write semantics), parking the worker thread
            // even after our timeout cancels the future. New paths (Files.exists==false) are
            // fine and fall through to createDirectories+createTempFile below.
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.builder().error(true)
                    .content("refusing to overwrite non-regular file (FIFO/device/socket): " + p).build();
            }
            Files.createDirectories(p.getParent());
            // Capture previous content (empty for new-file create) so we can render a diff
            // for the UI/LLM after the atomic move. Best-effort: if the read fails (huge file,
            // permission glitch), fall back to byte-only diff via {@link FileDiffRenderer}'s
            // binary path by passing an empty placeholder.
            boolean existedBefore = Files.isRegularFile(p);
            String beforeContent = "";
            if (existedBefore) {
                try {
                    beforeContent = Files.readString(p, StandardCharsets.UTF_8);
                } catch (Exception readEx) {
                    // Force binary/oversized fallback so we still emit some signal.
                    beforeContent = "\0";
                }
            }
            // Preserve POSIX permissions across the atomic replace: Files.move(REPLACE_EXISTING)
            // creates a new inode whose perms come from the umask of the temp file (typically
            // 0644), silently dropping the executable bit on shell scripts and the like.
            Set<PosixFilePermission> origPerms = null;
            if (Files.exists(p) && p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                try {
                    origPerms = Files.getPosixFilePermissions(p);
                } catch (Exception ignored) {
                    /* best effort */ }
            }
            if (ctx.getSnapshotContext() != null) {
                final String capturedBefore = beforeContent;
                final boolean capturedExisted = existedBefore;
                final Set<PosixFilePermission> capturedPerms = origPerms;
                ToolResult strictError = ctx.getSnapshotContext().withPathLock(p, () -> {
                    boolean captured = ctx.getSnapshotContext().captureBeforeLocked(p);
                    if (!captured && cfg.getSnapshot().isStrictCaptureForWrites()) {
                        return ToolResult.builder().error(true)
                            .content("snapshot capture failed before write; write blocked").build();
                    }
                    if (cfg.getSnapshot().isStrictCaptureForWrites()
                        && !targetUnchanged(p, capturedExisted, capturedBefore)) {
                        return ToolResult.builder().error(true)
                            .content("snapshot capture became stale before write; retry the operation").build();
                    }
                    writeContent(p, content, capturedPerms);
                    ctx.getSnapshotContext().recordAfterHashLocked(p);
                    return null;
                });
                if (strictError != null) {
                    return strictError;
                }
            } else {
                writeContent(p, content, origPerms);
            }
            if (tracker != null && threadId != null) {
                tracker.refreshAfterWrite(threadId, p);
            }
            FileDiff diff = FileDiffRenderer.compute(path, beforeContent, content);
            return ToolResult.builder()
                .content(FileDiffRenderer.renderForLlm("wrote", diff) + " [" + content.length() + " chars]")
                .metadata(FileDiffRenderer.toMetadata(diff)).build();
        } catch (Exception e) {
            return ToolResult.failure(e);
        }
    }

    private static void writeContent(Path p, String content, Set<PosixFilePermission> origPerms) throws Exception {
        // Atomic write: tmp file + ATOMIC_MOVE prevents partially-written files from being observed by readers.
        Path tmp = Files.createTempFile(p.getParent(), p.getFileName().toString() + ".", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        boolean promoted = false;
        try {
            try {
                Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
            promoted = true;
        } finally {
            if (!promoted) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    /* best effort */ }
            }
        }
        if (origPerms != null) {
            try {
                Files.setPosixFilePermissions(p, origPerms);
            } catch (Exception ignored) {
                /* best effort */ }
        }
    }

    private static boolean targetUnchanged(Path p, boolean existedBefore, String beforeContent) {
        try {
            if (!existedBefore) {
                return !Files.exists(p, LinkOption.NOFOLLOW_LINKS);
            }
            if ("\0".equals(beforeContent) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            return beforeContent.equals(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
