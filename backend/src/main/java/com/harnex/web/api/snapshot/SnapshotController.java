package com.harnex.web.api.snapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.config.ProjectRuntime;
import com.harnex.conversation.ConversationException;
import com.harnex.contract.StrictRequest;
import com.harnex.run.ActiveAgentRegistry;
import com.harnex.run.RunCoordinator;
import com.harnex.session.SessionStore;
import com.harnex.snapshot.SnapshotService;
import com.harnex.util.Ids;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Snapshot / undo / branch endpoints.
 *
 * <ul>
 * <li>{@code GET /api/threads/{id}/snapshots} \u2014 list (newest first)</li>
 * <li>{@code POST /api/threads/{id}/snapshots?label=...} \u2014 take a manual snapshot</li>
 * <li>{@code POST /api/threads/{id}/snapshots/{seq}/restore} \u2014 in-place undo to that snapshot</li>
 * <li>{@code POST /api/threads/{id}/snapshots/{seq}/branch?title=...}\u2014 fork into a NEW thread</li>
 * </ul>
 */
@RestController
@ProjectRuntime
@RequestMapping("/api/v1/agent/conversations/{id}/snapshots")
public class SnapshotController {

    private final SnapshotService snapshots;
    private final SessionStore store;
    private final ActiveAgentRegistry active;
    @Autowired(required = false)
    private RunCoordinator runs;

