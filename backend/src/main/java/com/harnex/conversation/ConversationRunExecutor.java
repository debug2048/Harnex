package com.harnex.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.execution.ConversationSpec;
import com.harnex.execution.ExecutionSpec;
import com.harnex.execution.RunExecutionContext;
import com.harnex.execution.RunExecutor;
import com.harnex.execution.TextTarget;
import com.harnex.image.ImageCallText;
import com.harnex.image.ImageServiceException;
import com.harnex.image.MediaInvocationService;
import com.harnex.model.StreamEvent;
import com.harnex.model.ToolCall;
import com.harnex.provider.ModelProvider;
import com.harnex.provider.ProviderCallScope;
import com.harnex.provider.ProviderException;
import com.harnex.provider.ProviderRouter;
import com.harnex.provider.TextTargetResolver;

/**
 * Fixed conversation flow with no Agent, client-executed tool registry, snapshot or Goal dependencies. Provider-hosted
 * native tools are allowed because they complete inside the model request and require no local tool dispatch loop.
 */
@Component
public final class ConversationRunExecutor implements RunExecutor {
    private final ProviderRouter router;
    private final ConversationContextPlanner planner;
    private final ConversationRepository repository;
    private final ConversationContinuations continuations;
    private final TextTargetResolver targets;
    private final ConversationTextSnapshots snapshots;
    private final MediaInvocationService media;
    public ConversationRunExecutor(ProviderRouter router, ConversationContextPlanner planner,
        ConversationRepository repository, ConversationContinuations continuations, TextTargetResolver targets,
        ConversationTextSnapshots snapshots, MediaInvocationService media) {
        this.router = router; this.planner = planner; this.repository = repository; this.continuations = continuations;
        this.targets = targets;
        this.snapshots = snapshots;
        this.media = media;
    }
    @Override public ExecutionSpec resolve(ExecutionSpec requested) {
        ConversationSpec spec = (ConversationSpec) requested;
        return new ConversationSpec(spec.action(), targets.resolve(spec.text()), spec.imageServiceId(), null);
    }
    @Override public boolean supports(ExecutionSpec spec) {
        return spec instanceof ConversationSpec conversation
            && (conversation.action() == ConversationSpec.Action.TEXT || conversation.action() == ConversationSpec.Action.AUTO);
    }
    @Override public Preparation prepare(ExecutionSpec requested) {
        try (var configuration = router.openConfigurationScope()) {
            ConversationSpec resolved = (ConversationSpec) resolve(requested);
            String textConfiguration = snapshots.freeze(resolved.text()).snapshotId();
            return new Preparation(resolved, resolved.action() == ConversationSpec.Action.AUTO
                ? media.freeze(textConfiguration, resolved.imageServiceId()) : textConfiguration);
        }
    }
    @Override public Result execute(Request request, RunExecutionContext context) {
        try (var configuration = router.openConfigurationScope()) {
            return executeWithConfiguration(request, context);
        }
    }

