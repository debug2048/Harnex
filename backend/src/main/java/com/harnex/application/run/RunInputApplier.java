package com.harnex.application.run;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.harnex.artifact.ArtifactAttachments;
import com.harnex.artifact.ArtifactException;
import com.harnex.attachment.AttachmentService;
import com.harnex.audit.RunInputAuditService;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentConversationSink;
import com.harnex.core.AgentHistoryContext;
import com.harnex.core.ConversationContinuity;
import com.harnex.interaction.InteractionStateBoundary;
import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.model.MessageAttachment;
import com.harnex.model.StreamEvent;
import com.harnex.run.RunInputCheckpoint;
import com.harnex.run.RunInputRecord;
import com.harnex.run.RunInputStore;
import com.harnex.run.RunRegistry;
import com.harnex.session.SessionStore;

/** Exactly-once Steering application at AgentLoop safe points. */
@Service
@ProjectRuntime
public class RunInputApplier implements RunInputCheckpoint {
    @Autowired(required = false)
    private InteractionStateBoundary interactionBoundary = InteractionStateBoundary.DIRECT;

    @Autowired(required = false)
    private AgentConversationSink archive;
    @Autowired(required = false)
    private RunRegistry runs;
    @Autowired(required = false)
    private ArtifactAttachments artifactAttachments;
    @Autowired(required = false)
    private AgentHistoryContext historyContext;
    private final RunInputStore store;
    private final AttachmentService attachments;
    private final SessionStore sessions;
    private final RunInputAuditService audit;

    public RunInputApplier(RunInputStore store, AttachmentService attachments, SessionStore sessions,
        RunInputAuditService audit) {
        this.store = store;
        this.attachments = attachments;
        this.sessions = sessions;
        this.audit = audit;
    }

    @Override
    public boolean hasPendingSteering(String runId) {
        return store.hasPendingSteering(runId);
    }

    @Override
    public ApplyResult applySteering(String runId, ConversationThread thread, String stepId,
        Consumer<StreamEvent> emit) {
        return applySteering(runId, thread, stepId, emit, () -> true);
    }

    @Override
    public ApplyResult applySteering(String runId, ConversationThread thread, String stepId,
        Consumer<StreamEvent> emit, BooleanSupplier mayApply) {
        return applySteering(runId, thread, stepId, emit, mayApply, null);
    }

