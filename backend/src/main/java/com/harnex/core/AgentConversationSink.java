package com.harnex.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.harnex.config.ProjectRuntime;
import com.harnex.conversation.CommitReceipt;
import com.harnex.conversation.ContentPart;
import com.harnex.conversation.ConversationRepository;
import com.harnex.conversation.ConversationTextSnapshots;
import com.harnex.execution.AgentSpec;
import com.harnex.execution.RunExecutor;
import com.harnex.execution.ScopeIdentity;
import com.harnex.image.ImageResultContent;
import com.harnex.provider.TextTargetResolver;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.run.RunCoordinator.RunSpec;
import com.harnex.run.RunHandle;
import com.harnex.run.RunInputRecord;
import com.harnex.run.RunRecord;

/** Publishes explicit Agent entry inputs and step results; internal continuation prompts never enter the archive. */
@Component
@ProjectRuntime
public final class AgentConversationSink {
    private final ConversationRepository archive;
    private final ScopeIdentity scope;
    @Autowired(required = false)
    private ImageResultContent images;
    @Autowired(required = false)
    private ConversationTextSnapshots textSnapshots;
    @Autowired(required = false)
    private TextTargetResolver targets;
    public AgentConversationSink(ConversationRepository archive, ScopeIdentity scope) { this.archive = archive; this.scope = scope; }
    public RunSpec prepare(RunSpec spec, String conversationId, String input, String channel, String hash) {
        var current = archive.loadConversation(scope.scopeId(), conversationId).orElse(null);
        String leaf = current == null ? null : current.activeLeafId();
        var execution = spec.execution();
        String configurationRef = null;
        if (textSnapshots != null && targets != null && execution instanceof AgentSpec agent) {
            var text = targets.resolve(agent.text());
            execution = new AgentSpec(text,agent.toolsets(),agent.permissionMode(),agent.readOnly(),agent.limits(),agent.planReview(),agent.interactionTarget(),agent.ideContext());
            configurationRef = textSnapshots.freezeAgent(text).snapshotId();
        }
        var frozen = new RunExecutor.Request(execution, input, List.of(), leaf, channel, configurationRef);
        var reference = new RunRecord.SubmissionRef(scope.scopeId(), "turn_" + UUID.randomUUID().toString().replace("-", ""),
            "accept_" + UUID.randomUUID().toString().replace("-", ""), frozen, current == null ? 0 : current.historyRevision(),
            hash == null ? IdempotencyReceiptStore.requestHash(Map.of("input", input, "execution", spec.execution(), "channel", channel)) : hash);
        var text = execution instanceof AgentSpec agent ? agent.text() : null;
        return new RunSpec(spec.origin(), spec.options(), text == null ? spec.provider() : text.provider(), text == null ? spec.model() : text.model(),
            text == null ? spec.reasoningEffort() : text.reasoningEffort(), spec.goalId(),
            spec.toolsets(), spec.permissionMode(), execution, reference);
    }
    public void accept(RunHandle run) {
        var ref = run.getSubmission();
        if (ref == null) return;
        List<ContentPart> parts = new ArrayList<>();
        if (!ref.request().input().isBlank()) parts.add(ContentPart.text(ref.request().input()));
        ref.request().attachmentIds().forEach(id -> parts.add(ContentPart.artifact(id)));
        archive.acceptTurn(new ConversationRepository.AcceptTurn(ref.scopeId(), run.getThreadId(), ref.acceptOperationId(),
            ref.turnId(), run.getRunId(), run.getCreationRequest() == null ? "entry:" + run.getRunId() : run.getCreationRequest().scope() + ":" + run.getCreationRequest().key(),
            ref.requestHash(), ref.expectedHistoryRevision(), ref.request().parentMessageId(), null, parts, run.getExecutionSpec()));
    }
    public CommitReceipt step(RunHandle run, StepResult result) {
        if (run.getSubmission() == null) return null;
        acceptIfMissing(run);
        return archive.commitAgentStepResult(new ConversationRepository.AgentStepResult(scope.scopeId(), run.getThreadId(),
            run.getRunId() + "_step_" + result.stepId(), run.getSubmission().turnId(), run.getRunId(), result.stepId(),
            images == null ? resultContent(result) : images.append(run.getRunId(), result.stepId(), resultContent(result))));
    }
    public void acceptIfMissing(RunHandle run) {
        if (run.getSubmission() != null && archive.findOperation(scope.scopeId(), run.getSubmission().acceptOperationId()).isEmpty()) accept(run);
    }
    public void inputs(RunHandle run, List<RunInputRecord> inputs) {
        if (run == null || run.getSubmission() == null) return;
        for (var input : inputs) {
            List<ContentPart> parts = new ArrayList<>();
            if (!input.text().isBlank()) parts.add(ContentPart.text(input.text()));
            input.attachments().forEach(id -> parts.add(ContentPart.artifact(id)));
            archive.appendRunInput(new ConversationRepository.AppendRunInput(scope.scopeId(), run.getThreadId(),
                "input_" + input.inputId(), run.getSubmission().turnId(), run.getRunId(), input.inputId(), parts));
        }
    }
    /** Review feedback is a real human input and must survive rebuilding context from the archive. */
    public void feedback(RunHandle run, String text, String inputKey) {
        if (run == null || run.getSubmission() == null || text == null || text.isBlank()) return;
        acceptIfMissing(run);
        String id = "review_" + IdempotencyReceiptStore.requestHash(Map.of("key", inputKey)).substring(0, 32);
        archive.appendRunInput(new ConversationRepository.AppendRunInput(scope.scopeId(), run.getThreadId(),
            "input_" + id, run.getSubmission().turnId(), run.getRunId(), id, List.of(ContentPart.text(text.trim()))));
    }
    public static List<ContentPart> resultContent(StepResult result) {
        List<ContentPart> parts = new ArrayList<>();
        if (!result.output().isBlank()) parts.add(ContentPart.text(result.output()));
        if (result.reason() == StepResult.Reason.NEEDS_INPUT && result.detail() != null && !result.detail().isBlank()
            && !result.output().contains(result.detail())) parts.add(ContentPart.text(result.detail()));
        return List.copyOf(parts);
    }
}
