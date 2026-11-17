package com.harnex.application.run;

import com.harnex.core.AgentExecutionMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import com.harnex.artifact.ArtifactAttachments;
import com.harnex.artifact.ArtifactException;
import com.harnex.config.AgentConfig;
import com.harnex.observability.HarnexMetrics;
import com.harnex.config.RuntimeConfiguration;
import com.harnex.conversation.*;
import com.harnex.cron.AutomationResultArchive;
import com.harnex.execution.*;
import com.harnex.image.ImageResultContent;
import com.harnex.image.ImageServiceException;
import com.harnex.contract.run.RunCreateRequest;
import com.harnex.model.StreamEvent;
import com.harnex.provider.ProviderCallScope;
import com.harnex.provider.ProviderException;
import com.harnex.run.*;
import com.harnex.run.RunCoordinator.RunLease;
import com.harnex.util.Ids;
import lombok.extern.slf4j.Slf4j;

/** Conversation admission and dispatch. Executors own their model, workspace and media assembly. */
@Slf4j
@Service
public class RunRequestService {
    private final RunCoordinator coordinator;
    private final RunExecutorRegistry executors;
    private final ConversationRepository archive;
    private final ArtifactAttachments attachments;
    private final ScopeIdentity scope;
    private final AgentConfig config;
    private final HarnexMetrics metrics;
    private final TaskExecutor workers;
    private final List<RunSubmissionInterceptor> interceptors;
    private final Object admission = new Object();
    @Autowired(required = false)
    private RuntimeConfiguration runtimeConfiguration;

    public RunRequestService(RunCoordinator coordinator, RunExecutorRegistry executors, ConversationRepository archive,
        ArtifactAttachments attachments, ScopeIdentity scope, AgentConfig config, HarnexMetrics metrics,
        @Qualifier("chatExec") TaskExecutor workers, List<RunSubmissionInterceptor> interceptors) {
        this.coordinator = coordinator; this.executors = executors; this.archive = archive; this.attachments = attachments;
        this.scope = scope; this.config = config; this.metrics = metrics; this.workers = workers; this.interceptors = List.copyOf(interceptors);
    }

    public CreateResult create(RunCreateRequest request) {
        return create(request, RunClient.INTERNAL);
    }

    public CreateResult create(RunCreateRequest request, RunClient client) {
        return create(request, null, null, null, client.origin());
    }

    /** Queue admission freezes capability defaults without creating a Run or consuming a model budget. */
    public RunExecutor.Request freezeSubmission(RunCreateRequest request, RunClient client) {
        validate(request);
        validateClientTools(request.getExecution(), client.origin());
        var prepared = executors.resolve(request.getExecution()).prepare(request.getExecution());
        attachments.validate(request.getAttachmentIds() == null ? List.of() : request.getAttachmentIds());
        return new RunExecutor.Request(prepared.execution(), request.effectiveInput(), request.getAttachmentIds(), null,
            request.getChannel() == null || request.getChannel().isBlank() ? "webui" : request.getChannel(), prepared.configurationRef(),
            null, null, request.getNotificationMode());
    }

    public CreateResult createQueued(RunCreateRequest request, String configurationRef, String predecessorTurnId) {
        RunOrigin origin = RunClient.INTERNAL.origin();
        if (request.getConversationId() != null && predecessorTurnId != null) {
            var predecessor = archive.findTurn(scope.scopeId(), request.getConversationId(), predecessorTurnId).orElse(null);
            var view = predecessor == null ? null : coordinator.view(predecessor.runId());
            if (view != null && view.get("origin") instanceof Map<?, ?> previous && "CLIENT".equals(previous.get("kind"))) {
                origin = new RunOrigin(RunOrigin.Kind.CLIENT, previous.get("reference") instanceof String client ? client : null);
            }
        }
        return create(request, null, new RunExecutor.Preparation(request.getExecution(), configurationRef), predecessorTurnId, origin);
    }

    /** Trusted scheduling entry; public channel strings never select execution authority or scheduling priority. */
    public CreateResult createAutomation(RunCreateRequest request, String jobId) {
        return create(request, null, null, null, new RunOrigin(RunOrigin.Kind.AUTOMATION, jobId));
    }

