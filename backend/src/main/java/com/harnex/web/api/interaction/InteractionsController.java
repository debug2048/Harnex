package com.harnex.web.api.interaction;

import com.harnex.auth.ClientIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentInteractionSource;
import com.harnex.core.PendingSlots;
import com.harnex.core.PlanReviewService;
import com.harnex.core.PlanReviewService.PlanDecision;
import com.harnex.interaction.PendingInteraction;
import com.harnex.run.RunRegistry;
import com.harnex.audit.InteractionAuditService;
import com.harnex.tool.approval.ApprovalService;
import com.harnex.tool.approval.UserPromptService;
import com.harnex.util.Ids;

/**
 * Unified interactions resource (docs/03 §3.14): approvals, plan reviews and ask_user questions share one lifecycle
 * (pending → resolved, thread-scoped callId, TTL) and differ only in payload — so they share one pending-list and one
 * resolve endpoint, discriminated by {@code kind}. IM bridges / reconnecting SPAs / VS Code ask "what is waiting on
 * the user?" exactly once instead of polling three services. The underlying {@link ApprovalService},
 * {@link PlanReviewService} and {@link UserPromptService} are unchanged; only the controller layer is unified.
 */
@RestController
@ProjectRuntime
public class InteractionsController {

    public static final String KIND_APPROVAL = "approval";
    public static final String KIND_PLAN_REVIEW = "plan_review";
    public static final String KIND_QUESTION = "question";

    private final ApprovalService approval;
    private final PlanReviewService planReview;
    private final UserPromptService prompts;
    private final InteractionAuditService audit;
    @Autowired(required = false)
    private RunRegistry runs;

    public InteractionsController(ApprovalService approval, PlanReviewService planReview, UserPromptService prompts,
        InteractionAuditService audit) {
        this.approval = approval;
        this.planReview = planReview;
        this.prompts = prompts;
        this.audit = audit;
    }

    /** Cross-thread aggregation — "what is waiting on me anywhere" for IM bridges / tray / VS Code badges. */
    @GetMapping("/api/v1/interactions")
    public ResponseEntity<Map<String, Object>> listAll(@RequestParam(defaultValue = "pending") String status) {
        if ("resolved".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(Map.of("interactions", audit.listResolved(null)));
        }
        if (!"pending".equalsIgnoreCase(status)) {
            return badRequest("status must be pending or resolved");
        }
        return ResponseEntity.ok(Map.of("interactions", collect(null)));
    }

    @GetMapping("/api/v1/agent/conversations/{threadId}/interactions")
    public ResponseEntity<Map<String, Object>> listForThread(@PathVariable String threadId,
        @RequestParam(defaultValue = "pending") String status) {
        if (!Ids.isSafe(threadId)) {
            return badRequest("invalid threadId");
        }
        if ("resolved".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(Map.of("interactions", audit.listResolved(threadId)));
        }
        if (!"pending".equalsIgnoreCase(status)) {
            return badRequest("status must be pending or resolved");
        }
        return ResponseEntity.ok(Map.of("interactions", collect(threadId)));
    }

    /**
     * Resolve a pending interaction. Body: {@code {kind, decision?, answer?, feedback?, whitelist?}} —
     * <ul>
     * <li>{@code kind=approval}: {@code decision=allow|deny}, optional {@code whitelist=true} (allow + auto-approve
     * future calls of the same tool on this thread)</li>
     * <li>{@code kind=plan_review}: {@code decision=approve|reject}, optional {@code feedback}</li>
     * <li>{@code kind=question}: {@code answer} free text / selected option</li>
     * </ul>
     * Returns 410 when nothing is pending for the callId (already resolved, expired, or unknown).
     */
    @PostMapping("/api/v1/agent/conversations/{threadId}/interactions/{callId}")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable String threadId, @PathVariable String callId,
        @RequestBody Map<String, Object> body) {
        if (!Ids.isSafe(threadId) || !Ids.isSafeCallId(callId)) {
            return badRequest("invalid id");
        }
        String kind = body == null ? "" : String.valueOf(body.getOrDefault("kind", ""));
        boolean accepted;
        switch (kind) {
            case KIND_APPROVAL -> {
                String decision = str(body, "decision", "deny").toLowerCase();
                boolean whitelist = Boolean.parseBoolean(String.valueOf(body.getOrDefault("whitelist", "false")));
                String choice = "allow".equals(decision) ? (whitelist ? "whitelist" : "allow") : "deny";
                accepted = approval.recordDecision(threadId, callId, "", choice);
            }
            case KIND_PLAN_REVIEW -> {
                String decision = str(body, "decision", "").toLowerCase();
                boolean approve = "approve".equals(decision) || "approved".equals(decision);
                boolean reject = "reject".equals(decision) || "rejected".equals(decision);
                if (!approve && !reject) {
                    return badRequest("decision must be 'approve' or 'reject' for kind=plan_review");
                }
                String feedback = body.get("feedback") == null ? null : String.valueOf(body.get("feedback"));
                accepted = planReview.record(threadId, callId,
                    approve ? PlanDecision.approve() : PlanDecision.reject(feedback));
            }
            case KIND_QUESTION -> accepted = prompts.recordAnswer(threadId, callId, str(body, "answer", ""));
            default -> {
                return badRequest("kind must be one of: approval, plan_review, question");
            }
        }
        if (!accepted) {
            return ResponseEntity.status(410).body(Map.of("error", Map.of("code", "interaction_gone", "message",
                "no pending " + kind + " for this callId (already resolved, expired, or unknown)")));
        }
        audit.recordResolved(kind, threadId, callId, body);
        return ResponseEntity.ok(Map.of("resolved", true, "kind", kind, "callId", callId));
    }

    private List<PendingInteraction> collect(String threadIdOrNull) {
        return new AgentInteractionSource(approval, planReview, prompts).collect(threadIdOrNull).stream()
            .filter(item -> runs == null || runs.clientCanControlConversation(item.conversationId(), ClientIdentity.actor())).toList();
    }

    private static String str(Map<String, Object> body, String key, String dflt) {
        Object v = body == null ? null : body.get(key);
        return v == null ? dflt : String.valueOf(v);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "bad_request", "message", message)));
    }
}
