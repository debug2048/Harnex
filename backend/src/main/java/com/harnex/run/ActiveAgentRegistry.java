package com.harnex.run;

import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.core.ThreadExecutionGuard;
import com.harnex.model.ConversationThread;

/**
 * Thread-id → live AgentCtx registry. Lets the user-initiated cancel endpoint find and stop the in-flight agent loop
 * without going through the SSE emitter.
 */
@Component
@ProjectRuntime
public class ActiveAgentRegistry implements ThreadExecutionGuard {

    public enum PermissionModeUpdate {
        NONE, LIVE, PENDING
    }

    private final ConcurrentMap<String, AgentCtx> live = new ConcurrentHashMap<>();
    /**
     * Pre-registration single-flight reservations. {@link #register} only fires once AgentLoop has actually built an
     * {@link AgentCtx}, which happens AFTER store.get / attachment resolution / thread.getMessages().add. Two
     * near-simultaneous requests for the same thread used to slip past an isLive() check (both saw "no live ctx") and
     * then race those pre-loop steps — interleaving user messages, racing register(), cancelling the wrong ctx, and
     * corrupting the persisted thread on save. {@code tryReserve} closes that window: a request claims the thread the
     * moment it knows the id, and the second request sees the reservation and is refused even though no ctx is
     * registered yet. Releases happen in the cleanup hook so a crashed dispatcher cannot deadlock the thread
     * permanently — see {@code RunCoordinator}.
     */
    private final Set<String> reservations = ConcurrentHashMap.newKeySet();
    /**
     * Threads that have been {@code DELETE}d while a run was still live OR reserved. Consulted by AgentLoop's final
     * {@code sessions.save} so a loop that finishes AFTER the user pressed "delete" cannot resurrect the on-disk thread
     * JSON. Without this, ThreadController.delete would race AgentLoop's finally — uploads/snapshots all gone but a
     * fresh {@code <id>.json} written from in-memory state, leaving "the chat I just deleted reappears but its files
     * are missing". Cleared on {@link #unregister} so the entry cannot leak.
     */
    private final Set<String> tombstones = ConcurrentHashMap.newKeySet();
    /**
     * Cancel-intents recorded during the pre-registration window. {@link #cancel} used to return 404 (and
     * ChatController would relay that to a STILL-streaming SPA) when the user pressed STOP while the dispatch was
     * either (a) sitting in chatExec's queue, or (b) past {@code tryReserve} but before {@link AgentLoop} had built and
     * registered an {@link AgentCtx}. In that window cancel had nothing to interrupt; the dispatcher would then proceed
     * into the first LLM call and start burning tokens even though the user had clearly asked to stop.
     *
     * <p>
     * Now {@code cancel} records the intent here when no live ctx exists yet (but a reservation does, OR the id is
     * otherwise valid). The dispatcher consults {@link #isCancelled} at each pre-loop gate and the {@code onCtxReady}
     * hook auto-cancels the ctx the instant it is built. Cleared by {@link #unregister} / {@link #releaseReservation}
     * alongside the other markers so intents cannot leak across runs of the same id.
     * </p>
     */
    private final Set<String> cancelIntents = ConcurrentHashMap.newKeySet();
    /**
     * Permission-mode updates that land while a request has reserved the thread id but AgentLoop has not registered its
     * {@link AgentCtx} yet. This window is common for brand-new WebUI threads: the user can switch Full auto while the
     * first approval is pending, but the thread may not be persisted yet. Store the mode here so register() can apply
     * it to the live in-memory thread instead of forcing the user to wait for the next turn.
     */
    private final ConcurrentMap<String, String> pendingPermissionModes = new ConcurrentHashMap<>();

    public void register(String threadId, AgentCtx ctx) {
        if (threadId == null || ctx == null)
            return;
        live.put(threadId, ctx);
        String pendingMode = pendingPermissionModes.remove(threadId);
        if (pendingMode != null && ctx.getThread() != null && ctx.getThread().getState() != null) {
            ctx.getThread().getState().setPermissionMode(pendingMode);
        }
        // Honour any cancel-intent that landed during the pre-registration window
        // (chatExec queue / between tryReserve and AgentCtx build). Done here — inside
        // register() rather than at each call site — so the SIX register paths
        // Every root adapter registers through RunCoordinator's lease callback.
        // all honour STOP uniformly without per-caller plumbing. The loop's first
        // {@code ctx.checkpoint()} call will then short-circuit before any LLM I/O.
        if (cancelIntents.contains(threadId)) {
            ctx.cancel();
        }
    }

