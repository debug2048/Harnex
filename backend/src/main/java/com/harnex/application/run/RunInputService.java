package com.harnex.application.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.harnex.artifact.ArtifactAttachments;
import com.harnex.artifact.ArtifactException;
import com.harnex.audit.RunInputAuditService;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.PlanReviewService;
import com.harnex.execution.AgentSpec;
import com.harnex.model.MessageAttachment;
import com.harnex.contract.run.RunInputCreateRequest;
import com.harnex.model.StreamEvent;
import com.harnex.run.IdempotencyReceiptRecord;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.run.RunCoordinator;
import com.harnex.run.RunHandle;
import com.harnex.run.RunInputMailboxRecord;
import com.harnex.run.RunInputMode;
import com.harnex.run.RunInputRecord;
import com.harnex.run.RunInputStatus;
import com.harnex.run.RunInputStore;
import com.harnex.run.RunInputStore.InputIdempotencyConflictException;
import com.harnex.run.RunInputStore.InputNotFoundException;
import com.harnex.run.RunInputStore.InputNotWithdrawableException;
import com.harnex.run.RunStatus;
import com.harnex.util.Ids;
import com.harnex.tool.approval.ApprovalService;
import com.harnex.tool.approval.UserPromptService;

/** Application service for durable Run Input enqueue/list/withdraw operations. */
@Service
@ProjectRuntime
public class RunInputService {

    private static final int MAX_PENDING = 20;
    private static final int MAX_OPERATIONS_PER_MINUTE = 60;
    private static final int MAX_RATE_BUCKETS = 2048;

    private final RunCoordinator runs;
    private final RunInputStore store;
    private final IdempotencyReceiptStore receipts;
    private final ArtifactAttachments attachments;
    private final RunInputAuditService audit;
    private final PlanReviewService planReviews;
    private final ApprovalService approvals;
    private final UserPromptService userPrompts;
    private final Map<String, RateBucket> rateByRun = new ConcurrentHashMap<>();

    public RunInputService(RunCoordinator runs, RunInputStore store, IdempotencyReceiptStore receipts,
        ArtifactAttachments attachments, RunInputAuditService audit) {
        this(runs, store, receipts, attachments, audit, null, null, null);
    }

    @Autowired
    public RunInputService(RunCoordinator runs, RunInputStore store, IdempotencyReceiptStore receipts,
        ArtifactAttachments attachments, RunInputAuditService audit, PlanReviewService planReviews,
        ApprovalService approvals, UserPromptService userPrompts) {
        this.runs = runs;
        this.store = store;
        this.receipts = receipts;
        this.attachments = attachments;
        this.audit = audit;
        this.planReviews = planReviews;
        this.approvals = approvals;
        this.userPrompts = userPrompts;
    }

