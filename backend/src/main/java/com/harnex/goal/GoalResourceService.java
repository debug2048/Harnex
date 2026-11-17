package com.harnex.goal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.core.PermissionContext;
import com.harnex.execution.PlanReviewPolicy;
import com.harnex.core.StepLimits;
import com.harnex.goal.GoalRecord.Budget;
import com.harnex.goal.GoalRecord.Criterion;
import com.harnex.goal.GoalRecord.CriterionType;
import com.harnex.goal.GoalRecord.FileAssertion;
import com.harnex.goal.GoalRecord.RunDefaults;
import com.harnex.contract.goal.GoalApiRequests;
import com.harnex.model.ConversationThread;
import com.harnex.run.IdempotencyReceiptRecord;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.run.RunCoordinator;
import com.harnex.run.RunNotification;
import com.harnex.run.RunRecord;
import com.harnex.session.SessionStore;
import com.harnex.util.Ids;

/** Application service and sole mutation boundary for structured Goal resources. */
@Service
@ProjectRuntime
public class GoalResourceService {
    private static final int MAX_OBJECTIVE_CHARS = 65_536;

    private final GoalStore goals;
    private final IdempotencyReceiptStore receipts;
    private final SessionStore sessions;
    private final RunCoordinator runs;
    private final AgentConfig cfg;

    public GoalResourceService(GoalStore goals, IdempotencyReceiptStore receipts, SessionStore sessions,
        RunCoordinator runs, AgentConfig cfg) {
        this.goals = goals;
        this.receipts = receipts;
        this.sessions = sessions;
        this.runs = runs;
        this.cfg = cfg;
    }

    public Operation create(GoalApiRequests.Create request) {
        if (request == null) throw new GoalRequestException("bad_request", "request body is required");
        requireSafe(request.threadId(), "threadId");
        requireRequestId(request.clientRequestId());
        ConversationThread thread = sessions.get(request.threadId()).orElse(null);
        if (thread == null) {
            throw new GoalRequestException("thread_not_found", "Goal thread does not exist");
        }
        String objective = objective(request.objective());
        String target = request.threadId();
        String hash = IdempotencyReceiptStore.requestHash(createFingerprint(request, objective));
        IdempotencyReceiptRecord receipt = receipts.find("goal-create", target, request.clientRequestId()).orElse(null);
        if (receipt != null) return replay(receipt, hash);

        String scope = "goal-create:" + target;
        GoalRecord recovered = goals.findByCreationRequest(scope, request.clientRequestId()).orElse(null);
        if (recovered != null) {
            verifyHash(recovered.creationRequest().requestHash(), hash);
            return commitCreateReceipt(recovered, target, request.clientRequestId(), hash, true);
        }

        long now = System.currentTimeMillis();
        String goalId = "goal_" + UUID.randomUUID().toString().replace("-", "");
        GoalRecord record = new GoalRecord(GoalRecord.CURRENT_SCHEMA_VERSION, goalId, target, 1, objective,
            criteria(request.criteria()), budget(request.budget(), null), initialRunDefaults(request.run(), thread), List.of(),
            List.of(), now, now, "goal-create:" + request.clientRequestId(),
            new GoalRecord.CreationRequest(scope, request.clientRequestId(), hash));
        goals.create(record);
        return commitCreateReceipt(record, target, request.clientRequestId(), hash, false);
    }

    public Operation patch(String goalId, GoalApiRequests.Patch request) {
        requireSafeCall(goalId, "goalId");
        if (request == null) throw new GoalRequestException("bad_request", "request body is required");
        requireRequestId(request.clientRequestId());
        if (request.revision() < 1) throw new GoalRequestException("bad_request", "revision is required");
        GoalRecord current = getRecord(goalId);
        Map<String, Object> fingerprint = new TreeMap<>();
        fingerprint.put("revision", request.revision());
        fingerprint.put("objective", request.objective());
        fingerprint.put("criteria", request.criteria());
        fingerprint.put("budget", request.budget());
        fingerprint.put("run", request.run());
        String hash = IdempotencyReceiptStore.requestHash(fingerprint);
        IdempotencyReceiptRecord receipt = receipts.find("goal-patch", goalId, request.clientRequestId()).orElse(null);
        if (receipt != null) return replay(receipt, hash);

        String operationId = operation("goal-patch", request.clientRequestId(), hash);
        if (operationId.equals(current.lastCommittedOperationId())) {
            return commitReceipt("goal-patch", goalId, request.clientRequestId(), hash, current, true);
        }
        if (current.revision() != request.revision()) {
            throw new GoalRevisionException(current);
        }
        GoalRecord replacement = new GoalRecord(current.schemaVersion(), current.goalId(), current.threadId(),
            current.revision() + 1,
            request.objective() == null ? current.objective() : objective(request.objective()),
            request.criteria() == null ? current.criteria() : criteria(request.criteria()),
            budget(request.budget(), current.budget()), runDefaults(request.run(), current.runDefaults()),
            current.instructionRevisions(), current.runIds(), current.createdAt(), System.currentTimeMillis(), operationId,
            current.creationRequest());
        GoalRecord saved;
        try {
            saved = goals.replace(replacement, current.revision());
        } catch (GoalStore.GoalRevisionConflictException conflict) {
            throw new GoalRevisionException(conflict.current());
        }
        return commitReceipt("goal-patch", goalId, request.clientRequestId(), hash, saved, false);
    }

