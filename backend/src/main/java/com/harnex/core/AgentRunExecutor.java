package com.harnex.core;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.harnex.artifact.ArtifactAttachments;
import com.harnex.command.Directive;
import com.harnex.command.SlashCommandService;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.config.RuntimeProfile;
import com.harnex.conversation.ConversationException;
import com.harnex.conversation.ConversationRepository;
import com.harnex.conversation.ConversationTextSnapshots;
import com.harnex.conversation.MessageRecord;
import com.harnex.execution.AgentSpec;
import com.harnex.run.RunOrigin;
import com.harnex.execution.ExecutionSpec;
import com.harnex.execution.RunExecutionContext;
import com.harnex.execution.RunExecutor;
import com.harnex.execution.RunSubmissionInterceptor;
import com.harnex.execution.ScopeIdentity;
import com.harnex.goal.GoalSlashService;
import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.contract.run.RunCreateRequest;
import com.harnex.model.StreamEvent;
import com.harnex.provider.TextTargetResolver;
import com.harnex.run.RunHandle;
import com.harnex.session.SessionStore;
import com.harnex.snapshot.SnapshotService;

/** Agent assembly stays behind the Agent execution boundary; the public Run service has no AgentExecutionPort dependency. */
@Component
@ProjectRuntime
public final class AgentRunExecutor implements RunExecutor, RunSubmissionInterceptor {
    private final AgentExecutionPort loop;
    private final AgentConfig config;
    private final SessionStore sessions;
    private final SnapshotService snapshots;
    private final SlashCommandService slash;
    private final GoalSlashService goals;
    private final AgentExecutionRegistry agents;
    private final ArtifactAttachments attachments;
    private final ConversationRepository archive;
    private final TextTargetResolver targets;
    private final ScopeIdentity scope;
    private final AgentHistoryContext history;
    @Autowired(required = false)
    private ConversationTextSnapshots textSnapshots;
    public AgentRunExecutor(AgentExecutionPort loop, AgentConfig config, SessionStore sessions, SnapshotService snapshots,
        SlashCommandService slash, GoalSlashService goals, AgentExecutionRegistry agents, ArtifactAttachments attachments,
        ConversationRepository archive, TextTargetResolver targets, ScopeIdentity scope, AgentHistoryContext history) {
        this.loop = loop; this.config = config; this.sessions = sessions; this.snapshots = snapshots; this.slash = slash;
        this.goals = goals; this.agents = agents; this.attachments = attachments; this.archive = archive; this.targets = targets; this.scope = scope;
        this.history = history;
    }
    @Override public boolean supports(ExecutionSpec spec) { return spec instanceof AgentSpec; }
    @Override public Preparation prepare(ExecutionSpec requested) {
        try (var configuration = targets.openConfigurationScope()) {
            AgentSpec resolved = (AgentSpec)resolve(requested);
            return new Preparation(resolved, textSnapshots == null ? null : textSnapshots.freezeAgent(resolved.text()).snapshotId());
        }
    }
    @Override public ExecutionSpec resolve(ExecutionSpec requested) {
        if (scope.profile() != RuntimeProfile.PROJECT) {
            throw new ConversationException("unsupported_execution", "Agent requires a project scope");
        }
        AgentSpec spec = (AgentSpec) requested;
        if (spec.ideContext() != null && spec.ideContext().getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new ConversationException("bad_request", "ideContext must not exceed 1024 UTF-8 bytes");
        }
        AgentExecutionOptions options = AgentExecutionMapper.options(spec, config);
        return new AgentSpec(targets.resolve(spec.text()), spec.toolsets(), spec.permissionMode(), spec.readOnly(),
            new AgentSpec.Limits(options.limits().maxWorkIterations()), options.planReview(), options.interactionTarget(), spec.ideContext());
    }
    @Override public RunHandle intercept(RunCreateRequest request, RunOrigin origin) {
        if (!(request.getExecution() instanceof AgentSpec spec) || !goals.matches(request.effectiveInput())) return null;
        String id = request.getConversationId();
        if (id == null) id = UUID.randomUUID().toString();
        ConversationThread thread = sessions.get(id).orElseGet(ConversationThread::new);
        thread.setId(id); configure(thread, spec);
        sessions.save(thread);
        GoalSlashService.Result result = goals.handle(thread, request, origin);
        if (!result.accepted()) throw new ConversationException(result.rejectCode(), result.rejectMessage());
        return result.run();
    }
    @Override public Result execute(Request request, RunExecutionContext context) {
        AgentSpec spec = (AgentSpec) request.execution();
        ConversationThread thread = sessions.get(context.conversationId()).orElseGet(ConversationThread::new);
        thread.setId(context.conversationId());
        history.prepare(thread, context.scopeId(), request.parentMessageId());
        configure(thread, spec);
        Directive directive = slash.handle(thread, request.input());
        if (directive.notice() != null) context.events().accept(StreamEvent.of("command_result", Map.of("command", "slash", "message", directive.notice())));
        if (directive.kind() == Directive.Kind.RESPOND) {
            String response = directive.text() == null ? "" : directive.text();
            thread.getMessages().add(ChatMessage.user(request.input()));
            if (!response.isBlank()) thread.getMessages().add(ChatMessage.assistant(response));
            thread.getState().setConversationLeafId(archive.findTurn(context.scopeId(), context.conversationId(), context.turnId()).orElseThrow().activeAttemptId());
            sessions.save(thread);
            context.events().accept(StreamEvent.of("command_result", Map.of("command", "slash", "message", response)));
            return Result.completed(response);
        }
        String input = directive.kind() == Directive.Kind.EXPAND ? directive.text() : request.input();
        if (spec.ideContext() != null && !spec.ideContext().isBlank()) {
            input = "<ide_context source=\"vscode\">\n" + spec.ideContext().replace("</ide_context>", "</ide_\u200Bcontext>")
                + "\n</ide_context>\n\n" + input;
        }
        ChatMessage user = ChatMessage.user(input); user.setOriginalInput(request.input()); user.setChannel(request.channel());
        user.setAttachments(attachments.resolve(context.conversationId(), request.attachmentIds()));
        thread.getMessages().add(user);
        try { snapshots.takeRunStart(thread, context.runId()); }
        catch (RuntimeException failure) { LoggerFactory.getLogger(getClass()).warn("Run snapshot failed: {}", failure.toString()); }
        try (var binding = agents.bind(context)) {
            context.checkActive();
            StepResult step = loop.executeStep(new StepRequest(thread, AgentExecutionMapper.options(spec, config)),
                new AgentExecutionContext(context.runId(), null, context.events(), binding.contextRef(), binding.onContextReady(), null, null));
            context.events().accept(StreamEvent.of("agent_step_result", Map.of("stepId", step.stepId(), "result", step)));
            MessageRecord.Status status = !step.persisted() ? MessageRecord.Status.FAILED : step.incomplete() ? MessageRecord.Status.INCOMPLETE
                : switch (step.reason()) { case RETURNED -> MessageRecord.Status.COMPLETED; case CANCELLED -> MessageRecord.Status.CANCELLED;
                    case PAUSED, SUPERSEDED -> MessageRecord.Status.INCOMPLETE; default -> MessageRecord.Status.FAILED; };
            // Bind the context to the exact result attempt, regardless of independently retained filesystem snapshots.
            thread.getState().setConversationLeafId(archive.findTurn(context.scopeId(), context.conversationId(), context.turnId()).orElseThrow().activeAttemptId());
            sessions.save(thread);
            return new Result(AgentConversationSink.resultContent(step), status, step.errorCode(), step.detail());
        }
    }
    private static void configure(ConversationThread thread, AgentSpec spec) {
        if (spec.text() != null) { thread.setProvider(spec.text().provider()); thread.setModel(spec.text().model()); thread.setReasoningEffort(spec.text().reasoningEffort()); }
        if (spec.toolsets() != null) thread.setToolsets(spec.toolsets());
        if (spec.permissionMode() != null) thread.getState().setPermissionMode(spec.permissionMode());
    }
}