    public synchronized OperationResult enqueue(String runId, RunInputCreateRequest request) {
        if (!Ids.isSafeCallId(runId)) return rejected(400, "bad_request", "invalid runId");
        if (request == null) return rejected(400, "bad_request", "request body is required");
        if (!request.unknownFieldNames().isEmpty()) {
            return rejected(400, "bad_request",
                "unknown fields: " + String.join(", ", request.unknownFieldNames()));
        }
        RunInputMode mode;
        try {
            mode = request.resolvedMode();
        } catch (IllegalArgumentException invalid) {
            return rejected(400, "invalid_input_mode", invalid.getMessage());
        }
        String requestId = request.getClientRequestId();
        if (requestId == null || requestId.isBlank() || requestId.length() > 256) {
            return rejected(400, "bad_request", "clientRequestId is required and must not exceed 256 characters");
        }
        String text = request.getText();
        List<String> requestedAttachments = request.getAttachmentIds() == null ? List.of()
            : new ArrayList<>(request.getAttachmentIds());
        if (requestedAttachments.size() > RunInputRecord.MAX_ATTACHMENTS
            || requestedAttachments.stream().anyMatch(value -> value == null || value.isBlank())) {
            return rejected(400, "bad_request", "attachmentIds must contain at most 20 non-blank asset identities");
        }
        if (((text == null || text.isBlank()) && requestedAttachments.isEmpty())
            || (text != null && text.length() > RunInputRecord.MAX_TEXT_CHARS)) {
            return rejected(400, "bad_request", "text or attachments are required; text must not exceed 32 KiB");
        }
        if (text == null) text = "";
        String channel = request.getChannel() == null || request.getChannel().isBlank()
            ? "webui" : request.getChannel().trim();
        if (!channel.matches("[A-Za-z0-9_.:-]{1,64}")) {
            return rejected(400, "bad_request", "invalid channel");
        }
        String requestHash = enqueueHash(mode, text, requestedAttachments, channel);
        OperationResult replay = replayEnqueue(runId, requestId, requestHash);
        if (replay != null) return replay;

        RunSnapshot source = snapshot(runId);
        if (source == null) return rejected(404, "run_not_found", "no such run");
        if (!source.agent()) return rejected(409, "unsupported_control", "Only Agent tasks accept steering; queue a new conversation submission instead");
        if (mode == RunInputMode.STEER && source.status().isTerminal()) {
            return rejected(409, "run_terminal", "STEER cannot be queued after the source run terminated");
        }
        if (!tryAcquire(runId)) return rejected(429, "rate_limited", "run input rate limit exceeded");
        if (store.pendingCount(runId) >= MAX_PENDING) {
            return rejected(409, "pending_input_limit", "the run already has 20 pending inputs");
        }

        try { attachments.validate(requestedAttachments); }
        catch (ArtifactException invalid) { return rejected(400, invalid.code(), invalid.getMessage()); }
        List<String> safeAttachments = List.copyOf(requestedAttachments);
        RunInputStatus initial = RunInputStatus.QUEUED;
        RunInputStore.Mutation mutation;
        try {
            mutation = store.enqueue(runId, source.threadId(), mode, text, safeAttachments, channel, initial,
                requestId, requestHash);
        } catch (InputIdempotencyConflictException conflict) {
            return rejected(409, "idempotency_conflict",
                "clientRequestId was already used with a different run input");
        }

        // Close the enqueue-vs-terminal race. If terminalisation happened after the source snapshot but before this
        // atomic mailbox replace, converge the newly written input before committing its success receipt.
        RunSnapshot after = snapshot(runId);
        if (after != null && after.status().isTerminal()) store.onRunTerminal(runId, after.status());
        RunInputRecord persisted = store.findInput(runId, mutation.input().inputId()).orElseThrow();
        Map<String, Object> body = inputBody(persisted);
        receipts.commit("run.input.enqueue", runId, requestId, requestHash, body, persisted.inputId(),
            store.mailboxRevision(runId));
        audit.record("enqueued", persisted);
        publish(persisted, eventFor(persisted.status()));
        if (persisted.mode() == RunInputMode.STEER && persisted.status() == RunInputStatus.QUEUED) {
            supersedePendingInteractions(persisted);
        }
        return accepted(body);
    }