    public GoalRecord getRecord(String goalId) {
        return goals.find(goalId).orElseThrow(() -> new GoalRequestException("goal_not_found", "no such Goal"));
    }

    public Map<String, Object> view(String goalId) {
        return project(getRecord(goalId));
    }

    public List<Map<String, Object>> list(String threadId, String status) {
        if (threadId != null) requireSafe(threadId, "threadId");
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        return goals.list(threadId).stream().map(this::project)
            .filter(view -> filter == null || filter.equals(view.get("status"))).toList();
    }

    public List<Map<String, Object>> history(String goalId) {
        GoalRecord goal = getRecord(goalId);
        List<Map<String, Object>> history = new ArrayList<>();
        for (String runId : goal.runIds()) {
            Map<String, Object> run = runs.view(runId);
            if (run != null) history.add(run);
        }
        return List.copyOf(history);
    }

    public GoalRecord appendRun(String goalId, String runId, long expectedRevision, String operationId) {
        try {
            return goals.appendRun(goalId, runId, expectedRevision, operationId);
        } catch (GoalStore.GoalRevisionConflictException conflict) {
            throw new GoalRevisionException(conflict.current());
        }
    }

    public GoalRecord appendInstruction(String goalId, long inputSequence, String inputId, String text, long appliedAt) {
        return goals.appendInstruction(goalId,
            new GoalRecord.InstructionRevision(inputSequence, inputId, text, appliedAt));
    }

    public RunDefaults effectiveRunDefaults(GoalRecord goal, GoalApiRequests.RunConfig override) {
        return runDefaults(override, goal.runDefaults());
    }

    public void purgeThread(String threadId) {
        for (GoalRecord goal : goals.list(threadId)) {
            receipts.purgeScope("goal-create", threadId);
            receipts.purgeScope("goal-patch", goal.goalId());
            receipts.purgeScope("goal-start-run", goal.goalId());
        }
        goals.purgeThread(threadId);
    }

    private Map<String, Object> project(GoalRecord goal) {
        Map<String, Object> view = new LinkedHashMap<>(goal.toView());
        Map<String, Object> latest = goal.runIds().isEmpty() ? null : runs.view(goal.runIds().get(goal.runIds().size() - 1));
        String latestStatus = latest == null ? null : String.valueOf(latest.getOrDefault("status", "CREATED"));
        boolean latestTerminal = latestStatus != null
            && List.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED").contains(latestStatus);
        boolean patchedAfterLatestRun = latestTerminal && goal.lastCommittedOperationId() != null
            && goal.lastCommittedOperationId().startsWith("goal-patch:");
        String status = latest == null || patchedAfterLatestRun ? "CREATED" : latestStatus;
        view.put("status", status);
        if (latest != null) {
            view.put("latestRunId", latest.get("runId"));
            view.put("latestRunStatus", latestStatus);
            view.put("latestRunRevision", latest.get("revision"));
            if (!latestTerminal && !patchedAfterLatestRun) {
                view.put("activeRunId", latest.get("runId"));
                view.put("activeRunRevision", latest.get("revision"));
            } else {
                view.remove("activeRunId");
                view.remove("activeRunRevision");
            }
        } else {
            view.remove("activeRunId");
            view.remove("activeRunRevision");
        }
        return view;
    }

    private Operation commitCreateReceipt(GoalRecord goal, String target, String key, String hash, boolean replayed) {
        return commitReceipt("goal-create", target, key, hash, goal, replayed);
    }

    private Operation commitReceipt(String operation, String target, String key, String hash, GoalRecord goal,
        boolean replayed) {
        Map<String, Object> view = project(goal);
        try {
            IdempotencyReceiptRecord receipt = receipts.commit(operation, target, key, hash, view, goal.goalId(),
                goal.revision());
            return new Operation(goal, receipt.result(), replayed);
        } catch (IdempotencyReceiptStore.IdempotencyConflictException conflict) {
            throw new GoalRequestException("idempotency_conflict",
                "clientRequestId was already used with a different payload");
        }
    }

