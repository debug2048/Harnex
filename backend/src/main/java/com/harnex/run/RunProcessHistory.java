package com.harnex.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.harnex.contract.run.RunView;
import com.harnex.execution.ExecutionSpec;
import com.harnex.model.ChatMessage;
import com.harnex.model.ToolCall;
import com.harnex.session.SessionStore;

/** Restore all retained Run progress without treating the live 500-activity window as history. */
@Service
public class RunProcessHistory {
    private final RunToolStore tools;
    private final RunProcessStore processes;
    private final ObjectProvider<SessionStore> sessions;

    public RunProcessHistory(RunToolStore tools, RunProcessStore processes, ObjectProvider<SessionStore> sessions) {
        this.tools = tools; this.processes = processes; this.sessions = sessions;
    }

    public RunPresentationState.Snapshot restore(RunView run, RunPresentationState.Snapshot live) {
        return restore(run, live, prepare(run.runId()));
    }

    public record Prepared(List<RunToolStore.ToolDetail> tools, List<RunPresentationState.ProcessMessage> messages) {}
    /** Disk reads happen before taking the Run event lock. The later live capture overrides these older entries. */
    public Prepared prepare(String runId) {
        return new Prepared(tools.list(runId).stream().filter(tool -> tool.subagentConversationId() == null).toList(), processes.list(runId));
    }
    public RunPresentationState.Snapshot restore(RunView run, RunPresentationState.Snapshot live, Prepared prepared) {
        if (prepared == null) return live;
        var savedTools = prepared.tools();
        var restoredTools = new TreeMap<Long, RunPresentationState.ToolActivity>();
        for (var tool : savedTools) {
            String state = tool.state();
            if (state == null) {
                state = tool.resultPending() ? "RUNNING" : "COMPLETED";
                for (var activity : run.activities()) {
                    if (activity.seq() >= tool.seq() && activity.subagentThreadId() == null
                        && Objects.equals(tool.id(), activity.id()) && "tool_use_end".equals(activity.eventType())) {
                        state = "failed".equals(activity.activityOutcome()) ? "FAILED" : "COMPLETED";
                        break;
                    }
                }
            }
            if (run.terminal() && ("QUEUED".equals(state) || "RUNNING".equals(state))) state = "FAILED";
            restoredTools.put(tool.seq(), new RunPresentationState.ToolActivity(tool.id(), tool.name(), null, state,
                clip(tool.content(), 240), tool.seq(), clip(tool.arguments(), 16_000), clip(tool.content(), 16_000),
                tool.durationMs(), tool.resultPending() && !run.terminal(), tool.afterMessageId(), tool.purpose()));
        }
        // The captured display owns in-flight states. Durable records supply entries trimmed from that display.
        for (var tool : live.tools()) restoredTools.put(tool.seq(), tool);
        var messages = new TreeMap<Long, RunPresentationState.ProcessMessage>();
        for (var message : live.messages()) messages.put(message.seq(), message);
        for (var message : prepared.messages()) messages.put(message.seq(), message);
        if (messages.isEmpty() && run.terminal() && run.executionSpec().kind() == ExecutionSpec.Kind.AGENT) {
            for (var message : recoverLegacy(run, savedTools)) {
                processes.save(run.runId(), message);
                messages.put(message.seq(), message);
            }
        }
        return new RunPresentationState.Snapshot(live.text(), live.thinking(), live.messageStartSeq(), live.truncated(),
            List.copyOf(restoredTools.values()), live.imageOperations(), live.modelCalls(), List.copyOf(messages.values()));
    }

    /** Older versions retained progress only in Agent context. Bind it to unique calls owned by this Run. */
    private List<RunPresentationState.ProcessMessage> recoverLegacy(RunView run, List<RunToolStore.ToolDetail> ownedTools) {
        SessionStore source = sessions.getIfAvailable();
        if (source == null || ownedTools.isEmpty()) return List.of();
        var thread = source.get(run.conversationId()).orElse(null);
        if (thread == null) return List.of();
        var saved = new ArrayList<>(thread.getMessages());
        Map<CallIdentity, Integer> counts = new HashMap<>();
        for (var message : saved) for (var call : calls(message)) counts.merge(identity(call), 1, Integer::sum);
        Map<CallIdentity, List<RunToolStore.ToolDetail>> byCall = new HashMap<>();
        for (var tool : ownedTools) byCall.computeIfAbsent(new CallIdentity(tool.id(), tool.name(), tool.arguments()), ignored -> new ArrayList<>()).add(tool);
        List<RunPresentationState.ProcessMessage> recovered = new ArrayList<>();
        List<ChatMessage> pending = new ArrayList<>();
        int lastOwned = -1;
        for (int index = 0; index < saved.size(); index++) {
            var message = saved.get(index);
            if ("user".equals(message.getRole())) { pending.clear(); continue; }
            if (!"assistant".equals(message.getRole())) continue;
            pending.add(message);
            if (calls(message).isEmpty()) continue;
            var matches = calls(message).stream().filter(call -> counts.getOrDefault(identity(call), 0) == 1)
                .map(call -> byCall.getOrDefault(identity(call), List.of())).filter(rows -> rows.size() == 1)
                .map(rows -> rows.get(0)).sorted(Comparator.comparingLong(RunToolStore.ToolDetail::seq)).toList();
            if (!matches.isEmpty()) {
                var tool = matches.get(0);
                addProgress(recovered, Math.max(1, tool.seq() - 1), pending, tool.afterMessageId(), false);
                lastOwned = index;
            }
            pending.clear();
        }
        // Preserve the final reasoning block, while the public archive continues to own the final answer.
        if (lastOwned >= 0) {
            pending.clear();
            for (int index = lastOwned + 1; index < saved.size(); index++) {
                var message = saved.get(index);
                if ("user".equals(message.getRole()) || !calls(message).isEmpty()) break;
                if (!"assistant".equals(message.getRole())) continue;
                pending.add(message);
                if (message.getContent() != null && !message.getContent().isBlank()) break;
            }
            addProgress(recovered, Math.max(1, run.lastSeq() - 1), pending, null, true);
        }
        return recovered;
    }
    private static void addProgress(List<RunPresentationState.ProcessMessage> target, long seq, List<ChatMessage> messages,
        String afterMessageId, boolean reasoningOnly) {
        String text = reasoningOnly ? "" : join(messages.stream().map(ChatMessage::getContent).toList());
        String thinking = join(messages.stream().map(ChatMessage::getReasoning).toList());
        if (!text.isBlank() || !thinking.isBlank()) target.add(new RunPresentationState.ProcessMessage(seq, text, thinking, afterMessageId));
    }
    private record CallIdentity(String id, String name, String arguments) {}
    private static CallIdentity identity(ToolCall call) { return new CallIdentity(call.getId(), call.getName(), call.getArguments()); }
    private static List<ToolCall> calls(ChatMessage message) { return message.getToolCalls() == null ? List.of() : message.getToolCalls(); }
    private static String join(List<String> values) { return String.join("\n\n", values.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).toList()); }
    private static String clip(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max) + "…"; }
}