    public synchronized OperationResult withdraw(String runId, String inputId, String clientRequestId) {
        if (!Ids.isSafeCallId(runId) || !Ids.isSafeCallId(inputId)) {
            return rejected(400, "bad_request", "invalid runId or inputId");
        }
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 256) {
            return rejected(400, "bad_request", "clientRequestId is required and must not exceed 256 characters");
        }
        String target = withdrawTarget(runId, inputId);
        String requestHash = IdempotencyReceiptStore.requestHash(Map.of("runId", runId, "inputId", inputId));
        OperationResult replay = replayWithdraw(runId, inputId, target, clientRequestId, requestHash);
        if (replay != null) return replay;
        if (snapshot(runId) == null) return rejected(404, "run_not_found", "no such run");
        if (!tryAcquire(runId)) return rejected(429, "rate_limited", "run input rate limit exceeded");
        RunInputStore.Mutation mutation;
        try {
            mutation = store.withdraw(runId, inputId, clientRequestId, requestHash);
        } catch (InputNotFoundException missing) {
            return rejected(404, "input_not_found", "no such run input");
        } catch (InputNotWithdrawableException conflict) {
            return rejected(409, "input_not_withdrawable",
                "run input cannot be withdrawn in status " + conflict.status().name());
        } catch (InputIdempotencyConflictException conflict) {
            return rejected(409, "idempotency_conflict",
                "clientRequestId was already used with a different withdrawal");
        }
        Map<String, Object> body = inputBody(mutation.input());
        receipts.commit("run.input.withdraw", target, clientRequestId, requestHash, body,
            mutation.input().inputId(), mutation.mailboxRevision());
        audit.record("withdrawn", mutation.input());
        publish(mutation.input(), "run_input_cancelled");
        return ok(body);
    }

    public ListResult list(String runId, String status) {
        if (!Ids.isSafeCallId(runId)) return ListResult.rejected("bad_request", "invalid runId");
        if (snapshot(runId) == null) return ListResult.rejected("run_not_found", "no such run");
        RunInputStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = RunInputStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                return ListResult.rejected("bad_request", "invalid run input status: " + status);
            }
        }
        List<Map<String, Object>> views = store.list(runId, filter).stream().map(RunInputRecord::toView).toList();
        return ListResult.ok(views);
    }

    /** Thread deletion hook: purge mailbox projections, idempotency receipts and transient rate buckets. */
    public synchronized void purgeThread(String threadId) {
        List<RunInputMailboxRecord> victims = store.purgeThread(threadId);
        for (RunInputMailboxRecord mailbox : victims) {
            receipts.purgeScope("run.input.enqueue", mailbox.runId());
            for (RunInputRecord input : mailbox.inputs()) {
                receipts.purgeScope("run.input.withdraw", withdrawTarget(mailbox.runId(), input.inputId()));
            }
            rateByRun.remove(mailbox.runId());
        }
    }

    private OperationResult replayEnqueue(String runId, String requestId, String requestHash) {
        IdempotencyReceiptRecord receipt = receipts.find("run.input.enqueue", runId, requestId).orElse(null);
        if (receipt != null) {
            if (!requestHash.equals(receipt.requestHash())) {
                return rejected(409, "idempotency_conflict",
                    "clientRequestId was already used with a different run input");
            }
            RunInputRecord existing = store.findInput(runId, receipt.resourceId()).orElse(null);
            if (existing == null) {
                return rejected(410, "idempotency_resource_gone", "the original run input no longer exists");
            }
            if (existing.mode() == RunInputMode.STEER && existing.status() == RunInputStatus.QUEUED) {
                supersedePendingInteractions(existing);
            }
            return accepted(receipt.result());
        }
        RunInputRecord claimed = store.findByEnqueueRequest(runId, requestId).orElse(null);
        if (claimed == null) return null;
        if (!requestHash.equals(claimed.requestHash())) {
            return rejected(409, "idempotency_conflict",
                "clientRequestId was already used with a different run input");
        }
        Map<String, Object> body = inputBody(claimed);
        receipts.commit("run.input.enqueue", runId, requestId, requestHash, body, claimed.inputId(),
            store.mailboxRevision(runId));
        audit.record("enqueue_receipt_reconstructed", claimed);
        if (claimed.mode() == RunInputMode.STEER && claimed.status() == RunInputStatus.QUEUED) {
            supersedePendingInteractions(claimed);
        }
        return accepted(body);
    }

    private OperationResult replayWithdraw(String runId, String inputId, String target, String requestId,
        String requestHash) {
        IdempotencyReceiptRecord receipt = receipts.find("run.input.withdraw", target, requestId).orElse(null);
        if (receipt != null) {
            if (!requestHash.equals(receipt.requestHash())) {
                return rejected(409, "idempotency_conflict",
                    "clientRequestId was already used with a different withdrawal");
            }
            if (store.findInput(runId, receipt.resourceId()).isEmpty()) {
                return rejected(410, "idempotency_resource_gone", "the original run input no longer exists");
            }
            return ok(receipt.result());
        }
        RunInputRecord claimed = store.findByWithdrawRequest(runId, inputId, requestId).orElse(null);
        if (claimed == null) return null;
        if (!requestHash.equals(claimed.withdrawRequestHash())) {
            return rejected(409, "idempotency_conflict",
                "clientRequestId was already used with a different withdrawal");
        }
        Map<String, Object> body = inputBody(claimed);
        receipts.commit("run.input.withdraw", target, requestId, requestHash, body, claimed.inputId(),
            store.mailboxRevision(runId));
        audit.record("withdraw_receipt_reconstructed", claimed);
        return ok(body);
    }

    private RunSnapshot snapshot(String runId) {
        Map<String, Object> view = runs.view(runId);
        if (view == null) return null;
        try {
            return new RunSnapshot(String.valueOf(view.get("threadId")),
                RunStatus.valueOf(String.valueOf(view.get("status"))), view.get("executionSpec") instanceof AgentSpec);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private void publish(RunInputRecord input, String type) {
        RunHandle run = runs.get(input.runId());
        if (run == null) return;
        runs.eventBus().publish(run, StreamEvent.of(type, Map.of(
            "inputId", input.inputId(), "mode", input.mode().name(), "status", input.status().name(),
            "sequence", input.sequence(), "revision", input.revision())));
    }

    private void supersedePendingInteractions(RunInputRecord input) {
        int planCount = planReviews == null ? 0 : planReviews.supersedePendingForThread(input.threadId());
        int approvalCount = approvals == null ? 0 : approvals.supersedePendingForThread(input.threadId());
        int questionCount = userPrompts == null ? 0 : userPrompts.supersedePendingForThread(input.threadId());
        int total = planCount + approvalCount + questionCount;
        if (total == 0) return;
        RunHandle run = runs.get(input.runId());
        if (run != null) runs.eventBus().publish(run, StreamEvent.of("interactions_superseded", Map.of(
            "reason", "user_steering", "inputId", input.inputId(), "count", total,
            "planReviews", planCount, "approvals", approvalCount, "questions", questionCount)));
    }

    private boolean tryAcquire(String runId) {
        if (rateByRun.size() >= MAX_RATE_BUCKETS && !rateByRun.containsKey(runId)) {
            long cutoff = System.currentTimeMillis() - 120_000L;
            rateByRun.entrySet().removeIf(entry -> entry.getValue().lastAccess < cutoff);
            if (rateByRun.size() >= MAX_RATE_BUCKETS) return false;
        }
        return rateByRun.computeIfAbsent(runId, ignored -> new RateBucket()).tryAcquire();
    }

    private static String enqueueHash(RunInputMode mode, String text, List<String> attachments, String channel) {
        Map<String, Object> fingerprint = new TreeMap<>();
        fingerprint.put("mode", mode.name());
        fingerprint.put("text", text);
        fingerprint.put("attachmentIds", attachments);
        fingerprint.put("channel", channel);
        return IdempotencyReceiptStore.requestHash(fingerprint);
    }

    private static Map<String, Object> inputBody(RunInputRecord input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", input.toView());
        return Map.copyOf(body);
    }

    private static String withdrawTarget(String runId, String inputId) {
        return runId + ":" + inputId;
    }

    private static String eventFor(RunInputStatus status) {
        return switch (status) {
            case CANCELLED -> "run_input_cancelled";
            case HELD -> "run_input_held";
            case REJECTED -> "run_input_rejected";
            default -> "run_input_queued";
        };
    }

    private static OperationResult accepted(Map<String, Object> body) {
        return new OperationResult(202, body, null, null);
    }

    private static OperationResult ok(Map<String, Object> body) {
        return new OperationResult(200, body, null, null);
    }

    private static OperationResult rejected(int status, String code, String message) {
        return new OperationResult(status, Map.of(), code, message);
    }

    public record OperationResult(int httpStatus, Map<String, Object> body, String rejectCode, String rejectMessage) {
        public boolean rejected() {
            return rejectCode != null;
        }
    }

    public record ListResult(List<Map<String, Object>> inputs, String rejectCode, String rejectMessage) {
        static ListResult ok(List<Map<String, Object>> inputs) {
            return new ListResult(inputs, null, null);
        }

        static ListResult rejected(String code, String message) {
            return new ListResult(List.of(), code, message);
        }
    }

    private record RunSnapshot(String threadId, RunStatus status, boolean agent) {}

    private static final class RateBucket {
        private long windowStart = System.currentTimeMillis();
        private volatile long lastAccess = windowStart;
        private int count;

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            lastAccess = now;
            if (now - windowStart > 60_000L) {
                windowStart = now;
                count = 0;
            }
            if (count >= MAX_OPERATIONS_PER_MINUTE) return false;
            count++;
            return true;
        }
    }
}
