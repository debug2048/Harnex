package com.harnex.goal;

import com.harnex.todo.TodoStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.conversation.ConversationException;
import com.harnex.goal.GoalRecord.Criterion;
import com.harnex.run.RunRecord;
import com.harnex.run.RunRecord.CriterionResult;
import com.harnex.run.RunRecord.CriterionStatus;
import com.harnex.process.CommandExecutor;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.conversation.ConversationRepository;
import com.harnex.conversation.MessageRecord;
import com.harnex.run.RunRegistry;

/** Server-owned Goal completion validators. No request-supplied command is ever executed. */
@Service
@ProjectRuntime
public class GoalCriteriaEvaluator {
    private final TodoStore todoStore;

    private static final long MAX_CONTAINS_BYTES = 8L * 1024 * 1024;
    private final AgentConfig cfg;
    private final CommandExecutor sandbox;
    private final WorkspacePathResolver paths;
    private final ConversationRepository archive;
    private final RunRegistry runs;

    public GoalCriteriaEvaluator(AgentConfig cfg, CommandExecutor sandbox, WorkspacePathResolver paths, TodoStore todoStore) {
        this(cfg, sandbox, paths, null, null, todoStore);
    }

    @Autowired
    public GoalCriteriaEvaluator(AgentConfig cfg, CommandExecutor sandbox, WorkspacePathResolver paths,
        ConversationRepository archive, RunRegistry runs, TodoStore todoStore) {
        this.todoStore = todoStore;
        this.cfg = cfg;
        this.sandbox = sandbox;
        this.paths = paths;
        this.archive = archive; this.runs = runs;
    }

    public Evaluation evaluate(GoalRecord goal) {
        List<CriterionResult> results = new ArrayList<>();
        for (Criterion criterion : goal.criteria()) {
            if (Thread.currentThread().isInterrupted()) break;
            results.add(switch (criterion.type()) {
                case VERIFY_CHECK -> verify(goal, criterion);
                case FILE_ASSERTION -> file(goal, criterion);
                case TODO_COMPLETE -> todos(goal, criterion);
                case TEXT, USER_REVIEW -> result(criterion, CriterionStatus.REVIEW_REQUIRED,
                    "requires explicit user review", Map.of());
            });
        }
        boolean requiredFailure = Thread.currentThread().isInterrupted()
            || results.stream().anyMatch(r -> r.required() && r.status() == CriterionStatus.FAIL);
        boolean requiredReview = results.stream()
            .anyMatch(r -> r.required() && r.status() == CriterionStatus.REVIEW_REQUIRED);
        return new Evaluation(List.copyOf(results), requiredFailure, requiredReview);
    }

    private CriterionResult verify(GoalRecord goal, Criterion criterion) {
        String command = cfg.getVerify().getChecks().get(criterion.ref());
        if (command == null || command.isBlank()) {
            return result(criterion, CriterionStatus.FAIL, "configured verify check is unavailable",
                Map.of("check", criterion.ref()));
        }
        CommandExecutor.ExecResult run;
        try {
            run = sandbox.exec(command, paths.workspaceFor(goal.threadId()), cfg.getVerify().getTimeoutMs(),
                null, null, null);
        } catch (RuntimeException failure) {
            return result(criterion, CriterionStatus.FAIL, "verify check could not run",
                Map.of("check", criterion.ref(), "error", failure.getClass().getSimpleName()));
        }
        boolean pass = !run.error() && run.exitCode() == 0;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("check", criterion.ref());
        evidence.put("exitCode", run.exitCode());
        evidence.put("outputSha256", hash(run.output() == null ? "" : run.output()));
        if (!pass && run.output() != null && !run.output().isBlank()) {
            String output = run.output();
            evidence.put("outputTail", output.substring(Math.max(0, output.length() - 2_000)));
        }
        return result(criterion, pass ? CriterionStatus.PASS : CriterionStatus.FAIL,
            pass ? "configured verify check passed" : "configured verify check failed", evidence);
    }