    @Override
    public ApplyResult applySteering(String runId, ConversationThread thread, String stepId,
        Consumer<StreamEvent> emit, BooleanSupplier mayApply, Runnable beforeDurableApply) {
        RunInputStore.ApplyBatch batch = store.claimSteering(runId);
        if (batch.isEmpty()) return ApplyResult.none();
        batch.inputs().forEach(input -> {
            audit.record(batch.recovered() ? "applying_recovered" : "applying", input);
            publish(emit, "run_input_applying", input, batch.operationId(), stepId, null);
        });

        List<ResolvedInput> valid = new ArrayList<>();
        Set<String> rejectedIds = new LinkedHashSet<>();
        for (RunInputRecord input : batch.inputs()) {
            List<String> dropped = new ArrayList<>();
            List<MessageAttachment> resolved;
            try { resolved = artifactAttachments != null ? artifactAttachments.resolve(thread.getId(), input.attachments())
                : attachments.resolve(thread.getId(), input.attachments(), dropped); }
            catch (ArtifactException unavailable) { resolved = List.of(); dropped.addAll(input.attachments()); }
            if (!dropped.isEmpty() || resolved.size() != input.attachments().size()) {
                rejectedIds.add(input.inputId());
            } else {
                valid.add(new ResolvedInput(input, resolved));
            }
        }
        List<RunInputRecord> rejected = store.rejectApplying(runId, batch.operationId(), rejectedIds,
            "attachment_unavailable_at_apply");
        rejected.forEach(input -> {
            audit.record("rejected", input);
            publish(emit, "run_input_rejected", input, batch.operationId(), stepId,
                "attachment_unavailable_at_apply");
        });
        if (valid.isEmpty()) {
            return new ApplyResult(batch.inputs().size(), 0, rejected.size(), batch.recovered());
        }

        Set<String> validIds = valid.stream().map(item -> item.input().inputId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean[] alreadyCommitted = {false};
        List<RunInputRecord> applied = interactionBoundary.update(thread.getId(), "inputs", () -> store.commitApplying(runId, batch.operationId(), validIds, stepId, null,
            current -> {
                if (mayApply != null && !mayApply.getAsBoolean()) return RunInputStore.CommitDecision.abort();
                Set<String> durableIds = durableAppliedIds(thread);
                alreadyCommitted[0] = current.stream().allMatch(input -> durableIds.contains(input.inputId()));
                if (!alreadyCommitted[0]) {
                    if (beforeDurableApply != null) beforeDurableApply.run();
                    List<ResolvedInput> currentResolved = valid.stream()
                        .filter(item -> current.stream().anyMatch(input -> input.inputId().equals(item.input().inputId())))
                        .toList();
                    List<String> ids = currentResolved.stream().map(item -> item.input().inputId()).toList();
                    List<MessageAttachment> mergedAttachments = mergeAttachments(currentResolved);
                    String content = mergedContent(currentResolved);
                    ChatMessage steering = ChatMessage.builder().role("user").content(content)
                        .attachments(mergedAttachments.isEmpty() ? null : mergedAttachments)
                        .runInputIds(ids).channel(commonChannel(currentResolved)).build();
                    for (ResolvedInput item : currentResolved) {
                        ConversationContinuity.appendHumanRequest(thread, ChatMessage.user(item.input().text()));
                    }
                    thread.getMessages().add(steering);
                    sessions.save(thread);
                }
                if (archive != null && runs != null) archive.inputs(runs.get(runId), current);
                if (alreadyCommitted[0] && historyContext != null) historyContext.refreshContinuity(thread, runId);
                StreamEvent appliedEvent = StreamEvent.of("run_input_applied", Map.of(
                    "operationId", batch.operationId(), "stepId", stepId == null ? "" : stepId,
                    "inputIds", current.stream().map(RunInputRecord::inputId).toList(),
                    "count", current.size(), "recovered", batch.recovered() || alreadyCommitted[0]));
                if (emit != null) emit.accept(appliedEvent);
                return RunInputStore.CommitDecision.applied(appliedEvent.getSeq());
            }));
        applied.forEach(input -> audit.record(alreadyCommitted[0] ? "applied_recovered" : "applied", input));
        return new ApplyResult(batch.inputs().size(), applied.size(), rejected.size(),
            batch.recovered() || alreadyCommitted[0]);
    }

    private static Set<String> durableAppliedIds(ConversationThread thread) {
        Set<String> ids = new HashSet<>();
        for (ChatMessage message : thread.getMessages()) {
            if (message.getRunInputIds() != null) ids.addAll(message.getRunInputIds());
        }
        return ids;
    }

    private static List<MessageAttachment> mergeAttachments(List<ResolvedInput> inputs) {
        Map<String, MessageAttachment> unique = new LinkedHashMap<>();
        for (ResolvedInput input : inputs) {
            for (MessageAttachment attachment : input.attachments()) {
                unique.putIfAbsent(attachment.getName(), attachment);
            }
        }
        return List.copyOf(unique.values());
    }

    private static String mergedContent(List<ResolvedInput> inputs) {
        if (inputs.size() == 1) return inputs.get(0).input().text();
        StringBuilder out = new StringBuilder("The user supplied these steering updates in order:\n");
        for (int index = 0; index < inputs.size(); index++) {
            RunInputRecord input = inputs.get(index).input();
            out.append("\n--- Steering update ").append(index + 1).append('/').append(inputs.size())
                .append(" (inputId: ").append(input.inputId()).append(") ---\n").append(input.text());
        }
        return out.toString();
    }

    private static String commonChannel(List<ResolvedInput> inputs) {
        String first = inputs.get(0).input().channel();
        return inputs.stream().allMatch(item -> first.equals(item.input().channel())) ? first : "steering";
    }

    private static void publish(Consumer<StreamEvent> emit, String type, RunInputRecord input,
        String operationId, String stepId, String reason) {
        if (emit == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inputId", input.inputId());
        data.put("mode", input.mode().name());
        data.put("status", input.status().name());
        data.put("sequence", input.sequence());
        data.put("revision", input.revision());
        data.put("operationId", operationId);
        if (stepId != null) data.put("stepId", stepId);
        if (reason != null) data.put("reason", reason);
        emit.accept(StreamEvent.of(type, Map.copyOf(data)));
    }

    private record ResolvedInput(RunInputRecord input, List<MessageAttachment> attachments) {}
}