    private CreateResult create(RunCreateRequest request, MessageRecord retryUser, RunExecutor.Preparation queuedPreparation,
        String predecessorTurnId, RunOrigin origin) {
        metrics.chatRequests.increment();
        try {
            validate(request);
            String hash = retryUser == null ? requestHash(request) : IdempotencyReceiptStore.requestHash(Map.of("request", requestHash(request), "retryUserMessageId", retryUser.messageId()));
            if (origin.kind() == RunOrigin.Kind.AUTOMATION) hash = IdempotencyReceiptStore.requestHash(Map.of("request", hash, "automation", origin.toView()));
            String requestId = request.getClientRequestId();
            // Exact retries are resolved before mutable configuration, asset availability or history revisions.
            synchronized (admission) {
                var replay = coordinator.replayCreate(scope.scopeId(), requestId, hash);
                if (replay.replayed()) {
                    reconcile(replay.replayedRun());
                    return CreateResult.created(replay.replayedRun(), request.isStream());
                }
                if ("idempotency_conflict".equals(replay.rejectCode()) || "idempotency_resource_gone".equals(replay.rejectCode())) {
                    return CreateResult.rejected(replay.rejectCode(), replay.rejectMessage());
                }
                RunExecutor executor = executors.resolve(request.getExecution());
                if (queuedPreparation == null) validateClientTools(request.getExecution(), origin);
                RunExecutor.Preparation preparation = queuedPreparation == null ? executor.prepare(request.getExecution()) : queuedPreparation;
                ExecutionSpec execution = preparation.execution();
                List<String> ids = request.getAttachmentIds() == null ? List.of() : List.copyOf(request.getAttachmentIds());
                attachments.validate(ids);
                RunCreateRequest resolved = copy(request, execution);
                if (execution instanceof AgentSpec && retryUser == null && origin.kind() != RunOrigin.Kind.AUTOMATION) {
                    for (RunSubmissionInterceptor interceptor : interceptors) {
                        RunHandle intercepted = interceptor.intercept(resolved, origin);
                        if (intercepted != null) return CreateResult.created(intercepted, request.isStream());
                    }
                }
                String conversationId = request.getConversationId() == null ? UUID.randomUUID().toString() : request.getConversationId();
                if (retryUser == null) validateParent(conversationId, request.getExpectedHistoryRevision(), request.getParentMessageId());
                else {
                    if (request.getExpectedHistoryRevision() == null) throw new ConversationException("history_revision_required", "expectedHistoryRevision is required");
                    var current = archive.loadConversation(scope.scopeId(), conversationId).orElseThrow(() -> new ConversationException("conversation_not_found", "conversation not found"));
                    if (current.historyRevision() != request.getExpectedHistoryRevision()) throw new ConversationException("history_conflict", "conversation history changed");
                }
                String turnId = retryUser == null ? id("turn") : retryUser.turnId();
                var frozen = new RunExecutor.Request(execution, request.effectiveInput(), ids, request.getParentMessageId(),
                    request.getChannel() == null || request.getChannel().isBlank() ? "webui" : request.getChannel(), preparation.configurationRef(), retryUser == null ? null : retryUser.messageId(), predecessorTurnId);
                var submission = new RunRecord.SubmissionRef(scope.scopeId(), turnId, id("accept"), frozen,
                    request.getExpectedHistoryRevision(), hash);
                var begun = coordinator.begin(conversationId, RunCoordinator.RunSpec.submission(origin, execution,
                    submission, execution instanceof AgentSpec agent ? AgentExecutionMapper.options(agent, config) : null).withNotification(request.getNotificationMode()), scope.scopeId(), requestId, hash);
                if (!begun.started()) return CreateResult.rejected(begun.rejectCode(), begun.rejectMessage());
                if (begun.replayed()) return CreateResult.created(begun.replayedRun(), request.isStream());
                RunLease lease = begun.lease();
                boolean handedOff = false;
                try {
                    var accepted = acceptArchive(lease.run());
                    lease.events().accept(StreamEvent.of("turn_accepted", Map.of("receipt", accepted,
                        "conversationId", conversationId, "turnId", turnId, "executionSpec", execution)));
                    lease.events().accept(StreamEvent.of("run_started", Map.of("origin", lease.run().getOrigin().toView(),
                        "executionSpec", execution, "conversationId", conversationId, "turnId", turnId)));
                    workers.execute(ProviderCallScope.captureRunnable(() -> execute(lease, executor, frozen)));
                    handedOff = true;
                    return CreateResult.created(lease.run(), request.isStream());
                } catch (RejectedExecutionException busy) {
                    commit(lease, new RunExecutor.Result(List.of(), MessageRecord.Status.FAILED,
                        "executor_saturated", "run executor is busy"));
                    // Admission and its failure are durable: replay returns this Run and does not silently resubmit.
                    return CreateResult.created(lease.run(), request.isStream());
                } catch (RuntimeException failure) {
                    lease.fail(failure);
                    throw failure;
                } finally { if (!handedOff) lease.close(); }
            }
        } catch (ConversationException failure) { return CreateResult.rejected(failure.code(), failure.getMessage()); }
        catch (ArtifactException failure) { return CreateResult.rejected(failure.code(), failure.getMessage()); }
        catch (ImageServiceException failure) { return CreateResult.rejected(failure.code(), failure.getMessage()); }
        catch (ProviderException failure) { return CreateResult.rejected(failure.code, failure.getMessage()); }
        catch (IllegalArgumentException failure) { return CreateResult.rejected("invalid_execution_spec", failure.getMessage()); }
    }