    public void unregister(String threadId, AgentCtx ctx) {
        if (threadId == null || ctx == null) {
            // ConcurrentHashMap.remove(key, null) returns false unless the *current value* is null,
            // which never happens here — register() refuses null ctx. Treating null ctx as a no-op
            // makes the contract obvious and prevents a silent leak when callers misuse the API.
            return;
        }
        if (!live.remove(threadId, ctx)) {
            return;
        }
        // Same reasoning for the tombstone: it has done its job (told the just-finished loop to
        // skip its final save). A future re-use of the same thread id (e.g. cron job that
        // deliberately reuses an id) must NOT inherit a stale "deleted" verdict.
        tombstones.remove(threadId);
        // Cancel-intents recorded during the pre-registration window are also single-run: if the
        // intent fired and the loop honoured it, we're done; if the intent fired AFTER the loop
        // had already finished cleanly, the next turn must not start out cancelled.
        cancelIntents.remove(threadId);
        pendingPermissionModes.remove(threadId);
    }

    /**
     * @return true if a live agent existed and was asked to cancel, OR a pre-registration reservation existed and we
     *         recorded a cancel-intent that the dispatcher will honour at its next gate / when AgentLoop builds the
     *         ctx. Returns false only when there is nothing in flight for this id (no live ctx, no reservation) — we
     *         deliberately do NOT record an intent in that case because it would have no consumer and no TTL, and the
     *         next legitimate run for this id (WebUI/cron/gateway, even minutes later) would inherit the stale intent
     *         via {@link #register} and self-cancel before its first LLM call. The race "intent landed microseconds
     *         before reservation" is accepted as lost — the user can press STOP again the instant they see the first
     *         SSE frame, which by definition means the ctx exists and direct cancel works.
     */
    public boolean cancel(String threadId) {
        if (threadId == null)
            return false;
        AgentCtx ctx = live.get(threadId);
        if (ctx != null) {
            ctx.cancel();
            // Also record so the SECOND register() (if a parallel-tool harness or retry rebuilds
            // the ctx) re-applies the cancel. Cleared by unregister/releaseReservation.
            cancelIntents.add(threadId);
            return true;
        }
        // No live ctx — only record the intent when SOMETHING in flight will consume it.
        // Without this guard, a stray cancel on an idle id leaves a dangling intent that
        // gets garbage-collected by the next legitimate run via register() — i.e. silently
        // kills the very next normal request for that id. The previous version had exactly
        // this bug ("P1: cancel-intent will pollute the next idle WebUI run").
        if (reservations.contains(threadId)) {
            cancelIntents.add(threadId);
            return true;
        }
        return false;
    }

    /** Abort the current in-memory execution without recording a RunCancel intent. Used only for Runtime DRAIN. */
    public boolean interruptExecution(String threadId) {
        if (threadId == null) return false;
        AgentCtx ctx = live.get(threadId);
        if (ctx == null) return false;
        ctx.interruptExecution();
        return true;
    }

    /**
     * @return true iff a cancel-intent was recorded during the pre-registration window and not yet consumed. Dispatcher
     *         pre-loop gates and the {@code onCtxReady} hook use this to abort BEFORE the first LLM call when the user
     *         pressed STOP while queued/reserving.
     */
    public boolean isCancelled(String threadId) {
        return threadId != null && cancelIntents.contains(threadId);
    }

    /**
     * @return true iff a live agent is currently running for this thread, OR a dispatcher has reserved the thread but
     *         not yet registered its ctx. Used by RunCoordinator to enforce single-flight (refuse a second concurrent run
     *         on the same thread).
     */
    public boolean isLive(String threadId) {
        return threadId != null && (live.containsKey(threadId) || reservations.contains(threadId));
    }

    /** Snapshot of every root thread currently reserved or executing. */
    public Set<String> activeThreadIds() {
        Set<String> ids = new HashSet<>(reservations);
        ids.addAll(live.keySet());
        return Set.copyOf(ids);
    }

    public int activeCount() {
        return activeThreadIds().size();
    }

    /** Signal cancellation to every currently reserved or executing root thread. */
    public int cancelAll() {
        Set<String> ids = activeThreadIds();
        ids.forEach(this::cancel);
        return ids.size();
    }

    /**
     * @return true iff a live AgentCtx has actually been registered (i.e. the loop has started making LLM calls).
     *         Distinct from {@link #isLive} which also returns true during the pre-registration reservation window.
     *         Callers that need to know whether a {@link #cancel} call could possibly succeed should consult this
     *         rather than {@code isLive}, which would otherwise lead them to report "cancelled=true" for a
     *         reservation-only entry where no ctx exists yet (and {@code cancel} has nothing to interrupt).
     */
    public boolean hasLiveCtx(String threadId) {
        return threadId != null && live.containsKey(threadId);
    }

