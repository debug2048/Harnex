package com.harnex.goal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.harnex.core.AgentExecutionOptions;
import com.harnex.run.RunRecord;
import com.harnex.util.Ids;

import io.swagger.v3.oas.annotations.media.Schema;

/** Versioned workspace-local contract for one long-lived Goal resource. */
public record GoalRecord(
    int schemaVersion,
    String goalId,
    String threadId,
    long revision,
    String objective,
    List<Criterion> criteria,
    Budget budget,
    RunDefaults runDefaults,
    List<InstructionRevision> instructionRevisions,
    List<String> runIds,
    long createdAt,
    long updatedAt,
    String lastCommittedOperationId,
    CreationRequest creationRequest
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public GoalRecord {
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        budget = budget == null ? Budget.defaults(0, 0, 0) : budget;
        runDefaults = runDefaults == null ? RunDefaults.defaults() : runDefaults;
        instructionRevisions = instructionRevisions == null ? List.of() : List.copyOf(instructionRevisions);
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
    }

    public GoalRecord validated() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported goal schemaVersion: " + schemaVersion);
        }
        if (!Ids.isSafeCallId(goalId) || !Ids.isSafe(threadId)) {
            throw new IllegalArgumentException("invalid Goal identity");
        }
        if (revision < 1 || createdAt <= 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("invalid Goal revision/timestamps");
        }
        if (objective == null || objective.isBlank() || objective.length() > 65_536) {
            throw new IllegalArgumentException("Goal objective must be 1..65536 characters");
        }
        if (criteria.size() > 100) throw new IllegalArgumentException("Goal criteria limit is 100");
        criteria.forEach(Criterion::validated);
        budget.validated();
        runDefaults.validated();
        instructionRevisions.forEach(InstructionRevision::validated);
        for (String runId : runIds) {
            if (!Ids.isSafeCallId(runId)) throw new IllegalArgumentException("invalid Goal runId");
        }
        if (creationRequest != null) creationRequest.validated();
        return this;
    }

    public Map<String, Object> toView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", schemaVersion);
        out.put("goalId", goalId);
        out.put("threadId", threadId);
        out.put("revision", revision);
        out.put("objective", objective);
        out.put("criteria", criteria.stream().map(Criterion::toView).toList());
        out.put("budget", budget.toView());
        out.put("run", runDefaults.toView());
        out.put("instructionRevisions", instructionRevisions.stream().map(InstructionRevision::toView).toList());
        out.put("runIds", new ArrayList<>(runIds));
        if (!runIds.isEmpty()) out.put("activeRunId", runIds.get(runIds.size() - 1));
        out.put("createdAt", createdAt);
        out.put("updatedAt", updatedAt);
        return out;
    }

    public enum CriterionType {
        TEXT,
        VERIFY_CHECK,
        FILE_ASSERTION,
        TODO_COMPLETE,
        USER_REVIEW
    }

    public enum FileAssertion {
        EXISTS,
        NOT_EXISTS,
        CONTAINS
    }

    @Schema(name = "GoalCriterion", requiredProperties = {"criterionId", "type", "required"})
    public record Criterion(
        String criterionId,
        CriterionType type,
        String ref,
        boolean required,
        FileAssertion assertion,
        String expected
    ) {
        Criterion validated() {
            if (!Ids.isSafeCallId(criterionId) || type == null) {
                throw new IllegalArgumentException("criterion id/type is required");
            }
            switch (type) {
                case VERIFY_CHECK -> requireRef("verify check");
                case FILE_ASSERTION -> {
                    requireRef("file assertion");
                    if (assertion == null) throw new IllegalArgumentException("file assertion kind is required");
                    if (assertion == FileAssertion.CONTAINS && (expected == null || expected.isEmpty())) {
                        throw new IllegalArgumentException("CONTAINS file assertion requires expected text");
                    }
                }
                case TEXT -> {
                    if ((ref == null || ref.isBlank()) && (expected == null || expected.isBlank())) {
                        throw new IllegalArgumentException("TEXT criterion requires ref or expected");
                    }
                }
                case TODO_COMPLETE, USER_REVIEW -> { }
            }
            return this;
        }

        private void requireRef(String label) {
            if (ref == null || ref.isBlank()) throw new IllegalArgumentException(label + " ref is required");
        }

        Map<String, Object> toView() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("criterionId", criterionId);
            out.put("type", type.name());
            out.put("required", required);
            if (ref != null && !ref.isBlank()) out.put("ref", ref);
            if (assertion != null) out.put("assertion", assertion.name());
            if (expected != null) out.put("expected", expected);
            return out;
        }
    }

    @Schema(name = "GoalBudget", requiredProperties = {"maxGoalTurns", "maxModelCalls", "maxToolCalls", "tokenBudget", "costBudget", "activeWallClockBudgetMs"})
    public record Budget(
        int maxGoalTurns,
        int maxModelCalls,
        int maxToolCalls,
        long tokenBudget,
        double costBudget,
        long activeWallClockBudgetMs,
        Long elapsedDeadline
    ) {
        public static Budget defaults(int maxGoalTurns, long tokenBudget, long activeWallClockBudgetMs) {
            return new Budget(Math.max(0, maxGoalTurns), 0, 0, Math.max(0, tokenBudget), 0,
                Math.max(0, activeWallClockBudgetMs), null);
        }

        Budget validated() {
            if (maxGoalTurns < 0 || maxModelCalls < 0 || maxToolCalls < 0 || tokenBudget < 0
                || !Double.isFinite(costBudget) || costBudget < 0
                || activeWallClockBudgetMs < 0 || elapsedDeadline != null && elapsedDeadline <= 0) {
                throw new IllegalArgumentException("invalid Goal budget");
            }
            return this;
        }

        Map<String, Object> toView() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("maxGoalTurns", maxGoalTurns);
            out.put("maxModelCalls", maxModelCalls);
            out.put("maxToolCalls", maxToolCalls);
            out.put("tokenBudget", tokenBudget);
            out.put("costBudget", costBudget);
            out.put("activeWallClockBudgetMs", activeWallClockBudgetMs);
            if (elapsedDeadline != null) out.put("elapsedDeadline", elapsedDeadline);
            return out;
        }
    }

    public record RunDefaults(
        String provider,
        String model,
        String reasoningEffort,
        List<String> toolsets,
        String permissionMode,
        AgentExecutionOptions options
    ) {
        public RunDefaults {
            toolsets = toolsets == null ? List.of() : List.copyOf(toolsets);
        }

        public static RunDefaults defaults() {
            return new RunDefaults(null, null, null, List.of(), "ask", null);
        }

        RunDefaults validated() {
            if (permissionMode != null && !List.of("ask", "accept-edits", "yolo").contains(permissionMode)) {
                throw new IllegalArgumentException("invalid Goal permissionMode");
            }

            return this;
        }

        Map<String, Object> toView() {
            Map<String, Object> out = new LinkedHashMap<>();
            if (provider != null) out.put("provider", provider);
            if (model != null) out.put("model", model);
            if (reasoningEffort != null) out.put("reasoningEffort", reasoningEffort);
            out.put("toolsets", toolsets);
            out.put("permissionMode", permissionMode == null ? "ask" : permissionMode);
            if (options != null) out.put("options", options.toView());
            return out;
        }
    }

    public record InstructionRevision(long sequence, String inputId, String text, long appliedAt) {
        InstructionRevision validated() {
            if (sequence < 1 || !Ids.isSafeCallId(inputId) || text == null || text.isBlank() || appliedAt <= 0) {
                throw new IllegalArgumentException("invalid Goal instruction revision");
            }
            return this;
        }

        Map<String, Object> toView() {
            return Map.of("sequence", sequence, "inputId", inputId, "text", text, "appliedAt", appliedAt);
        }
    }

    /** Durable recovery link for the create-record/receipt crash window. */
    public record CreationRequest(String scope, String key, String requestHash) {
        CreationRequest validated() {
            if (scope == null || scope.isBlank() || key == null || key.isBlank()
                || requestHash == null || requestHash.isBlank()) {
                throw new IllegalArgumentException("incomplete Goal creation request");
            }
            return this;
        }
    }
}
