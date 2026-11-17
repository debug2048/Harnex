package com.harnex.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.harnex.conversation.ConversationException;
import com.harnex.conversation.ConversationRepository;
import com.harnex.execution.ScopeIdentity;
import com.harnex.contract.run.RunView;
import com.harnex.session.SessionStore;
import com.harnex.tool.budget.BudgetEnforcer;
import io.swagger.v3.oas.annotations.media.Schema;

/** Resolve a specific occurrence, including existing runs whose tool bodies remain in Agent execution storage. */
@Service
public class RunToolDetails {
    private final RunRegistry registry;
    private final RunToolStore tools;
    private final ConversationRepository archive;
    private final ScopeIdentity scope;
    private final ObjectProvider<SessionStore> sessions;
    private final ObjectProvider<BudgetEnforcer> budgets;

    public RunToolDetails(RunRegistry registry, RunToolStore tools, ConversationRepository archive,
        ScopeIdentity scope, ObjectProvider<SessionStore> sessions, ObjectProvider<BudgetEnforcer> budgets) {
        this.registry = registry; this.tools = tools; this.archive = archive; this.scope = scope; this.sessions = sessions;
        this.budgets = budgets;
    }

    public RunToolStore.ToolDetail get(String runId, long seq) {
        var run = readableRun(runId);
        var saved = tools.find(runId, seq);
        return expandResult(run, saved.orElseGet(() -> recover(run, seq)));
    }

    @Schema(name = "RunSubagentResult", requiredProperties = {"output"})
    public record SubagentResult(String output) {}

    public SubagentResult subagentResult(String runId, String threadId) {
        var run = readableRun(runId);
        var child = run.subagents().stream().filter(item -> threadId.equals(item.subagentThreadId()))
            .findFirst().orElseThrow(() -> new ConversationException("subagent_not_found", "Subagent not found in this run"));
        String output = child.output() == null ? "" : child.output();
        // Older runtimes persisted only 480 characters here. The delegate tool body
        // still owns the full result, including results externalized by the tool budget.
        if (output.length() == 483 && output.endsWith("...")) {
            var saved = tools.subagentResult(runId, threadId);
            if (saved.isPresent()) {
                String content = expandResult(run, saved.get()).content();
                int headerEnd = content == null ? -1 : content.indexOf(")\n\n");
                if (headerEnd >= 0) output = content.substring(headerEnd + 3);
            }
        }
        return new SubagentResult(withoutToolTrace(output));
    }

    private static String withoutToolTrace(String output) {
        String marker = "\n\n[sub-agent tool trace]\n";
        int index = output.lastIndexOf(marker);
        if (index < 0) return output;
        String trace = output.substring(index + marker.length()).strip();
        return !trace.isEmpty() && trace.lines().allMatch(line -> line.matches("- .+ \\((ok|error)\\)(:.*)?"))
            ? output.substring(0, index).stripTrailing() : output;
    }

    private RunView readableRun(String runId) {
        var run = RunView.from(registry.view(runId));
        if (run == null) throw new ConversationException("run_not_found", "Run not found");
        var conversation = archive.loadConversation(scope.scopeId(), run.conversationId())
            .orElseThrow(() -> new ConversationException("conversation_not_found", "Conversation not found"));
        if (conversation.deleted()) throw new ConversationException("conversation_deleted", "Conversation was deleted");
        return run;
    }

    private RunToolStore.ToolDetail expandResult(RunView run, RunToolStore.ToolDetail detail) {
        BudgetEnforcer budget = budgets.getIfAvailable();
        var metadata = detail.metadata();
        String threadId = detail.subagentConversationId() == null ? run.conversationId() : detail.subagentConversationId();
        if (budget == null || metadata == null || !threadId.equals(metadata.get("resultThreadId"))
            || !(metadata.get("resultRef") instanceof String ref)) return detail;
        return budget.readPersisted(threadId, ref).map(content -> new RunToolStore.ToolDetail(detail.runId(), detail.seq(),
            detail.id(), detail.name(), detail.subagentConversationId(), detail.arguments(), content, detail.metadata(),
            detail.durationMs(), detail.resultPending(), detail.state(), detail.afterMessageId(), detail.purpose())).orElse(detail);
    }