    private static void validateClientTools(ExecutionSpec execution, RunOrigin origin) {
        if (origin.kind() == RunOrigin.Kind.CLIENT && origin.reference() != null && execution instanceof AgentSpec spec
            && spec.toolsets() != null && spec.toolsets().stream()
                .anyMatch(name -> name.startsWith("ide-vscode-") && !name.equals("ide-" + origin.reference()))) {
            throw new ConversationException("bad_request", "Editor tools belong to another client; select this client's toolset");
        }
    }

    private void execute(RunLease lease, RunExecutor executor, RunExecutor.Request request) {
        try (var settings = runtimeConfiguration == null ? RuntimeConfiguration.Scope.empty()
            : runtimeConfiguration.acquire()) {
            RunExecutor.Result result;
            if (!lease.startExecution()) result = new RunExecutor.Result(List.of(), MessageRecord.Status.CANCELLED, "cancelled", null);
            else {
                var reference = lease.run().getSubmission();
                boolean agent = request.execution() instanceof AgentSpec;
                RunExecutionContext context = new RunExecutionContext(scope.scopeId(), lease.run().getThreadId(), reference.turnId(),
                    lease.runId(), lease.run().getControlSignal(), Instant.ofEpochMilli(lease.run().getCreatedAt()).plusSeconds(4 * 3600),
                    // Main answer + up to three distinct summary targets; AUTO may also resume after a media tool.
                    new ExecutionBudget(agent ? Integer.MAX_VALUE : request.execution() instanceof ConversationSpec c && c.action() == ConversationSpec.Action.AUTO ? 5 : 4,
                        agent ? Integer.MAX_VALUE : 1), lease.events());
                result = executor.execute(request, context);
            }
            commit(lease, result);
        } catch (Exception failure) {
            metrics.chatErrors.increment();
            log.error("Run {} failed", lease.runId(), failure);
            try {
                var turn = archive.findTurn(scope.scopeId(), lease.run().getThreadId(), lease.run().getSubmission().turnId()).orElseThrow();
                var previous = archive.findMessage(scope.scopeId(), lease.run().getThreadId(), turn.activeAttemptId()).orElseThrow();
                if (previous.isMutable()) commit(lease, new RunExecutor.Result(previous.contentParts(),
                    lease.run().isCancelRequested() ? MessageRecord.Status.CANCELLED : MessageRecord.Status.FAILED,
                    "execution_failed", failure.getMessage()));
                else lease.fail(failure);
            } catch (RuntimeException archiveFailure) {
                log.error("Could not persist result for {}", lease.runId(), archiveFailure);
                lease.fail(archiveFailure);
            }
        } finally { lease.close(); }
    }