    public SnapshotController(SnapshotService snapshots, SessionStore store,
        ActiveAgentRegistry active) {
        this.snapshots = snapshots;
        this.store = store;
        this.active = active;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SnapshotService.Snapshot.class))))
    public ResponseEntity<?> list(@PathVariable String id) {
        if (!Ids.isSafe(id)) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(snapshots.listOrThrow(id));
        } catch (SnapshotService.SnapshotIndexException e) {
            return ResponseEntity.status(500).body(Map.of("error", "snapshot index is unreadable",
                "reason", "snapshot_index_unreadable"));
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> take(@PathVariable String id,
        @RequestParam(required = false) String label) {
        if (!Ids.isSafe(id)) {
            return ResponseEntity.badRequest().build();
        }
        // Refuse manual snapshot while the thread is being driven by an AgentLoop. SnapshotService
        // serialises {@code thread.getMessages()} directly and FtsSessionStore.get returns the
        // SAME live {@code ConversationThread} instance held by the in-flight loop, so a manual
        // take racing the loop's {@code messages.add(toolResult)} can either CME on the ArrayList
        // or capture a half-step transcript that restores into an inconsistent state (e.g.
        // assistant tool_call with no matching tool result). File before-blob capture can also
        // race a running file_write / bash tool. AgentLoop's own pre-tool snapshot calls are still
        // allowed because they are issued from inside the loop at a safe checkpoint (after one step,
        // before the next tool).
        //
        // CRITICAL: a bare {@code isLive} check is check-then-act — between our check and
        // {@code snapshots.take} another /api/v1/runs request can reserve the same id
        // and proceed into AgentLoop, so the take would still race the loop's first
        // messages.add. Atomically claim the reservation for the duration of the take so a
        // racing dispatcher gets a clean {@code thread_busy} at its tryReserve instead.
        // {@code tryReserve} also rejects when a live ctx already exists, which subsumes the
        // prior isLive() check. Mirrors the restore endpoint's posture exactly.
        Reservation reservation = reserve(id);
        if (reservation == null) {
            return ResponseEntity.status(409).body(Map.of("snapshotted", false, "error",
                "thread is currently running an agent — manual snapshot is unsafe while the loop is mutating messages/workspace; cancel the run or wait for it to finish",
                "reason", "thread_busy"));
        }
        try {
            return store.get(id).<ResponseEntity<Map<String, Object>>>map(t -> {
                long seq = snapshots.takeManual(t, label);
                return ResponseEntity.ok(Map.of("seq", seq));
            }).orElseGet(() -> ResponseEntity.notFound().build());
        } finally {
            // Always release — a crashed take must not lock the thread out permanently.
            reservation.close();
        }
    }

    @PostMapping("/{seq}/restore")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SnapshotService.RestoreResponse.class)))
    public ResponseEntity<?> restore(@PathVariable String id, @PathVariable long seq,
        @RequestBody SnapshotService.RestoreRequest request) {
        if (!Ids.isSafe(id)) {
            return ResponseEntity.badRequest().build();
        }
        var replay = snapshots.replayRestore(id,seq,request);
        if (replay != null) return ResponseEntity.status(replay.restored ? 200 : 409).body(replay);
        // Refuse in-place restore while the thread is being driven by an AgentLoop (live ctx OR
        // a dispatcher reservation that hasn't registered its ctx yet). The loop holds the
        // ConversationThread in memory and will overwrite it on its terminal sessions.save,
        // silently undoing the user's restore; concurrent workspace restoration would also
        // race the loop's file_write / bash tools mid-step. Branching is unaffected — it
        // always forks into a NEW thread id and never touches the live one.
        //
        // CRITICAL: just calling isLive() here is check-then-act — between our check and
        // {@code snapshots.restore} another /api/v1/runs request can reserve the same
        // id and proceed into AgentLoop, with the same end state we tried to prevent (loop
        // overwrites our restore on terminal save). So we ATOMICALLY claim the reservation
        // for the duration of the restore. {@code tryReserve} also rejects if a live ctx is
        // already registered, so this single call subsumes the prior {@code isLive} check.
        // Released in finally so a crashed restore cannot lock the thread out permanently.
        Reservation reservation = reserve(id);
        if (reservation == null) {
            return ResponseEntity.status(409)
                .body(Map.of("restored", false, "error",
                    "thread is currently running an agent — cancel the run before restoring,"
                        + " or use /branch to fork into a new thread instead",
                    "reason", "thread_busy"));
        }
        try {
            SnapshotService.RestoreResponse result = snapshots.restore(id, seq, request);
            if (result.restored) {
                return ResponseEntity.ok(result);
            }
            String reason = result.reason == null ? "unknown" : result.reason;
            int status = "not_found".equals(reason) ? 404 : Set.of("conflict","history_conflict","restore_outcome_unknown").contains(reason) ? 409 : 400;
            return ResponseEntity.status(status).body(result);
        } finally {
            reservation.close();
        }
    }

    @PostMapping("/{seq}/restore-plan")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SnapshotService.RestorePlanResponse.class)))
    public ResponseEntity<?> restorePlan(@PathVariable String id, @PathVariable long seq,
        @RequestBody SnapshotService.RestorePlanRequest request) {
        if (!Ids.isSafe(id)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(snapshots.restorePlan(id, seq, request));
    }

    @PostMapping("/{seq}/branch")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BranchResponse.class)))
    public ResponseEntity<Map<String, Object>> branch(@PathVariable String id, @PathVariable long seq,
        @RequestBody BranchRequest request) {
        if (!Ids.isSafe(id)) {
            return ResponseEntity.badRequest().build();
        }
        try {
            if (!Ids.isSafeCallId(request.commandId())) throw new ConversationException("bad_request","commandId is required");
            String forkId = snapshots.branch(id, seq, request.title(), request.commandId());
            return forkId != null ? ResponseEntity.ok(Map.of("conversationId", forkId))
                : ResponseEntity.status(404).body(Map.of("error", "snapshot or thread not found"));
        } catch (SnapshotService.SnapshotIndexException e) {
            return ResponseEntity.status(500).body(Map.of("error", "snapshot index is unreadable",
                "reason", "snapshot_index_unreadable"));
        }
    }
    @Schema(name = "WorkspaceSnapshotBranchRequest", requiredProperties = {"commandId"})
    public record BranchRequest(String commandId, String title) implements StrictRequest {}
    @Schema(name = "WorkspaceSnapshotBranchResult", requiredProperties = {"conversationId"})
    public record BranchResponse(String conversationId) {}
    private Reservation reserve(String id) {
        var common = runs == null ? null : runs.tryReserveConversationMutation(id);
        if (runs != null && common == null) return null;
        if (!active.tryReserve(id)) { if (common != null) common.close(); return null; }
        return new Reservation(id,common);
    }
    private final class Reservation implements AutoCloseable {
        private final String id;
        private final RunCoordinator.MutationLease common;
        private Reservation(String id, RunCoordinator.MutationLease common) { this.id = id; this.common = common; }
        @Override public void close() { try { active.releaseReservation(id); } finally { if (common != null) common.close(); } }
    }
    @ExceptionHandler(ConversationException.class)
    public ResponseEntity<?> historyFailure(ConversationException failure) {
        int status = Set.of("history_conflict","idempotency_conflict").contains(failure.code()) ? 409
            : "conversation_deleted".equals(failure.code()) ? 410 : 400;
        return ResponseEntity.status(status).body(Map.of("error",Map.of("code",failure.code(),"message",failure.getMessage())));
    }
}