    private RunToolStore.ToolDetail recover(RunView run, long seq) {
        List<RunView.SemanticActivity> activities = new ArrayList<>(run.activities());
        for (var child : run.subagents()) if (child.details() != null) activities.addAll(child.details());
        activities.sort(Comparator.comparingLong(RunView.SemanticActivity::seq));
        var start = activities.stream().filter(item -> item.seq() == seq && "tool".equals(item.activityKind())).findFirst()
            .orElseThrow(RunToolDetails::unavailable);
        String id = id(start);
        String name = start.name() == null ? start.toolName() == null ? start.activityTitle() : start.toolName() : start.name();
        String argumentsSummary = null, resultSummary = null;
        boolean ended = false;
        for (var activity : activities) {
            if (activity.seq() < seq || !Objects.equals(id, id(activity))
                || !Objects.equals(start.subagentThreadId(), activity.subagentThreadId())) continue;
            String type = activity.childEventType() == null ? activity.eventType() : activity.childEventType();
            if ("tool_use_queued".equals(type) || "tool_use_start".equals(type)) {
                argumentsSummary = activity.activitySummary();
            } else if ("tool_use_end".equals(type)) {
                resultSummary = activity.activitySummary(); ended = true; break;
            }
        }
        SessionStore source = sessions.getIfAvailable();
        if (source == null || id == null) throw unavailable();
        String threadId = start.subagentThreadId() == null ? run.conversationId() : start.subagentThreadId();
        var thread = source.get(threadId).orElseThrow(RunToolDetails::unavailable);
        List<SavedCall> calls = new ArrayList<>();
        for (var message : new ArrayList<>(thread.getMessages())) {
            if (message == null) continue;
            if (message.getToolCalls() != null) {
                for (var call : message.getToolCalls()) {
                    if (Objects.equals(id, call.getId()) && Objects.equals(name, call.getName())) calls.add(new SavedCall(call.getArguments()));
                }
            }
            if ("tool".equals(message.getRole()) && Objects.equals(id, message.getToolCallId())) {
                calls.stream().filter(call -> !call.received).findFirst().ifPresent(call -> {
                    call.content = message.getContent() == null ? "" : message.getContent(); call.received = true;
                });
            }
        }
        String expectedArguments = argumentsSummary, expectedResult = resultSummary;
        boolean requireResult = ended;
        var matches = calls.stream().filter(call -> (!requireResult || call.received)
            && matchesSummary(call.arguments, expectedArguments) && matchesSummary(call.content, expectedResult))
            .map(call -> new RunToolStore.ToolDetail(run.runId(), seq, id, name, start.subagentThreadId(),
                call.arguments, call.content, null, null, !call.received)).distinct().toList();
        // Reused call ids are not globally unique. Never substitute another invocation's output.
        if (matches.size() != 1) throw unavailable();
        return tools.save(matches.get(0));
    }
    private static String id(RunView.SemanticActivity activity) { return activity.id() == null ? activity.callId() : activity.id(); }
    private static boolean matchesSummary(String full, String summary) {
        if (summary == null || summary.isEmpty()) return true;
        if (full == null) return false;
        return summary.length() == 241 && summary.endsWith("…") ? full.startsWith(summary.substring(0, 240)) : full.equals(summary);
    }
    private static ConversationException unavailable() {
        return new ConversationException("tool_detail_not_found", "Full tool details are unavailable for this call.");
    }
    private static final class SavedCall {
        final String arguments;
        String content;
        boolean received;
        SavedCall(String arguments) { this.arguments = arguments; }
    }
}