    private Operation replay(IdempotencyReceiptRecord receipt, String hash) {
        verifyHash(receipt.requestHash(), hash);
        GoalRecord record = goals.find(receipt.resourceId())
            .orElseThrow(() -> new GoalRequestException("idempotency_resource_gone", "original Goal was deleted"));
        return new Operation(record, receipt.result(), true);
    }

    private List<Criterion> criteria(List<GoalApiRequests.Criterion> requests) {
        if (requests == null) return List.of();
        if (requests.size() > 100) throw new GoalRequestException("bad_request", "criteria limit is 100");
        List<Criterion> criteria = new ArrayList<>();
        int sequence = 0;
        for (GoalApiRequests.Criterion request : requests) {
            if (request == null || request.type() == null) {
                throw new GoalRequestException("bad_request", "criterion type is required");
            }
            CriterionType type;
            FileAssertion assertion = null;
            try {
                type = CriterionType.valueOf(request.type().trim().toUpperCase(Locale.ROOT));
                if (request.assertion() != null && !request.assertion().isBlank()) {
                    assertion = FileAssertion.valueOf(request.assertion().trim().toUpperCase(Locale.ROOT));
                }
            } catch (IllegalArgumentException invalid) {
                throw new GoalRequestException("bad_request", "invalid criterion type/assertion");
            }
            String criterionId = request.criterionId();
            if (criterionId == null || criterionId.isBlank()) criterionId = "criterion_" + (++sequence);
            requireSafeCall(criterionId, "criterionId");
            if (type == CriterionType.VERIFY_CHECK
                && (request.ref() == null || !cfg.getVerify().getChecks().containsKey(request.ref()))) {
                throw new GoalRequestException("unknown_verify_check",
                    "VERIFY_CHECK must reference harnex.verify.checks by name");
            }
            if (type == CriterionType.FILE_ASSERTION && unsafeWorkspaceRelativePath(request.ref())) {
                throw new GoalRequestException("bad_request",
                    "FILE_ASSERTION ref must be a workspace-relative path without '..'");
            }
            try {
                criteria.add(new Criterion(criterionId, type, request.ref(),
                    request.required() == null || request.required(), assertion, request.expected()).validated());
            } catch (IllegalArgumentException invalid) {
                throw new GoalRequestException("bad_request", invalid.getMessage());
            }
        }
        if (criteria.stream().map(Criterion::criterionId).distinct().count() != criteria.size()) {
            throw new GoalRequestException("bad_request", "criterionId values must be unique");
        }
        return List.copyOf(criteria);
    }

    private static boolean unsafeWorkspaceRelativePath(String value) {
        if (value == null || value.isBlank()) return true;
        try {
            Path path = Path.of(value);
            if (path.isAbsolute()) return true;
            for (Path part : path) if ("..".equals(part.toString())) return true;
            return false;
        } catch (RuntimeException invalid) {
            return true;
        }
    }

    private Budget budget(GoalApiRequests.Budget request, Budget current) {
        Budget base = current == null
            ? Budget.defaults(cfg.getGoal().getMaxTurns(), cfg.getGoal().getDefaultTokenBudget(),
                cfg.getGoal().getWallClockBudgetMs())
            : current;
        if (request == null) return base;
        try {
            return new Budget(value(request.maxGoalTurns(), base.maxGoalTurns()),
                value(request.maxModelCalls(), base.maxModelCalls()), value(request.maxToolCalls(), base.maxToolCalls()),
                value(request.tokenBudget(), base.tokenBudget()), value(request.costBudget(), base.costBudget()),
                value(request.activeWallClockBudgetMs(), base.activeWallClockBudgetMs()),
                request.elapsedDeadline() == null ? base.elapsedDeadline() : request.elapsedDeadline()).validated();
        } catch (IllegalArgumentException invalid) {
            throw new GoalRequestException("bad_request", invalid.getMessage());
        }
    }

