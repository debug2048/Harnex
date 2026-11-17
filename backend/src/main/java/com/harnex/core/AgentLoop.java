package com.harnex.core;

import com.harnex.todo.TodoStore;
import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.audit.LlmAuditLog;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.conversation.AgentExecutionSearch;
import com.harnex.goal.GoalPromptContext;
import com.harnex.image.AgentImageService;
import com.harnex.memory.CoreMemory;
import com.harnex.middleware.SensitiveDataMiddleware;
import com.harnex.middleware.TitleMiddleware;
import com.harnex.provider.ProviderCallScope;
import com.harnex.provider.ProviderException;
import com.harnex.tool.ToolCallClassifier;
import com.harnex.middleware.MiddlewareChain;
import com.harnex.middleware.SummarizationMiddleware;
import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.model.StreamEvent;
import com.harnex.model.ToolCall;
import com.harnex.provider.ModelProvider;
import com.harnex.provider.NativeResponseReplay;
import com.harnex.provider.ProviderRouter;
import com.harnex.run.RunControlSignal;
import com.harnex.run.RunInputCheckpoint;
import com.harnex.session.SessionStore;
import com.harnex.snapshot.SnapshotService;
import com.harnex.tool.*;
import com.harnex.toolset.ToolsetRegistry;
import com.harnex.conversation.context.MessageBudget;
import com.harnex.skill.SkillDirectiveHelper;
import com.harnex.tool.ToolCallSummary;

import lombok.extern.slf4j.Slf4j;

/**
 * Core while-loop agent engine (Claude Code style) with DeerFlow middleware hooks.
 */
@Slf4j
@Component
@ProjectRuntime
public class AgentLoop implements AgentExecutionPort {
    private final TodoStore todoStore;

    private final ObjectMapper M = new ObjectMapper();
    private final AgentConfig cfg;
    private final ProviderRouter router;
    private final ToolRegistry toolRegistry;
    private final ToolsetRegistry toolsets;
    private final ParallelToolExecutor toolExecutor;
    private final MiddlewareChain chain;
    private final PromptBuilder promptBuilder;
    private final SessionStore sessions;
    private final SummarizationMiddleware summariser;
    private final SnapshotService snapshots;
    private final CoreMemory coreMemory;
    private final LlmAuditLog auditLog;
    /**
     * Optional — only present in the Spring runtime. Lets the final {@code sessions.save} defer to a tombstone marker
     * set by {@link com.harnex.web.api.conversation.ConversationsController} when the user deletes a thread mid-run, so we don't
     * resurrect the JSON we just wiped.
     */
    private final ThreadExecutionGuard executionGuard;
    private final SensitiveDataMiddleware sensitiveData;
    private final RunInputCheckpoint runInputs;
    @Autowired(required = false)
    private AgentImageService agentImages;
    @Autowired(required = false)
    private AgentExecutionSearch executionSearch;
    @Autowired(required = false)
    private AgentContextAdapter contextAdapter;
    @Autowired(required = false)
    private AgentHistoryContext historyContext;

    public AgentLoop(AgentConfig cfg, ProviderRouter router, ToolRegistry toolRegistry, ToolsetRegistry toolsets,
        ParallelToolExecutor toolExecutor, MiddlewareChain chain, PromptBuilder promptBuilder, SessionStore sessions,
        SummarizationMiddleware summariser, SnapshotService snapshots, CoreMemory coreMemory,
        LlmAuditLog auditLog, ThreadExecutionGuard executionGuard,
        SensitiveDataMiddleware sensitiveData, RunInputCheckpoint runInputs, TodoStore todoStore) {
        this.todoStore = todoStore;
        this.cfg = cfg;
        this.router = router;
        this.toolRegistry = toolRegistry;
        this.toolsets = toolsets;
        this.toolExecutor = toolExecutor;
        this.chain = chain;
        this.promptBuilder = promptBuilder;
        this.sessions = sessions;
        this.summariser = summariser;
        this.snapshots = snapshots;
        this.coreMemory = coreMemory;
        this.auditLog = auditLog;
        this.executionGuard = executionGuard;
        this.sensitiveData = sensitiveData;
        this.runInputs = runInputs == null ? RunInputCheckpoint.noop() : runInputs;
    }

    public StepLimits defaultStepLimits(Integer requested) {
        return StepLimits.resolve(cfg, requested, cfg.getOrdinaryMaxIterations());
    }