    private CriterionResult file(GoalRecord goal, Criterion criterion) {
        try {
            Path path = paths.toReal(criterion.ref(), goal.threadId());
            boolean exists = path != null && Files.exists(path);
            boolean pass = switch (criterion.assertion()) {
                case EXISTS -> exists;
                case NOT_EXISTS -> !exists;
                case CONTAINS -> exists && Files.isRegularFile(path)
                    && Files.size(path) <= MAX_CONTAINS_BYTES
                    && Files.readString(path, StandardCharsets.UTF_8).contains(criterion.expected());
            };
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("path", criterion.ref());
            evidence.put("assertion", criterion.assertion().name());
            evidence.put("exists", exists);
            if (exists && Files.isRegularFile(path)) {
                evidence.put("size", Files.size(path));
                evidence.put("lastModified", Files.getLastModifiedTime(path).toMillis());
            }
            return result(criterion, pass ? CriterionStatus.PASS : CriterionStatus.FAIL,
                pass ? "file assertion passed" : "file assertion failed", evidence);
        } catch (Exception failure) {
            return result(criterion, CriterionStatus.FAIL, "file assertion could not be evaluated",
                Map.of("path", criterion.ref(), "error", failure.getClass().getSimpleName()));
        }
    }

    private CriterionResult todos(GoalRecord goal, Criterion criterion) {
        var snapshot = todoStore.snapshotState(goal.threadId());
        if (archive != null || runs != null) {
            String problem = todoProvenanceProblem(goal, snapshot.orElse(null));
            if (problem != null) return result(criterion, CriterionStatus.FAIL, problem,
                Map.of("snapshotPresent", snapshot.isPresent(), "sourceVerified", false));
        }
        List<Map<String, Object>> todos = snapshot.map(TodoStore.SnapshotState::todos).orElse(List.of());
        long incomplete = todos.stream().filter(row -> !"completed".equals(String.valueOf(row.get("status")))).count();
        boolean pass = !todos.isEmpty() && incomplete == 0;
        return result(criterion, pass ? CriterionStatus.PASS : CriterionStatus.FAIL,
            pass ? "all todos are complete" : todos.isEmpty() ? "no todo checklist exists" : "todos remain incomplete",
            Map.of("total", todos.size(), "incomplete", incomplete));
    }

    /** A completed list from a sibling attempt is not evidence about this Goal's current branch. */
    private String todoProvenanceProblem(GoalRecord goal, TodoStore.SnapshotState snapshot) {
        if (snapshot == null) return "no todo checklist exists";
        var origin = snapshot.provenance();
        if (archive == null || runs == null || origin == null || goal.runIds().isEmpty()) {
            return "current Todo source and branch could not be verified";
        }
        var run = runs.get(goal.runIds().get(goal.runIds().size() - 1));
        if (run == null || run.getSubmission() == null || !goal.threadId().equals(run.getThreadId())
            || !goal.goalId().equals(run.getGoalId())) return "current Goal Run branch is unavailable";
        var submission = run.getSubmission();
        if (!submission.scopeId().equals(origin.scopeId()) || origin.sourceMessageId() == null || origin.runId() == null) {
            return "Todo source is not verified for the current Goal scope";
        }
        try {
            var current = archive.captureRunHistory(submission.scopeId(), goal.threadId(), submission.turnId(), run.getRunId());
            if (current.answer() == null) return "current Goal Run branch is unavailable";
            String leaf = current.answer().messageId();
            var source = archive.findMessageHeader(submission.scopeId(), goal.threadId(), origin.sourceMessageId()).orElse(null);
            boolean verified = source != null && source.role() == MessageRecord.Role.USER && !source.isMutable()
                && archive.isMessageAncestor(submission.scopeId(), goal.threadId(), origin.sourceMessageId(), leaf)
                && (run.getRunId().equals(origin.runId())
                    || archive.hasCompletedRun(submission.scopeId(), goal.threadId(), origin.runId(), leaf));
            return verified ? null : "Todo snapshot belongs to an unverified or different conversation branch";
        } catch (ConversationException unavailable) {
            return "Todo source history is unavailable for branch verification";
        }
    }

    private static CriterionResult result(Criterion criterion, CriterionStatus status, String detail,
        Map<String, Object> evidence) {
        return new CriterionResult(criterion.criterionId(), criterion.type().name(), status, criterion.required(),
            System.currentTimeMillis(), detail, evidence);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public record Evaluation(List<RunRecord.CriterionResult> results, boolean requiredFailure,
        boolean requiredReview) { }
}