    @Autowired(required = false)
    private ImageResultContent imageResults;
    @Autowired(required = false)
    private AutomationResultArchive automationResults;
    private void commit(RunLease lease, RunExecutor.Result result) {
            MessageRecord.Status status = lease.run().isCancelRequested() ? MessageRecord.Status.CANCELLED : result.status();
            CommitReceipt receipt = archive.commitTurnResult(new ConversationRepository.WriteResult(scope.scopeId(),
                lease.run().getThreadId(), lease.runId() + "_result", lease.run().getSubmission().turnId(), lease.runId(),
                imageResults == null ? result.content() : imageResults.append(lease.runId(), null, result.content()), status));
            lease.events().accept(StreamEvent.of("message_committed", Map.of("receipt", receipt)));
            RunStatus terminal = switch (status) { case COMPLETED -> RunStatus.COMPLETED; case CANCELLED -> RunStatus.CANCELLED;
                case INCOMPLETE, INTERRUPTED -> RunStatus.INCOMPLETE; default -> RunStatus.FAILED; };
            if (automationResults != null) automationResults.capture(lease.run(), result.content(), terminal, result.detail());
            lease.finishResult(receipt, terminal, result.errorCode(), result.detail());
    }
    public CreateResult retry(String conversationId, String messageId, String requestId, Long expectedHistoryRevision,
        ExecutionSpec requestedExecution, boolean rerunAgent, RunClient client) {
        try {
            var selected = archive.findMessage(scope.scopeId(), conversationId, messageId).orElseThrow(() -> new ConversationException("message_not_found", "message not found"));
            if (selected.isMutable()) return CreateResult.rejected("predecessor_incomplete", "Stop the current answer before retrying");
            var turn = archive.findTurn(scope.scopeId(), conversationId, selected.turnId()).orElseThrow();
            if (selected.status() == MessageRecord.Status.PROGRESS) return CreateResult.rejected("progress_not_retryable", "Resume or re-execute the owning Agent task through its task controls");
            var user = selected;
            if (selected.role() != MessageRecord.Role.USER) {
                MessageRecord ancestor = selected;
                Set<String> seen = new HashSet<>();
                while (ancestor.parentMessageId() != null && seen.add(ancestor.messageId())) {
                    ancestor = archive.findMessage(scope.scopeId(), conversationId, ancestor.parentMessageId()).orElseThrow();
                    if (!selected.turnId().equals(ancestor.turnId())) break;
                    if (ancestor.role() == MessageRecord.Role.USER) user = ancestor;
                }
                if (user.role() != MessageRecord.Role.USER) throw new ConversationException("invalid_retry", "The original user message is unavailable");
            }
            var originalRun = coordinator.view(selected.runId() == null ? turn.runId() : selected.runId());
            if (originalRun != null && originalRun.get("goalId") != null && conversationId.equals(originalRun.get("conversationId"))) return CreateResult.rejected("goal_not_retryable", "Use the Goal controls to resume or restart this task");
            ExecutionSpec originalExecution = originalRun != null && originalRun.get("executionSpec") instanceof ExecutionSpec spec ? spec : turn.executionSnapshot();
            ExecutionSpec execution = requestedExecution == null ? originalExecution : requestedExecution;
            if (execution instanceof AgentSpec && !rerunAgent) return CreateResult.rejected("agent_rerun_requires_explicit_action", "Use re-execute task to run the Agent again; workspace actions may execute again");
            var request = new RunCreateRequest(); request.setConversationId(conversationId); request.setClientRequestId(requestId);
            request.setExpectedHistoryRevision(expectedHistoryRevision); request.setParentMessageId(user.parentMessageId());
            request.setExecution(execution); request.setInput(user.plainText());
            request.setAttachmentIds(user.contentParts().stream().filter(part -> part.type() == ContentPart.Type.ARTIFACT_REF).map(ContentPart::artifactId).toList());
            return create(request, user, null, null, client.origin());
        } catch (ConversationException failure) { return CreateResult.rejected(failure.code(), failure.getMessage()); }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileArchives() {
        for (var view : coordinator.listViews(null, null)) {
            RunHandle run = coordinator.get(String.valueOf(view.get("runId")));
            if (run != null) reconcile(run);
        }
    }

    private void reconcile(RunHandle run) {
        if (!run.isRecovered() || run.isTerminal() || run.getGoalId() != null || run.getSubmission() == null) return;
        try {
            var ref = run.getSubmission();
            // An admitted request may have survived while its first archive projection did not.
            if (archive.findOperation(scope.scopeId(), ref.acceptOperationId()).isEmpty()) acceptArchive(run);
            var turn = archive.findTurn(scope.scopeId(), run.getThreadId(), ref.turnId()).orElseThrow();
            var answer = archive.findMessage(scope.scopeId(), run.getThreadId(), turn.activeAttemptId()).orElseThrow();
            CommitReceipt receipt;
            MessageRecord.Status status = answer.status();
            if (answer.isMutable()) {
                RunExecutor.Result recovered = null;
                if (ref.request().execution() instanceof ConversationSpec conversation
                    && (conversation.action() == ConversationSpec.Action.IMAGE_GENERATE || conversation.action() == ConversationSpec.Action.IMAGE_EDIT)) {
                    recovered = executors.resolve(conversation).recover(ref.request(), new RunExecutionContext(scope.scopeId(), run.getThreadId(),
                        ref.turnId(), run.getRunId(), run.getControlSignal(), Instant.now(), new ExecutionBudget(0, 0), ignored -> {}));
                }
                status = run.isCancelRequested() ? MessageRecord.Status.CANCELLED : recovered == null ? MessageRecord.Status.INTERRUPTED : recovered.status();
                receipt = archive.commitTurnResult(new ConversationRepository.WriteResult(scope.scopeId(), run.getThreadId(),
                    run.getRunId() + "_recovered_result", ref.turnId(), run.getRunId(), recovered == null ? answer.contentParts() : recovered.content(), status));
            } else {
                receipt = archive.findOperation(scope.scopeId(), run.getRunId() + "_result")
                    .or(() -> archive.findOperation(scope.scopeId(), run.getRunId() + "_recovered_result")).orElseThrow();
            }
            RunStatus terminal = switch (status) { case COMPLETED -> RunStatus.COMPLETED; case CANCELLED -> RunStatus.CANCELLED;
                case INCOMPLETE, INTERRUPTED -> RunStatus.INCOMPLETE; default -> RunStatus.FAILED; };
            coordinator.finishRecoveredResult(run.getRunId(), receipt, terminal,
                terminal == RunStatus.INCOMPLETE ? "Runtime restarted before this answer completed" : null);
        } catch (ConversationException deleted) {
            if ("conversation_deleted".equals(deleted.code())) coordinator.finishRecoveredResult(run.getRunId(), null, RunStatus.FAILED, deleted.getMessage());
            else log.error("Archive recovery failed for {}", run.getRunId(), deleted);
        } catch (RuntimeException failure) { log.error("Archive recovery failed for {}", run.getRunId(), failure); }
    }

    private ConversationRepository.AcceptTurn acceptCommand(RunHandle run) {
        var ref = run.getSubmission();
        List<ContentPart> input = new ArrayList<>();
        if (!ref.request().input().isBlank()) input.add(ContentPart.text(ref.request().input()));
        ref.request().attachmentIds().forEach(id -> input.add(ContentPart.artifact(id)));
        return new ConversationRepository.AcceptTurn(ref.scopeId(), run.getThreadId(), ref.acceptOperationId(), ref.turnId(), run.getRunId(),
            run.getCreationRequest() == null ? null : run.getCreationRequest().key(), ref.requestHash(), ref.expectedHistoryRevision(),
            ref.request().parentMessageId(), ref.request().predecessorTurnId(), input, run.getExecutionSpec());
    }
    private CommitReceipt acceptArchive(RunHandle run) {
        var ref = run.getSubmission();
        if (ref.request().retryUserMessageId() == null) return archive.acceptTurn(acceptCommand(run));
        return archive.acceptRetry(new ConversationRepository.RetryTurn(ref.scopeId(), run.getThreadId(), ref.acceptOperationId(), ref.turnId(),
            run.getRunId(), run.getCreationRequest().key(), ref.requestHash(), ref.expectedHistoryRevision(), ref.request().retryUserMessageId(), run.getExecutionSpec()));
    }

    private void validateParent(String conversationId, Long revision, String parent) {
        var current = archive.loadConversation(scope.scopeId(), conversationId).orElse(null);
        if (current == null) {
            if (revision != null && revision != 0 || parent != null) throw new ConversationException("history_conflict", "new conversation requires revision 0 and no parent");
            return;
        }
        if (current.deleted()) throw new ConversationException("conversation_deleted", "conversation was deleted");
        if (revision == null) throw new ConversationException("history_revision_required", "expectedHistoryRevision is required");
        if (revision != current.historyRevision()) throw new ConversationException("history_conflict", "conversation history changed");
        if (parent == null && current.messageCount() > 0) throw new ConversationException("parent_required", "parentMessageId is required");
        if (parent != null) {
            var message = archive.findMessage(scope.scopeId(), conversationId, parent).orElseThrow(
                () -> new ConversationException("message_not_found", "parent message does not exist"));
            if (message.isMutable()) throw new ConversationException("predecessor_incomplete", "parent answer is still running");
        }
    }

    private static void validate(RunCreateRequest request) {
        if (request == null) throw new ConversationException("bad_request", "request body is required");
        if (!request.unknownFieldNames().isEmpty()) throw new ConversationException("unknown_request_field", "unknown request fields: " + String.join(", ", request.unknownFieldNames()));
        if (request.getExecution() == null) throw new ConversationException("invalid_execution_spec", "execution.kind is required");
        if (request.getConversationId() != null && !Ids.isSafeResourceId(request.getConversationId())) throw new ConversationException("bad_request", "invalid conversationId");
        if (!Ids.isSafeCallId(request.getClientRequestId())) throw new ConversationException("bad_request", "clientRequestId is required");
        var ids = request.getAttachmentIds();
        if (ids != null && (ids.size() > RunInputRecord.MAX_ATTACHMENTS || ids.stream().anyMatch(id -> !Ids.isSafeCallId(id)))) {
            throw new ConversationException("bad_request", "invalid attachmentIds");
        }
        if (request.effectiveInput().isBlank() && (ids == null || ids.isEmpty())) throw new ConversationException("bad_request", "input or attachmentIds are required");
        if (request.effectiveInput().length() > 1_000_000) throw new ConversationException("bad_request", "input is too large");
    }
    private static RunCreateRequest copy(RunCreateRequest request, ExecutionSpec execution) {
        RunCreateRequest copy = new RunCreateRequest(); copy.setConversationId(request.getConversationId()); copy.setInput(request.getInput());
        copy.setAttachmentIds(request.getAttachmentIds() == null ? List.of() : List.copyOf(request.getAttachmentIds())); copy.setExecution(execution);
        copy.setClientRequestId(request.getClientRequestId()); copy.setExpectedHistoryRevision(request.getExpectedHistoryRevision());
        copy.setParentMessageId(request.getParentMessageId()); copy.setChannel(request.getChannel()); copy.setStream(request.isStream());
        copy.setNotificationMode(request.getNotificationMode()); return copy;
    }
    private static String requestHash(RunCreateRequest request) {
        Map<String, Object> values = new TreeMap<>(); values.put("conversationId", request.getConversationId()); values.put("input", request.effectiveInput());
        values.put("attachmentIds", request.getAttachmentIds() == null ? List.of() : request.getAttachmentIds()); values.put("execution", request.getExecution());
        values.put("expectedHistoryRevision", request.getExpectedHistoryRevision()); values.put("parentMessageId", request.getParentMessageId());
        values.put("channel", request.getChannel());
        values.put("notificationMode", RunNotification.Mode.orOff(request.getNotificationMode()));
        return IdempotencyReceiptStore.requestHash(values);
    }
    private static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", ""); }
    public record CreateResult(RunHandle run, boolean streamRequested, String rejectCode, String rejectMessage) {
        public static CreateResult created(RunHandle run, boolean streamRequested) { return new CreateResult(run, streamRequested, null, null); }
        public static CreateResult rejected(String code, String message) { return new CreateResult(null, false, code, message); }
    }
}