    @Override
    public StepResult executeStep(StepRequest request, AgentExecutionContext execution) {
        ConversationThread thread = request.thread();
        AgentExecutionOptions options = request.options();
        Consumer<StreamEvent> emit = execution.events();
        AtomicReference<AgentCtx> ctxRef = execution.contextRef();
        Consumer<AgentCtx> onCtxReady = execution.onContextReady();
        SubAgentBudget parentBudget = execution.parentBudget();
        String inheritedRunId = execution.runId();
        SnapshotService.SnapshotCaptureContext inheritedSnapshotContext = execution.snapshotContext();
        long tokensBefore = parentBudget == null ? 0L : parentBudget.getTokensUsed();
        AgentCtx ctx = new AgentCtx(thread, emit);
        ctx.setTodoStore(todoStore);
        ctx.setOptions(options);
        // Parent's tracker takes priority so token/cost + ask accounting is shared across the whole
        // family for one user turn; otherwise allocate a fresh root-scope budget. Iterations are NOT
        // tracked here as a gate — each agent bounds its own loop locally (see loopBound below).
        // The family-wide sub-agent run count is enforced by the same budget in SubAgentExecutor
        // via harnex.subagent.max-runs-per-turn; maxDepth / maxPerThread are additional recursion
        // and concurrency guards. The token cap stays at 0 (=unlimited) by default; cost-capped
        // deployments can surface a real limit via config.
        SubAgentBudget budget =
            parentBudget != null ? parentBudget : new SubAgentBudget(cfg.getSubagent().getMaxRunsPerTurn(), 0L);
        ctx.setBudget(budget);
        if (ctxRef != null) {
            ctxRef.set(ctx);
        }
        // Bind the executing thread so cancel() can interrupt an in-flight provider read instead
        // of waiting for the next iteration. The provider's BufferedReader.readLine throws
        // InterruptedIOException when the worker is interrupted, which is then mapped to a
        // ProviderException("network", ...) and \u2014 because emitted=true once any text/thinking
        // delta has gone out \u2014 propagates upward without spurious failover. Cleared in finally
        // to avoid a stale interrupt being observed by whoever runs next on this pool thread.
        final Thread worker = Thread.currentThread();
        ctx.onCancel(worker::interrupt);
        // Hard cap on token-limit recovery retries within a single run() so a pathological
        // history that compresses by a tiny amount each pass cannot loop forever.
        int tokenRecoveryAttempts = 0;
        final int MAX_TOKEN_RECOVERY = 2;
        // Hard cap on transient network-error retries within a single run(). A single retry covers
        // the vast majority of real-world hiccups (TCP RST, brief 502, DNS blip) without burning a
        // user's iteration budget on a provider that is genuinely down.
        int transientRetryAttempts = 0;
        final int MAX_TRANSIENT_RETRY = 1;
        // Latch the approaching-cap warnings (remaining==3 / remaining==1) so a token-limit or
        // transient-network retry — which rewinds with iter-- and re-enters the SAME iteration —
        // cannot inject the same "[system_reminder] N remaining" line twice. Keyed on the
        // remaining-count so each threshold fires at most once per run(), honouring the
        // "fired exactly twice" contract documented at the injection site below.
        Set<Integer> firedIterationWarnings = new HashSet<>();
        Set<Integer> firedFinalizationWarnings = new HashSet<>();
        NoProgressGuard noProgressGuard = new NoProgressGuard();
        boolean noProgressCheckpoint = false;
        boolean runtimeCheckpoint = false;
        int noProgressCheckpointAttempts = 0;
        int runtimeFinalizationAttempts = 0;
        boolean noProgressFinalization = false;
        int noProgressFinalizationAttempts = 0;
        boolean compressionFinalization = false;
        final String runId = inheritedRunId == null || inheritedRunId.isBlank()
            ? "run_" + UUID.randomUUID().toString().replace("-", "") : inheritedRunId;
        ctx.setPendingSteering(() -> runInputs.hasPendingSteering(runId));

        try (var configuration = router.openConfigurationScope();
            var callScope = ProviderCallScope.open(new ProviderCallScope.Context(
            runId, thread.getId(), execution.stepId(), "agent", ctx::isCancelled, ctx::registerCancellation, ctx::emit,true,
            contextAdapter == null ? null : (provider,model) -> contextAdapter.validate(runId,provider,model)))) {
            if (onCtxReady != null) onCtxReady.accept(ctx);
            // CRITICAL: setup steps (system-prompt seeding, before-chain, toolset resolution) MUST
            // sit inside the try/finally — a failure in any of them previously skipped chain.after,
            // sessions.save and the terminal `done` SSE frame, leaving the frontend spinning and the
            // thread state on disk stale.
            // History-integrity sweep: repair orphan tool_use / missing tool_result pairs BEFORE
            // promptBuilder/middleware/provider see the message list. Stored threads can carry
            // orphans through process crash, snapshot rollback, manual thread.json edit, or
            // pre-1.0 middleware bugs; left untouched, OpenAI/Anthropic reject the very first
            // request with HTTP 400 ("tool_calls must be followed by tool messages") and the
            // user sees a permanently broken conversation. O(N), no-op on clean histories.
            HistoryIntegrity.SweepResult sweep = HistoryIntegrity.sweep(thread);
            if (!sweep.isClean()) {
                ctx.emit(StreamEvent.of("notice", Map.of("kind", "history_repaired", "missingResultsInserted",
                    sweep.missingResultsInserted(), "orphanResultsDropped", sweep.orphanResultsDropped())));
            }
            // Derive a small runtime projection from the selected archive branch before compression.
            if (historyContext != null) historyContext.refreshContinuity(thread, runId);
            else ConversationContinuity.restore(thread, thread.getMessages());
            String updatedTitle = deriveTitle(thread);
            if (updatedTitle != null) {
                // Make the derived title durable before announcing it. This is intentionally an early, lightweight
                // full-thread save: at this point a new thread contains only the triggering user turn, and
                // FtsSessionStore serialises save/delete under the same per-thread lock.
                try {
                    sessions.save(thread);
                } catch (RuntimeException saveFailure) {
                    // The terminal save remains authoritative and may still succeed; title latency must not abort the
                    // model run merely because this eager persistence optimisation failed.
                    log.warn("early title save failed for thread {}: {}", thread.getId(), saveFailure.toString());
                }
                ctx.emit(StreamEvent.of("title_updated",
                    Map.of("threadId", thread.getId(), "title", updatedTitle,
                        "fullTitle", thread.getFullTitle() == null ? updatedTitle : thread.getFullTitle())));
            }
            // Detect /skill explicit invocation: the SlashCommandService expands the skill body
            // into the user message wrapped as `<skill name="...">`. When present, skip the
            // skills summary and plan-mode nudge in the system prompt to save tokens.
            boolean skillDirective = isSkillDirective(thread);
            promptBuilder.ensureSystem(thread, skillDirective);
            if (skillDirective) ctx.getActiveSkills().observeDirective(thread.getMessages());
            chain.before(ctx);

            Set<String> allowedTools = toolsets.resolve(thread.getToolsets());

            // Remove update_goal when not in goal mode — prevents the model from
            // seeing an irrelevant tool definition and wasting a tool call.
            if (!GoalPromptContext.active(thread.getMessages())) {
                allowedTools.remove("update_goal");
            }

            // Sub-agents cannot interact with the user — remove ask_user to prevent
            // the model from attempting user interaction that would deadlock or be ignored.
            if (thread.getSubagentDepth() > 0) {
                allowedTools.remove("ask_user");
            }

            ToolContext toolCtx = new ToolContext(thread, null, ctx::emit, budget);
            toolCtx.setAgentContext(ctx);
            toolCtx.setOptions(options);
            toolCtx.setRunId(runId);
            String interactionRoot = thread.getInteractionRootThreadId();
            toolCtx.setRootThreadId(interactionRoot != null && !interactionRoot.isBlank() ? interactionRoot
                : inheritedSnapshotContext == null ? thread.getId() : inheritedSnapshotContext.rootThreadId);
            toolCtx.setAgentId(thread.getSubagentDepth() == 0 ? "root" : "subagent_" + thread.getId());
            toolCtx.setSubagentThreadId(thread.getSubagentDepth() == 0 ? null : thread.getId());

            // Resolve the effective per-agent work-iteration cap for THIS loop. Child-thread overrides remain
            // authoritative. Only a top-level interactive chat receives the additional shorter ceiling; autonomous
            // Goal loops and sub-agents keep their independent general/sub-agent budgets.
            final int rawCap = options.limits().maxWorkIterations();
            final boolean unlimited = rawCap <= 0;
            final int finalizationGrace = unlimited ? 0 : options.limits().finalizationGraceIterations();
            final int loopBound = unlimited ? Integer.MAX_VALUE
                : (int)Math.min(Integer.MAX_VALUE, (long)rawCap + finalizationGrace);
            // The thread stores the user's preference. These run-local values store the endpoint/model that actually
            // served this top-level run. Once fallback takes over, later tool iterations stay there; recovery probing
            // belongs to the next user run and cannot change model behaviour halfway through one task.
            AtomicReference<String> runProvider = new AtomicReference<>(blankToNull(thread.getProvider()));
            AtomicReference<String> runModel = new AtomicReference<>(blankToNull(thread.getModel()));
            ctx.setLastProvider(runProvider.get() != null
                ? runProvider.get() : router.defaultProviderName().orElse(null));
            ctx.setLastModel(runModel.get());
            ctx.setContextRequestProjection(() -> {
                List<ChatMessage> projected = ConversationContinuity.previewRequestMessages(ctx, ctx.getIteration());
                if (agentImages != null) projected = agentImages.withImageReferences(projected);
                List<Map<String, Object>> definitions = ctx.getRequestToolDefinitions();
                if (agentImages != null && !definitions.isEmpty()) {
                    definitions = agentImages.inspectionToolsForTarget(definitions, ctx.getLastProvider(), ctx.getLastModel());
                }
                return ModelProvider.ChatRequest.builder().messages(projected).tools(definitions).build();
            });

            for (int iter = 0; iter < loopBound; iter++) {
                final boolean capFinalization = !unlimited && iter >= rawCap;
                RunControlSignal.Decision beforeModel = ctx.controlDecision();
                if (beforeModel == RunControlSignal.Decision.RUN_CANCEL) break;
                if (beforeModel == RunControlSignal.Decision.PAUSE
                    || beforeModel == RunControlSignal.Decision.STEP_CANCEL) {
                    acknowledgeControlCheckpoint(ctx, beforeModel, "MODEL");
                    break;
                }
                if (ctx.isCancelled() || Thread.currentThread().isInterrupted()) {
                    // Honour both cooperative cancellation (ctx.cancel) and a hard interrupt of the
                    // worker thread (e.g. SubAgentExecutor timing out and cancelling the Future).
                    // Without the interrupt check, a sub-agent loop kept firing tool calls in the
                    // background after the parent had already returned with a timeout marker.
                    if (Thread.currentThread().isInterrupted() && !ctx.isCancelled()) {
                        ctx.cancel();
                    }
                    log.warn("Agent loop cancelled (cancelled={}, interrupted={}) at iteration {}", ctx.isCancelled(),
                        Thread.currentThread().isInterrupted(), iter);
                    break;
                }
                if (thread.getSubagentDepth() > 0 && runInputs.hasPendingSteering(runId)) {
                    ctx.setStopReason(StepResult.Reason.SUPERSEDED);
                    ctx.emit(StreamEvent.of("subagent_interrupted", Map.of("reason", "parent_steering",
                        "phase", "BEFORE_MODEL", "iteration", iter)));
                    break;
                }
                if (thread.getSubagentDepth() == 0) {
                    RunInputCheckpoint.ApplyResult steering = runInputs.applySteering(runId, thread,
                        "BEFORE_MODEL:" + iter, ctx::emit, () -> !ctx.isCancelled()
                            && ctx.controlDecision() != RunControlSignal.Decision.RUN_CANCEL);
                    if (steering.applied()) {
                        ctx.clearRequiredInput();
                        noProgressGuard.steeringApplied();
                        noProgressCheckpoint = false;
                        runtimeCheckpoint = false;
                        noProgressCheckpointAttempts = 0;
                        noProgressFinalization = false;
                        noProgressFinalizationAttempts = 0;
                        compressionFinalization = false;
                    }
                }
                // The shared budget gates two family-wide resources: token/cost (checked here) and
                // the total number of sub-agent runs (checked in SubAgentExecutor at delegation
                // time, not here). A delegating root agent observes the cost of every descendant via
                // the shared budget, and a cost-capped deployment stops the whole family here.
                // Iterations are bounded per-agent by loopBound above, not family-wide. Record this
                // iteration for observability.
                if (!budget.checkLimits()) {
                    String reason =
                        budget.getExhaustionReason() == null ? "shared budget exhausted" : budget.getExhaustionReason();
                    log.warn("Shared sub-agent budget exhausted at iteration {} (depth={}): {}", iter,
                        thread.getSubagentDepth(), reason);
                    ctx.emit(StreamEvent.of("error", Map.of("reason", "budget_exhausted", "detail", reason,
                        "iterationsUsed", budget.getIterationsUsed(), "tokensUsed", budget.getTokensUsed())));
                    break;
                }
                budget.recordIteration();
                AgentRuntimeBudget runtime = ctx.getRuntimeBudget();
                final boolean runtimeFinalization = thread.getSubagentDepth() > 0
                    && runtime != null && runtime.shouldFinalize();
                if (!noProgressCheckpoint && runtime != null && runtime.checkpointDue()) {
                    noProgressCheckpoint = true;
                    runtimeCheckpoint = true;
                }
                boolean checkpointTurn = noProgressCheckpoint && !capFinalization && !noProgressFinalization
                    && !runtimeFinalization;
                boolean finalizationTurn = capFinalization || checkpointTurn || noProgressFinalization
                    || runtimeFinalization;
                ctx.setIteration(iter);

                // Approaching the work cap: inject a one-shot system_reminder so the model knows
                // it has only N tool-capable iterations left and should wrap up / delegate. Once
                // the work cap is reached we switch to a no-tool finalization grace instead of
                // cutting the run off immediately.
                if (!unlimited && !finalizationTurn) {
                    // Local per-agent remaining: this agent's own cap minus the iterations it has
                    // run. Sub-agents have their own independent cap, so this is purely local.
                    int remaining = rawCap - iter;
                    if ((remaining == 3 || remaining == 1) && firedIterationWarnings.add(remaining)) {
                        String action = remaining <= 1 ? " Please summarise progress and finalise your answer now."
                            : " Please summarise progress and finalise your answer, or delegate a well-scoped"
                                + " subtask only if enough iterations remain for it to inspect and return.";
                        String reminder = "[system_reminder] " + remaining + " iteration" + (remaining == 1 ? "" : "s")
                            + " remaining before max_iterations cap." + action;
                        ctx.queueRequestReminder("max-iterations-warning:" + remaining, reminder);
                        ctx.emit(
                            StreamEvent.of("notice", Map.of("kind", "max_iterations_warning", "remaining", remaining)));
                    }
                }
                if (checkpointTurn && firedFinalizationWarnings.add(iter)) {
                    String reminder = (runtimeCheckpoint
                        ? "[system_reminder] execution time checkpoint reached. Do not call tools in this turn. "
                        : "[system_reminder] recurring tool work detected. Do not call tools in this turn. ")
                        + "Write a concise progress checkpoint with: current objective, checked files, key findings, "
                        + "completed work and unresolved issues. "
                        + (runtimeCheckpoint
                            ? "If the completion criteria are satisfied, deliver the final result with "
                                + "SUBTASK_STATUS: COMPLETE. Otherwise record the next concrete action; tools resume "
                                + "after the checkpoint and productive work may continue within the hard limit."
                            : "Record one materially different next action. This is not the final answer; "
                                + "tool access resumes after the checkpoint.");
                    ctx.queueRequestReminder("no-progress-checkpoint:" + iter, reminder);
                    ctx.emit(StreamEvent.of("notice", Map.of("kind", runtimeCheckpoint
                        ? "runtime_checkpointing" : "no_progress_checkpointing",
                        "policy", "checkpoint_then_resume")));
                } else if (runtimeFinalization && firedFinalizationWarnings.add(iter)) {
                    ctx.queueRequestReminder("runtime-finalization:" + iter,
                        "Execution time is nearly exhausted. Do not call tools. Return a concise handoff with "
                            + "completed work, evidence, remaining work and the next action. If the completion "
                            + "criteria are satisfied, report COMPLETE; otherwise a sub-agent must end with "
                            + "SUBTASK_STATUS: BUDGET_EXHAUSTED. Time exhaustion is not an external blocker.");
                    ctx.emit(StreamEvent.of("notice", Map.of("kind", "runtime_finalizing",
                        "remainingMs", runtime.remainingMs())));
                } else if (noProgressFinalization && firedFinalizationWarnings.add(iter)) {
                    String reason = "similar tool work recurred after a progress checkpoint";
                    String reminder = "[system_reminder] no-progress protection activated: " + reason + ". "
                        + "Do not call tools. Produce the best continuation summary now, preserving: current objective, "
                        + "checked files, key findings and decisions, completed work, unresolved issues, and concrete "
                        + "next steps. Clearly mark anything not verified or incomplete.";
                    ctx.queueRequestReminder("no-progress-finalization:" + iter, reminder);
                    ctx.emit(StreamEvent.of("notice", Map.of("kind", "no_progress_finalizing", "reason", reason)));
                    // Preserve the established cap event contract when both guards become true on the same turn.
                    if (capFinalization) {
                        ctx.emit(StreamEvent.of("notice", Map.of("kind", "max_iterations_finalizing",
                            "graceRemaining", loopBound - iter)));
                    }
                } else if (capFinalization && firedFinalizationWarnings.add(iter)) {
                    int graceRemaining = loopBound - iter;
                    String reminder = "[system_reminder] max_iterations work cap reached. Do not call tools. "
                        + "Based only on the existing conversation and tool results, provide the best current "
                        + "answer now. Include what was completed, what remains uncertain or incomplete, and "
                        + "recommended next steps. Finalization grace iterations remaining: " + graceRemaining + ".";
                    ctx.queueRequestReminder("max-iterations-finalization:" + iter, reminder);
                    ctx.emit(StreamEvent.of("notice",
                        Map.of("kind", "max_iterations_finalizing", "graceRemaining", graceRemaining)));
                }

                // Check the complete pending request, including the current input, fixed rules, reminders and tools.
                // This also gives text-only conversations the soft door, without summarising after an idle reply.
                ctx.setRequestToolDefinitions(finalizationTurn ? List.of()
                    : toolRegistry.definitionsFor(allowedTools).stream().map(ToolDefinition::toApi).toList());
                if (historyContext != null) historyContext.refreshRequestState(ctx, runId);
                boolean compacted = summariser != null && summariser.beforeRequest(ctx);
                if (historyContext != null) historyContext.recall(ctx, runId);
                if (summariser != null) compacted |= summariser.enforceHardCeiling(ctx);
                if (compacted) ctx.emit(StreamEvent.of("notice", Map.of("kind", "compressed", "reason", "request_watermark")));
                // Evaluate compaction pressure only after checking the complete tool-capable request. A prior
                // handoff can become obsolete after truncation, a model switch, or a smaller tool/reminder payload.
                compressionFinalization = summariser != null
                    && summariser.consumeFinalizationRequest(ctx, compressionFinalization);
                if (compressionFinalization) {
                    if (!finalizationTurn) {
                        checkpointTurn = false;
                        finalizationTurn = true;
                        ctx.setRequestToolDefinitions(List.of());
                        ctx.queueRequestReminder("compression-finalization:" + iter,
                            "History compaction could not create safe working headroom. Do not call tools. "
                                + "Return a concise continuation summary with the current objective, completed work, "
                                + "evidence and unresolved next steps. Do not claim unfinished work is complete.");
                    }
                }
                ModelProvider.ChatRequest projectedRequest = ctx.getContextRequestProjection().get();
                List<ChatMessage> requestMessages = projectedRequest.getMessages();
                ctx.drainRequestReminders();
                if (!budget.tryAcquireModelCall()) {
                    ctx.emit(StreamEvent.of("error", Map.of("reason", "budget_exhausted",
                        "detail", budget.getExhaustionReason())));
                    break;
                }
                List<Map<String, Object>> toolApiDefs = ctx.getRequestToolDefinitions();
                AtomicReference<ModelProvider.ChatRequest> preparedRequest = new AtomicReference<>();
                ModelProvider.ChatRequest req = ModelProvider.ChatRequest.builder().model(runModel.get())
                    .provider(runProvider.get())
                    .reasoningEffort(thread.getReasoningEffort())
                    .messages(requestMessages)
                    .targetPreparation((provider,model,call) -> {
                        // Overflow recovery must compact for the actual target, including a smaller fallback model.
                        ctx.setLastProvider(provider);
                        ctx.setLastModel(model);
                        if (agentImages != null && !toolApiDefs.isEmpty()) call.setTools(agentImages.inspectionToolsForTarget(toolApiDefs, provider, model));
                        preparedRequest.set(call);
                        if (contextAdapter != null) contextAdapter.record(runId,provider,model,call);
                    })
                    .tools(projectedRequest.getTools().isEmpty() ? null : projectedRequest.getTools())
                    .webSearchAllowed(!finalizationTurn && allowedTools.contains("web_search"))
                    // Kimi/xAI recommend a stable session/conversation id for sticky cache routing. Providers hash
                    // this scope before sending it, so the upstream never receives the raw Harnex thread id.
                    .promptCacheScope(thread.getId())
                    // Stable per-LLM-call key: same (thread, iteration, msg-count) tuple ALWAYS
                    // identifies the same logical call, so an in-flight POST whose response was
                    // lost (network drop / proxy timeout) and gets re-issued by the failover
                    // layer is recognised by upstream providers as a retry instead of a fresh
                    // generation — preventing duplicate billing and duplicate tool execution.
                    .idempotencyKey(thread.getId() == null ? null
                        : thread.getId() + "-i" + iter + "-m" + thread.getMessages().size())
                    .build();

                AtomicReference<String> assistantText = new AtomicReference<>("");
                AtomicReference<String> assistantReasoning = new AtomicReference<>("");
                // Opaque reasoning-continuation captured from message_end (Anthropic thinking signature /
                // Responses encrypted reasoning items) so the standalone reasoning-block message can carry it
                // for verbatim next-turn replay. Null unless the provider emitted one.
                AtomicReference<String> assistantReasoningProvider = new AtomicReference<>();
                AtomicReference<String> assistantReasoningToken = new AtomicReference<>();
                // Complete provider-native output items for faithful stateless history replay (currently Responses).
                // This is separate from the reasoning token because it also carries messages, calls, phase and caller.
                AtomicReference<String> assistantResponseOutputProvider = new AtomicReference<>();
                AtomicReference<String> assistantResponseOutputItems = new AtomicReference<>();
                AtomicReference<List<ToolCall>> toolCalls = new AtomicReference<>(List.of());
                // Track streamed-but-not-yet-finalised text so a mid-stream provider failure
                // still persists what the user already saw.
                StringBuilder streamedSoFar = new StringBuilder();
                StringBuilder reasoningSoFar = new StringBuilder();
                // Mutable flag because it is captured by the provider-stream lambda; tracks whether
                // a thinking_start boundary has already been emitted for the current assistant message.
                AtomicBoolean reasoningBlockOpen = new AtomicBoolean(false);
                boolean messageEnded = false;
                // Latched when the provider emits message_end with {interrupted:true} — see
                // OpenAiCompatibleProvider.flushAccumulators. Providers signal "I tore the stream
                // down but here's the validated partial payload" so AgentLoop's catch block can
                // PREFER the provider's accumulated content + toolCalls over the local text
                // accumulator (which carries text only). Without this flag the catch block falls
                // back to {@code streamedSoFar + "[stream interrupted]"}, silently dropping the
                // toolCalls the provider already validated and which the SSE consumer ALREADY
                // observed in the message_end frame — producing a server-side history that
                // diverges from what the user saw on screen.
                AtomicReference<Boolean> interrupted = new AtomicReference<>(Boolean.FALSE);
                // Seed audit attribution from the run's selected/default provider. A real fallback transition below
                // replaces it; successful first-attempt streams need no switch event.
                ctx.setLastProvider(runProvider.get() != null
                    ? runProvider.get() : router.defaultProviderName().orElse(null));

                try {
                    if (!ctx.isCancelled()) ConversationContinuity.commitPreparedRequest(ctx, req.getMessages());
                    router.streamChat(req, ev -> {
                        switch (ev.getType()) {
                            case "text_delta" -> {
                                Object t = ev.getData().get("text");
                                if (t != null) {
                                    streamedSoFar.append(t);
                                }
                                ctx.emit(ev);
                            }
                            case "message_start" -> {
                                // Capture the provider/model that actually accepted this stream so audit attribution
                                // stays correct even when no provider_switched event occurs.
                                Object provider = ev.getData().get("provider");
                                if (provider instanceof String s && !s.isBlank()) {
                                    ctx.setLastProvider(s);
                                    runProvider.set(s);
                                }
                                Object model = ev.getData().get("model");
                                if (model instanceof String s && !s.isBlank()) {
                                    runModel.set(s);
                                    ctx.setLastModel(s);
                                }
                                ctx.emit(ev);
                            }
                            case "provider_switched" -> {
                                // Capture the post-fallback target so auditLog.recordAssistant attributes the message
                                // to the endpoint that actually served it.
                                Object to = ev.getData().get("to");
                                if (to instanceof String s && !s.isBlank()) {
                                    ctx.setLastProvider(s);
                                    runProvider.set(s);
                                }
                                Object actualModel = ev.getData().get("actualModel");
                                if (actualModel instanceof String s && !s.isBlank()) {
                                    runModel.set(s);
                                    ctx.setLastModel(s);
                                }
                                // Reset per-iteration accumulators. The router only fails over when
                                // no user-visible text/thinking has been emitted (see
                                // {@link ProviderRouter.guardedEmit}), but the failed provider may
                                // still have emitted a message_end carrying validated toolCalls or
                                // an `interrupted=true` flag (think: provider produced tool_calls
                                // then died before any text_delta). If we leave those values
                                // latched, a subsequent non-transient failure on the NEW provider
                                // would persist an assistant message that mixes:
                                // - the previous provider's tool_calls
                                // - the new provider's stream-interrupted marker
                                // and on the NEXT iteration the model would see orphan
                                // assistant.tool_calls with no matching tool result \u2014 which
                                // OpenAI/Anthropic reject outright ("tool_calls must be followed
                                // by tool messages"). Clear everything so the new provider starts
                                // from a clean slate, matching the semantics the user saw.
                                assistantText.set("");
                                assistantReasoning.set("");
                                // Clear reasoning-continuation metadata too, else a failed provider's
                                // token/provider tag leaks onto the NEXT provider's reasoning block
                                // (e.g. openai-responses token mis-tagged onto an openai-compatible turn).
                                assistantReasoningProvider.set(null);
                                assistantReasoningToken.set(null);
                                assistantResponseOutputProvider.set(null);
                                assistantResponseOutputItems.set(null);
                                toolCalls.set(List.of());
                                interrupted.set(Boolean.FALSE);
                                streamedSoFar.setLength(0);
                                reasoningSoFar.setLength(0);
                                reasoningBlockOpen.set(false);
                                ctx.emit(ev);
                            }
                            case "thinking_delta" -> {
                                Object t = ev.getData().get("text");
                                if (t != null) {
                                    reasoningSoFar.append(t);
                                    if (!reasoningBlockOpen.getAndSet(true)) {
                                        ctx.emit(StreamEvent.of("thinking_start", Map.of()));
                                    }
                                }
                                ctx.emit(ev);
                            }
                            case "message_end" -> {
                                Object intr = ev.getData().get("interrupted");
                                boolean eventInterrupted = intr instanceof Boolean b && b;
                                if (eventInterrupted) {
                                    interrupted.set(Boolean.TRUE);
                                }
                                String finalContent = String.valueOf(ev.getData().getOrDefault("content", ""));
                                // An interrupted terminal payload is not newly authorised visible output. Retain only
                                // content already delivered through text_delta; otherwise a strict-terminal provider
                                // could leak a draft while Router still considers the attempt safe to fail over.
                                assistantText.set(eventInterrupted ? streamedSoFar.toString() : finalContent);

                                // Process reasoning first to determine if we need to emit it
                                Object reasoning = ev.getData().get("reasoning");
                                String reasoningStr = null;
                                if (!eventInterrupted && reasoning instanceof String s && !s.isBlank()) {
                                    assistantReasoning.set(s);
                                    reasoningStr = s;
                                } else if (reasoningSoFar.length() > 0) {
                                    assistantReasoning.set(reasoningSoFar.toString());
                                    reasoningStr = reasoningSoFar.toString();
                                }

                                // Opaque reasoning-continuation token (Anthropic signature / Responses encrypted
                                // reasoning items). Persisted on the reasoning block below for next-turn replay.
                                if (!eventInterrupted) {
                                    Object rProv = ev.getData().get("reasoningProvider");
                                    if (rProv instanceof String rp && !rp.isBlank()) {
                                        assistantReasoningProvider.set(rp);
                                    }
                                    Object rTok = ev.getData().get("reasoningToken");
                                    if (rTok instanceof String rt && !rt.isBlank()) {
                                        assistantReasoningToken.set(rt);
                                    }
                                    Object outputProvider = ev.getData().get("responseOutputProvider");
                                    if (outputProvider instanceof String op && !op.isBlank()) {
                                        assistantResponseOutputProvider.set(op);
                                    }
                                    Object outputItems = ev.getData().get("responseOutputItems");
                                    if (outputItems instanceof String oi && !oi.isBlank()) {
                                        assistantResponseOutputItems.set(oi);
                                    }
                                }

                                // Close the active reasoning block so the UI can render it as a standalone node.
                                if (reasoningStr != null && !reasoningStr.isEmpty()) {
                                    ctx.emit(StreamEvent.of("thinking_end", Map.of("text", reasoningStr)));
                                    reasoningBlockOpen.set(false);
                                }

                                // Defensive: ensure frontend always receives a signal when there's visible content
                                // or a tool-only assistant turn. Reasoning-only turns are already surfaced via
                                // thinking_end above, so don't synthesize a text_delta for them.
                                if (!eventInterrupted && streamedSoFar.length() == 0) {
                                    if (!finalContent.isEmpty()) {
                                        // Normal case: model produced text content
                                        ctx.emit(StreamEvent.of("text_delta", Map.of("text", finalContent)));
                                        streamedSoFar.append(finalContent);
                                    } else {
                                        // Neither content nor reasoning, but check if there are toolCalls
                                        Object tc = ev.getData().get("toolCalls");
                                        if (tc instanceof List<?> l && !l.isEmpty()) {
                                            // Tool-only turn: emit empty text_delta as a signal that the
                                            // assistant DID respond (by calling tools), even if no text.
                                            // This prevents the "ends on tool message" condition that
                                            // would otherwise trigger unnecessary defensive loadThread.
                                            ctx.emit(StreamEvent.of("text_delta", Map.of("text", "")));
                                            streamedSoFar.append("");
                                        }
                                    }
                                }

                                Object tc = ev.getData().get("toolCalls");
                                if (tc instanceof List<?> l) {
                                    @SuppressWarnings("unchecked")
                                    List<ToolCall> cs = (List<ToolCall>)l;
                                    toolCalls.set(cs);
                                }
                                // Provider tore down the stream on error but still flushed a
                                // best-effort message_end (see OpenAiCompatibleProvider). Latch
                                // the flag so the catch block knows to honour this payload
                                // instead of falling back to streamedSoFar+[interrupted].
                            }
                            case "usage" -> {
                                // Accumulate provider-reported token usage into the SHARED budget so a
                                // delegating root agent observes the cost of every descendant
                                // sub-agent. We prefer total_tokens; fall back to prompt+completion when
                                // the provider only emits the split fields. Best-effort — a missing /
                                // malformed usage event must never break the stream.
                                long total = toLong(ev.getData().get("total"));
                                if (total <= 0) {
                                    total = toLong(ev.getData().get("prompt")) + toLong(ev.getData().get("completion"));
                                }
                                if (total > 0) {
                                    budget.addTokens(total);
                                }
                                long prompt = toLong(ev.getData().get("prompt"));
                                if (summariser != null && prompt > 0) {
                                    summariser.recordObservedPromptTokens(ctx, prompt,
                                        preparedRequest.get() == null ? req : preparedRequest.get());
                                }
                                ctx.emit(ev);
                            }
                            default -> ctx.emit(ev);
                        }
                    });
                    messageEnded = true;
                    if (cfg.getSummarizer().isEnabled() && cfg.getSummarizer().isPrefixReuse()) {
                        try {
                            ctx.setLastTaskRequest(com.harnex.provider.SummaryPrefix.snapshot(
                                preparedRequest.get() == null ? req : preparedRequest.get(), ctx.getLastProvider(), ctx.getLastModel()));
                        } catch (RuntimeException unavailable) {
                            ctx.setLastTaskRequest(null);
                            log.debug("Summary prefix snapshot unavailable; bounded summary remains available", unavailable);
                        }
                    }
                } catch (RuntimeException streamErr) {
                    ctx.setLastTaskRequest(null);
                    // B9: detect token-limit / context-overflow and recover by force-compressing then
                    // retrying the SAME iteration. The provider's typed code ("context_length_exceeded")
                    // is the authoritative signal; the string heuristics remain only as a fallback for
                    // upstreams that don't surface the typed code.
                    boolean tokenOverflow = isContextOverflow(streamErr);
                    if (tokenOverflow && summariser != null && streamedSoFar.length() == 0
                        && tokenRecoveryAttempts < MAX_TOKEN_RECOVERY) {
                        log.warn("Token-limit error detected; force-compressing and retrying ({}/{}): {}",
                            tokenRecoveryAttempts + 1, MAX_TOKEN_RECOVERY, streamErr.getMessage());
                        int beforeChars = totalChars(thread);
                        boolean compressed = summariser.forceCompressForOverflow(ctx);
                        int afterChars = totalChars(thread);
                        if (compressed && afterChars < beforeChars) {
                            tokenRecoveryAttempts++;
                            ctx.emit(StreamEvent.of("notice",
                                Map.of("kind", "compressed", "reason", "token_limit_recovery", "beforeChars",
                                    beforeChars, "afterChars", afterChars, "attempt", tokenRecoveryAttempts)));
                            // Re-run the SAME logical iteration with the compressed history. The
                            // for-loop's iter-- replays this step; iterations are bounded per-agent
                            // by loopBound (no shared slot to release).
                            iter--; // retry the same iteration with the compressed history
                            continue;
                        }
                        // Compression no-op: another retry will hit the same error. Surface a typed event
                        // so the UI can render a useful message instead of the raw provider string.
                        log.error("Token-limit unrecoverable: compression did not shrink history ({} -> {} chars)",
                            beforeChars, afterChars);
                        ctx.emit(StreamEvent.of("error",
                            Map.of("reason", "token_limit_unrecoverable", "message",
                                "Context exceeds model window and cannot be compressed further; "
                                    + "start a new thread or remove large attachments.",
                                "providerMessage", String.valueOf(streamErr.getMessage()))));
                        break;
                    }
                    // Persist whatever the user saw, so the saved history matches the UI.
                    log.warn("Provider stream failed mid-message: {}", streamErr.getMessage());
                    // Transient-error recovery: a TCP reset / read timeout / 502 from the provider
                    // before any user-visible content has been emitted is almost always a network
                    // hiccup. Silently retry the SAME iteration once instead of breaking the loop
                    // and surfacing a scary "[stream interrupted]" marker to the user. Only retry
                    // when nothing has been streamed yet (otherwise we'd produce a duplicate prefix).
                    if (isTransientStreamError(streamErr) && streamedSoFar.length() == 0
                        && transientRetryAttempts < MAX_TRANSIENT_RETRY && !ctx.isCancelled()) {
                        transientRetryAttempts++;
                        log.info("Transient stream error — silently retrying iteration {} ({}/{})", iter,
                            transientRetryAttempts, MAX_TRANSIENT_RETRY);
                        // Don't even emit a notice: from the user's perspective nothing visible
                        // happened yet, so a transparent retry is the friendliest thing we can do.
                        // iter-- replays the same logical step; iterations are bounded per-agent by
                        // loopBound (no shared slot to release).
                        iter--;
                        continue;
                    }
                    if (streamedSoFar.length() > 0) {
                        // Cap the embedded error string: some providers serialise huge JSON payloads
                        // into the exception message (we've seen >10 KB), which then become part of
                        // the persisted assistant message AND get re-fed into every subsequent prompt
                        // \u2014 wasting tokens and degrading the model's focus.
                        String em = String.valueOf(streamErr.getMessage());
                        if (em.length() > 256) {
                            em = em.substring(0, 256) + "\u2026(truncated)";
                        }
                        // Prefer the provider's validated visible content when an
                        // interrupted message_end was captured before the throw. AgentLoop
                        // intentionally does NOT forward message_end to ctx.emit on either the
                        // normal or interrupted path — the SSE contract is text_delta + done,
                        // with message_end consumed server-side only to derive assistantText /
                        // payload for persistence. Executable tool calls are deliberately discarded: without a
                        // successful provider terminal state they are not authorised, and persisting them without
                        // matching tool results would create an orphan assistant tool-call turn on the next request.
                        if (interrupted.get()) {
                            String finalContent = assistantText.get() == null || assistantText.get().isEmpty()
                                ? streamedSoFar.toString() : assistantText.get();
                            String finalReasoning = firstNonBlank(assistantReasoning.get(), reasoningSoFar.toString());
                            thread.getMessages()
                                .add(ChatMessage.builder().role("assistant")
                                    .content(finalContent + "\n\n[stream interrupted: " + em + "]")
                                    .reasoning(blankToNull(finalReasoning))
                                    .reasoningProvider(assistantReasoningProvider.get()).build());
                        } else {
                            thread.getMessages()
                                .add(ChatMessage.builder().role("assistant")
                                    .content(streamedSoFar + "\n\n[stream interrupted: " + em + "]")
                                    .reasoning(blankToNull(reasoningSoFar.toString()))
                                    .reasoningProvider(assistantReasoningProvider.get()).build());
                        }
                    } else if (interrupted.get() && !toolCalls.get().isEmpty()) {
                        // Preserve an audit-visible interruption marker, never the uncommitted calls themselves.
                        String em = String.valueOf(streamErr.getMessage());
                        if (em.length() > 256) {
                            em = em.substring(0, 256) + "\u2026(truncated)";
                        }
                        thread.getMessages()
                            .add(ChatMessage.builder().role("assistant").content("[stream interrupted: " + em + "]")
                                .reasoning(
                                    blankToNull(firstNonBlank(assistantReasoning.get(), reasoningSoFar.toString())))
                                .reasoningProvider(assistantReasoningProvider.get()).build());
                    }
                    String errorReason = streamErr instanceof ProviderException providerFailure
                        ? providerFailure.safeToRetry() ? "provider_" + providerFailure.code : "provider_result_unknown"
                        : "agent_error";
                    ctx.emit(StreamEvent.of("error", Map.of("reason", errorReason, "message", String.valueOf(streamErr.getMessage()))));
                    break;
                }
                if (!messageEnded) {
                    break;
                }

                // A StepCancel observed immediately after a complete provider response discards the uncommitted
                // response before any assistant tool-call message reaches history. This is the cleanest safe point:
                // no tool started and therefore no synthetic tool result is required. Pause intentionally continues
                // far enough to commit a completed text response, and, when calls exist, through the whole tool batch.
                RunControlSignal.Decision afterModel = ctx.controlDecision();
                if (afterModel == RunControlSignal.Decision.RUN_CANCEL) {
                    break;
                }
                if (afterModel == RunControlSignal.Decision.STEP_CANCEL) {
                    acknowledgeControlCheckpoint(ctx, afterModel, "MODEL");
                    break;
                }
                // Build assistant messages: first a standalone reasoning block, then the content/tool message.
                String reasoning = firstNonBlank(assistantReasoning.get(), reasoningSoFar.toString());
                String content = firstNonBlank(assistantText.get(), streamedSoFar.toString());
                ChatMessage reasoningBlock = null;
                // Persist the reasoning block when there is visible thinking text OR an opaque
                // reasoning-continuation token to replay next turn. OpenAI Responses can return
                // encrypted reasoning items with an empty/auto summary — no visible text, but a
                // token that MUST be replayed before the next tool call. Gating purely on
                // hasText(reasoning) would silently drop that token and break reasoning continuity.
                if (hasText(reasoning) || hasText(assistantReasoningToken.get())) {
                    reasoningBlock =
                        ChatMessage.builder().role("assistant").content("").reasoning(blankToNull(reasoning))
                            .reasoningBlock(true).reasoningProvider(assistantReasoningProvider.get())
                            .reasoningToken(assistantReasoningToken.get()).build();
                    thread.getMessages().add(reasoningBlock);
                }
                ChatMessage assistant = null;
                if (hasText(content) || !toolCalls.get().isEmpty() || hasText(assistantResponseOutputItems.get())) {
                    assistant = ChatMessage.builder().role("assistant").content(content)
                        .toolCalls(toolCalls.get().isEmpty() ? null : toolCalls.get())
                        .responseOutputProvider(assistantResponseOutputProvider.get())
                        .responseOutputItems(assistantResponseOutputItems.get()).build();
                    thread.getMessages().add(assistant);
                    if (toolCalls.get().isEmpty() && hasText(content)) ctx.setStepAnswer(assistant);
                }
                // Keep an immutable-by-copy view of what the provider actually returned. Middleware receives a
                // separate copy and may suppress or rewrite calls; comparing against this snapshot later is what lets
                // us invalidate opaque reasoning state even when native output metadata was omitted by a size/safety
                // cap.
                List<ToolCall> providerToolCalls = snapshotToolCalls(toolCalls.get());
                ctx.setLastToolCalls(snapshotToolCalls(toolCalls.get()));
                // Forensic audit trail — captured BEFORE chain.afterLlm and tool execution so that
                // any subsequent middleware mutation (summariser rewrite, hook rewrite) cannot rewrite
                // history in the audit copy. Best-effort + async; never blocks the loop.
                String auditProvider = ctx.getLastProvider() != null ? ctx.getLastProvider()
                    : router.defaultProviderName().orElse(null);
                if (reasoningBlock != null) {
                    auditLog.recordAssistant(thread.getId(), iter, thread.getModel(), auditProvider, reasoningBlock);
                }
                if (assistant != null) {
                    auditLog.recordAssistant(thread.getId(), iter, thread.getModel(), auditProvider, assistant);
                }

                chain.afterLlm(ctx);
                // Middleware (e.g. SubagentLimitMiddleware) may have mutated ctx.lastToolCalls
                // — keep the persisted assistant message in lock-step so audit / history /
                // snapshots reflect what was actually executed, and so the next provider call
                // doesn't see orphaned tool_calls referenced by tool_result messages later on.
                List<ToolCall> effectiveCalls = ctx.getLastToolCalls();
                boolean suppressedFinalizationToolCalls = false;
                if (finalizationTurn && effectiveCalls != null && !effectiveCalls.isEmpty()) {
                    log.warn("Suppressing {} tool call(s) returned during no-tool {} turn (thread={}, iter={})",
                        effectiveCalls.size(), checkpointTurn ? "checkpoint" : "finalization", thread.getId(), iter);
                    String suppressionKind = checkpointTurn ? "no_progress_checkpoint_tool_calls_suppressed"
                        : noProgressFinalization ? "no_progress_finalization_tool_calls_suppressed"
                        : compressionFinalization ? "compression_finalization_tool_calls_suppressed"
                        : "max_iterations_tool_calls_suppressed";
                    ctx.emit(StreamEvent.of("notice",
                        Map.of("kind", suppressionKind, "count", effectiveCalls.size())));
                    effectiveCalls = List.of();
                    ctx.setLastToolCalls(new ArrayList<>());
                    suppressedFinalizationToolCalls = true;
                }
                if (assistant != null) {
                    assistant.setToolCalls(
                        effectiveCalls == null || effectiveCalls.isEmpty() ? null : new ArrayList<>(effectiveCalls));
                    // Middleware/finalization may suppress or rewrite calls after the provider response was captured.
                    // Raw replay and opaque reasoning state are valid only while the provider's original function-call
                    // set still matches what we actually execute and persist. The direct snapshot comparison also
                    // covers responses whose raw native array was safely omitted because it exceeded a metadata cap.
                    if (!sameToolCalls(providerToolCalls, effectiveCalls)
                        || !NativeResponseReplay.matchesTools(assistant.getResponseOutputProvider(),
                            assistant.getResponseOutputItems(), effectiveCalls)) {
                        assistant.setResponseOutputProvider(null);
                        assistant.setResponseOutputItems(null);
                        // Opaque reasoning state belongs to the original native call set. It is unsafe to replay next
                        // to middleware-rewritten calls even when the visible reasoning summary remains useful.
                        if (reasoningBlock != null) {
                            reasoningBlock.setReasoningProvider(null);
                            reasoningBlock.setReasoningToken(null);
                            if (!hasText(reasoningBlock.getReasoning())) {
                                thread.getMessages().remove(reasoningBlock);
                            }
                        }
                    }
                }
                // A no-tool checkpoint/finalization can receive reasoning plus function calls but no visible answer.
                // Once those calls are suppressed, the ordinary assistant message becomes structurally empty. Keeping
                // it after the standalone reasoning block creates an invalid provider history on the next request
                // (OpenAI-compatible APIs reject it with "assistant must not be empty"). Remove that shell
                // immediately; the separate reasoning block remains available for providers that can replay it.
                if (assistant != null && !hasText(assistant.getContent())
                    && (assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty())
                    && !hasText(assistant.getResponseOutputItems())) {
                    thread.getMessages().remove(assistant);
                    assistant = null;
                }

                // A control intent can race the post-model checkpoint while middleware is processing the response.
                // Once an assistant tool-call message has been committed, preserve provider history invariants by
                // appending one synthetic result per unstarted call before leaving the loop.
                RunControlSignal.Decision beforeTools = ctx.isCancelled()
                    ? RunControlSignal.Decision.RUN_CANCEL : ctx.controlDecision();
                if (!budget.checkLimits()) {
                    if (effectiveCalls != null && !effectiveCalls.isEmpty()) {
                        appendUnstartedControlResults(ctx, thread, assistant, reasoningBlock, effectiveCalls,
                            RunControlSignal.Decision.RUN_CANCEL);
                        chain.afterTools(ctx);
                    }
                    ctx.emit(StreamEvent.of("error", Map.of("reason", "budget_exhausted",
                        "detail", budget.getExhaustionReason())));
                    break;
                }
                if (beforeTools == RunControlSignal.Decision.RUN_CANCEL
                    || beforeTools == RunControlSignal.Decision.STEP_CANCEL) {
                    if (effectiveCalls != null && !effectiveCalls.isEmpty()) {
                        appendUnstartedControlResults(ctx, thread, assistant, reasoningBlock, effectiveCalls,
                            beforeTools);
                        chain.afterTools(ctx);
                    }
                    if (beforeTools == RunControlSignal.Decision.STEP_CANCEL) {
                        acknowledgeControlCheckpoint(ctx, beforeTools, "BEFORE_TOOLS");
                    }
                    break;
                }
                if (beforeTools == RunControlSignal.Decision.PAUSE
                    && (effectiveCalls == null || effectiveCalls.isEmpty())) {
                    acknowledgeControlCheckpoint(ctx, beforeTools, "BEFORE_TOOLS");
                    break;
                }

                if (thread.getSubagentDepth() > 0 && runInputs.hasPendingSteering(runId)) {
                    if (effectiveCalls != null && !effectiveCalls.isEmpty()) {
                        appendUnstartedSteeringResults(ctx, thread, assistant, reasoningBlock, effectiveCalls);
                        chain.afterTools(ctx);
                    }
                    ctx.setStopReason(StepResult.Reason.SUPERSEDED);
                    ctx.emit(StreamEvent.of("subagent_interrupted", Map.of("reason", "parent_steering",
                        "phase", "BEFORE_TOOLS", "iteration", iter)));
                    break;
                }
                if (thread.getSubagentDepth() == 0) {
                    final List<ToolCall> steeringCalls = effectiveCalls;
                    final ChatMessage steeringAssistant = assistant;
                    final ChatMessage steeringReasoningBlock = reasoningBlock;
                    RunInputCheckpoint.ApplyResult steering = runInputs.applySteering(runId, thread,
                        "BEFORE_TOOLS:" + iter, ctx::emit, () -> !ctx.isCancelled()
                            && ctx.controlDecision() != RunControlSignal.Decision.RUN_CANCEL, () -> {
                                if (steeringCalls != null && !steeringCalls.isEmpty()) {
                                    try {
                                        appendUnstartedSteeringResults(ctx, thread, steeringAssistant,
                                            steeringReasoningBlock, steeringCalls);
                                        chain.afterTools(ctx);
                                    } catch (Exception failure) {
                                        throw new IllegalStateException("failed to supersede pre-steering tool plan",
                                            failure);
                                    }
                                }
                            });
                    if (steering.applied()) {
                        ctx.clearRequiredInput();
                        noProgressGuard.steeringApplied();
                        noProgressCheckpoint = false;
                        runtimeCheckpoint = false;
                        noProgressCheckpointAttempts = 0;
                        noProgressFinalization = false;
                        noProgressFinalizationAttempts = 0;
                        compressionFinalization = false;
                        iter--;
                        continue;
                    }
                }

                if (effectiveCalls == null || effectiveCalls.isEmpty()) {
                    // A progress checkpoint is a visible continuation artifact, not hidden chain-of-thought.
                    // Reasoning-only output must therefore retry rather than silently resuming tool work.
                    if (checkpointTurn && hasText(content)) {
                        if (runtimeCheckpoint && content.strip().matches("(?s).*SUBTASK_STATUS: COMPLETE$")) break;
                        boolean completedRuntimeCheckpoint = runtimeCheckpoint;
                        noProgressCheckpoint = false;
                        noProgressCheckpointAttempts = 0;
                        if (!runtimeCheckpoint) noProgressGuard.checkpointCompleted();
                        runtimeCheckpoint = false;
                        ctx.queueRequestReminder("no-progress-resume:" + iter,
                            "Progress checkpoint recorded. Resume with the next concrete action you "
                                + "identified. Reuse prior findings; do not restart the same read/search cycle unless "
                                + "new evidence requires it.");
                        ctx.emit(StreamEvent.of("notice", Map.of("kind", completedRuntimeCheckpoint
                            ? "runtime_checkpoint_completed" : "no_progress_checkpoint_completed",
                            "policy", "resume_tools")));
                        continue;
                    }
                    if (checkpointTurn) {
                        noProgressCheckpointAttempts++;
                        if (noProgressCheckpointAttempts >= 2) {
                            noProgressCheckpoint = false;
                            noProgressFinalization = true;
                            noProgressFinalizationAttempts = 0;
                            ctx.emit(StreamEvent.of("notice", Map.of("kind", "no_progress_checkpoint_failed",
                                "policy", "finalize_without_tools")));
                        }
                    }
                    if (finalizationTurn && !hasText(content)) {
                        if (runtimeFinalization && ++runtimeFinalizationAttempts >= 2) {
                            ctx.emit(StreamEvent.of("error", Map.of("reason", "runtime_finalization",
                                "message", "Execution time exhausted without a visible handoff")));
                            break;
                        }
                        if (noProgressFinalization) {
                            noProgressFinalizationAttempts++;
                            int allowedAttempts = Math.max(1, finalizationGrace);
                            if (noProgressFinalizationAttempts >= allowedAttempts) {
                                ctx.emit(StreamEvent.of("error", Map.of(
                                    "reason", "no_progress_finalization",
                                    "message", "Model did not produce a visible final answer after recurring tool "
                                        + "work was stopped",
                                    "attempts", noProgressFinalizationAttempts)));
                                break;
                            }
                        }
                        if (iter == loopBound - 1) {
                            emitMaxIterationsExhausted(ctx, thread, rawCap);
                            break;
                        }
                        String retryKind = checkpointTurn
                            ? suppressedFinalizationToolCalls ? "no_progress_checkpoint_retry"
                                : "no_progress_empty_checkpoint_retry"
                            : noProgressFinalization ? "no_progress_finalization_retry"
                            : compressionFinalization ? "compression_finalization_retry"
                            : suppressedFinalizationToolCalls ? "max_iterations_finalization_retry"
                            : "max_iterations_empty_finalization_retry";
                        ctx.emit(StreamEvent.of("notice",
                            Map.of("kind", retryKind)));
                        continue;
                    }
                    // Model is done.
                    if (thread.getId() != null) {
                        todoStore.completeFinalInProgress(thread);
                    }
                    break;
                }

                // Publish admission/queue events. `tool_use_start` is emitted by ParallelToolExecutor only when the
                // call actually enters a worker; emitting it for the whole batch here made serial prerequisites look
                // as if they were running alongside later parallel delegations.
                for (ToolCall c : ctx.getLastToolCalls()) {
                    ctx.emit(StreamEvent.of("tool_use_queued",
                        Map.of("id", c.getId(), "name", c.getName(), "arguments", c.getArguments())));
                }

                // Pre-tool snapshot: conversation state is captured now; file tools attach before-blobs
                // to the same snapshot immediately before each actual write.
                List<ToolCall> snapshotCalls =
                    ctx.getLastToolCalls().stream().filter(this::shouldCreateRestorePointFor).toList();
                long restorePointSeq = -1L;
                SnapshotService.SnapshotCaptureContext snapshotContext = null;
                toolCtx.setSnapshotContext(null);
                if (inheritedSnapshotContext != null && !snapshotCalls.isEmpty()) {
                    snapshotContext = snapshots == null ? null
                        : snapshots.beginSubagentPreTool(inheritedSnapshotContext, thread, iter, snapshotCalls);
                    restorePointSeq = snapshotContext == null ? -1L : snapshotContext.seq;
                    toolCtx.setSnapshotContext(snapshotContext);
                } else if (snapshots != null && !snapshotCalls.isEmpty()) {
                    List<String> summaries = ToolCallSummary.forRestorePoints(snapshotCalls);
                    snapshotContext = snapshots.beginPreTool(thread, runId, iter, snapshotCalls);
                    restorePointSeq = snapshotContext == null ? -1L : snapshotContext.seq;
                    toolCtx.setSnapshotContext(snapshotContext);
                    if (restorePointSeq > 0) {
                        ctx.emit(StreamEvent.of("snapshot",
                            Map.of("seq", restorePointSeq, "kind", "pre_tool", "iter", iter, "tools", summaries,
                                "fileCoverage", "unverified", "fileRestorable", false)));
                    }
                }

                // Tell tools how many iterations THIS loop has left (incl. the current one) so a
                // tool that produces a result needing a follow-up turn — chiefly `delegate` — can
                // refuse when the parent has no turn left to consume it. unlimited → MAX_VALUE.
                toolCtx.setIterationsRemaining(unlimited ? Integer.MAX_VALUE : rawCap - iter);
                toolCtx.setAdvertisedTools(req.localToolNames());
                List<ToolResult> results;
                boolean snapshotFinalized = false;
                try {
                    results = toolExecutor.executeBatch(ctx.getLastToolCalls(), toolCtx, ctx, result -> {
                        Map<String, Object> status = new LinkedHashMap<>();
                        status.put("id", result.getCallId() == null ? "" : result.getCallId());
                        status.put("name", result.getToolName() == null ? "" : result.getToolName());
                        status.put("error", result.isError());
                        status.put("durationMs", result.getDurationMs());
                        // Cancellation intent lives in AgentCtx/RunControlSignal. Once a tool has returned,
                        // clear the wake-up interrupt before publishing its durable result (FileChannel is interruptible).
                        if (ctx.isCancelled()) Thread.interrupted();
                        ctx.emit(StreamEvent.of("tool_use_status", status));
                    });
                    if (ctx.isCancelled()) Thread.interrupted();
                    ctx.setLastToolResults(results);
                    if (snapshots != null && snapshotContext != null && restorePointSeq > 0) {
                        snapshots.finalizeCoverage(snapshotContext.rootThreadId, restorePointSeq, results);
                        snapshotFinalized = true;
                        toolCtx.setSnapshotContext(null);
                    }
                } catch (Exception e) {
                    if (snapshots != null && snapshotContext != null && restorePointSeq > 0 && !snapshotFinalized) {
                        snapshots.finalizeAborted(snapshotContext.rootThreadId, restorePointSeq,
                            "tool_batch_failed:" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    }
                    toolCtx.setSnapshotContext(null);
                    throw e;
                }
                if (snapshots != null && snapshotContext != null && restorePointSeq > 0
                    && shouldDiscardRestorePoint(snapshotCalls, results)) {
                    snapshots.discard(snapshotContext.rootThreadId, restorePointSeq);
                }

                for (ToolResult r : results) {
                    // Apply secret/PII redaction BEFORE the SSE emit AND before appending to
                    // the thread, so neither the WebUI/TUI live frame nor the persisted/next-
                    // turn LLM payload ever sees raw credentials. afterTools below would still
                    // re-scrub the appended message, but that's too late for the SSE frame the
                    // browser already received — the previous order leaked one frame of raw
                    // output before the redactor ran. No-op when both redact flags are off, so
                    // free for the default config.
                    String rawContent = r.getContent() == null ? "" : r.getContent();
                    // When BudgetEnforcer already scrubbed this result (r.isScrubbed()), its content AND
                    // llmContent both derive from a single SecretRedactor pass done BEFORE truncation, so
                    // re-running redactToolContent here is pure wasted work on an idempotent no-op — skip it.
                    // Only budget-BYPASSING results (scrubbed=false: MCP side-channel, executor error stubs)
                    // fall through to the redactor, preserving the bypass safety net. BudgetEnforcer runs
                    // inside toolExecutor.executeBatch (enforcePerTool per tool + enforceTurn on the batch),
                    // which completes BEFORE this loop iterates the SAME ToolResult objects, so the flag is
                    // always settled by the time we read it here.
                    boolean alreadyScrubbed = r.isScrubbed();
                    String safeContent = safeToolView(alreadyScrubbed, rawContent, sensitiveData);
                    // LLM / persisted-thread view: the FULL-or-large llmContent produced by
                    // BudgetEnforcer, falling back to content when no separate LLM version exists
                    // (small result within the per-tool UI cap, or a tool that bypassed the budget
                    // path). Same scrubbed-skip rule as the UI view above.
                    String rawLlmContent = r.getLlmContent() != null ? r.getLlmContent() : rawContent;
                    String safeLlmContent = safeToolView(alreadyScrubbed, rawLlmContent, sensitiveData);
                    // Structured side-channel (file diffs and persisted-result references) travels through
                    // SSE and the durable tool-detail store, never thread.messages, so model input stays lean.
                    // Use a map so we can conditionally include
                    // the optional `metadata` key without nulls in the wire payload.
                    Map<String, Object> endData = new LinkedHashMap<>();
                    endData.put("id", r.getCallId() == null ? "" : r.getCallId());
                    endData.put("name", r.getToolName() == null ? "" : r.getToolName());
                    endData.put("error", r.isError());
                    endData.put("content", safeContent);
                    endData.put("durationMs", r.getDurationMs());
                    if (r.getMetadata() != null && !r.getMetadata().isEmpty() || r.getPersistRef() != null) {
                        // The diff side-channel (FileDiffRenderer) ships the raw unified diff
                        // in metadata.diff.unifiedDiff so the WebUI can render the patch card.
                        // That bypasses the content-redaction above: a write/edit on a file
                        // containing api_key=... would scrub the result content but leak the
                        // secret inside the diff card. Run the same redactor over the diff
                        // body — cheap (only when sensitiveData is wired AND the diff key is
                        // present) and the only structured side-channel we currently emit.
                        Map<String, Object> meta = new LinkedHashMap<>(r.getMetadata() == null ? Map.of() : r.getMetadata());
                        if (r.getPersistRef() != null) {
                            meta.put("resultRef", r.getPersistRef());
                            meta.put("resultThreadId", thread.getId());
                        }
                        if (sensitiveData != null && meta.get("diff") instanceof Map<?, ?> diffMap) {
                            Object ud = diffMap.get("unifiedDiff");
                            if (ud instanceof String uds && !uds.isEmpty()) {
                                String scrubbed = sensitiveData.redactToolContent(uds);
                                if (!scrubbed.equals(uds)) {
                                    Map<String, Object> diffCopy = new LinkedHashMap<>();
                                    for (Map.Entry<?, ?> e : diffMap.entrySet()) {
                                        diffCopy.put(String.valueOf(e.getKey()), e.getValue());
                                    }
                                    diffCopy.put("unifiedDiff", scrubbed);
                                    Map<String, Object> metaCopy = new LinkedHashMap<>(meta);
                                    metaCopy.put("diff", diffCopy);
                                    meta = metaCopy;
                                }
                            }
                        }
                        endData.put("metadata", meta);
                    }
                    ctx.emit(StreamEvent.of("tool_use_end", endData));
                    ChatMessage toolMessage = ChatMessage.tool(r.getCallId(), safeLlmContent, r.isError());
                    thread.getMessages().add(toolMessage);
                    ctx.getActiveSkills().observeToolResult(r, toolMessage, ctx.getLastToolCalls());
                    if (executionSearch != null) {
                        var reference = executionSearch.record(toolCtx.getRootThreadId(),runId,execution.stepId(),iter,r,safeLlmContent);
                        if (reference != null) toolMessage.setContextSources(List.of(reference));
                    }
                    if (r.isRequiresInput()) ctx.needsInput(safeLlmContent);
                }

                if (agentImages != null) agentImages.appendInspections(ctx, results);
                chain.afterTools(ctx);

                // Tools are not interrupted by StepCancel/Pause after they have started: their actual results are
                // first persisted and paired with the assistant calls, then the control is acknowledged here.
                RunControlSignal.Decision afterTools = ctx.controlDecision();
                if (afterTools == RunControlSignal.Decision.RUN_CANCEL) {
                    break;
                }
                if (afterTools == RunControlSignal.Decision.PAUSE
                    || afterTools == RunControlSignal.Decision.STEP_CANCEL) {
                    acknowledgeControlCheckpoint(ctx, afterTools, "AFTER_TOOLS");
                    break;
                }

                if (thread.getSubagentDepth() > 0 && runInputs.hasPendingSteering(runId)) {
                    ctx.setStopReason(StepResult.Reason.SUPERSEDED);
                    ctx.emit(StreamEvent.of("subagent_interrupted", Map.of("reason", "parent_steering",
                        "phase", "AFTER_TOOLS", "iteration", iter)));
                    break;
                }
                if (thread.getSubagentDepth() == 0) {
                    RunInputCheckpoint.ApplyResult steering = runInputs.applySteering(runId, thread,
                        "AFTER_TOOLS:" + iter, ctx::emit, () -> !ctx.isCancelled()
                            && ctx.controlDecision() != RunControlSignal.Decision.RUN_CANCEL);
                    if (steering.applied()) {
                        ctx.clearRequiredInput();
                        noProgressGuard.steeringApplied();
                        noProgressCheckpoint = false;
                        runtimeCheckpoint = false;
                        noProgressCheckpointAttempts = 0;
                        noProgressFinalization = false;
                        noProgressFinalizationAttempts = 0;
                        compressionFinalization = false;
                        continue;
                    }
                }

                if (ctx.getStopReason() == StepResult.Reason.NEEDS_INPUT) break;

                NoProgressGuard.Observation noProgress = noProgressGuard.observe(ctx.getLastToolCalls(), results);
                if (noProgress.action() == NoProgressGuard.Action.CHECKPOINT) {
                    noProgressCheckpoint = true;
                    runtimeCheckpoint = false;
                    noProgressCheckpointAttempts = 0;
                    ctx.emit(StreamEvent.of("notice", Map.of("kind", "no_progress_detected", "repetitions",
                        noProgress.repetitions(), "policy", "checkpoint_then_resume")));
                } else if (noProgress.action() == NoProgressGuard.Action.FINALIZE) {
                    noProgressCheckpoint = false;
                    runtimeCheckpoint = false;
                    noProgressFinalization = true;
                    noProgressFinalizationAttempts = 0;
                    ctx.emit(StreamEvent.of("notice", Map.of("kind", "no_progress_detected", "repetitions",
                        noProgress.repetitions(), "policy", "finalize_after_checkpoint")));
                }

                if (!unlimited && iter == loopBound - 1) {
                    emitMaxIterationsExhausted(ctx, thread, rawCap);
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Agent loop failed", e);
            chain.onError(ctx, e);
            ctx.emit(StreamEvent.of("error", Map.of("message", String.valueOf(e.getMessage()))));
            // Record the failure so future runs benefit from a hint about what tends to break.
            // Best-effort — never let a memory-write swallow the user-visible error frame above
            // and never let it block the `finally` cleanup.
            try {
                String lastTool = "";
                if (ctx.getLastToolCalls() != null && !ctx.getLastToolCalls().isEmpty()) {
                    var tc = ctx.getLastToolCalls().get(ctx.getLastToolCalls().size() - 1);
                    lastTool = tc.getName() == null ? "" : tc.getName();
                }
                // Provider/middleware exceptions occasionally embed API keys or PII inside their
                // message text — scrub before the lesson hits disk and gets re-injected into every
                // future system prompt. No-op when sensitiveData is null or both redact flags are off.
                String lesson = String.format(
                    "agent_loop_error on thread=%s model=%s lastTool=%s exceptionClass=%s message=%s", thread.getId(),
                    thread.getModel() == null ? "" : thread.getModel(), lastTool, e.getClass().getSimpleName(),
                    e.getMessage() == null ? "" : e.getMessage().replaceAll("\\s+", " "));
                if (sensitiveData != null) {
                    lesson = sensitiveData.redactToolContent(lesson);
                }
                coreMemory.appendLesson(lesson);
            } catch (Exception memEx) {
                log.debug("appendLesson(agent_loop_error) failed: {}", memEx.getMessage());
            }
        } finally {
            // Clear the skillDirective marker so it does not leak into subsequent runs on the same thread.
            thread.getState().getExtras().remove("skillDirective");
            // Clear any lingering interrupt set by ctx.cancel() so the pool worker that runs next
            // is not poisoned. Idempotent: noop when no interrupt is pending.
            Thread.interrupted();
            try {
                chain.after(ctx);
            } catch (Exception e) {
                log.warn("chain.after failed: {}", e.getMessage());
            }
            // Final race barrier: a Pause/StepCancel arriving after the last loop checkpoint but before persistence
            // still owns this step. Once acknowledged, close the step so later non-terminal controls cannot reopen a
            // naturally completed Chat run during the narrow save/done/lease-close window.
            if (!ctx.isPauseAcknowledged() && !ctx.isStepCancelAcknowledged()) {
                RunControlSignal.Decision finalControl = ctx.controlDecision();
                if (finalControl == RunControlSignal.Decision.PAUSE
                    || finalControl == RunControlSignal.Decision.STEP_CANCEL) {
                    acknowledgeControlCheckpoint(ctx, finalControl, "CHECKPOINT");
                }
            }
            ctx.closeControlStep();
            // Note: success-path lesson auto-write was removed — low signal-to-noise (a flat
            // "task_completed iters=N firstUser=..." line offered no actionable hint to future runs
            // and consumed prompt budget on every injection). Deliberate cross-thread learnings
            // are now exclusively driven by the `lesson_record` tool (model self-curation) and the
            // failure paths above (max_iterations / agent_loop_error), which carry real diagnostic
            // value. See LESSONS-design notes for rationale and the planned retrieval-based
            // injection follow-up.
            // Persist the step payload before returning. A failure is part of the typed result;
            // the host owns the root status and done frame.
            // Note: the tombstone check that used to live here is now enforced ATOMICALLY inside
            // {@code FtsSessionStore.save} under the same per-thread lock as {@code delete},
            // closing the previous check-then-save race window. We still log a hint when a run
            // ends on a tombstoned thread so operators can correlate "deleted mid-run" with the
            // resulting silent skip.
            try {
                if (executionGuard != null && executionGuard.isTombstoned(thread.getId())) {
                    log.info("Thread {} was tombstoned during run — store.save will be a no-op", thread.getId());
                }
                sessions.save(thread);
                ctx.setResultPersisted(true);
            } catch (Exception e) {
                log.error("sessions.save failed for thread {}: {}", thread.getId(), e.getMessage(), e);
                ctx.emit(StreamEvent.of("error",
                    Map.of("reason", "persist_failed", "message", String.valueOf(e.getMessage()))));
            }
            // The execution host owns root completion after this persisted result returns.
            ctx.complete();
            Thread.interrupted();
        }
        if (sensitiveData != null && ctx.getStopDetail() != null) {
            ctx.setStopDetail(sensitiveData.redactToolContent(ctx.getStopDetail()));
        }
        return ctx.result(inheritedRunId, execution.stepId(), tokensBefore);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void acknowledgeControlCheckpoint(AgentCtx ctx, RunControlSignal.Decision decision, String phase) {
        String control;
        if (decision == RunControlSignal.Decision.PAUSE) {
            ctx.acknowledgePause();
            control = "PAUSE";
        } else if (decision == RunControlSignal.Decision.STEP_CANCEL) {
            ctx.acknowledgeStepCancel();
            control = "STEP_CANCEL";
        } else {
            return;
        }
        ctx.emit(StreamEvent.of("run_control_checkpoint",
            Map.of("control", control, "phase", phase, "acknowledged", true)));
    }

    private void appendUnstartedControlResults(AgentCtx ctx, ConversationThread thread, ChatMessage assistant,
        ChatMessage reasoningBlock, List<ToolCall> calls, RunControlSignal.Decision decision) throws Exception {
        String code = decision == RunControlSignal.Decision.RUN_CANCEL
            ? "cancelled_before_tool_start" : "superseded_by_step_cancel";
        String message = decision == RunControlSignal.Decision.RUN_CANCEL
            ? "Tool call was not started because the run was cancelled."
            : "Tool call was not started because the current step was cancelled.";
        appendUnstartedResults(ctx, thread, assistant, reasoningBlock, calls, code,
            decision == RunControlSignal.Decision.RUN_CANCEL ? "RUN_CANCEL" : "STEP_CANCEL", message);
    }

    private void appendUnstartedSteeringResults(AgentCtx ctx, ConversationThread thread, ChatMessage assistant,
        ChatMessage reasoningBlock, List<ToolCall> calls) throws Exception {
        appendUnstartedResults(ctx, thread, assistant, reasoningBlock, calls, "superseded_by_user_steer", "STEER",
            "Tool call was not started because new user steering superseded the unfinished plan.");
    }

    private void appendUnstartedResults(AgentCtx ctx, ConversationThread thread, ChatMessage assistant,
        ChatMessage reasoningBlock, List<ToolCall> calls, String code, String control, String message) throws Exception {
        List<ToolResult> synthetic = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            String content = M.writeValueAsString(Map.of(
                "error", code,
                "control", control,
                "tool", call.getName() == null ? "" : call.getName(),
                "message", message));
            ToolResult result = ToolResult.builder()
                .callId(call.getId())
                .toolName(call.getName())
                .error(true)
                .content(content)
                .llmContent(content)
                .build();
            synthetic.add(result);
            thread.getMessages().add(ChatMessage.tool(call.getId(), content, true));
        }
        ctx.setLastToolResults(synthetic);
        // The native response array and opaque reasoning continuation describe the provider's original plan to run
        // these calls. They must not be replayed as if that plan were still live after a control superseded it.
        if (assistant != null) {
            assistant.setResponseOutputProvider(null);
            assistant.setResponseOutputItems(null);
        }
        if (reasoningBlock != null) {
            reasoningBlock.setReasoningProvider(null);
            reasoningBlock.setReasoningToken(null);
            if (!hasText(reasoningBlock.getReasoning())) thread.getMessages().remove(reasoningBlock);
        }
        ctx.emit(StreamEvent.of("tool_calls_superseded", Map.of(
            "control", control,
            "reason", code,
            "count", calls.size())));
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private void emitMaxIterationsExhausted(AgentCtx ctx, ConversationThread thread, int rawCap) {
        ctx.emit(StreamEvent.of("error", Map.of("reason", "max_iterations")));
        // Record the failure to LESSONS.md so future runs see a hint about which
        // tool sequences tend to exhaust the iteration budget. Best-effort — never
        // let a memory write swallow the user-visible error frame above.
        try {
            String lastTool = "";
            if (ctx.getLastToolCalls() != null && !ctx.getLastToolCalls().isEmpty()) {
                var tc = ctx.getLastToolCalls().get(ctx.getLastToolCalls().size() - 1);
                lastTool = tc.getName() == null ? "" : tc.getName();
            }
            // Redact secrets/PII before persisting: lastTool name is safe but we keep the
            // pipe consistent with the other appendLesson site below where the exception
            // message can carry leaked credentials from a misbehaving provider response.
            String lesson = String.format(
                "max_iterations(%d) hit on thread=%s model=%s lastTool=%s — consider raising the cap, "
                    + "splitting the task, or steering the agent away from this loop.",
                rawCap, thread.getId(), thread.getModel() == null ? "" : thread.getModel(), lastTool);
            if (sensitiveData != null) {
                lesson = sensitiveData.redactToolContent(lesson);
            }
            coreMemory.appendLesson(lesson);
        } catch (Exception memEx) {
            log.debug("appendLesson(max_iterations) failed: {}", memEx.getMessage());
        }
    }

    /**
     * Detect whether the current turn is an explicit {@code /skill} invocation. Delegates to
     * {@link SkillDirectiveHelper} which checks the ThreadState marker first, then falls back to
     * content-based detection for backward compatibility.
     */
    private static boolean isSkillDirective(ConversationThread thread) {
        return SkillDirectiveHelper.isSkillDirective(thread);
    }

    /** Derive the title from the exact human input before compression or directive expansion can obscure it. */
    private String deriveTitle(ConversationThread thread) {
        if (thread.getSubagentDepth() != 0) {
            return null;
        }
        List<ChatMessage> msgs = thread.getMessages();
        ChatMessage trigger = null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessage m = msgs.get(i);
            if ("user".equals(m.getRole())
                && !TitleMiddleware.isInternalUserMessage(m.getContent())) {
                trigger = m;
                break;
            }
        }
        if (trigger == null || trigger.getContent() == null || trigger.getContent().isBlank()) {
            return null;
        }
        // Prefer the originalInput (user's raw slash-command, e.g. "/skill code-debug ...") for
        // title generation; fall back to the expanded content otherwise.
        String content = trigger.getOriginalInput() != null
            ? trigger.getOriginalInput() : trigger.getContent();
        String updatedTitle = null;
        if (thread.getTitle() == null || thread.getTitle().isBlank() || "新会话".equals(thread.getTitle())) {
            String fullTitle = TitleMiddleware.fullTitleFor(content);
            String title = TitleMiddleware.titleFor(content);
            if (!title.isBlank()) {
                thread.setTitle(title);
                thread.setFullTitle(fullTitle);
                updatedTitle = title;
            }
        }
        return updatedTitle;
    }

    private boolean shouldCreateRestorePointFor(ToolCall call) {
        String name = call == null ? "" : String.valueOf(call.getName());
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.isBlank() || lower.startsWith("memory_") || lower.equals("todo_write") || lower.equals("verify")
            || lower.equals("delegate") || lower.equals("moa")) {
            return false;
        }

        Tool tool = toolRegistry.get(name);
        if (tool == null) {
            return false;
        }
        if (ToolCallClassifier.isReadOnly(tool, call)) {
            return false;
        }
        Set<ToolCapability> caps = tool.capabilities();
        return caps.contains(ToolCapability.WRITES_FILESYSTEM) || caps.contains(ToolCapability.WRITES_NETWORK)
            || caps.contains(ToolCapability.SPAWNS_PROCESS) || caps.contains(ToolCapability.DESTRUCTIVE_IRREVERSIBLE);
    }

    /**
     * Resolve the redaction-safe view of a tool-result string for the main loop's SSE emit + thread append.
     *
     * <p>
     * When {@code scrubbed} is true the string already went through {@link com.harnex.safety.SecretRedactor} inside
     * {@link com.harnex.tool.budget.BudgetEnforcer} (which scrubs the FULL content BEFORE splitting it into the UI
     * preview and the LLM version), so re-running the redactor would only re-scan a payload that has no remaining
     * matches — wasted work. We return it unchanged and rely on the flag as proof of a prior real scrub.
     *
     * <p>
     * When {@code scrubbed} is false the result BYPASSED the budget path (e.g. an MCP side-channel result or an
     * executor-level error stub created outside {@code run(...)}); it has NOT been scrubbed yet, so we run
     * {@code sd.redactToolContent} as the PRIMARY redaction pass. A {@code null} middleware means redaction is not
     * wired at all, matching the pre-optimisation fallback (return the raw string). Package-visible for
     * {@code AgentLoopRedactionSkipTest}.
     */
    static String safeToolView(boolean scrubbed, String raw,
        SensitiveDataMiddleware sd) {
        if (scrubbed || sd == null) {
            return raw;
        }
        return sd.redactToolContent(raw);
    }

    private static boolean shouldDiscardRestorePoint(List<ToolCall> snapshotCalls, List<ToolResult> results) {
        if (snapshotCalls == null || snapshotCalls.isEmpty()) {
            return false;
        }
        Map<String, ToolResult> byCallId = new HashMap<>();
        for (ToolResult result : results == null ? List.<ToolResult>of() : results) {
            if (result.getCallId() != null) {
                byCallId.put(result.getCallId(), result);
            }
        }
        for (ToolCall call : snapshotCalls) {
            if (!canDiscardFailedRestorePointFor(call)) {
                return false;
            }
            ToolResult result = byCallId.get(call.getId());
            if (result == null || !result.isError()) {
                return false;
            }
        }
        return true;
    }

    private static boolean canDiscardFailedRestorePointFor(ToolCall call) {
        String name = call == null || call.getName() == null ? "" : call.getName().toLowerCase(Locale.ROOT);
        return name.equals("file_write") || name.equals("file_edit");
    }

    private List<ToolCall> snapshotToolCalls(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        List<ToolCall> snapshot = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            if (call == null) {
                snapshot.add(null);
            } else {
                snapshot.add(ToolCall.builder().id(call.getId()).name(call.getName())
                    .arguments(call.getArguments()).build());
            }
        }
        return snapshot;
    }

    private boolean sameToolCalls(List<ToolCall> left, List<ToolCall> right) {
        List<ToolCall> a = left == null ? List.of() : left;
        List<ToolCall> b = right == null ? List.of() : right;
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            ToolCall x = a.get(i);
            ToolCall y = b.get(i);
            if (x == null || y == null) {
                if (x != y) {
                    return false;
                }
                continue;
            }
            if (!Objects.equals(x.getId(), y.getId()) || !Objects.equals(x.getName(), y.getName())
                || !sameJson(x.getArguments(), y.getArguments())) {
                return false;
            }
        }
        return true;
    }

