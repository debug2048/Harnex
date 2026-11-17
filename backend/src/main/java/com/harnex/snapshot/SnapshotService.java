package com.harnex.snapshot;

import com.harnex.todo.TodoStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.conversation.ConversationException;
import com.harnex.contract.StrictRequest;
import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.model.ThreadState;
import com.harnex.model.ToolCall;
import com.harnex.session.SessionStore;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.workspace.WorkspacePathResolver.Zone;
import com.harnex.tool.ToolResult;
import com.harnex.tool.builtin.support.FileDiffRenderer;
import com.harnex.tool.builtin.support.FileDiffRenderer.FileDiff;
import com.harnex.process.ChildProcessEnvironment;
import com.harnex.util.Ids;
import com.harnex.tool.ToolCallSummary;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Snapshot store for short-lived AI undo insurance.
 *
 * <p>
 * New snapshots are conversation manifests plus per-file before blobs. Whole-workspace tar restore is intentionally not
 * part of the normal path.
 */
@Slf4j
@Component
@ProjectRuntime
public class SnapshotService {
    private final TodoStore todoStore;

    public static final int MAX_SNAPSHOTS_PER_THREAD = 50;
    private static final long DISK_BUDGET_CHECK_INTERVAL_MS = 5_000L;
    private static final long GC_NEW_BLOB_GRACE_MS = 30_000L;
    private static final String SCOPE_WORKSPACE = "workspace";
    private static final String SCOPE_OUTSIDE = "outside";

    private static final ObjectMapper M =
        new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<List<Snapshot>> SNAPSHOT_LIST = new TypeReference<>() {};
    private static final TypeReference<List<ChatMessage>> MESSAGE_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> TODO_LIST = new TypeReference<>() {};

    private final SessionStore store;
    private final Path snapshotsRoot;
    private final AgentConfig.Snapshot snapCfg;
    private final AgentConfig.Workspace workspaceCfg;
    private final WorkspacePathResolver vp;
    @Autowired(required = false)
    private SnapshotArchive archive;
    private final ConcurrentMap<String, Object> threadLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> pathLocks = new ConcurrentHashMap<>();
    private volatile long lastDiskBudgetCheckMs;