    /**
     * Atomically claim the thread for an upcoming dispatch. Returns {@code false} if another run (live ctx OR an
     * earlier reservation) is already in progress; the caller should refuse the new request and not touch the thread
     * state. Always pair with {@link #releaseReservation} in a finally / cleanup hook so a crashed dispatcher cannot
     * lock the thread out permanently.
     */
    public boolean tryReserve(String threadId) {
        if (threadId == null)
            return false;
        if (!reservations.add(threadId))
            return false;
        // Re-check live AFTER claiming the reservation: an in-flight loop that has already
        // registered its ctx (so isn't represented in `reservations`) would otherwise be
        // missed and we'd incorrectly admit a second run.
        if (live.containsKey(threadId)) {
            reservations.remove(threadId);
            return false;
        }
        return true;
    }

    public void releaseReservation(String threadId) {
        if (threadId == null)
            return;
        reservations.remove(threadId);
        // Reservation-only paths (for example a pre-loop tombstone abort)
        // refused after tryReserve) never call unregister(ctx) — that's gated on a live AgentCtx
        // which the dispatcher never created. Without clearing here, the advisory tombstone /
        // related marker would leak forever for this id, so a future reuse of the same thread
        // id (cron job recycling stable ids, tests, an operator manually crafting a reused id)
        // would be permanently refused at the pre-loop gate even after FtsSessionStore's own
        // tombstone TTL has elapsed.
        //
        // Only safe to clear when no live ctx exists for the same id — otherwise we'd be
        // wiping a marker that belongs to an actually-running agent (e.g. a second dispatcher
        // racing in and out of tryReserve while the first one is mid-loop). This mirrors the
        // unregister() semantics: the markers' lifecycle is tied to "is this thread currently
        // being driven by anyone".
        if (!live.containsKey(threadId)) {
            tombstones.remove(threadId);
            cancelIntents.remove(threadId);
            pendingPermissionModes.remove(threadId);
        }
    }

    /**
     * Apply a user-selected permission mode to a running or reserved thread.
     *
     * @return whether the mode was applied to a live {@link AgentCtx}, recorded for a reserved-but-not-registered run,
     *         or ignored because there is no active run for this thread.
     */
    public PermissionModeUpdate setPermissionMode(String threadId, String mode) {
        if (threadId == null || mode == null || mode.isBlank()) {
            return PermissionModeUpdate.NONE;
        }
        AgentCtx ctx = live.get(threadId);
        if (ctx != null) {
            if (ctx.getThread() != null && ctx.getThread().getState() != null) {
                ctx.getThread().getState().setPermissionMode(mode);
                pendingPermissionModes.remove(threadId);
                return PermissionModeUpdate.LIVE;
            }
            return PermissionModeUpdate.NONE;
        }
        if (reservations.contains(threadId)) {
            pendingPermissionModes.put(threadId, mode);
            // Close the race where AgentLoop registered immediately after the first live.get() but before the pending
            // put. If that happened, apply directly and clear the pending entry.
            ctx = live.get(threadId);
            if (ctx != null && ctx.getThread() != null && ctx.getThread().getState() != null) {
                ctx.getThread().getState().setPermissionMode(mode);
                pendingPermissionModes.remove(threadId);
                return PermissionModeUpdate.LIVE;
            }
            return PermissionModeUpdate.PENDING;
        }
        return PermissionModeUpdate.NONE;
    }

    /**
     * Apply a permission-mode update that landed during the reservation-only window to a thread that will be persisted
     * without creating an AgentCtx (for example slash-command RESPOND paths). AgentLoop-backed runs consume the same
     * pending value in {@link #register(String, AgentCtx)}.
     */
    public boolean applyPendingPermissionMode(String threadId, ConversationThread thread) {
        if (threadId == null || thread == null || thread.getState() == null) {
            return false;
        }
        String pendingMode = pendingPermissionModes.remove(threadId);
        if (pendingMode == null) {
            return false;
        }
        thread.getState().setPermissionMode(pendingMode);
        return true;
    }

    /**
     * Mark a thread as deleted while it was still running. Consulted by AgentLoop's final {@code sessions.save} so an
     * in-flight loop cannot resurrect the on-disk JSON after the user has already pressed delete (and SessionHooks has
     * wiped uploads/snapshots).
     */
    public void markTombstone(String threadId) {
        if (threadId != null)
            tombstones.add(threadId);
    }

    /** Whether {@link #markTombstone} was called for this thread (and not yet cleared). */
    public boolean isTombstoned(String threadId) {
        return threadId != null && tombstones.contains(threadId);
    }
}