    private RunDefaults runDefaults(GoalApiRequests.RunConfig request, RunDefaults current) {
        RunDefaults base = current == null ? RunDefaults.defaults() : current;
        if (request == null && current != null) return current;
        GoalApiRequests.RunConfig supplied = request == null
            ? new GoalApiRequests.RunConfig(null, null, null, null, null, null, null, null, null) : request;
        String permission = supplied.permissionMode() == null ? base.permissionMode()
            : supplied.permissionMode().trim().toLowerCase(Locale.ROOT);
        try {
            AgentExecutionOptions prior = base.options() == null
                ? new AgentExecutionOptions(PermissionContext.UNRESTRICTED,
                    StepLimits.resolve(cfg, null, cfg.getMaxIterations()), null,
                    PlanReviewPolicy.AUTOMATIC) : base.options();
            StepLimits limits = StepLimits.resolve(cfg,
                supplied.maxWorkIterations(), prior.limits().maxWorkIterations());
            String reply = supplied.interactionTarget() == null ? prior.interactionTarget()
                : supplied.interactionTarget().isBlank() ? null : supplied.interactionTarget();
            AgentExecutionOptions options = new AgentExecutionOptions(supplied.readOnly() == null ? prior.permissions()
                : new PermissionContext(!supplied.readOnly()), limits, reply,
                supplied.planReview() == null ? prior.planReview() : supplied.planReview());
            return new RunDefaults(supplied.provider() == null ? base.provider() : blank(supplied.provider()),
                supplied.model() == null ? base.model() : blank(supplied.model()),
                supplied.reasoningEffort() == null ? reasoningEffort(base.reasoningEffort())
                    : reasoningEffort(supplied.reasoningEffort()),
                supplied.toolsets() == null ? base.toolsets() : supplied.toolsets(), permission, options).validated();
        } catch (IllegalArgumentException invalid) {
            throw new GoalRequestException("bad_request", invalid.getMessage());
        }
    }

    private RunDefaults initialRunDefaults(GoalApiRequests.RunConfig request, ConversationThread thread) {
        GoalApiRequests.RunConfig supplied = request == null
            ? new GoalApiRequests.RunConfig(null, null, null, null, null, null, null, null, null) : request;
        String permission = thread.getState() == null ? "ask" : thread.getState().getPermissionMode();
        return runDefaults(new GoalApiRequests.RunConfig(
            supplied.provider() == null ? thread.getProvider() : supplied.provider(),
            supplied.model() == null ? thread.getModel() : supplied.model(),
            supplied.reasoningEffort() == null ? thread.getReasoningEffort() : supplied.reasoningEffort(),
            supplied.toolsets() == null ? thread.getToolsets() : supplied.toolsets(),
            supplied.permissionMode() == null ? permission : supplied.permissionMode(),
            supplied.maxWorkIterations(), supplied.interactionTarget(), supplied.planReview(), supplied.readOnly()), null);
    }

    private static Map<String, Object> createFingerprint(GoalApiRequests.Create request, String objective) {
        Map<String, Object> fingerprint = new TreeMap<>();
        fingerprint.put("threadId", request.threadId());
        fingerprint.put("objective", objective);
        fingerprint.put("criteria", request.criteria());
        fingerprint.put("budget", request.budget());
        fingerprint.put("run", request.run());
        fingerprint.put("start", request.start() == null || request.start());
        fingerprint.put("notificationMode", RunNotification.Mode.orOff(request.notificationMode()));
        return fingerprint;
    }

    private static String operation(String kind, String key, String hash) {
        return kind + ":" + key + ":" + hash;
    }

    private static void verifyHash(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new GoalRequestException("idempotency_conflict",
                "clientRequestId was already used with a different payload");
        }
    }

    private static String objective(String value) {
        String objective = value == null ? "" : value.trim();
        if (objective.isEmpty() || objective.length() > MAX_OBJECTIVE_CHARS) {
            throw new GoalRequestException("bad_request", "objective must be 1..65536 characters");
        }
        return objective;
    }

    private static void requireRequestId(String value) {
        requireSafeCall(value, "clientRequestId");
    }

    private static void requireSafe(String value, String field) {
        if (!Ids.isSafe(value)) throw new GoalRequestException("bad_request", "invalid " + field);
    }

    private static void requireSafeCall(String value, String field) {
        if (!Ids.isSafeCallId(value)) throw new GoalRequestException("bad_request", "invalid " + field);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String reasoningEffort(String value) {
        String normalised = blank(value);
        if (normalised == null || "auto".equalsIgnoreCase(normalised)) return null;
        normalised = normalised.toLowerCase(Locale.ROOT);
        if (!Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max")
            .contains(normalised)) {
            throw new GoalRequestException("bad_request",
                "reasoningEffort must be auto|none|minimal|low|medium|high|xhigh|max");
        }
        return normalised;
    }

    private static int value(Integer value, int fallback) { return value == null ? fallback : value; }
    private static long value(Long value, long fallback) { return value == null ? fallback : value; }
    private static double value(Double value, double fallback) { return value == null ? fallback : value; }

    public record Operation(GoalRecord record, Map<String, Object> view, boolean replayed) { }

    public static class GoalRequestException extends IllegalArgumentException {
        private final String code;
        public GoalRequestException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public static final class GoalRevisionException extends GoalRequestException {
        private final GoalRecord current;
        public GoalRevisionException(GoalRecord current) {
            super("revision_conflict", "Goal revision conflict; current=" + current.revision());
            this.current = current;
        }
        public GoalRecord current() { return current; }
    }
}