    private boolean sameJson(String left, String right) {
        String a = left == null || left.isBlank() ? "{}" : left;
        String b = right == null || right.isBlank() ? "{}" : right;
        try {
            return Objects.equals(M.readTree(a), M.readTree(b));
        } catch (Exception ignored) {
            return Objects.equals(a, b);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /** Sum of approximate provider-context character weights \u2014 cheap proxy for total context size. */
    private static int totalChars(ConversationThread thread) {
        int total = 0;
        for (ChatMessage m : thread.getMessages()) {
            total += MessageBudget.approximateChars(m);
        }
        return total;
    }

    /** Defensive coerce-to-long for {@code usage} event fields whose JSON type varies by provider. */
    private static long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * Detect a context-overflow / token-limit error from a provider exception. Prefers the typed
     * {@code ProviderException.code} ({@code "context_length_exceeded"}) when present; falls back to substring matching
     * against well-known phrases used by upstreams that don't emit a typed code. Both paths are intentionally cheap and
     * side-effect free.
     *
     * <p>
     * HTTP 400 alone is NOT enough — many providers fold malformed-body / bad-tool-schema / model-unavailable errors
     * into 400 too, and treating them as overflow would trigger a useless "compress and retry" cycle. We therefore
     * require the message text to also contain a known overflow phrase before accepting a 400.
     */
    private static boolean isContextOverflow(RuntimeException streamErr) {
        String emsg = String.valueOf(streamErr.getMessage()).toLowerCase(Locale.ROOT);
        boolean phraseMatch = emsg.contains("maximum context") || emsg.contains("context length")
            || emsg.contains("context_length_exceeded") || emsg.contains("too many tokens")
            || emsg.contains("prompt is too long") || emsg.contains("reduce the length");
        if (streamErr instanceof ProviderException pe && pe.code != null) {
            String c = pe.code.toLowerCase(Locale.ROOT);
            if (c.contains("context_length_exceeded") || c.contains("context_length")) {
                return true;
            }
            // HTTP 400: only accept as overflow when the body text also matches a known phrase.
            // Stops malformed-body 400s from triggering a doomed compress+retry loop.
            if (c.equals("400")) {
                return phraseMatch;
            }
        }
        return phraseMatch;
    }

    /**
     * Detect a transient stream error that is safe to retry once: TCP reset, brief 5xx, read-timeout, EOF before
     * message_end. Distinct from {@link #isContextOverflow} (which is a permanent client-side error) and from
     * cancellation (which must propagate).
     *
     * <p>
     * The signal sources are:
     * <ul>
     * <li>typed {@code ProviderException.code} of {@code "network" | "timeout" | "5xx"}</li>
     * <li>fallback substring match for upstreams that don't carry a typed code</li>
     * </ul>
     * Cancellation ({@code code="cancelled"}) is explicitly excluded so the loop honours STOP.
     */
    private static boolean isTransientStreamError(RuntimeException streamErr) {
        if (streamErr instanceof ProviderException pe) {
            if (!pe.safeToRetry()) return false;
            String c = pe.code == null ? "" : pe.code.toLowerCase(Locale.ROOT);
            if ("cancelled".equals(c) || "agent_oversize".equals(c) || "parse".equals(c)) {
                return false;
            }
            if ("network".equals(c) || "timeout".equals(c)) {
                return true;
            }
            // 5xx
            if (c.length() == 3 && c.startsWith("5")) {
                return true;
            }
        }
        // Unclassified socket failures carry no proof that the upstream did not accept the request.
        return false;
    }
}
