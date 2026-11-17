package com.harnex.application.session;

import com.harnex.model.ConversationThread;
import com.harnex.todo.TodoStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import com.harnex.attachment.UploadService;
import com.harnex.application.run.RunInputService;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.goal.GoalResourceService;
import com.harnex.core.PlanModeService;
import com.harnex.core.PlanReviewService;
import com.harnex.safety.FileAccessTracker;
import com.harnex.safety.PathProtection;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.session.SessionStore;
import com.harnex.snapshot.SnapshotService;
import com.harnex.tool.approval.ApprovalService;
import com.harnex.tool.approval.UserPromptService;
import com.harnex.tool.budget.BudgetEnforcer;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Wires cross-component lifecycle hooks that would otherwise create circular Spring bean dependencies. Kept
 * intentionally small.
 */
@Slf4j
@Component
@ProjectRuntime
public class SessionHooks {
    private final TodoStore todoStore;

    private final SessionStore store;
    private final ApprovalService approval;
    private final UserPromptService userPrompt;
    private final PlanModeService planMode;
    private final PlanReviewService planReview;
    private final WorkspacePathResolver vp;
    private final SnapshotService snapshots;
    private final FileAccessTracker tracker;
    private final PathProtection protection;
    private final BudgetEnforcer budget;
    private final AgentConfig cfg;
    /** Upload-owned transient state is purged with the rest of a thread's workspace data. */
    private final UploadService uploads;
    private final RunInputService runInputs;
    private final GoalResourceService goals;

    public SessionHooks(SessionStore store, ApprovalService approval,
        UserPromptService userPrompt, PlanModeService planMode, PlanReviewService planReview,
        WorkspacePathResolver vp, SnapshotService snapshots, FileAccessTracker tracker, PathProtection protection,
        BudgetEnforcer budget, AgentConfig cfg,
        UploadService uploads, RunInputService runInputs, GoalResourceService goals, TodoStore todoStore) {
        this.todoStore = todoStore;
        this.store = store;
        this.approval = approval;
        this.userPrompt = userPrompt;
        this.planMode = planMode;
        this.planReview = planReview;
        this.vp = vp;
        this.snapshots = snapshots;
        this.tracker = tracker;
        this.protection = protection;
        this.budget = budget;
        this.cfg = cfg;
        this.uploads = uploads;
        this.runInputs = runInputs;
        this.goals = goals;
    }

    @PostConstruct
    void register() {
        // Use the SessionStore interface hook rather than instanceof on a concrete impl —
        // this keeps the wiring intact when an alternative store (in-memory, JDBC, …) is
        // injected for tests or future deployments.
        // Each listener runs in isolation — a single noisy purge must not abort the rest.
        store.onThreadDeleted(safe("approval.purgeThread", approval::purgeThread));
        store.onThreadDeleted(safe("userPrompt.purgeThread", userPrompt::purgeThread));
        store.onThreadDeleted(safe("todoStore.purge", todoStore::purge));
        store.onThreadDeleted(safe("planMode.purge", planMode::purge));
        store.onThreadDeleted(safe("planReview.purgeThread", planReview::purgeThread));
        store.onThreadDeleted(safe("sessionHooks.purgePlans", this::purgePlans));
        store.onThreadDeleted(safe("sessionHooks.purgeUploads", this::purgeUploads));
        // Drop UploadService's per-thread in-memory rate-bucket entries so
        // they don't accumulate forever on a long-running server with many transient threads.
        store.onThreadDeleted(safe("uploadService.purge", uploads::purge));
        store.onThreadDeleted(safe("snapshots.purge", snapshots::purge));
        store.onThreadDeleted(safe("fileAccessTracker.purge", tracker::purge));
        store.onThreadDeleted(safe("pathProtection.purge", protection::purge));
        // Drop persisted full-payload tool results so <dataDir>/agent/tool-results/<thread>/ does not
        // grow unbounded on long-running deployments.
        store.onThreadDeleted(safe("budget.purge", budget::purgeThread));
        store.onThreadDeleted(safe("runInputs.purgeThread", runInputs::purgeThread));
        store.onThreadDeleted(safe("goals.purgeThread", goals::purgeThread));
    }

    private static Consumer<String> safe(String name, Consumer<String> inner) {
        return id -> {
            try {
                inner.accept(id);
            } catch (Exception e) {
                log.warn("thread-delete listener '{}' failed for {}: {}", name, id, e.toString());
            }
        };
    }

    /** Best-effort recursive cleanup of {@code data/uploads/<threadId>/} on thread deletion. */
    private void purgeUploads(String threadId) {
        try {
            Path dir = vp.uploadsFor(threadId);
            FileUtils.deleteDirectory(dir.toFile());
        } catch (IOException e) {
            log.debug("Cleanup uploads for {} failed: {}", threadId, e.toString());
        }
    }

    /** Best-effort cleanup of {@code data/plans/<threadId>/} (submit_plan output) on thread deletion. */
    private void purgePlans(String threadId) {
        try {
            Path dir = Path.of(cfg.getDataDir(), "agent/plans", threadId);
            FileUtils.deleteDirectory(dir.toFile());
        } catch (IOException e) {
            log.debug("Cleanup plans for {} failed: {}", threadId, e.toString());
        }
    }

    /**
     * Public cleanup hook for <b>ephemeral / synthetic threads</b> that never went through
     * {@link com.harnex.session.SessionStore#save(ConversationThread)} and therefore will never fire
     * {@code onThreadDeleted} listeners. Today the only such caller is {@code McpServerController#handleRpc}, which
     * mints a fresh {@code mcp-server-<uuid>} thread per JSON-RPC {@code tools/call} so that {@code ApprovalService} /
     * {@code PlanMode} decisions stay isolated. Without this cleanup, every MCP tool call that uploads a file leaves a
     * {@code data/uploads/mcp-server-<uuid>/} directory on disk forever — a steady inode / disk-space leak on any
     * long-running deployment that exposes the MCP server.
     *
     * <p>
     * This intentionally only wipes filesystem state (uploads) and a small set of in-memory caches that
     * {@code ApprovalService} may have populated. It does NOT touch snapshots / budget / tracker — those services are
     * only ever populated through {@code SessionStore.save}, which a synthetic thread never invokes, so they have no
     * per-id state to purge.
     */
    public void purgeEphemeralThread(String threadId) {
        if (threadId == null || threadId.isBlank())
            return;
        purgeUploads(threadId);
        try {
            approval.purgeThread(threadId);
        } catch (Exception e) {
            log.debug("ephemeral purge approval for {} failed: {}", threadId, e.toString());
        }
        try {
            planMode.purge(threadId);
        } catch (Exception e) {
            log.debug("ephemeral purge planMode for {} failed: {}", threadId, e.toString());
        }
    }
}
