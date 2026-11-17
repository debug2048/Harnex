package com.harnex.run;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.harnex.conversation.*;
import com.harnex.execution.ExecutionSpec;
import com.harnex.execution.ScopeIdentity;
import com.harnex.interaction.PendingInteraction;
import com.harnex.interaction.RunInteractionSource;
import com.harnex.contract.run.RunInputView;
import com.harnex.contract.run.RunView;

import io.swagger.v3.oas.annotations.media.Schema;

/** Capture event position, public history and pending decisions as one replacement view. */
@Service
public final class RunPresentationProjector {
    private final RunRegistry registry;
    private final RunEventBus events;
    private final ConversationRepository archive;
    private final ScopeIdentity scope;
    private final List<RunInteractionSource> interactions;
    private final RunInputStore inputs;
    private final RunProcessHistory processes;
    @Autowired
    public RunPresentationProjector(RunRegistry registry, RunEventBus events, ConversationRepository archive, ScopeIdentity scope,
        List<RunInteractionSource> interactions, RunInputStore inputs, RunProcessHistory processes) {
        this.registry = registry; this.events = events; this.archive = archive; this.scope = scope;
        this.interactions = List.copyOf(interactions); this.inputs = inputs;
        this.processes = processes;
    }
    public RunPresentationProjector(RunRegistry registry, RunEventBus events, ConversationRepository archive, ScopeIdentity scope,
        List<RunInteractionSource> interactions, RunInputStore inputs) {
        this(registry, events, archive, scope, interactions, inputs, null);
    }
    @Schema(requiredProperties = {"schemaVersion", "run", "cursor", "history", "live", "interactionRevision", "interactions", "inputs"})
    public record Presentation(int schemaVersion, RunView run, RunEventBus.PresentationCursor cursor,
        ConversationRepository.RunHistory history, RunPresentationState.Snapshot live,
        long interactionRevision, List<PendingInteraction> interactions, List<RunInputView> inputs) {}
    public Presentation capture(String runId) { return capture(runId, RunClient.INTERNAL); }
    public Presentation capture(String runId, RunClient client) {
        RunHandle run = registry.get(runId);
        if (run == null) {
            var durable = RunView.from(registry.view(runId), client);
            if (durable == null) throw new ConversationException("run_not_found", "Run not found");
            var prepared = processes != null ? processes.prepare(runId) : null;
            return assemble(durable, new RunEventBus.PresentationCursor(null, durable.lastSeq(), false), RunPresentationState.empty(), prepared);
        }
        var prepared = processes != null ? processes.prepare(runId) : null;
        return events.capture(run, (cursor, live) -> {
            synchronized (run) { return assemble(RunView.from(run.view(), client), cursor, live, prepared); }
        });
    }
    private Presentation assemble(RunView run, RunEventBus.PresentationCursor cursor, RunPresentationState.Snapshot live,
        RunProcessHistory.Prepared prepared) {
        var history = archive.captureRunHistory(scope.scopeId(), run.conversationId(), run.turnId(), run.runId());
        if (history.conversation() != null && history.conversation().deleted()) throw new ConversationException("conversation_deleted", "Conversation was deleted");
        if (processes != null) live = processes.restore(run, live, prepared);
        var answer = history.answer();
        boolean displayLive = !run.terminal() && run.executing() && (answer == null || answer.isMutable());
        if (displayLive && run.goalId() != null && run.result() != null && Objects.equals(run.currentStepId(), run.result().stepId())
            && archive.findOperation(scope.scopeId(), run.runId() + "_step_" + run.result().stepId()).isPresent()) displayLive = false;
        String text = displayLive ? live.text().isEmpty() && answer != null ? answer.plainText() : live.text() : "";
        var display = new RunPresentationState.Snapshot(text, displayLive ? live.thinking() : "", live.messageStartSeq(), live.truncated(),
            live.tools(), live.imageOperations(), live.modelCalls(), live.messages());
        List<PendingInteraction> pending = "EXTERNAL".equals(run.controlState()) || run.terminal() || run.executionSpec().kind() != ExecutionSpec.Kind.AGENT
            ? List.of() : interactions.stream().flatMap(source -> source.collect(run.conversationId()).stream()).sorted(Comparator.comparingLong(PendingInteraction::createdAt)).toList();
        return new Presentation(1, run, cursor, history, display, cursor.projectionSeq(), pending,
            inputs.list(run.runId(), null).stream().map(RunInputView::from).toList());
    }
}