    public SnapshotService(AgentConfig cfg, SessionStore store, WorkspacePathResolver vp, TodoStore todoStore) {
        this.todoStore = todoStore;
        this.store = store;
        this.snapshotsRoot = Paths.get(cfg.getDataDir(), "agent/snapshots");
        this.snapCfg = cfg.getSnapshot();
        this.workspaceCfg = cfg.getWorkspace();
        this.vp = vp;
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(snapshotsRoot);
        } catch (IOException e) {
            log.warn("SnapshotService init: cannot create {}: {}", snapshotsRoot, e.getMessage());
        }
    }

    private Object lockFor(String threadId) {
        return threadLocks.computeIfAbsent(threadId, k -> new Object());
    }

    private Object indexLockFor(String threadId) {
        return lockFor(threadId);
    }

    private Object pathLockFor(String runId, Path path) {
        String key = (runId == null || runId.isBlank() ? "run" : runId) + ":"
            + path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        return pathLocks.computeIfAbsent(key, k -> new Object());
    }

    public long takeManual(ConversationThread thread, String label) {
        return takeConversation(thread, "manual", label == null || label.isBlank() ? "manual" : label, null, null,
            null, "root", null, null, List.of());
    }

    public long takePreTool(ConversationThread thread, int iteration, List<String> toolSummaries) {
        return takeConversation(thread, "pre_tool", "pre-tool", null, iteration, null, "root", null, null,
            toolSummaries);
    }

    public SnapshotCaptureContext beginPreTool(ConversationThread thread, String runId, Integer iteration,
        List<ToolCall> calls) {
        List<String> summaries = ToolCallSummary.forRestorePoints(calls);
        long seq = takeConversation(thread, "pre_tool", "pre-tool", runId, iteration, null, "root", null, null,
            summaries);
        return seq > 0 ? new SnapshotCaptureContext(this, thread.getId(), thread.getId(), runId, seq, "root", null,
            null) : null;
    }

    public SnapshotCaptureContext beginSubagentPreTool(SnapshotCaptureContext parent, ConversationThread childThread,
        Integer iteration, List<ToolCall> calls) {
        if (parent == null || childThread == null || !Ids.isSafe(parent.rootThreadId)) {
            return null;
        }
        List<String> summaries = ToolCallSummary.forRestorePoints(calls);
        synchronized (lockFor(parent.rootThreadId)) {
            try {
                Path threadDir = ensureThreadDir(parent.rootThreadId);
                List<Snapshot> idx = readIndex(threadDir);
                long seq = idx.isEmpty() ? 1L : idx.get(idx.size() - 1).seq + 1L;
                Snapshot s = new Snapshot();
                s.seq = seq;
                s.label = "pre-tool";
                s.kind = "pre_tool";
                s.runId = parent.runId;
                s.threadId = childThread.getId();
                s.rootThreadId = parent.rootThreadId;
                s.agentId = "subagent_" + childThread.getId();
                s.parentThreadId = parent.threadId;
                s.subagentThreadId = childThread.getId();
                s.takenAt = Instant.now().toString();
                s.conversationHash = null;
                s.conversationRestorable = false;
                s.fileRestorable = false;
                s.fileCoverage = "unverified";
                s.toolSummaries = sanitizeSnapshotStrings(summaries);
                s.warnings = new ArrayList<>();
                s.fileEntries = new ArrayList<>();
                s.iteration = iteration;
                s.msgCount = childThread.getMessages().size();
                idx.add(s);
                pruneThread(idx, threadDir);
                writeIndex(threadDir, idx);
                enforceGlobalDiskBudget();
                return new SnapshotCaptureContext(this, childThread.getId(), parent.rootThreadId, parent.runId, seq,
                    s.agentId, parent.threadId, childThread.getId());
            } catch (Exception e) {
                log.warn("subagent snapshot take failed for root {} child {}: {}", parent.rootThreadId,
                    childThread.getId(), e.getMessage());
                return null;
            }
        }
    }

    public long takeRunStart(ConversationThread thread, String runId) {
        return takeConversation(thread, "run_start", "run-start", runId, null, null, "root", null, null, List.of());
    }

    public long takePreRestore(ConversationThread thread) {
        return takePreRestore(thread, null);
    }

    public long takePreRestore(ConversationThread thread, String runId) {
        return takeConversation(thread, "pre_restore", "pre-restore", runId, null, null, "root", null, null, List.of());
    }

    private SnapshotCaptureContext takePreRestore(ConversationThread thread, String runId, List<RestoreFilePlan> files) {
        long seq = takePreRestore(thread, runId);
        if (seq <= 0) {
            return null;
        }
        SnapshotCaptureContext ctx =
            new SnapshotCaptureContext(this, thread.getId(), thread.getId(), runId, seq, "root", null, null);
        for (RestoreFilePlan fp : files == null ? List.<RestoreFilePlan>of() : files) {
            if (fp == null || fp.path == null || "noop".equals(fp.action) || "skip".equals(fp.action)) {
                continue;
            }
            ctx.captureBefore(resolveRestorePlanPath(thread.getId(), fp));
        }
        return ctx;
    }

    public long takeBranchSeed(ConversationThread thread, String sourceThreadId, long sourceSeq) {
        return takeConversation(thread, "branch", "branch", null, null, null, "root", sourceThreadId, null,
            List.of("from " + sourceThreadId + "@" + sourceSeq));
    }

    private long takeConversation(ConversationThread thread, String kind, String label, String runId, Integer iteration,
        String toolCallId, String agentId, String parentThreadId, String subagentThreadId, List<String> toolSummaries) {
        if (thread == null || !Ids.isSafe(thread.getId())) {
            return -1;
        }
        synchronized (lockFor(thread.getId())) {
            try {
                Path threadDir = ensureThreadDir(thread.getId());
                ConversationSnapshotPayload payload =
                    snapCfg.isConversation() ? writeConversationPayload(threadDir, thread) : null;
                List<Snapshot> idx = readIndex(threadDir);
                String previousConversationHash = idx.isEmpty() ? null : idx.get(idx.size() - 1).conversationHash;
                if (payload != null && Objects.equals(previousConversationHash, payload.manifestHash)
                    && !"pre_restore".equals(kind)
                    && !"pre_tool".equals(kind) && !"run_start".equals(kind)) {
                    return idx.get(idx.size() - 1).seq;
                }
                long seq = idx.isEmpty() ? 1L : idx.get(idx.size() - 1).seq + 1L;
                Snapshot s = new Snapshot();
                s.seq = seq;
                s.label = label == null ? kind : label;
                s.kind = kind;
                if ("run_start".equals(kind)) s.runBaselineComplete = true;
                s.runId = runId;
                s.threadId = thread.getId();
                s.rootThreadId = thread.getId();
                s.toolCallId = toolCallId;
                s.agentId = agentId;
                s.parentThreadId = parentThreadId;
                s.subagentThreadId = subagentThreadId;
                s.takenAt = Instant.now().toString();
                s.conversationHash = payload == null ? null : payload.manifestHash;
                s.conversationRestorable = payload != null;
                s.fileRestorable = false;
                s.fileCoverage = "unverified";
                s.toolSummaries = sanitizeSnapshotStrings(toolSummaries);
                s.warnings = new ArrayList<>();
                s.fileEntries = new ArrayList<>();
                s.iteration = iteration;
                s.msgCount = thread.getMessages().size();
                idx.add(s);
                pruneThread(idx, threadDir);
                writeIndex(threadDir, idx);
                enforceGlobalDiskBudget();
                return seq;
            } catch (Exception e) {
                log.warn("snapshot take failed for thread {}: {}", thread.getId(), e.getMessage());
                return -1;
            }
        }
    }

    public List<Snapshot> list(String threadId) {
        return listOrThrow(threadId);
    }

    public List<Snapshot> listOrThrow(String threadId) {
        if (!Ids.isSafe(threadId)) {
            return List.of();
        }
        Path threadDir = snapshotsRoot.resolve(threadId);
        if (!Files.isDirectory(threadDir)) {
            return List.of();
        }
        List<Snapshot> idx = readIndex(threadDir);
        enrichLegacyPreToolSummaries(threadDir, idx);
        Collections.reverse(idx);
        return idx;
    }

    /**
     * Render the final task-local diff from the first before-image captured for a
     * path to its current workspace content. This deliberately does not use Git
     * HEAD, because the file may already have been dirty when the Run started.
     */
    public RunFileDiff diffFromRunBaseline(String threadId, FileSnapshotEntry baseline, Path currentPath)
        throws IOException {
        if (!Ids.isSafe(threadId) || baseline == null || baseline.skipped
            || !SCOPE_WORKSPACE.equals(baseline.scope)) {
            throw new IOException("run baseline is unavailable");
        }
        Path threadDir = snapshotsRoot.resolve(threadId);
        boolean beforeExists = baseline.existed;
        boolean afterExists = Files.isRegularFile(currentPath, LinkOption.NOFOLLOW_LINKS);
        byte[] beforeBytes = beforeExists
            ? Files.readAllBytes(verifyFileBlob(threadDir, baseline.beforeHash))
            : new byte[0];
        byte[] afterBytes;
        try {
            afterBytes = afterExists ? readFileForCapture(currentPath) : new byte[0];
        } catch (CaptureSkippedException e) {
            throw new IOException("current file cannot be diffed: " + e.reason, e);
        }

        FileDiff diff;
        try {
            diff = FileDiffRenderer.compute(baseline.path,
                decodeUtf8ForDiff(beforeBytes), decodeUtf8ForDiff(afterBytes));
        } catch (CharacterCodingException e) {
            diff = new FileDiff();
            diff.path = baseline.path;
            diff.beforeBytes = beforeBytes.length;
            diff.afterBytes = afterBytes.length;
            diff.unchanged = beforeExists == afterExists && sha256(beforeBytes).equals(sha256(afterBytes));
            diff.binary = !diff.unchanged;
            diff.unifiedDiff = diff.unchanged ? "" : "[binary content; task diff suppressed]";
        }
        // Empty create/delete changes existence even though their text bodies compare equal.
        if (beforeExists != afterExists) {
            diff.unchanged = false;
        }
        return new RunFileDiff(beforeExists, afterExists, diff);
    }

    public boolean discard(String threadId, long seq) {
        return pruneOneSnapshot(threadId, seq) > 0;
    }

    public boolean restore(String threadId, long seq) {
        return restoreDetailed(threadId, seq).ok();
    }

    public RestoreResult restoreDetailed(String threadId, long seq) {
        RestoreResponse r = restore(threadId, seq, RestoreRequest.conversationOnly());
        return new RestoreResult(r.restored, r.conversationRestored, r.filesRestored,
            r.restored ? null : (r.reason == null ? "restore_failed" : r.reason));
    }

    public RestorePlanResponse restorePlan(String threadId, long seq, RestorePlanRequest request) {
        RestoreMode mode = RestoreMode.from(request == null ? null : request.mode);
        Set<String> pathFilter = request == null || request.paths == null ? null : new HashSet<>(request.paths);
        RestorePlanResponse out = new RestorePlanResponse();
        out.seq = seq;
        out.mode = mode.value;
        out.warnings = new ArrayList<>();
        out.files = new ArrayList<>();
        out.conflicts = new ArrayList<>();
        if (!Ids.isSafe(threadId)) {
            out.canRestoreFiles = false;
            out.warnings.add("invalid_thread_id");
            return out;
        }
        if (archive != null) out.historyRevision = archive.revision(threadId);
        Path threadDir = snapshotsRoot.resolve(threadId);
        List<Snapshot> idx;
        try {
            idx = readIndex(threadDir);
        } catch (SnapshotIndexException e) {
            out.canRestoreFiles = false;
            out.warnings.add("snapshot_index_unreadable");
            return out;
        }
        Snapshot target = findBySeq(idx, seq);
        if (target == null) {
            out.canRestoreFiles = false;
            out.warnings.add("snapshot_not_found");
            return out;
        }
        out.conversationRestorable = target.conversationRestorable && target.conversationHash != null
            && !target.conversationHash.isBlank();
        if (target.warnings != null) out.warnings.addAll(target.warnings);
        if (archive != null && out.conversationRestorable) {
            try { archive.validate(readConversationPayload(threadDir,target.conversationHash).archive); }
            catch (Exception unavailable) { out.conversationRestorable = false; out.warnings.add("snapshot_archive_unavailable"); }
        }
        out.fileCoverage = target.fileCoverage == null ? "unverified" : target.fileCoverage;
        List<FileSnapshotEntry> restoreEntries = filterEntries(target.fileEntries, pathFilter);
        if ("run_start".equals(target.kind) && !restoreEntries.isEmpty()) {
            out.fileCoverage = runStartCoverageFor(idx, target, restoreEntries);
        }
        out.canRestoreFiles = !restoreEntries.isEmpty() && !"unverified".equals(out.fileCoverage);
        if (!mode.includesFiles()) {
            return out;
        }
        if (!out.canRestoreFiles) {
            out.warnings.add("snapshot_files_not_restorable");
            return out;
        }
        Map<String, KnownFileState> knownAfter = knownAfterStates(idx, target);
        for (FileSnapshotEntry e : restoreEntries) {
            RestoreFilePlan fp = planFile(threadId, e, knownAfter);
            out.files.add(fp);
            if (fp.conflict) {
                out.conflicts.add(fp);
            }
        }
        if (out.files.stream().noneMatch(fp -> fp != null && !"skip".equals(fp.action))) {
            out.canRestoreFiles = false;
            if (!out.warnings.contains("snapshot_files_not_restorable")) {
                out.warnings.add("snapshot_files_not_restorable");
            }
        }
        return out;
    }

    public RestoreResponse replayRestore(String threadId, long seq, RestoreRequest request) {
        return archive == null ? null : archive.replay(threadId,seq,request);
    }
    public RestoreResponse restore(String threadId, long seq, RestoreRequest request) {
        if (!Ids.isSafe(threadId)) return restoreCurrent(threadId,seq,request);
        synchronized (lockFor(threadId)) {
            if (archive != null) {
                RestoreResponse replay = archive.replay(threadId,seq,request);
                if (replay != null) return replay;
            }
            RestoreResponse result = restoreCurrent(threadId,seq,request);
            if (archive != null) archive.complete(threadId,seq,request,result);
            return result;
        }
    }
    private RestoreResponse restoreCurrent(String threadId, long seq, RestoreRequest request) {
        RestoreMode mode = RestoreMode.from(request == null ? null : request.mode);
        boolean force = request != null && request.force;
        RestoreResponse out = new RestoreResponse();
        out.restored = false;
        out.conversationRestored = false;
        out.filesRestored = false;
        out.fileCoverage = "unverified";
        out.conflicts = new ArrayList<>();
        out.warnings = new ArrayList<>();
        out.failedFiles = new ArrayList<>();
        out.restoredFileCount = 0;
        out.skippedFileCount = 0;
        if (!Ids.isSafe(threadId)) {
            out.reason = "invalid_thread_id";
            return out;
        }
        synchronized (lockFor(threadId)) {
            Optional<ConversationThread> tOpt = store.get(threadId);
            if (tOpt.isEmpty()) {
                out.reason = "not_found";
                return out;
            }
            Path threadDir = snapshotsRoot.resolve(threadId);
            List<Snapshot> idx;
            try {
                idx = readIndex(threadDir);
            } catch (SnapshotIndexException e) {
                out.reason = "snapshot_index_unreadable";
                return out;
            }
            Snapshot target = findBySeq(idx, seq);
            if (target == null) {
                out.reason = "not_found";
                return out;
            }
            RestorePlanResponse plan = restorePlan(threadId, seq,
                new RestorePlanRequest(mode.value, request == null ? null : request.paths));
            out.fileCoverage = plan.fileCoverage;
            if (plan.warnings != null) {
                out.warnings.addAll(plan.warnings);
            }
            if (mode == RestoreMode.FILES_ONLY && !plan.canRestoreFiles) {
                out.reason = "files_not_restorable";
                return out;
            }
            if (mode.includesFiles() && !force && plan.conflicts != null && !plan.conflicts.isEmpty()) {
                out.conflicts = plan.conflicts;
                out.reason = "conflict";
                return out;
            }
            ConversationThread current = tOpt.get();
            if (mode.includesConversation()
                && (!target.conversationRestorable || target.conversationHash == null || target.conversationHash.isBlank())) {
                out.reason = "conversation_not_restorable";
                return out;
            }
            ConversationRestoreData conversationData = null;
            if (mode.includesConversation()) {
                try {
                    conversationData = loadConversationRestoreData(threadDir, target.conversationHash);
                    if (archive != null) archive.validateRestore(threadId,conversationData.payload.archive,request);
                } catch (ConversationException e) {
                    out.reason = e.code(); out.warnings.add(e.getMessage()); return out;
                } catch (Exception e) {
                    out.reason = "conversation_blob_unreadable";
                    return out;
                }
            }
            if (mode.includesFiles()) {
                try {
                    validateRestoreFileBlobs(threadDir, plan.files);
                } catch (Exception e) {
                    out.reason = "file_blob_unreadable";
                    out.warnings.add(e.getMessage());
                    return out;
                }
            }
            SnapshotCaptureContext preRestore = takePreRestore(current, null, mode.includesFiles() ? plan.files : List.of());
            if (preRestore == null) {
                out.reason = "pre_restore_failed";
                out.warnings.add("pre_restore_failed");
                return out;
            }
            if (archive != null) archive.begin(threadId,seq,request);
            if (mode.includesConversation()) {
                try {
                    if (archive != null) {
                        var receipt = archive.restore(threadId,conversationData.payload.archive,request);
                        out.historyRevision = receipt.historyRevision(); out.conversationId = threadId; out.leafId = conversationData.payload.archive.leafId();
                    }
                    applyConversation(threadId, current, conversationData);
                    if (archive != null) current.getState().setConversationLeafId(conversationData.payload.archive.leafId());
                    store.save(current);
                    out.conversationRestored = true;
                } catch (Exception e) {
                    out.reason = "conversation_restore_failed";
                    out.warnings.add(e.getMessage());
                    return out;
                }
            }
            if (mode.includesFiles()) {
                for (RestoreFilePlan fp : plan.files == null ? List.<RestoreFilePlan>of() : plan.files) {
                    if (fp == null) {
                        continue;
                    }
                    if ("skip".equals(fp.action)) {
                        out.skippedFileCount++;
                        continue;
                    }
                    if ("noop".equals(fp.action)) {
                        continue;
                    }
                    if (fp.conflict && !force) {
                        out.skippedFileCount++;
                        continue;
                    }
                    try {
                        applyFileRestore(threadId, threadDir, fp);
                        if ("restore".equals(fp.action) || "delete".equals(fp.action)) {
                            out.restoredFileCount++;
                        }
                    } catch (Exception e) {
                        out.failedFiles.add(Map.of("path", fp.path, "reason", e.getMessage() == null ? "io" : e.getMessage()));
                    }
                }
                recordPreRestoreAfterHashes(preRestore, plan.files);
                out.filesRestored = out.restoredFileCount > 0 && out.failedFiles.isEmpty();
                if (!out.failedFiles.isEmpty()) {
                    out.warnings.add(out.failedFiles.size() + " file restore failed, see failedFiles");
                }
            }
            out.restored = out.conversationRestored || out.restoredFileCount > 0 || (mode == RestoreMode.FILES_ONLY && out.failedFiles.isEmpty());
            return out;
        }
    }

    public void captureGitBefore(SnapshotCaptureContext ctx, String relativePath, byte[] beforeBytes) {
        if (ctx == null || relativePath == null || relativePath.isBlank() || beforeBytes == null
            || !snapCfg.isFileRollback()) {
            return;
        }
        String rel = relativePath.replace('\\', '/');
        Path abs = vp.getProjectRoot().resolve(rel).normalize();
        if (vp.classify(abs, ctx.threadId) == Zone.RED) {
            addWarning(ctx.rootThreadId, ctx.seq, "red_zone_not_captured");
            return;
        }
        synchronized (pathLockFor(ctx.runId, abs)) {
            synchronized (indexLockFor(ctx.rootThreadId)) {
                try {
                    Path threadDir = ensureThreadDir(ctx.rootThreadId);
                    List<Snapshot> idx = readIndex(threadDir);
                    Snapshot s = findBySeq(idx, ctx.seq);
                    if (s == null || findFileEntry(s, SCOPE_WORKSPACE, rel) != null) {
                        return;
                    }
                    if (s.fileEntries == null) {
                        s.fileEntries = new ArrayList<>();
                    }
                    if (s.fileEntries.size() >= snapCfg.getMaxFilesPerSnapshot()) {
                        addWarning(s, "max_files_per_snapshot");
                        writeIndex(threadDir, idx);
                        return;
                    }
                    FileSnapshotEntry e = new FileSnapshotEntry();
                    e.scope = SCOPE_WORKSPACE;
                    e.path = rel;
                    e.threadId = ctx.threadId;
                    e.agentId = ctx.agentId;
                    e.parentThreadId = ctx.parentThreadId;
                    e.subagentThreadId = ctx.subagentThreadId;
                    e.existed = true;
                    String policySkip = skipReasonForPolicy(abs, new SnapshotPath(SCOPE_WORKSPACE, rel, abs));
                    if (policySkip != null) {
                        e.skipped = true;
                        e.skippedReason = policySkip;
                        addWarning(s, policySkip);
                    } else if (snapCfg.getMaxFileBytes() > 0 && beforeBytes.length > snapCfg.getMaxFileBytes()) {
                        e.beforeSize = (long)beforeBytes.length;
                        e.skipped = true;
                        e.skippedReason = "too_large";
                    } else {
                        e.beforeSize = (long)beforeBytes.length;
                        e.beforeHash = sha256(beforeBytes);
                        writeFileBlob(threadDir, e.beforeHash, beforeBytes);
                    }
                    if (e.skippedReason != null) {
                        markSkippedSummary(e);
                    }
                    s.fileEntries.add(e);
                    writeIndex(threadDir, idx);
                } catch (Exception e) {
                    addWarning(ctx.rootThreadId, ctx.seq, "git_capture_failed:" + e.getMessage());
                }
            }
        }
    }

    public void recordGitCreated(SnapshotCaptureContext ctx, String relativePath, Path currentPath) {
        if (ctx == null || relativePath == null || relativePath.isBlank() || currentPath == null
            || !snapCfg.isFileRollback()) {
            return;
        }
        String rel = relativePath.replace('\\', '/');
        Path abs = currentPath.toAbsolutePath().normalize();
        if (vp.classify(abs, ctx.threadId) == Zone.RED) {
            addWarning(ctx.rootThreadId, ctx.seq, "red_zone_not_captured");
            return;
        }
        synchronized (pathLockFor(ctx.runId, abs)) {
            synchronized (indexLockFor(ctx.rootThreadId)) {
                try {
                    Path threadDir = ensureThreadDir(ctx.rootThreadId);
                    List<Snapshot> idx = readIndex(threadDir);
                    Snapshot s = findBySeq(idx, ctx.seq);
                    if (s == null || findFileEntry(s, SCOPE_WORKSPACE, rel) != null) {
                        return;
                    }
                    if (s.fileEntries == null) {
                        s.fileEntries = new ArrayList<>();
                    }
                    if (s.fileEntries.size() >= snapCfg.getMaxFilesPerSnapshot()) {
                        addWarning(s, "max_files_per_snapshot");
                        writeIndex(threadDir, idx);
                        return;
                    }
                    FileSnapshotEntry e = new FileSnapshotEntry();
                    e.scope = SCOPE_WORKSPACE;
                    e.path = rel;
                    e.threadId = ctx.threadId;
                    e.agentId = ctx.agentId;
                    e.parentThreadId = ctx.parentThreadId;
                    e.subagentThreadId = ctx.subagentThreadId;
                    e.existed = false;
                    e.afterExisted = Files.exists(abs, LinkOption.NOFOLLOW_LINKS);
                    String policySkip = skipReasonForPolicy(abs, new SnapshotPath(SCOPE_WORKSPACE, rel, abs));
                    if (policySkip != null) {
                        e.skipped = true;
                        e.skippedReason = policySkip;
                        addWarning(s, policySkip);
                    } else if (!Files.isRegularFile(abs, LinkOption.NOFOLLOW_LINKS)) {
                        e.skipped = true;
                        e.skippedReason = "not_regular_file";
                    } else {
                        long size = Files.size(abs);
                        if (snapCfg.getMaxFileBytes() > 0 && size > snapCfg.getMaxFileBytes()) {
                            e.skipped = true;
                            e.skippedReason = "too_large";
                        } else {
                            try {
                                e.afterHash = sha256(readFileForCapture(abs));
                                populateDiffSummary(threadDir, e, abs);
                            } catch (CaptureSkippedException cse) {
                                e.skipped = true;
                                e.skippedReason = cse.reason;
                            }
                        }
                    }
                    if (e.skippedReason != null) {
                        markSkippedSummary(e);
                    }
                    s.fileEntries.add(e);
                    updateRunBaselineAfter(idx, s, threadDir, e, abs);
                    writeIndex(threadDir, idx);
                } catch (Exception e) {
                    addWarning(ctx.rootThreadId, ctx.seq, "git_created_capture_failed:" + e.getMessage());
                }
            }
        }
    }

    public void warn(SnapshotCaptureContext ctx, String warning) {
        if (ctx != null) {
            addWarning(ctx.rootThreadId, ctx.seq, warning);
        }
    }

    public String branch(String sourceThreadId, long seq, String newTitle) {
        return branch(sourceThreadId,seq,newTitle,UUID.randomUUID().toString());
    }
    public String branch(String sourceThreadId, long seq, String newTitle, String commandId) {
        if (!Ids.isSafe(sourceThreadId)) {
            return null;
        }
        synchronized (lockFor(sourceThreadId)) {
            try {
                Optional<ConversationThread> srcOpt = store.get(sourceThreadId);
                if (srcOpt.isEmpty()) {
                    return null;
                }
                Path threadDir = snapshotsRoot.resolve(sourceThreadId);
                Snapshot target = findBySeq(threadDir, seq);
                if (target == null || target.conversationHash == null) {
                    return null;
                }
                ConversationPayload p = readConversationPayload(threadDir, target.conversationHash);
                ConversationThread fork = new ConversationThread();
                if (archive != null) fork.setId(archive.forkId(commandId));
                fork.setTitle(newTitle == null || newTitle.isBlank() ? "branch@" + seq : newTitle);
                fork.setMessages(new ArrayList<>(readJsonBlob(threadDir, "conversations/messages", p.messagesHash, MESSAGE_LIST)));
                fork.setState(readJsonBlob(threadDir, "conversations/states", p.stateHash, ThreadState.class));
                fork.setModel(p.model);
                fork.setProvider(p.provider);
                fork.setReasoningEffort(p.reasoningEffort);
                fork.setToolsets(p.toolsets == null ? List.of() : new ArrayList<>(p.toolsets));
                if (archive != null) {
                    if (store.get(fork.getId()).isPresent()) {
                        archive.fork(p.archive,fork.getId(),fork.getTitle(),commandId); return fork.getId();
                    }
                    fork.getState().setConversationLeafId(p.archive.leafId());
                }
                store.save(fork);
                if (archive != null) archive.fork(p.archive,fork.getId(),fork.getTitle(),commandId);
                if (p.todosHash != null) {
                    List<Map<String, Object>> todos =
                        readJsonBlob(threadDir, "conversations/todos", p.todosHash, TODO_LIST);
                    todoStore.restoreSnapshot(fork.getId(), todos);
                }
                takeBranchSeed(fork, sourceThreadId, seq);
                return fork.getId();
            } catch (SnapshotIndexException e) {
                throw e;
            } catch (ConversationException e) {
                throw e;
            } catch (Exception e) {
                log.warn("snapshot branch failed (thread={} seq={}): {}", sourceThreadId, seq, e.getMessage());
                return null;
            }
        }
    }

    public void purge(String threadId) {
        if (!Ids.isSafe(threadId)) {
            return;
        }
        synchronized (lockFor(threadId)) {
            try {
                Path threadDir = snapshotsRoot.resolve(threadId);
                if (Files.exists(threadDir)) {
                    FileUtils.deleteDirectory(threadDir.toFile());
                }
            } catch (Exception e) {
                log.debug("snapshot purge for {} failed: {}", threadId, e.getMessage());
            }
        }
    }

    public boolean captureBefore(SnapshotCaptureContext ctx, Path path) {
        if (ctx == null || path == null || !snapCfg.isFileRollback()) {
            return true;
        }
        synchronized (pathLockFor(ctx.runId, path)) {
            return captureBeforeWithHeldPathLock(ctx, path);
        }
    }

    public List<Path> captureDirectoryBefore(SnapshotCaptureContext ctx, Path directory) {
        if (ctx == null || directory == null || !snapCfg.isFileRollback()) {
            return List.of();
        }
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            if (!snapshotWritable(ctx, snapshotPath(root))) {
                return List.of();
            }
        } catch (Exception e) {
            addWarning(ctx.rootThreadId, ctx.seq, "directory_capture_failed:" + e.getMessage());
            return List.of();
        }
        List<Path> captured = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> it = paths.filter(p -> !p.equals(root))
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).iterator();
            while (it.hasNext()) {
                if (currentFileEntryCount(ctx) >= snapCfg.getMaxFilesPerSnapshot()) {
                    addWarning(ctx.rootThreadId, ctx.seq, "max_files_per_snapshot");
                    break;
                }
                Path file = it.next().toAbsolutePath().normalize();
                if (captureBefore(ctx, file)) {
                    captured.add(file);
                }
            }
        } catch (Exception e) {
            addWarning(ctx.rootThreadId, ctx.seq, "directory_capture_failed:" + e.getMessage());
        }
        return captured;
    }

    public void recordAfterHash(SnapshotCaptureContext ctx, Path path) {
        if (ctx == null || path == null) {
            return;
        }
        synchronized (pathLockFor(ctx.runId, path)) {
            recordAfterHashWithHeldPathLock(ctx, path);
        }
    }

    public void finalizeCoverage(String threadId, long seq, List<ToolResult> results) {
        boolean bashRisk = results != null && results.stream().anyMatch(r -> "bash".equals(r.getToolName()));
        updateCoverage(threadId, seq, false, bashRisk ? "bash_partial" : null, bashRisk);
    }

    public void finalizeAborted(String threadId, long seq, String reason) {
        updateCoverage(threadId, seq, true, reason == null ? "aborted" : reason, false);
    }

    private boolean captureBeforeWithHeldPathLock(SnapshotCaptureContext ctx, Path path) {
        if (ctx == null || path == null || !snapCfg.isFileRollback()) {
            return true;
        }
        return captureBeforeLocked(ctx, path.toAbsolutePath().normalize());
    }

    private void recordAfterHashWithHeldPathLock(SnapshotCaptureContext ctx, Path path) {
        if (ctx == null || path == null) {
            return;
        }
        Path abs = path.toAbsolutePath().normalize();
        try {
            SnapshotPath sp = snapshotPath(abs);
            boolean afterExisted = Files.exists(abs, LinkOption.NOFOLLOW_LINKS);
            String afterHash = hashPathIfRegular(abs);
            Path threadDir = ensureThreadDir(ctx.rootThreadId);
            synchronized (indexLockFor(ctx.rootThreadId)) {
                List<Snapshot> idx = readIndex(threadDir);
                Snapshot s = findBySeq(idx, ctx.seq);
                if (s == null) {
                    return;
                }
                FileSnapshotEntry entry = findFileEntry(s, sp.scope(), sp.path());
                if (entry == null) {
                    return;
                }
                entry.afterExisted = afterExisted;
                entry.afterHash = afterHash;
                populateDiffSummary(threadDir, entry, abs);
                updateRunBaselineAfter(idx, s, threadDir, entry, abs);
                writeIndex(threadDir, idx);
            }
        } catch (Exception e) {
            addWarning(ctx.rootThreadId, ctx.seq, "record_after_failed:" + e.getMessage());
        }
    }

    private void updateRunBaselineAfter(List<Snapshot> idx, Snapshot snapshot, Path threadDir,
        FileSnapshotEntry entry, Path path) {
        retainRunBaselines(idx);
        for (Snapshot baseline : idx) {
            if (!"run_start".equals(baseline.kind) || baseline.seq >= snapshot.seq
                || !Objects.equals(snapshot.runId, baseline.runId)) continue;
            FileSnapshotEntry first = findFileEntry(baseline, scopeOf(entry), entry.path);
            if (first != null) {
                first.afterExisted = entry.afterExisted;
                first.afterHash = entry.afterHash;
                populateDiffSummary(threadDir, first, path);
            }
        }
    }

    private int currentFileEntryCount(SnapshotCaptureContext ctx) {
        synchronized (indexLockFor(ctx.rootThreadId)) {
            try {
                Path threadDir = ensureThreadDir(ctx.rootThreadId);
                Snapshot s = findBySeq(readIndex(threadDir), ctx.seq);
                return fileEntryCount(s);
            } catch (Exception e) {
                addWarning(ctx.rootThreadId, ctx.seq, "directory_capture_failed:" + e.getMessage());
                return snapCfg.getMaxFilesPerSnapshot();
            }
        }
    }

    private boolean captureBeforeLocked(SnapshotCaptureContext ctx, Path path) {
        try {
            Path threadDir = ensureThreadDir(ctx.rootThreadId);
            SnapshotPath sp = snapshotPath(path);
            if (!snapshotWritable(ctx, sp)) {
                return false;
            }
            synchronized (indexLockFor(ctx.rootThreadId)) {
                List<Snapshot> idx = readIndex(threadDir);
                Snapshot s = findBySeq(idx, ctx.seq);
                if (s == null) {
                    return false;
                }
                FileSnapshotEntry existing = findFileEntry(s, sp.scope(), sp.path());
                if (existing != null) {
                    return isCaptureUsable(existing);
                }
                if (fileEntryCount(s) >= snapCfg.getMaxFilesPerSnapshot()) {
                    appendTooManyFilesEntry(threadDir, idx, s, sp);
                    return false;
                }
            }
            FileSnapshotEntry e = new FileSnapshotEntry();
            e.scope = sp.scope();
            e.path = sp.path();
            e.threadId = ctx.threadId;
            e.agentId = ctx.agentId;
            e.parentThreadId = ctx.parentThreadId;
            e.subagentThreadId = ctx.subagentThreadId;
            String policySkip = skipReasonForPolicy(path, sp);
            if (policySkip != null) {
                e.existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
                e.skipped = true;
                e.skippedReason = policySkip;
            }
            e.existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            if (!e.skipped && e.existed) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    e.skipped = true;
                    e.skippedReason = "not_regular_file";
                } else {
                    long size = Files.size(path);
                    e.beforeSize = size;
                    e.beforeMtime = Files.getLastModifiedTime(path).toMillis();
                    if (snapCfg.getMaxFileBytes() > 0 && size > snapCfg.getMaxFileBytes()) {
                        e.skipped = true;
                        e.skippedReason = "too_large";
                    } else {
                        try {
                            byte[] bytes = readFileForCapture(path);
                            e.beforeHash = sha256(bytes);
                            writeFileBlob(threadDir, e.beforeHash, bytes);
                        } catch (CaptureSkippedException cse) {
                            e.skipped = true;
                            e.skippedReason = cse.reason;
                        }
                    }
                }
            }
            synchronized (indexLockFor(ctx.rootThreadId)) {
                List<Snapshot> idx = readIndex(threadDir);
                Snapshot s = findBySeq(idx, ctx.seq);
                if (s == null) {
                    return false;
                }
                FileSnapshotEntry existing = findFileEntry(s, sp.scope(), sp.path());
                if (existing != null) {
                    return isCaptureUsable(existing);
                }
                if (fileEntryCount(s) >= snapCfg.getMaxFilesPerSnapshot()) {
                    appendTooManyFilesEntry(threadDir, idx, s, sp);
                    return false;
                }
                if (s.fileEntries == null) {
                    s.fileEntries = new ArrayList<>();
                }
                if (e.skippedReason != null) {
                    markSkippedSummary(e);
                    addWarning(s, e.skippedReason);
                }
                s.fileEntries.add(e);
                writeIndex(threadDir, idx);
            }
            return isCaptureUsable(e);
        } catch (Exception e) {
            addWarning(ctx.rootThreadId, ctx.seq, "capture_failed:" + e.getMessage());
            return false;
        }
    }

    private String skipReasonForPolicy(Path absPath, SnapshotPath path) {
        GitignoreScope gitignoreScope = gitignoreScope(absPath, path);
        if (matchesPolicyGlob(snapCfg.getExcludeGlobs(), path == null ? null : path.path(),
            gitignoreScope == null ? null : gitignoreScope.rel())) {
            return "excluded";
        }
        if (snapCfg.isRespectGitignore() && gitignoreScope != null
            && !matchesPolicyGlob(snapCfg.getProtectGlobs(), path == null ? null : path.path(), gitignoreScope.rel())
            && isIgnoredByGitignore(gitignoreScope)) {
            return "excluded";
        }
        return null;
    }

    private GitignoreScope gitignoreScope(Path absPath, SnapshotPath path) {
        if (path == null || path.path() == null || path.path().isBlank()) {
            return null;
        }
        if (SCOPE_OUTSIDE.equals(path.scope())) {
            Path root = findNearestGitRoot(absPath);
            if (root == null) {
                return null;
            }
            Path abs = absPath.toAbsolutePath().normalize();
            String rel = root.relativize(abs).toString().replace('\\', '/');
            return new GitignoreScope(root, rel);
        }
        Path root = vp.getProjectRoot().toAbsolutePath().normalize();
        return new GitignoreScope(root, path.path());
    }

    private boolean isIgnoredByGitignore(GitignoreScope scope) {
        Boolean gitIgnored = checkGitIgnore(scope.root(), scope.rel());
        if (gitIgnored != null) {
            return gitIgnored;
        }
        return isIgnoredByGitignoreFile(scope.root(), scope.rel());
    }

    private boolean isIgnoredByGitignoreFile(Path root, String rel) {
        Path gitignore = root.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) {
            return false;
        }
        try {
            boolean ignored = false;
            for (String raw : Files.readAllLines(gitignore)) {
                String line = raw == null ? "" : raw.trim();
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                boolean negated = line.startsWith("!");
                if (negated) {
                    line = line.substring(1).trim();
                }
                if (line.isBlank()) {
                    continue;
                }
                if (matchesGitignorePattern(line, rel)) {
                    ignored = !negated;
                }
            }
            return ignored;
        } catch (Exception e) {
            return false;
        }
    }

    private Path findNearestGitRoot(Path path) {
        if (path == null) {
            return null;
        }
        Path cursor = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
            cursor = cursor.getParent();
        }
        while (cursor != null) {
            if (Files.exists(cursor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private Boolean checkGitIgnore(Path root, String rel) {
        if (rel == null || rel.isBlank() || !Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Process p = null;
        try {
            p = ChildProcessEnvironment.start(
                new ProcessBuilder("git", "check-ignore", "-q", "--", rel)
                    .directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD));
            long timeout = snapCfg.getGitTimeoutMs() > 0 ? snapCfg.getGitTimeoutMs() : 2_000L;
            if (!p.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            int code = p.exitValue();
            if (code == 0) {
                return true;
            }
            if (code == 1) {
                return false;
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static boolean matchesGitignorePattern(String pattern, String rel) {
        if (pattern == null || pattern.isBlank() || rel == null || rel.isBlank()) {
            return false;
        }
        String p = pattern.trim().replace('\\', '/');
        String r = rel.replace('\\', '/');
        boolean anchored = p.startsWith("/");
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.endsWith("/")) {
            String dir = p.substring(0, p.length() - 1);
            return matchesGlob(dir + "/**", r) || (!anchored && matchesGlob("**/" + dir + "/**", r));
        }
        if (!anchored && !p.contains("/")) {
            String name = r.contains("/") ? r.substring(r.lastIndexOf('/') + 1) : r;
            return matchesGlob(p, name) || matchesGlob("**/" + p, r);
        }
        return matchesGlob(p, r) || (!anchored && matchesGlob("**/" + p, r));
    }

    private byte[] readFileForCapture(Path path) throws CaptureSkippedException {
        long timeoutMs = snapCfg.getCaptureTimeoutMs();
        if (timeoutMs <= 0) {
            try {
                return Files.readAllBytes(path);
            } catch (AccessDeniedException ade) {
                throw new CaptureSkippedException("permission_denied", ade);
            } catch (IOException ioe) {
                throw new CaptureSkippedException("unknown", ioe);
            }
        }
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "snapshot-capture");
            t.setDaemon(true);
            return t;
        });
        Future<byte[]> future = exec.submit(() -> Files.readAllBytes(path));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new CaptureSkippedException("timeout", te);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof AccessDeniedException ade) {
                throw new CaptureSkippedException("permission_denied", ade);
            }
            if (cause instanceof IOException ioe) {
                throw new CaptureSkippedException("unknown", ioe);
            }
            throw new CaptureSkippedException("unknown", ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CaptureSkippedException("timeout", ie);
        } finally {
            exec.shutdownNow();
        }
    }

    private static boolean matchesAnyGlob(List<String> patterns, String rel) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (matchesGlob(pattern, rel)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPolicyGlob(List<String> patterns, String primary, String secondary) {
        return matchesAnyGlob(patterns, primary) || (secondary != null && !Objects.equals(primary, secondary)
            && matchesAnyGlob(patterns, secondary));
    }

    private static boolean matchesGlob(String pattern, String rel) {
        if (pattern == null || pattern.isBlank() || rel == null || rel.isBlank()) {
            return false;
        }
        String p = pattern.trim().replace('\\', '/');
        String r = rel.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.endsWith("/")) {
            p = p + "**";
        }
        return Pattern.matches(globToRegex(p), r);
    }

    private static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doublestar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doublestar) {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        out.append("(?:.*/)?");
                    } else {
                        out.append(".*");
                    }
                } else {
                    out.append("[^/]*");
                }
            } else if (c == '?') {
                out.append("[^/]");
            } else if (".()[]{}+$^|".indexOf(c) >= 0) {
                out.append('\\').append(c);
            } else {
                out.append(c);
            }
        }
        out.append('$');
        return out.toString();
    }

    private static class CaptureSkippedException extends Exception {
        final String reason;

        CaptureSkippedException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }
    }

    private static boolean isCaptureUsable(FileSnapshotEntry e) {
        return e != null && !e.skipped && (!e.existed || e.beforeHash != null);
    }

    private static int fileEntryCount(Snapshot s) {
        return s == null || s.fileEntries == null ? 0 : s.fileEntries.size();
    }

    private void appendTooManyFilesEntry(Path threadDir, List<Snapshot> idx, Snapshot s, SnapshotPath path)
        throws IOException {
        if (s.fileEntries == null) {
            s.fileEntries = new ArrayList<>();
        }
        FileSnapshotEntry skipped = new FileSnapshotEntry();
        skipped.scope = path.scope();
        skipped.path = path.path();
        skipped.skipped = true;
        skipped.skippedReason = "too_many_files";
        markSkippedSummary(skipped);
        s.fileEntries.add(skipped);
        addWarning(s, "max_files_per_snapshot");
        writeIndex(threadDir, idx);
    }

    private void updateCoverage(String threadId, long seq, boolean aborted, String reason, boolean bashRisk) {
        if (!Ids.isSafe(threadId)) {
            return;
        }
        synchronized (lockFor(threadId)) {
            try {
                Path threadDir = ensureThreadDir(threadId);
                List<Snapshot> idx = readIndex(threadDir);
                Snapshot s = findBySeq(idx, seq);
                if (s == null) {
                    return;
                }
                if (reason != null) {
                    addWarning(s, reason);
                }
                int ok = 0;
                int skipped = 0;
                for (FileSnapshotEntry e : s.fileEntries == null ? List.<FileSnapshotEntry>of() : s.fileEntries) {
                    if (e.skipped || (e.existed && e.beforeHash == null)) {
                        skipped++;
                    } else {
                        ok++;
                    }
                }
                s.fileRestorable = ok > 0;
                if (bashRisk && ok > 0) {
                    s.fileCoverage = "partial";
                } else if (bashRisk) {
                    s.fileCoverage = "unverified";
                } else {
                    s.fileCoverage = coverageFor(s.fileEntries, aborted);
                }
                writeIndex(threadDir, idx);
            } catch (Exception e) {
                log.debug("snapshot finalize failed {} seq={}: {}", threadId, seq, e.getMessage());
            }
        }
    }

    private RestoreFilePlan planFile(String threadId, FileSnapshotEntry e, Map<String, KnownFileState> knownAfter) {
        RestoreFilePlan fp = new RestoreFilePlan();
        fp.scope = scopeOf(e);
        fp.path = e.path;
        copyDiffSummary(e, fp);
        fp.conflict = false;
        if (e.skipped) {
            fp.action = "skip";
            fp.reason = e.skippedReason;
            return fp;
        }
        if (e.existed && (e.beforeHash == null || e.beforeHash.isBlank())) {
            fp.action = "skip";
            fp.reason = "missing_before_hash";
            return fp;
        }
        Path p = resolveSnapshotEntryPath(threadId, e);
        String boundaryReason = restoreBoundarySkipReason(threadId, p);
        if (boundaryReason != null) {
            fp.action = "skip";
            fp.reason = boundaryReason;
            return fp;
        }
        boolean currentExists = Files.exists(p, LinkOption.NOFOLLOW_LINKS);
        String current = hashPathIfRegular(p);
        KnownFileState known = knownAfter.getOrDefault(snapshotPathKey(e), KnownFileState.EMPTY);
        if (!e.existed) {
            fp.action = "delete";
            if (!currentExists) {
                fp.action = "noop";
            } else if (Boolean.TRUE.equals(e.afterExisted) && e.afterHash != null && Objects.equals(current, e.afterHash)) {
                // safe delete
            } else if (current != null && known.hashes.contains(current)) {
                // safe delete after later AI writes in the same run
            } else {
                fp.conflict = true;
                fp.reason = "new_file_changed_after_snapshot";
            }
            return fp;
        }
        if (Objects.equals(current, e.beforeHash)) {
            fp.action = "noop";
        } else if (!currentExists && Boolean.FALSE.equals(e.afterExisted)) {
            fp.action = "restore";
            fp.beforeHash = e.beforeHash;
        } else if (!currentExists && known.deleted) {
            fp.action = "restore";
            fp.beforeHash = e.beforeHash;
        } else if (current != null && known.hashes.contains(current)) {
            fp.action = "restore";
            fp.beforeHash = e.beforeHash;
        } else if (Boolean.TRUE.equals(e.afterExisted) && e.afterHash != null && Objects.equals(current, e.afterHash)) {
            fp.action = "restore";
            fp.beforeHash = e.beforeHash;
        } else {
            fp.action = "restore";
            fp.beforeHash = e.beforeHash;
            fp.conflict = true;
            fp.reason = "current_file_changed";
        }
        return fp;
    }

    /** Keep each run's first before-image independent of the rolling pre-tool history. */
    private void retainRunBaselines(List<Snapshot> idx) {
        for (Snapshot start : idx) {
            if (!"run_start".equals(start.kind) || start.runId == null || start.runId.isBlank()) continue;
            if (start.runBaselineComplete == null) {
                // Old indexes inferred the baseline from tool snapshots. A sequence gap means
                // an earlier before-image may already have been deleted; never infer through it.
                long lastRunSeq = idx.stream().filter(s -> Objects.equals(start.runId, s.runId))
                    .mapToLong(s -> s.seq).max().orElse(start.seq);
                long expected = start.seq;
                start.runBaselineComplete = true;
                for (Snapshot s : idx) {
                    if (s.seq <= start.seq || s.seq > lastRunSeq) continue;
                    if (s.seq != ++expected) start.runBaselineComplete = false;
                }
                if (!start.runBaselineComplete) addWarning(start, "run_baseline_missing");
            }
            Map<String, FileSnapshotEntry> firstByPath = new LinkedHashMap<>();
            for (FileSnapshotEntry e : start.fileEntries == null ? List.<FileSnapshotEntry>of() : start.fileEntries) {
                firstByPath.put(snapshotPathKey(e), e);
            }
            if (start.warnings == null) start.warnings = new ArrayList<>();
            for (Snapshot s : idx) {
                if (s.seq <= start.seq || !Objects.equals(start.runId, s.runId)
                    || !"pre_tool".equals(s.kind)) continue;
                if (s.warnings != null) {
                    for (String warning : s.warnings) {
                        if (!start.warnings.contains(warning)) addWarning(start, warning);
                    }
                }
                if ("partial".equals(s.fileCoverage)) start.fileCoverage = "partial";
                for (FileSnapshotEntry e : s.fileEntries == null ? List.<FileSnapshotEntry>of() : s.fileEntries) {
                    if (e.path == null) continue;
                    firstByPath.computeIfAbsent(snapshotPathKey(e),
                        key -> M.convertValue(e, FileSnapshotEntry.class));
                }
            }
            start.fileEntries = new ArrayList<>(firstByPath.values());
            if (!start.runBaselineComplete) {
                for (FileSnapshotEntry e : start.fileEntries) {
                    e.skipped = true;
                    e.skippedReason = "run_baseline_missing";
                    markSkippedSummary(e);
                }
            }
            start.fileCoverage = runStartCoverageFor(idx, start, start.fileEntries);
            start.fileRestorable = start.fileEntries.stream().anyMatch(SnapshotService::isCaptureUsable);
        }
    }

    private String runStartCoverageFor(List<Snapshot> idx, Snapshot target, List<FileSnapshotEntry> entries) {
        String base = coverageFor(entries, false);
        if (!"verified".equals(base)) {
            return base;
        }
        for (Snapshot s : idx == null ? List.<Snapshot>of() : idx) {
            if (s.seq < target.seq || !Objects.equals(target.runId, s.runId)) {
                continue;
            }
            if ("partial".equals(s.fileCoverage)) {
                return "partial";
            }
            if (s.warnings != null && s.warnings.stream().anyMatch(w -> w != null
                && (w.contains("bash_partial") || w.contains("git_diff_timeout") || w.contains("git_status_timeout")))) {
                return "partial";
            }
        }
        return base;
    }

    private List<FileSnapshotEntry> filterEntries(List<FileSnapshotEntry> entries, Set<String> pathFilter) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<FileSnapshotEntry> out = new ArrayList<>();
        for (FileSnapshotEntry e : entries) {
            if (e.path != null && (pathFilter == null || pathFilter.contains(e.path))) {
                out.add(e);
            }
        }
        return out;
    }

    private static String coverageFor(List<FileSnapshotEntry> entries, boolean aborted) {
        int ok = 0;
        int skipped = 0;
        for (FileSnapshotEntry e : entries == null ? List.<FileSnapshotEntry>of() : entries) {
            if (e.skipped || (e.existed && e.beforeHash == null)) {
                skipped++;
            } else {
                ok++;
            }
        }
        if (aborted || (ok > 0 && skipped > 0)) {
            return "partial";
        }
        if (ok > 0) {
            return "verified";
        }
        return "unverified";
    }

    private void validateRestoreFileBlobs(Path threadDir, List<RestoreFilePlan> files) throws IOException {
        for (RestoreFilePlan fp : files == null ? List.<RestoreFilePlan>of() : files) {
            if (fp == null || !"restore".equals(fp.action)) {
                continue;
            }
            verifyFileBlob(threadDir, fp.beforeHash);
        }
    }

    private void recordPreRestoreAfterHashes(SnapshotCaptureContext preRestore, List<RestoreFilePlan> files) {
        if (preRestore == null) {
            return;
        }
        for (RestoreFilePlan fp : files == null ? List.<RestoreFilePlan>of() : files) {
            if (fp == null || fp.path == null || "noop".equals(fp.action) || "skip".equals(fp.action)) {
                continue;
            }
            preRestore.recordAfterHash(resolveRestorePlanPath(preRestore.threadId, fp));
        }
        finalizeCoverage(preRestore.rootThreadId, preRestore.seq, List.of());
    }

    private void applyFileRestore(String threadId, Path threadDir, RestoreFilePlan fp) throws IOException {
        if (fp == null || "noop".equals(fp.action) || "skip".equals(fp.action)) {
            return;
        }
        Path target = resolveRestorePlanPath(threadId, fp);
        if ("delete".equals(fp.action)) {
            Files.deleteIfExists(target);
            return;
        }
        if (fp.beforeHash == null) {
            throw new IOException("missing beforeHash");
        }
        Path blob = verifyFileBlob(threadDir, fp.beforeHash);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".restore");
        boolean promoted = false;
        try {
            Files.copy(blob, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            promoted = true;
        } finally {
            if (!promoted) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private Path verifyFileBlob(Path threadDir, String hash) throws IOException {
        if (hash == null || hash.isBlank()) {
            throw new IOException("missing beforeHash");
        }
        Path blob = threadDir.resolve("blobs").resolve("files").resolve(hash + ".blob");
        if (!Files.isRegularFile(blob)) {
            throw new IOException("missing file blob");
        }
        String actual = sha256(blob);
        if (!Objects.equals(actual, hash)) {
            throw new IOException("file blob hash mismatch");
        }
        return blob;
    }

    private ConversationSnapshotPayload writeConversationPayload(Path threadDir, ConversationThread thread)
        throws IOException {
        Files.createDirectories(threadDir.resolve("blobs").resolve("conversations"));
        List<Map<String, Object>> todos = todoStore.snapshot(thread.getId());
        String messagesHash = writeJsonBlob(threadDir, "conversations/messages", thread.getMessages());
        String stateHash = writeJsonBlob(threadDir, "conversations/states", thread.getState());
        String todosHash = writeJsonBlob(threadDir, "conversations/todos", todos);
        ConversationPayload manifest = new ConversationPayload();
        manifest.messagesHash = messagesHash;
        manifest.stateHash = stateHash;
        manifest.todosHash = todosHash;
        manifest.model = thread.getModel();
        manifest.provider = thread.getProvider();
        manifest.reasoningEffort = thread.getReasoningEffort();
        manifest.toolsets = thread.getToolsets() == null ? List.of() : List.copyOf(thread.getToolsets());
        if (archive != null) manifest.archive = archive.capture(thread.getId());
        manifest.todosMeta = Map.of("itemCount", todos.size(), "completedCount",
            todos.stream().filter(t -> "completed".equals(String.valueOf(t.get("status")))).count());
        byte[] bytes = M.writeValueAsBytes(manifest);
        String manifestHash = sha256(bytes);
        writeBlob(threadDir.resolve("blobs").resolve("conversations").resolve(manifestHash + ".json"), bytes);
        ConversationSnapshotPayload payload = new ConversationSnapshotPayload();
        payload.manifestHash = manifestHash;
        payload.approxBytes = bytes.length;
        return payload;
    }

    private ConversationPayload readConversationPayload(Path threadDir, String hash) throws IOException {
        return readJsonBlob(threadDir, "conversations", hash, ConversationPayload.class);
    }

    /**
     * Older restore points persisted only the tool name (for example, {@code bash}). Recover the call description from
     * the captured conversation so existing histories are useful without rewriting their on-disk indexes.
     */
    private void enrichLegacyPreToolSummaries(Path threadDir, List<Snapshot> snapshots) {
        for (Snapshot snapshot : snapshots) {
            if (!"pre_tool".equals(snapshot.kind) || snapshot.conversationHash == null
                || hasDetailedToolSummary(snapshot.toolSummaries)) {
                continue;
            }
            try {
                ConversationPayload payload = readConversationPayload(threadDir, snapshot.conversationHash);
                List<ChatMessage> messages = readJsonBlob(threadDir, "conversations/messages", payload.messagesHash,
                    MESSAGE_LIST);
                for (int i = messages.size() - 1; i >= 0; i--) {
                    List<ToolCall> calls = messages.get(i).getToolCalls();
                    if (calls == null || calls.isEmpty()) {
                        continue;
                    }
                    Set<String> legacyNames = snapshot.toolSummaries == null ? Set.of()
                        : snapshot.toolSummaries.stream().filter(Objects::nonNull)
                            .map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
                    List<ToolCall> matchingCalls = legacyNames.isEmpty() ? calls
                        : calls.stream().filter(call -> call != null && call.getName() != null
                            && legacyNames.contains(call.getName().toLowerCase(Locale.ROOT))).toList();
                    List<String> summaries = sanitizeSnapshotStrings(ToolCallSummary.forRestorePoints(matchingCalls));
                    if (!summaries.isEmpty()) {
                        snapshot.toolSummaries = summaries;
                    }
                    break;
                }
            } catch (Exception e) {
                log.debug("Cannot enrich legacy restore point {}: {}", snapshot.seq, e.getMessage());
            }
        }
    }

    private static boolean hasDetailedToolSummary(List<String> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return false;
        }
        return summaries.stream().filter(Objects::nonNull).anyMatch(summary -> summary.contains(": ")
            || summary.startsWith("write ") || summary.startsWith("edit "));
    }

    private ConversationRestoreData loadConversationRestoreData(Path threadDir, String conversationHash)
        throws IOException {
        ConversationPayload p = readConversationPayload(threadDir, conversationHash);
        List<ChatMessage> messages = readJsonBlob(threadDir, "conversations/messages", p.messagesHash, MESSAGE_LIST);
        ThreadState state = readJsonBlob(threadDir, "conversations/states", p.stateHash, ThreadState.class);
        List<Map<String, Object>> todos = p.todosHash == null ? null
            : readJsonBlob(threadDir, "conversations/todos", p.todosHash, TODO_LIST);
        return new ConversationRestoreData(p, messages, state, todos);
    }

    private void applyConversation(String threadId, ConversationThread target, ConversationRestoreData data) {
        target.setMessages(new ArrayList<>(data.messages));
        target.setHumanTurnContext(null);
        target.setState(data.state);
        target.setModel(data.payload.model);
        target.setProvider(data.payload.provider);
        target.setReasoningEffort(data.payload.reasoningEffort);
        target.setToolsets(data.payload.toolsets == null ? List.of() : new ArrayList<>(data.payload.toolsets));
        if (data.todos != null) {
            todoStore.restoreSnapshot(threadId, data.todos);
        }
    }

    private <T> T readJsonBlob(Path threadDir, String subdir, String hash, Class<T> type) throws IOException {
        Path p = threadDir.resolve("blobs").resolve(subdir).resolve(hash + ".json");
        byte[] bytes = Files.readAllBytes(p);
        String actual = sha256(bytes);
        if (!Objects.equals(actual, hash)) {
            throw new IOException("blob hash mismatch");
        }
        return M.readValue(bytes, type);
    }

    private <T> T readJsonBlob(Path threadDir, String subdir, String hash, TypeReference<T> type) throws IOException {
        Path p = threadDir.resolve("blobs").resolve(subdir).resolve(hash + ".json");
        byte[] bytes = Files.readAllBytes(p);
        String actual = sha256(bytes);
        if (!Objects.equals(actual, hash)) {
            throw new IOException("blob hash mismatch");
        }
        return M.readValue(bytes, type);
    }

    private String writeJsonBlob(Path threadDir, String subdir, Object value) throws IOException {
        Path dir = threadDir.resolve("blobs").resolve(subdir);
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "blob-", ".json.tmp");
        MessageDigest md = newSha256Digest();
        boolean promoted = false;
        try (OutputStream raw = Files.newOutputStream(tmp); DigestOutputStream out = new DigestOutputStream(raw, md)) {
            M.writeValue(out, value);
        }
        String hash = hex(md.digest());
        Path dest = dir.resolve(hash + ".json");
        try {
            if (Files.exists(dest)) {
                Files.deleteIfExists(tmp);
            } else {
                try {
                    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, dest);
                }
                promoted = true;
            }
        } finally {
            if (!promoted) {
                Files.deleteIfExists(tmp);
            }
        }
        return hash;
    }

    private void writeFileBlob(Path threadDir, String hash, byte[] bytes) throws IOException {
        writeBlob(threadDir.resolve("blobs").resolve("files").resolve(hash + ".blob"), bytes);
    }

    private void writeBlob(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) {
            return;
        }
        Path tmp = Files.createTempFile(path.getParent(), path.getFileName().toString() + ".", ".tmp");
        boolean promoted = false;
        try {
            Files.write(tmp, bytes);
            if (!Files.exists(path)) {
                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
                    promoted = true;
                } catch (AtomicMoveNotSupportedException e) {
                    try {
                        Files.move(tmp, path);
                        promoted = true;
                    } catch (FileAlreadyExistsException ignored) {
                        // Another writer won the same content-addressed blob race.
                    }
                } catch (FileAlreadyExistsException ignored) {
                    // Another writer won the same content-addressed blob race.
                }
            }
        } finally {
            if (!promoted) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private Path ensureThreadDir(String threadId) throws IOException {
        Path p = snapshotsRoot.resolve(threadId);
        Files.createDirectories(p.resolve("blobs").resolve("files"));
        Files.createDirectories(p.resolve("blobs").resolve("conversations"));
        return p;
    }

    private List<Snapshot> readIndex(Path threadDir) {
        Path idx = threadDir.resolve("index.json");
        if (!Files.isRegularFile(idx)) {
            return new ArrayList<>();
        }
        try {
            List<Snapshot> snapshots = new ArrayList<>(M.readValue(Files.readAllBytes(idx), SNAPSHOT_LIST));
            retainRunBaselines(snapshots);
            return snapshots;
        } catch (Exception e) {
            log.warn("snapshot index unreadable {}: {}", idx, e.getMessage());
            throw new SnapshotIndexException(idx, e);
        }
    }

    private void writeIndex(Path threadDir, List<Snapshot> idx) throws IOException {
        retainRunBaselines(idx);
        Files.createDirectories(threadDir);
        Path p = threadDir.resolve("index.json");
        Path tmp = threadDir.resolve("index.json.tmp");
        Files.write(tmp, M.writeValueAsBytes(idx));
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Snapshot findBySeq(Path threadDir, long seq) {
        return findBySeq(readIndex(threadDir), seq);
    }

    private static Snapshot findBySeq(List<Snapshot> idx, long seq) {
        for (Snapshot s : idx == null ? List.<Snapshot>of() : idx) {
            if (s.seq == seq) {
                return s;
            }
        }
        return null;
    }

    private FileSnapshotEntry findFileEntry(Snapshot s, String scope, String path) {
        for (FileSnapshotEntry e : s.fileEntries == null ? List.<FileSnapshotEntry>of() : s.fileEntries) {
            if (Objects.equals(scopeOf(e), normalizeScope(scope)) && Objects.equals(e.path, path)) {
                return e;
            }
        }
        return null;
    }

    private Map<String, KnownFileState> knownAfterStates(List<Snapshot> idx, Snapshot target) {
        Map<String, KnownFileState> out = new HashMap<>();
        for (Snapshot s : idx == null ? List.<Snapshot>of() : idx) {
            if (s.seq <= target.seq) {
                continue;
            }
            if ("run_start".equals(target.kind) && !Objects.equals(target.runId, s.runId)) {
                continue;
            }
            if ("pre_restore".equals(s.kind) || "manual".equals(s.kind) || "branch".equals(s.kind)) {
                continue;
            }
            for (FileSnapshotEntry e : s.fileEntries == null ? List.<FileSnapshotEntry>of() : s.fileEntries) {
                if (e.path == null) {
                    continue;
                }
                KnownFileState state = out.computeIfAbsent(snapshotPathKey(e), k -> new KnownFileState());
                if (e.afterHash != null) {
                    state.hashes.add(e.afterHash);
                }
                if (Boolean.FALSE.equals(e.afterExisted)) {
                    state.deleted = true;
                }
            }
        }
        return out;
    }

    private SnapshotPath snapshotPath(Path p) {
        Path root = vp.getProjectRoot().toAbsolutePath().normalize();
        Path abs = p.toAbsolutePath().normalize();
        if (abs.startsWith(root)) {
            return new SnapshotPath(SCOPE_WORKSPACE, root.relativize(abs).toString().replace('\\', '/'), abs);
        }
        return new SnapshotPath(SCOPE_OUTSIDE, abs.toString().replace('\\', '/'), abs);
    }

    private boolean snapshotWritable(SnapshotCaptureContext ctx, SnapshotPath path) {
        Zone zone = vp.classify(path.absolutePath(), ctx.threadId);
        if (zone == Zone.RED) {
            addWarning(ctx.rootThreadId, ctx.seq, "red_zone_not_captured");
            return false;
        }
        if (zone == Zone.YELLOW && !outsideWritesAllowed()) {
            addWarning(ctx.rootThreadId, ctx.seq, "outside_write_disabled");
            return false;
        }
        return true;
    }

    private String restoreBoundarySkipReason(String threadId, Path path) {
        Zone zone = vp.classify(path, threadId);
        if (zone == Zone.RED) {
            return "red_zone";
        }
        if (zone == Zone.YELLOW && !outsideWritesAllowed()) {
            return "outside_write_disabled";
        }
        return null;
    }

    private boolean outsideWritesAllowed() {
        String policy = workspaceCfg == null ? "ask" : workspaceCfg.getOutsideWritePolicy();
        return !"deny".equalsIgnoreCase(policy == null ? "ask" : policy.trim());
    }

    private Path resolveSnapshotEntryPath(String threadId, FileSnapshotEntry e) {
        if (SCOPE_OUTSIDE.equals(scopeOf(e))) {
            return Path.of(e.path).toAbsolutePath().normalize();
        }
        return vp.workspaceFor(threadId).resolve(e.path).normalize();
    }

    private Path resolveRestorePlanPath(String threadId, RestoreFilePlan fp) {
        if (SCOPE_OUTSIDE.equals(normalizeScope(fp == null ? null : fp.scope))) {
            return Path.of(fp.path).toAbsolutePath().normalize();
        }
        return vp.workspaceFor(threadId).resolve(fp.path).normalize();
    }

    private static String scopeOf(FileSnapshotEntry e) {
        return normalizeScope(e == null ? null : e.scope);
    }

    private static String normalizeScope(String scope) {
        return SCOPE_OUTSIDE.equals(scope) ? SCOPE_OUTSIDE : SCOPE_WORKSPACE;
    }

    private static String snapshotPathKey(FileSnapshotEntry e) {
        return scopeOf(e) + "\n" + e.path;
    }

    private void populateDiffSummary(Path threadDir, FileSnapshotEntry e, Path afterPath) {
        if (e == null || e.skipped) {
            markSkippedSummary(e);
            return;
        }
        boolean beforeExists = e.existed;
        boolean afterExists = Boolean.TRUE.equals(e.afterExisted);
        e.changeType = changeType(beforeExists, afterExists, e.beforeHash, e.afterHash);
        e.diffAvailable = false;
        e.diffBinary = false;
        e.diffWhitespaceOnly = false;
        e.diffTruncated = false;
        e.linesAdded = null;
        e.linesDeleted = null;
        try {
            byte[] beforeBytes = beforeExists && e.beforeHash != null && !e.beforeHash.isBlank()
                ? Files.readAllBytes(threadDir.resolve("blobs").resolve("files").resolve(e.beforeHash + ".blob"))
                : new byte[0];
            if (afterExists && Files.isRegularFile(afterPath, LinkOption.NOFOLLOW_LINKS)) {
                e.afterSize = Files.size(afterPath);
                if (e.afterHash == null || (snapCfg.getMaxFileBytes() > 0 && e.afterSize > snapCfg.getMaxFileBytes())) {
                    e.diffBinary = true;
                    return;
                }
            } else if (!afterExists) {
                e.afterSize = 0L;
            } else {
                e.diffBinary = true;
                return;
            }
            byte[] afterBytes = afterExists ? readFileForCapture(afterPath) : new byte[0];
            String beforeText = decodeUtf8ForDiff(beforeBytes);
            String afterText = decodeUtf8ForDiff(afterBytes);
            FileDiff diff = FileDiffRenderer.compute(e.path, beforeText, afterText);
            e.diffAvailable = !diff.binary;
            e.diffBinary = diff.binary;
            e.diffWhitespaceOnly = diff.whitespaceOnly;
            e.diffTruncated = diff.truncated;
            if (!diff.binary) {
                e.linesAdded = diff.added;
                e.linesDeleted = diff.removed;
            }
        } catch (Exception ex) {
            e.diffAvailable = false;
            e.diffBinary = true;
            if (afterExists) {
                try {
                    e.afterSize = Files.size(afterPath);
                } catch (Exception ignored) {
                }
            } else {
                e.afterSize = 0L;
            }
        }
    }

    private static String decodeUtf8ForDiff(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String changeType(boolean beforeExists, boolean afterExists, String beforeHash, String afterHash) {
        if (!beforeExists && afterExists) {
            return "created";
        }
        if (beforeExists && !afterExists) {
            return "deleted";
        }
        if (beforeExists && afterExists && Objects.equals(beforeHash, afterHash)) {
            return "unchanged";
        }
        if (beforeExists && afterExists) {
            return "modified";
        }
        return "noop";
    }

    private static void markSkippedSummary(FileSnapshotEntry e) {
        if (e != null) {
            e.changeType = "skipped";
        }
    }

    private static void copyDiffSummary(FileSnapshotEntry e, RestoreFilePlan fp) {
        if (e == null || fp == null) {
            return;
        }
        fp.changeType = e.changeType;
        fp.linesAdded = e.linesAdded;
        fp.linesDeleted = e.linesDeleted;
        fp.diffAvailable = e.diffAvailable;
        fp.diffBinary = e.diffBinary;
        fp.diffWhitespaceOnly = e.diffWhitespaceOnly;
        fp.diffTruncated = e.diffTruncated;
    }

    private String hashPathIfRegular(Path p) {
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            long size = Files.size(p);
            if (snapCfg.getMaxFileBytes() > 0 && size > snapCfg.getMaxFileBytes()) {
                return null;
            }
            return sha256(readFileForCapture(p));
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest md = newSha256Digest();
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    md.update(buf, 0, n);
                }
            }
        }
        return hex(md.digest());
    }

    private static String sha256(byte[] bytes) {
        MessageDigest md = newSha256Digest();
        return hex(md.digest(bytes));
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] dig) {
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static List<String> sanitizeSnapshotStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            String s = value == null ? "" : value.replaceAll("\\s+", " ").trim();
            if (!s.isBlank()) {
                out.add(s.length() > 180 ? s.substring(0, 177).trim() + "..." : s);
            }
        }
        return List.copyOf(out);
    }

    private void addWarning(String threadId, long seq, String warning) {
        if (!Ids.isSafe(threadId)) {
            return;
        }
        synchronized (lockFor(threadId)) {
            try {
                Path threadDir = ensureThreadDir(threadId);
                List<Snapshot> idx = readIndex(threadDir);
                Snapshot s = findBySeq(idx, seq);
                if (s != null) {
                    addWarning(s, warning);
                    writeIndex(threadDir, idx);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void addWarning(Snapshot s, String warning) {
        if (warning == null || warning.isBlank()) {
            return;
        }
        if (s.warnings == null) {
            s.warnings = new ArrayList<>();
        }
        s.warnings.add(warning);
    }

    private void pruneThread(List<Snapshot> idx, Path threadDir) throws IOException {
        int max = snapCfg.getMaxSnapshotsPerThread() > 0 ? snapCfg.getMaxSnapshotsPerThread() : MAX_SNAPSHOTS_PER_THREAD;
        if (idx.size() <= max) {
            return;
        }
        while (idx.size() > max) {
            int removeAt = firstPrunableSnapshotIndex(idx, runIdsWithRemainingToolSnapshots(idx));
            if (removeAt < 0) {
                break;
            }
            idx.remove(removeAt);
        }
        writeIndex(threadDir, idx);
        gcBlobs(threadDir, idx);
    }

    private static Set<String> runIdsWithRemainingToolSnapshots(List<Snapshot> idx) {
        Set<String> out = new HashSet<>();
        for (Snapshot s : idx == null ? List.<Snapshot>of() : idx) {
            if ("pre_tool".equals(s.kind) && s.runId != null && !s.runId.isBlank()) {
                out.add(s.runId);
            }
        }
        return out;
    }

    private static int firstPrunableSnapshotIndex(List<Snapshot> idx, Set<String> protectedRunIds) {
        for (int i = 0; i < (idx == null ? 0 : idx.size()); i++) {
            if (!isProtectedRunStart(idx.get(i), protectedRunIds)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isProtectedRunStart(Snapshot s, Set<String> protectedRunIds) {
        return s != null && "run_start".equals(s.kind) && s.runId != null && protectedRunIds != null
            && protectedRunIds.contains(s.runId);
    }

    private void gcBlobs(Path threadDir, List<Snapshot> idx) {
        long startedAt = System.currentTimeMillis();
        Set<String> live = new HashSet<>();
        for (Snapshot s : idx) {
            if (s.conversationHash != null) {
                addLive(live, s.conversationHash);
                try {
                    ConversationPayload p = readConversationPayload(threadDir, s.conversationHash);
                    addLive(live, p.messagesHash);
                    addLive(live, p.stateHash);
                    addLive(live, p.todosHash);
                } catch (Exception e) {
                    log.warn("snapshot blob GC skipped for {} because conversation manifest {} is unreadable: {}",
                        threadDir.getFileName(), s.conversationHash, e.getMessage());
                    return;
                }
            }
            for (FileSnapshotEntry e : s.fileEntries == null ? List.<FileSnapshotEntry>of() : s.fileEntries) {
                if (e.beforeHash != null) {
                    addLive(live, e.beforeHash);
                }
            }
        }
        try (Stream<Path> all = Files.walk(threadDir.resolve("blobs"))) {
            all.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                int dot = name.indexOf('.');
                String hash = dot > 0 ? name.substring(0, dot) : name;
                if (!live.contains(hash) && isGcEligible(p, startedAt)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void addLive(Set<String> live, String hash) {
        if (hash != null && !hash.isBlank()) {
            live.add(hash);
        }
    }

    private static boolean isGcEligible(Path blob, long gcStartedAt) {
        try {
            long modifiedAt = Files.getLastModifiedTime(blob).toMillis();
            return modifiedAt < gcStartedAt - GC_NEW_BLOB_GRACE_MS;
        } catch (IOException e) {
            return false;
        }
    }

    private void enforceGlobalDiskBudget() {
        long budget = snapCfg.getTotalDiskBudgetBytes();
        if (budget <= 0 || !Files.isDirectory(snapshotsRoot)) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastDiskBudgetCheckMs;
        if (last > 0 && now - last < DISK_BUDGET_CHECK_INTERVAL_MS) {
            return;
        }
        lastDiskBudgetCheckMs = now;
        try {
            long total;
            try (Stream<Path> s = Files.walk(snapshotsRoot)) {
                total = s.filter(Files::isRegularFile).mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0L;
                    }
                }).sum();
            }
            if (total <= budget) {
                return;
            }
            List<SnapshotRef> refs = new ArrayList<>();
            try (Stream<Path> threads = Files.list(snapshotsRoot)) {
                for (Path td : (Iterable<Path>)threads::iterator) {
                    if (!Files.isDirectory(td)) {
                        continue;
                    }
                    try {
                        for (Snapshot s : readIndex(td)) {
                            refs.add(new SnapshotRef(td.getFileName().toString(), s.seq,
                                s.takenAt == null ? "" : s.takenAt));
                        }
                    } catch (SnapshotIndexException e) {
                        log.warn("snapshot disk-budget enforcement skipped unreadable index under {}",
                            td.getFileName());
                    }
                }
            }
            refs.sort(Comparator.comparing((SnapshotRef r) -> r.takenAt).thenComparingLong(r -> r.seq));
            for (SnapshotRef r : refs) {
                if (total <= budget) {
                    break;
                }
                total -= pruneOneSnapshot(r.threadId, r.seq, true);
            }
        } catch (Exception e) {
            log.warn("snapshot disk-budget enforcement failed: {}", e.getMessage());
        }
    }

    private long pruneOneSnapshot(String threadId, long seq) {
        return pruneOneSnapshot(threadId, seq, false);
    }

    private long pruneOneSnapshot(String threadId, long seq, boolean protectRunStart) {
        if (!Ids.isSafe(threadId) || seq <= 0) {
            return 0L;
        }
        synchronized (lockFor(threadId)) {
            try {
                Path threadDir = snapshotsRoot.resolve(threadId);
                List<Snapshot> idx = readIndex(threadDir);
                if (protectRunStart) {
                    Snapshot target = findBySeq(idx, seq);
                    if (isProtectedRunStart(target, runIdsWithRemainingToolSnapshots(idx))) {
                        return 0L;
                    }
                }
                boolean removed = idx.removeIf(s -> s.seq == seq);
                if (!removed) {
                    return 0L;
                }
                long before = dirSize(threadDir.resolve("blobs"));
                writeIndex(threadDir, idx);
                gcBlobs(threadDir, idx);
                long freed = Math.max(0L, before - dirSize(threadDir.resolve("blobs")));
                return freed > 0L ? freed : 1L;
            } catch (Exception e) {
                return 0L;
            }
        }
    }

    private static long dirSize(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        }
    }

    public record RestoreResult(boolean ok, boolean messagesRestored, boolean workspaceRestored, String reason) {
    }

    public enum RestoreMode {
        CONVERSATION_ONLY("conversation_only"), FILES_ONLY("files_only"), CONVERSATION_AND_FILES("conversation_and_files");

        final String value;

        RestoreMode(String value) {
            this.value = value;
        }

        boolean includesConversation() {
            return this == CONVERSATION_ONLY || this == CONVERSATION_AND_FILES;
        }

        boolean includesFiles() {
            return this == FILES_ONLY || this == CONVERSATION_AND_FILES;
        }

        static RestoreMode from(String value) {
            if ("files_only".equals(value)) {
                return FILES_ONLY;
            }
            if ("conversation_and_files".equals(value)) {
                return CONVERSATION_AND_FILES;
            }
            return CONVERSATION_ONLY;
        }
    }

    public record RunFileDiff(boolean beforeExists, boolean afterExists, FileDiff diff) {
    }

    public record SnapshotRef(String threadId, long seq, String takenAt) {
    }

    public static class SnapshotIndexException extends RuntimeException {
        public SnapshotIndexException(Path path, Throwable cause) {
            super("snapshot index unreadable: " + path, cause);
        }
    }

    private static class KnownFileState {
        static final KnownFileState EMPTY = new KnownFileState();

        final Set<String> hashes = new HashSet<>();
        boolean deleted;
    }

    public static class SnapshotCaptureContext {
        private final SnapshotService service;
        public final String threadId;
        public final String rootThreadId;
        public final String runId;
        public final long seq;
        public final String agentId;
        public final String parentThreadId;
        public final String subagentThreadId;

        SnapshotCaptureContext(SnapshotService service, String threadId, String rootThreadId, String runId, long seq,
            String agentId, String parentThreadId, String subagentThreadId) {
            this.service = service;
            this.threadId = threadId;
            this.rootThreadId = rootThreadId == null ? threadId : rootThreadId;
            this.runId = runId;
            this.seq = seq;
            this.agentId = agentId;
            this.parentThreadId = parentThreadId;
            this.subagentThreadId = subagentThreadId;
        }

        public boolean captureBefore(Path path) {
            return service.captureBefore(this, path);
        }

        public List<Path> captureDirectoryBefore(Path directory) {
            return service.captureDirectoryBefore(this, directory);
        }

        public boolean captureBeforeLocked(Path path) {
            return service.captureBeforeWithHeldPathLock(this, path);
        }

        public void recordAfterHash(Path path) {
            service.recordAfterHash(this, path);
        }

        public void recordAfterHashLocked(Path path) {
            service.recordAfterHashWithHeldPathLock(this, path);
        }

        public void captureGitBefore(String relativePath, byte[] beforeBytes) {
            service.captureGitBefore(this, relativePath, beforeBytes);
        }

        public void recordGitCreated(String relativePath, Path currentPath) {
            service.recordGitCreated(this, relativePath, currentPath);
        }

        public void warn(String warning) {
            service.warn(this, warning);
        }

        public <T> T withPathLock(Path path, Callable<T> action) throws Exception {
            synchronized (service.pathLockFor(runId, path)) {
                return action.call();
            }
        }

        public SnapshotCaptureContext forSubagent(String childThreadId) {
            return new SnapshotCaptureContext(service, childThreadId, rootThreadId, runId, seq,
                "subagent_" + childThreadId, threadId, childThreadId);
        }
    }

    @Data
    @Schema(name = "WorkspaceSnapshot", requiredProperties = {"seq"})
    public static class Snapshot {
        public long seq;
        public String label;
        public String kind;
        public String runId;
        public String threadId;
        public String rootThreadId;
        public String toolCallId;
        public String agentId;
        public String parentThreadId;
        public String subagentThreadId;
        public String takenAt;
        public String conversationHash;
        public boolean conversationRestorable;
        public boolean fileRestorable;
        public String fileCoverage;
        public List<String> toolSummaries = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<FileSnapshotEntry> fileEntries = new ArrayList<>();
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(hidden = true)
        public Boolean runBaselineComplete;
        public Object vcs;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Integer iteration;
        public int msgCount;
    }

    @Data
    public static class FileSnapshotEntry {
        public String scope;
        public String path;
        public boolean existed;
        public String beforeHash;
        public Boolean afterExisted;
        public String afterHash;
        public Long beforeSize;
        public Long afterSize;
        public Long beforeMtime;
        public String changeType;
        public Integer linesAdded;
        public Integer linesDeleted;
        public boolean diffAvailable;
        public boolean diffBinary;
        public boolean diffWhitespaceOnly;
        public boolean diffTruncated;
        public boolean skipped;
        public String skippedReason;
        public String threadId;
        public String agentId;
        public String parentThreadId;
        public String subagentThreadId;
    }

    @Data
    public static class ConversationPayload {
        public SnapshotArchive.Reference archive;
        public String messagesHash;
        public String stateHash;
        public String todosHash;
        public String model;
        public String provider;
        public String reasoningEffort;
        public List<String> toolsets = new ArrayList<>();
        public Map<String, Object> todosMeta = new LinkedHashMap<>();
    }

    private static class ConversationSnapshotPayload {
        String manifestHash;
        long approxBytes;
    }

    private record ConversationRestoreData(ConversationPayload payload, List<ChatMessage> messages,
        ThreadState state, List<Map<String, Object>> todos) {
    }

    private record SnapshotPath(String scope, String path, Path absolutePath) {
    }

    private record GitignoreScope(Path root, String rel) {
    }

    @Data
    @Schema(name = "WorkspaceSnapshotRestorePlanRequest")
    public static class RestorePlanRequest {
        public String mode = "conversation_only";
        public List<String> paths;

        public RestorePlanRequest() {
        }

        public RestorePlanRequest(String mode, List<String> paths) {
            this.mode = mode;
            this.paths = paths;
        }
    }

    @Data
    @Schema(name = "WorkspaceSnapshotRestoreRequest", requiredProperties = {"commandId"})
    public static class RestoreRequest implements StrictRequest {
        public String commandId;
        public Long expectedHistoryRevision;
        public String mode = "conversation_only";
        public boolean force;
        public List<String> paths;

        public static RestoreRequest conversationOnly() {
            RestoreRequest r = new RestoreRequest();
            r.mode = "conversation_only";
            return r;
        }
    }

    @Data
    @Schema(name = "WorkspaceSnapshotRestorePlan", requiredProperties = {"seq","mode"})
    public static class RestorePlanResponse {
        public Long historyRevision;
        public long seq;
        public String mode;
        public boolean conversationRestorable;
        public String fileCoverage;
        public boolean canRestoreFiles;
        public List<String> warnings;
        public List<RestoreFilePlan> files;
        public List<RestoreFilePlan> conflicts;
    }

    @Data
    public static class RestoreFilePlan {
        public String scope;
        public String path;
        public String action;
        public boolean conflict;
        public String reason;
        public String beforeHash;
        public String changeType;
        public Integer linesAdded;
        public Integer linesDeleted;
        public boolean diffAvailable;
        public boolean diffBinary;
        public boolean diffWhitespaceOnly;
        public boolean diffTruncated;
    }

    @Data
    @Schema(name = "WorkspaceSnapshotRestoreResult")
    public static class RestoreResponse {
        public String conversationId;
        public Long historyRevision;
        public String leafId;
        public boolean restored;
        public boolean conversationRestored;
        public boolean filesRestored;
        public String fileCoverage;
        public List<RestoreFilePlan> conflicts;
        public List<String> warnings;
        public int restoredFileCount;
        public int skippedFileCount;
        public List<Map<String, Object>> failedFiles;
        public String reason;
    }
}
