package com.harnex.cron;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.harnex.config.*;
import com.harnex.conversation.*;
import com.harnex.execution.PlanReviewPolicy;
import com.harnex.execution.*;
import com.harnex.contract.run.RunCreateRequest;
import com.harnex.run.*;
import com.harnex.application.run.RunRequestService;
import com.harnex.workspace.WorkspaceReadService;
import com.harnex.submission.PendingSubmission;
import com.harnex.submission.PendingSubmissionStore;
import com.harnex.usage.UsageLedger;
import com.harnex.util.Ids;

import io.swagger.v3.oas.annotations.media.Schema;

/** Dispatch into an existing Runtime. There are no process creation or project-switch operations here. */
@Service
public class AutomationExecutionService {
    @Schema(name = "AutomationExecutionRequest")
    public record Request(String attemptId, String jobId, RuntimeProfile profile, String prompt,
        String provider, String model, List<String> toolsets, boolean allowMutations, Integer maxWorkIterations, PlanReviewPolicy planReview,
        RunNotification.Mode notificationMode) {
        public Request(String attemptId, String jobId, RuntimeProfile profile, String prompt, String provider, String model,
            List<String> toolsets, boolean allowMutations, Integer maxWorkIterations, PlanReviewPolicy planReview) {
            this(attemptId, jobId, profile, prompt, provider, model, toolsets, allowMutations, maxWorkIterations, planReview, null);
        }
    }
    @Schema(name = "AutomationExecutionStart")
    public record Start(String runId, String conversationId, String code, String message) {}
    @Schema(name = "AutomationChange")
    public record Change(String path, String status, int added, int removed) {}
    @Schema(name = "AutomationExecutionResult")
    public record Result(String runId, String conversationId, String status, boolean terminal, long startedAt, long finishedAt,
        String output, String error, UsageLedger.Summary usage, List<Change> changes) {}
    private final RunRequestService requests;
    private final RunCoordinator runs;
    private final ScopeIdentity scope;
    private final AgentConfig config;
    private final ConversationRepository archive;
    private final UsageLedger usage;
    private final AutomationResultArchive results;
    private final PendingSubmissionStore pending;
    public AutomationExecutionService(RunRequestService requests, RunCoordinator runs, ScopeIdentity scope, AgentConfig config,
        ConversationRepository archive, UsageLedger usage, AutomationResultArchive results, PendingSubmissionStore pending) {
        this.requests = requests; this.runs = runs; this.scope = scope; this.config = config; this.archive = archive;
        this.usage = usage; this.results = results; this.pending = pending;
    }
    public Start start(Request input) {
        if (input == null || !Ids.isSafeResourceId(input.attemptId())) throw new IllegalArgumentException("Invalid automation attempt");
        Ids.requireSafe(input.jobId(), "automation job");
        if (input.profile() != scope.profile()) throw new IllegalArgumentException("Execution environment does not match the task");
        RunHandle previous = lookup(input.attemptId());
        if (previous == null && pending.conversations().stream().anyMatch(id -> pending.list(id).stream()
            .anyMatch(item -> item.state() == PendingSubmission.State.QUEUED || item.state() == PendingSubmission.State.STARTING))) {
            return new Start(null, null, "automation_runtime_busy", "Waiting for queued user tasks to finish");
        }
        var request = new RunCreateRequest();
        request.setClientRequestId(key(input.attemptId())); request.setConversationId("automation-" + input.attemptId());
        request.setInput(input.prompt()); request.setChannel("cron"); request.setStream(false);
        request.setNotificationMode(input.notificationMode());
        var text = new TextTarget(input.provider(), input.model(), null);
        if (scope.profile() == RuntimeProfile.PERSONAL) {
            if (input.allowMutations() || input.toolsets() != null && !input.toolsets().isEmpty()) {
                throw new IllegalArgumentException("Personal automations use conversation capabilities and cannot request project tools");
            }
            request.setExecution(new ConversationSpec(ConversationSpec.Action.TEXT, text, null, null));
        } else {
            List<String> tools = input.toolsets() == null || input.toolsets().isEmpty() ? config.getToolsets().getDefaultSet() : input.toolsets();
            List<String> allowed = config.getCron().getAllowedToolsets();
            if (allowed != null && !allowed.isEmpty() && (tools == null || !allowed.containsAll(tools))) {
                throw new IllegalArgumentException("Task tools are not permitted by this execution environment");
            }
            request.setExecution(new AgentSpec(text, tools, input.allowMutations() ? "yolo" : "ask", !input.allowMutations(),
                new AgentSpec.Limits(input.maxWorkIterations() == null ? config.getMaxIterations() : input.maxWorkIterations()),
                input.planReview() == null ? PlanReviewPolicy.AUTOMATIC : input.planReview(), null, null));
        }
        var accepted = requests.createAutomation(request, input.jobId());
        return accepted.run() == null ? new Start(null, null, accepted.rejectCode(), accepted.rejectMessage())
            : new Start(accepted.run().getRunId(), accepted.run().getThreadId(), null, null);
    }
    public Result inspect(String attemptId) {
        RunHandle run = lookup(attemptId);
        if (run == null) return null;
        String output = "";
        String detail = run.getError();
        List<Change> changes = List.of();
        UsageLedger.Summary accounting = null;
        if (run.isTerminal()) {
            Result saved = results.read(run.getRunId());
            if (saved != null) return new Result(run.getRunId(), run.getThreadId(), run.getStatus().name(), true,
                run.getCreatedAt(), run.getFinishedAt(), saved.output(), run.getError() == null ? saved.error() : run.getError(),
                usage.summary(run.getRunId()), saved.changes());
            var history = archive.captureRunHistory(scope.scopeId(), run.getThreadId(), run.getSubmission().turnId(), run.getRunId());
            if (history.answer() != null) output = history.answer().contentParts().stream()
                .filter(part -> part.type() == ContentPart.Type.TEXT).map(ContentPart::text).collect(Collectors.joining("\n\n"));
            accounting = usage.summary(run.getRunId());
            // Recovery without a final snapshot must not attribute later edits to this automation.
            if (scope.profile() == RuntimeProfile.PROJECT) detail = (detail == null ? "" : detail + "\n")
                + "File-change snapshot is unavailable; showing the saved conversation result.";
        }
        return new Result(run.getRunId(), run.getThreadId(), run.getStatus().name(), run.isTerminal(), run.getCreatedAt(),
            run.getFinishedAt(), output, detail, accounting, changes);
    }
    public boolean cancel(String attemptId) {
        RunHandle run = lookup(attemptId);
        return run != null && runs.cancel(run.getRunId());
    }
    private RunHandle lookup(String id) {
        if (!Ids.isSafeResourceId(id)) throw new IllegalArgumentException("Invalid automation attempt");
        RunHandle run = runs.findCreation(scope.scopeId(), key(id));
        return run != null && run.getOrigin().kind() == RunOrigin.Kind.AUTOMATION ? run : null;
    }
    private static String key(String id) { return "automation:" + id; }
}
