package com.harnex.goal;

import com.harnex.run.RunOrigin;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.harnex.config.ProjectRuntime;
import com.harnex.execution.AgentSpec;
import com.harnex.execution.TextTarget;
import com.harnex.model.ConversationThread;
import com.harnex.contract.goal.GoalApiRequests;
import com.harnex.contract.run.RunCreateRequest;
import com.harnex.run.RunCoordinator;
import com.harnex.run.RunHandle;

/** Convenience `/goal` adapter; all state changes delegate to the structured Goal/Run services. */
@Service
@ProjectRuntime
public class GoalSlashService {
    private static final Pattern BUDGET = Pattern.compile("(?i)\\s+\\+(\\d+(?:\\.\\d+)?)([km]?)\\s*$");

    private final GoalResourceService goals;
    private final GoalStore store;
    private final GoalRunOrchestrator orchestrator;
    private final RunCoordinator runs;

    public GoalSlashService(GoalResourceService goals, GoalStore store, GoalRunOrchestrator orchestrator,
        RunCoordinator runs) {
        this.goals = goals;
        this.store = store;
        this.orchestrator = orchestrator;
        this.runs = runs;
    }

    public boolean matches(String input) {
        if (input == null) return false;
        String trimmed = input.stripLeading();
        return trimmed.equalsIgnoreCase("/goal") || trimmed.regionMatches(true, 0, "/goal ", 0, 6);
    }

    public Result handle(ConversationThread thread, RunCreateRequest transport, RunOrigin origin) {
        AgentSpec spec = (AgentSpec) transport.getExecution();
        TextTarget target = spec.text();
        String raw = transport.effectiveInput().stripLeading();
        String args = raw.length() <= 5 ? "" : raw.substring(5).trim();
        String key = transport.getClientRequestId();
        if (key == null || key.isBlank()) key = "slash_" + UUID.randomUUID().toString().replace("-", "");
        GoalRecord latest = store.list(thread.getId()).stream().findFirst().orElse(null);
        String command = args.isBlank() ? "status" : args.split("\\s+", 2)[0].toLowerCase();

        if (List.of("status", "pause", "resume", "clear", "cancel").contains(command)) {
            if (latest == null) return Result.rejected("goal_not_found", "No Goal exists on this Thread");
            String runId = latest.runIds().isEmpty() ? null : latest.runIds().get(latest.runIds().size() - 1);
            RunHandle run = runId == null ? null : runs.get(runId);
            if ("status".equals(command)) {
                return run == null ? Result.rejected("run_not_found", "Goal has no live/recoverable Run")
                    : Result.ok(run, "Goal status: " + run.getStatus().name());
            }
            if (run == null) return Result.rejected("run_not_found", "Goal has no controllable Run");
            GoalApiRequests.Control control = new GoalApiRequests.Control(run.getRevision(), key,
                "resume".equals(command) ? spec.interactionTarget() : null);
            GoalRunOrchestrator.ControlResult result = switch (command) {
                case "pause" -> orchestrator.pause(runId, control);
                case "resume" -> orchestrator.resume(runId, control);
                default -> orchestrator.cancel(runId, control);
            };
            return result.accepted() ? Result.ok(result.run(), "Goal " + command + " accepted")
                : Result.rejected(result.rejectCode(), result.rejectMessage());
        }

        long tokenBudget = 0;
        Matcher matcher = BUDGET.matcher(args);
        if (matcher.find()) {
            double amount = Double.parseDouble(matcher.group(1));
            tokenBudget = Math.round(amount * switch (matcher.group(2).toLowerCase()) {
                case "k" -> 1_000d;
                case "m" -> 1_000_000d;
                default -> 1d;
            });
            args = args.substring(0, matcher.start()).trim();
        }
        if (args.isBlank()) return Result.rejected("bad_request", "Usage: /goal <objective> [+budget]");
        GoalApiRequests.RunConfig config = new GoalApiRequests.RunConfig(target == null ? null : target.provider(),
            target == null ? null : target.model(), target == null ? null : target.reasoningEffort(), spec.toolsets(),
            spec.permissionMode(), spec.limits() == null ? null : spec.limits().maxWorkIterations(), null, spec.planReview(), spec.readOnly());
        GoalApiRequests.Budget budget = tokenBudget > 0
            ? new GoalApiRequests.Budget(null, null, null, tokenBudget, null, null, null) : null;
        GoalResourceService.Operation created = goals.create(new GoalApiRequests.Create(thread.getId(), args,
            List.of(), budget, config, false, key, transport.getNotificationMode()));
        GoalRunOrchestrator.StartResult started = orchestrator.start(created.record().goalId(),
            new GoalApiRequests.StartRun(created.record().revision(), config, key, transport.getNotificationMode()), origin);
        return started.started() ? Result.ok(started.run(), "Goal created: " + args)
            : Result.rejected(started.rejectCode(), started.rejectMessage());
    }

    public record Result(RunHandle run, String message, String rejectCode, String rejectMessage) {
        static Result ok(RunHandle run, String message) { return new Result(run, message, null, null); }
        static Result rejected(String code, String message) { return new Result(null, null, code, message); }
        public boolean accepted() { return run != null; }
    }
}