    private Result executeWithConfiguration(Request request, RunExecutionContext context) {
        context.checkActive();
        ConversationSpec spec = (ConversationSpec) request.execution();
        TextTarget target = spec.text();
        var mediaConfiguration = spec.action() == ConversationSpec.Action.AUTO ? media.configuration(request.configurationRef()) : null;
        var snapshot = snapshots.load(mediaConfiguration == null ? request.configurationRef() : mediaConfiguration.textConfiguration());
        var initialTarget = snapshots.requireTarget(snapshot, target.provider(), target.model());
        ConversationTextSnapshots.Target[] actualTarget = {initialTarget};
        Map<String, ConversationContextPlanner.Plan> plans = new LinkedHashMap<>();
        StringBuilder output = new StringBuilder();
        Map<String, Object> end = new LinkedHashMap<>();
        long[] lastCheckpoint = {System.nanoTime()};
        int[] checkpointIndex = {0};
        boolean[] ended = {false};
        boolean[] thinking = {false};
        var authorized = new AtomicReference<Set<String>>(Set.of());
        boolean[] mediaAllowed = {false};
        try (var ignored = ProviderCallScope.open(new ProviderCallScope.Context(context.runId(), context.conversationId(),
            null, "conversation-main", context.control()::isRunCancelRequested, context::onCancel, context.events(), true))) {
            context.budget().acquireModelCall();
            router.streamChat(ModelProvider.ChatRequest.builder().provider(target == null ? null : target.provider())
                .model(target == null ? null : target.model()).reasoningEffort(target == null ? null : target.reasoningEffort())
                .messages(List.of()).tools(List.of()).allowTextOnlyDone(true).promptCacheScope(context.conversationId())
                .targetPreparation((provider, model, call) -> {
                    var actual = snapshots.requireTarget(snapshot, provider, model);
                    boolean mediaFits = actual.functionTools() && actual.inputBudget() >= 7_168L
                        + planner.estimateText(request.input(), model)
                        + request.attachmentIds().size() * (snapshot.visionEnabled() && actual.modalities().contains("image") ? 8192L : 512L)
                        + (mediaConfiguration == null ? 0 : planner.estimateText(media.instructions(mediaConfiguration, true), model));
                    var planned = plans.computeIfAbsent(actual.identity(), key -> mediaConfiguration == null
                        ? planner.build(request, context, snapshot, actual, plans.isEmpty())
                        : planner.build(request, context, snapshot, actual, plans.isEmpty(), media.instructions(mediaConfiguration, mediaFits),
                            mediaFits ? 6144 : 0));
                    actualTarget[0] = actual;
                    call.setMessages(planned.messages()); call.setMaxTokens(actual.maxOutputTokens());
                    call.setContextPlanId(planned.trace().contextPlanId());
                    if (mediaConfiguration != null) {
                        var sources = new LinkedHashSet<>(request.attachmentIds());
                        var visibleImages = planned.trace().blocks().stream().filter(block -> "artifact".equals(block.sourceKind()))
                            .map(ConversationContextPlanner.Block::sourceRef).toList();
                        // Planner blocks are newest first. Prefer recent versions when the reference table is bounded.
                        sources.addAll(visibleImages);
                        authorized.set(media.availableSources(context.scopeId(), sources));
                        call.setTools(media.tools(mediaConfiguration, mediaFits, authorized.get()));
                        mediaAllowed[0] = !call.getTools().isEmpty();
                    }
                })
                .idempotencyKey(context.runId() + "-text").build(), event -> {
                    context.checkActive();
                    if ("thinking_delta".equals(event.getType())) thinking[0] = true;
                    if ("text_delta".equals(event.getType()) && event.getData().get("text") instanceof String text) {
                        if (output.length() + text.length() > 2_000_000) throw new ProviderException("output_limit", "answer exceeded the supported size");
                        output.append(text);
                    }
                    if ("message_end".equals(event.getType())) {
                        ended[0] = true; end.putAll(event.getData());
                        if (event.getData().get("content") instanceof String text) { output.setLength(0); output.append(text); }
                        // Commit visible reasoning before image generation starts or the answer is archived.
                        String reasoning = event.getData().get("reasoning") instanceof String value ? value : "";
                        if (thinking[0] || !reasoning.isBlank()) {
                            context.events().accept(StreamEvent.of("thinking_end", Map.of("text", reasoning)));
                            thinking[0] = false;
                        }
                    }
                    // Provider-private continuation is never sent to public clients.
                    if ("message_end".equals(event.getType())) {
                        Map<String, Object> publicEnd = new LinkedHashMap<>(event.getData());
                        List.of("reasoningToken", "responseOutputItems", "toolCalls").forEach(publicEnd::remove);
                        context.events().accept(StreamEvent.of("message_end", publicEnd));
                    } else context.events().accept(event);
                    if (!output.isEmpty() && System.nanoTime() - lastCheckpoint[0] > 1_000_000_000L) {
                        var receipt = repository.checkpointAnswer(new ConversationRepository.WriteResult(context.scopeId(), context.conversationId(),
                            context.runId() + "_checkpoint_" + ++checkpointIndex[0], context.turnId(), context.runId(),
                            List.of(ContentPart.text(output.toString())), MessageRecord.Status.STREAMING));
                        context.events().accept(StreamEvent.of("message_checkpoint", Map.of("receipt", receipt)));
                        lastCheckpoint[0] = System.nanoTime();
                    }
                });
            if (!ended[0] || Boolean.TRUE.equals(end.get("interrupted"))) {
                return new Result(List.of(ContentPart.text(output.toString())), MessageRecord.Status.INTERRUPTED,
                    "provider_result_unknown", "the provider did not return a complete answer");
            }
            if (end.get("toolCalls") instanceof List<?> calls && !calls.isEmpty()) {
                if (mediaAllowed[0] && calls.size() == 1) {
                    ToolCall call;
                    try { call = new ObjectMapper().convertValue(calls.get(0), ToolCall.class); }
                    catch (IllegalArgumentException invalid) { throw new ImageServiceException("invalid_media_call", "The model returned an invalid image call"); }
                    Result result = media.invoke(mediaConfiguration, call, authorized.get(), context);
                    if (!output.isEmpty()) {
                        var content = new ArrayList<ContentPart>(); content.add(ContentPart.text(output.toString())); content.addAll(result.content());
                        result = new Result(content, result.status(), result.errorCode(), result.detail());
                    }
                    if (end.containsKey("reasoningProvider") || end.containsKey("responseOutputProvider")) {
                        String text = result.content().stream().filter(part -> part.type() == ContentPart.Type.TEXT)
                            .map(ContentPart::text).collect(Collectors.joining("\n"));
                        try {
                            var turn = repository.findTurn(context.scopeId(), context.conversationId(), context.turnId());
                            if (turn.isPresent()) continuations.save(context.conversationId(), turn.get().activeAttemptId(), text, end, actualTarget[0].identity());
                        } catch (ConversationException unavailable) {
                            context.events().accept(StreamEvent.of("notice", Map.of("kind", "continuation_unavailable",
                                "message", "Image results were saved; provider continuation metadata could not be saved.")));
                        }
                    }
                    return result;
                }
                return new Result(List.of(ContentPart.text(output.toString())), MessageRecord.Status.FAILED,
                    "unsupported_tool", "this conversation did not authorize these tool calls");
            }
            if (mediaAllowed[0] && ImageCallText.isMalformedInvocation(request.input(), output.toString())) {
                output.setLength(0);
                context.events().accept(StreamEvent.of("message_end", Map.of("content", "")));
                // No image request exists yet. End this turn instead of hiding a second model request behind it.
                throw new ImageServiceException("media_tool_call_not_emitted",
                    "The conversation model returned an image call as text. No image request was sent. Choose another conversation model or provider and retry.");
            }
            String answerId = repository.findTurn(context.scopeId(), context.conversationId(), context.turnId()).orElseThrow().activeAttemptId();
            continuations.save(context.conversationId(), answerId, output.toString(), end, actualTarget[0].identity());
            return Result.completed(output.toString());
        } catch (ImageServiceException failure) {
            return new Result(List.of(ContentPart.text(output.toString())), MessageRecord.Status.FAILED, failure.code(), failure.getMessage());
        } catch (ProviderException failure) {
            boolean cancelled = context.control().isRunCancelRequested();
            MessageRecord.Status status = cancelled ? MessageRecord.Status.CANCELLED
                : !failure.safeToRetry() ? MessageRecord.Status.INTERRUPTED : MessageRecord.Status.FAILED;
            return new Result(List.of(ContentPart.text(output.toString())), status,
                failure.safeToRetry() ? failure.code : "provider_result_unknown", failure.getMessage());
        } catch (CancellationException cancelled) {
            return new Result(List.of(ContentPart.text(output.toString())), MessageRecord.Status.CANCELLED, "cancelled", cancelled.getMessage());
        }
    }
    @Override public Result recover(Request request, RunExecutionContext context) {
        return request.execution() instanceof ConversationSpec spec && spec.action() == ConversationSpec.Action.AUTO ? media.recover(context) : null;
    }
}
