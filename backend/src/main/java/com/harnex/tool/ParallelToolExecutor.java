package com.harnex.tool;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.observability.HarnexMetrics;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.core.InteractionBroker;
import com.harnex.core.PlanModeService;
import com.harnex.core.StepResult;
import com.harnex.middleware.MiddlewareChain;
import com.harnex.middleware.ToolCallEvent;
import com.harnex.model.StreamEvent;
import com.harnex.model.ToolCall;
import com.harnex.provider.ProviderCallScope;
import com.harnex.run.RunControlSignal;
import com.harnex.tool.approval.ApprovalService;
import com.harnex.tool.budget.BudgetEnforcer;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

/**
 * Parallel tool executor. - Concurrency-safe tools run in parallel (Claude Code style) - Non-concurrency-safe tools run
 * sequentially - Approval & Budget wired in around execution
 */
@Slf4j
@Component
@ProjectRuntime
public class ParallelToolExecutor {

    private static final ObjectMapper M = new ObjectMapper();
    private static final long APPROVAL_TIMEOUT_MS = 120_000;

    private final ToolRegistry registry;
    private final ApprovalService approval;
    private final BudgetEnforcer budget;
    private final MiddlewareChain chain;
    private final ExecutorService pool;
    /**
     * Unbounded cached pool for serially-dispatched tools that PARK on an external human response
     * ({@link Tool#awaitsUserInput()} — ask_user / submit_plan). Keeping them off the bounded {@link #pool} stops a few
     * unanswered prompts from occupying every {@code harnex.tool-parallelism} worker and stalling all other tool calls
     * with "tool pool saturated". Threads are daemon with a short keep-alive, so idle waiters cost nothing.
     */
    private final ExecutorService waiterPool;
    /** Blocking parent-side delegate waits. Kept separate so child agents cannot be starved by their parents. */
    private final ExecutorService orchestrationPool;
    private final ScheduledExecutorService timeoutWatch;
    private final HarnexMetrics metrics;
    private final PlanModeService planMode;
    private final int toolParallelism;
    /**
     * Per-tool-name concurrency semaphores (A6). Lazily created on first dispatch keyed by {@code Tool.maxParallel()}.
     * Tools whose cap is {@link Integer#MAX_VALUE} skip the map entirely and pay no overhead. Tools that change their
     * cap at runtime keep the originally-sized semaphore (caps are read once at registration).
     */
    private final ConcurrentHashMap<String, Semaphore> toolPermits = new ConcurrentHashMap<>();

    private Semaphore perToolSemaphore(String toolName, int cap) {
        return toolPermits.computeIfAbsent(toolName, k -> new Semaphore(cap, true));
    }

    /**
     * Hard upper bound on any per-call timeout. Bounds third-party Tool overrides that return unreasonably large values
     * (or {@link Integer#MAX_VALUE} for "no timeout") so the scheduled-watchdog delay and batch-deadline arithmetic
     * stay finite. 24h is generous enough for DelegateTool's "no inner timeout" case while keeping the math sane.
     */
    static final long MAX_TIMEOUT_MS = TimeUnit.HOURS.toMillis(24);
    static final long MIN_TIMEOUT_MS = 1_000L;

    public ParallelToolExecutor(ToolRegistry registry, ApprovalService approval, BudgetEnforcer budget, AgentConfig cfg,
        HarnexMetrics metrics, MiddlewareChain chain, PlanModeService planMode) {
        this.registry = registry;
        this.approval = approval;
        this.budget = budget;
        this.chain = chain;
        this.metrics = metrics;
        this.planMode = planMode;
        this.toolParallelism = Math.max(2, cfg.getToolParallelism());
        // Bounded queue + AbortPolicy: when interrupt-deaf tools occupy every worker thread,
        // additional dispatches are REJECTED with RejectedExecutionException (caught below
        // and translated into a structured "tool pool saturated" error) instead of silently
        // piling up in an unbounded LinkedBlockingQueue across sessions. Same back-pressure
        // model as SubAgentExecutor.delegatePool — see its class doc for rationale.
        int queueCap = Math.max(toolParallelism, cfg.getToolQueueCapacity());
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(toolParallelism, toolParallelism, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCap), r -> {
                Thread t = new Thread(r, "tool-worker-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
        // Used only for timeout cancellation — must not share with `pool` to avoid starvation.
        this.timeoutWatch = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "tool-timeout-watch");
            t.setDaemon(true);
            return t;
        });
        // Cached, daemon pool for interactive "parks on user input" tools (ask_user / submit_plan). Off the bounded
        // `pool` so long human waits never exhaust compute workers. Bounded indirectly by the per-turn ask cap +
        // single-flight per thread; idle threads reaped after 60s.
        this.waiterPool =
            new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "tool-waiter-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        this.orchestrationPool =
            new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "tool-orchestrator-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
    }

    @PreDestroy
    public void close() {
        pool.shutdownNow();
        waiterPool.shutdownNow();
        orchestrationPool.shutdownNow();
        timeoutWatch.shutdownNow();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                int active = (pool instanceof ThreadPoolExecutor tpe) ? tpe.getActiveCount() : -1;
                log.error("Tool executor pool did not terminate within 5s — {} active worker(s) may be stuck "
                    + "on uninterruptible I/O. Check for tools that swallow InterruptedException.", active);
            }
            if (!timeoutWatch.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("Tool timeout-watch did not terminate within 2s");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public List<ToolResult> executeBatch(List<ToolCall> calls, ToolContext ctx) {
        return executeBatch(calls, ctx, null);
    }

    public List<ToolResult> executeBatch(List<ToolCall> calls, ToolContext ctx, AgentCtx agentCtx) {
        return executeBatch(calls, ctx, agentCtx, ignored -> {});
    }

    /**
     * Execute a turn's tools and report each terminal result as soon as it is harvested. The callback deliberately
     * precedes turn-level output budgeting; callers must use it for status only, never for result content.
     */
    public List<ToolResult> executeBatch(List<ToolCall> calls, ToolContext ctx, AgentCtx agentCtx,
        Consumer<ToolResult> onCompleted) {
        // CRITICAL: tool_result order MUST match tool_call order — both OpenAI and Anthropic
        // reject batches whose results don't pair to call IDs in submission order. So we
        // index every result by its position in the original `calls` list and reassemble
        // at the end. Contiguous concurrency-safe calls run concurrently; every serial call is an ordering barrier.
        // This preserves model-declared prerequisites such as todo_write -> bash -> [delegate, delegate, delegate]
        // while still parallelising the sibling delegation phase.
        final int n = calls.size();
        final ToolResult[] slot = new ToolResult[n];
        final boolean[] completionPublished = new boolean[n];
        final boolean[] parallelCall = new boolean[n];

        for (int i = 0; i < n; i++) {
            ToolCall c = calls.get(i);
            if (stopBeforeDispatch(agentCtx)) {
                slot[i] = errResult(c, "execution stopped before dispatch");
                continue;
            }
            Tool t = registry.get(c.getName());
            if (t == null) {
                slot[i] = errResult(c, "Unknown tool: " + c.getName());
                continue;
            }
            if (t.capabilities().contains(ToolCapability.CONCURRENCY_SAFE)) {
                parallelCall[i] = true;
            }
        }
        for (int i = 0; i < n; i++) {
            publishCompletion(slot, completionPublished, i, onCompleted);
        }

        // Execute the admitted calls as ordered phases. A contiguous parallel group may finish out of order internally,
        // but a serial call and every later group wait for all earlier work. This is the smallest useful dependency
        // contract available without adding a separate DAG schema to tool calls.
        int cursor = 0;
        while (cursor < n) {
            if (slot[cursor] != null) {
                cursor++;
                continue;
            }
            if (stopBeforeDispatch(agentCtx)) {
                for (int index = cursor; index < n; index++) {
                    if (slot[index] == null) {
                        slot[index] = errResult(calls.get(index), "cancelled before dispatch");
                        publishCompletion(slot, completionPublished, index, onCompleted);
                    }
                }
                break;
            }
            if (parallelCall[cursor]) {
                List<Integer> phase = new ArrayList<>();
                while (cursor < n && (slot[cursor] != null || parallelCall[cursor])) {
                    if (slot[cursor] == null) {
                        slot[cursor] = admit(calls.get(cursor), ctx, agentCtx);
                        if (slot[cursor] == null) phase.add(cursor);
                        else publishCompletion(slot, completionPublished, cursor, onCompleted);
                    }
                    cursor++;
                }
                executeParallelPhase(phase, calls, slot, completionPublished, ctx, agentCtx, onCompleted);
            } else {
                slot[cursor] = admit(calls.get(cursor), ctx, agentCtx);
                if (slot[cursor] == null) executeSerialCall(cursor, calls, slot, completionPublished, ctx, agentCtx, onCompleted);
                else publishCompletion(slot, completionPublished, cursor, onCompleted);
                cursor++;
            }
        }

        // Defensive: any remaining null slots (should not happen) become errors so
        // we never emit a malformed tool_result list missing a callId.
        List<ToolResult> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (slot[i] == null) slot[i] = errResult(calls.get(i), "internal: missing tool result");
            publishCompletion(slot, completionPublished, i, onCompleted);
            out.add(slot[i]);
        }
        budget.enforceTurn(out, ctx);
        return out;
    }

    /** Evaluate interactive and mutable gates at the actual ordered dispatch boundary. */
    private ToolResult admit(ToolCall c, ToolContext ctx, AgentCtx agentCtx) {
        if (stopBeforeDispatch(agentCtx)) return errResult(c, "execution stopped before dispatch");
        Tool t = registry.get(c.getName());
        if (t == null) return errResult(c, "Unknown tool: " + c.getName());
        if (ctx.getAdvertisedTools() != null && !ctx.getAdvertisedTools().contains(c.getName())) {
            return errResult(c, "tool_unavailable (retryable=false): tool was not offered for this request. "
                + "Do NOT retry; use only the tools currently provided. Do not claim unperformed verification.");
        }
        if (!t.available()) {
            return errResult(c, "tool_unavailable (retryable=false): tool is disabled or not configured. "
                + "Do NOT retry; explain the limitation when it matters to the answer.");
        }
        ToolResult result;
        // Plan-mode admission: only tools whose capability set includes PLAN_MODE_SAFE may
        // run. Both the read-only research tools and the planning-workflow tools
        // (todo_write, submit_plan) opt in via their capabilities() declaration, so the
        // gate is a single Set membership check with no special cases here.
        String tid = ctx.getThread() == null ? null : ctx.getThread().getId();
        if (planMode != null && planMode.isActive(tid)
            && !t.capabilities().contains(ToolCapability.PLAN_MODE_SAFE)) {
            // Bash read-only commands are allowed in plan-mode (e.g. ls, cat, git log)
            boolean bashReadOnly = "bash".equals(t.getName()) && ToolCallClassifier.isReadOnly(t, c);
            if (!bashReadOnly) {
                result = errResult(c,
                    "Blocked: thread is in plan-mode. Only PLAN_MODE_SAFE tools "
                        + "(read-only research + submit_plan/todo_write control tools) are allowed. Call "
                        + "submit_plan(plan_markdown) once your plan is ready for user approval.");
                return result;
            }
        }
        if (ctx.getOptions() != null && !ctx.getOptions().permissions().allowMutations()
            && !isReadOnlyCall(t, c)) {
            result = errResult(c, "Denied: this task's authority ceiling is read-only");
            return result;
        }
        if (!t.capabilities().contains(ToolCapability.APPROVAL_FREE)) {
            ApprovalService.Decision d = approval.decide(c, ctx);
            if (d == ApprovalService.Decision.DENY) {
                metrics.approvalBlocks.increment();
                String reason = approval.denialReason(c, ctx);
                result = errResult(c, reason == null || reason.isBlank() ? "Denied by approval policy" : reason);
                return result;
            }
            if (d == ApprovalService.Decision.ASK) {
                if (!InteractionBroker.available(ctx)) {
                    if (!isReadOnlyCall(t, c) || approval.requiresExplicitDecision(c, ctx)) {
                        metrics.approvalBlocks.increment();
                        result = ctx.requireInput("Approval required for tool: " + c.getName());
                        result.setCallId(c.getId());
                        result.setToolName(c.getName());
                        return result;
                    }
                } else {
                    String tidForApproval = interactionThreadId(ctx);
                    String callIdForApproval = "approval_" + UUID.randomUUID();
                    boolean outsideWriteApproval = approval.requiresOutsideWriteApproval(c, ctx);
                    long approvalDeadline = System.currentTimeMillis()
                        + InteractionBroker.approvalTimeout(ctx, APPROVAL_TIMEOUT_MS);
                    // Register before notifying clients, including synchronous CLI callbacks. Each prompt has
                    // a fresh identity; a withdrawn or expired request can never authorize a later tool call.
                    approval.registerPending(tidForApproval, callIdForApproval, c.getName(), c.getArguments(),
                        outsideWriteApproval, c.getId(), ctx.getThread() != null && ctx.getThread().getSubagentDepth() > 0
                            ? ctx.getThread().getId() : null);
                    if (ctx.getEmit() != null) {
                        ctx.getEmit().accept(StreamEvent.of("approval_required", Map.of(
                            "callId", callIdForApproval,
                            "sourceCallId", c.getId() == null ? "" : c.getId(),
                            "threadId", tidForApproval == null ? "" : tidForApproval,
                            "outsideWrite", outsideWriteApproval,
                            "expiresAt", approvalDeadline,
                            "toolName", c.getName(),
                            "arguments", c.getArguments() == null ? "" : c.getArguments())));
                    }
                    ApprovalService.Decision userDecision =
                        approval.pollAndWait(tidForApproval, callIdForApproval, c.getName(),
                            Math.max(1L, approvalDeadline - System.currentTimeMillis()),
                            () -> InteractionBroker.stopped(ctx));
                    if (userDecision == ApprovalService.Decision.ASK) {
                        result = ctx.requireInput("Approval timed out for tool: " + c.getName());
                        result.setCallId(c.getId());
                        result.setToolName(c.getName());
                        return result;
                    }
                    if (userDecision != ApprovalService.Decision.ALLOW) {
                        metrics.approvalBlocks.increment();
                        result = errResult(c, "Denied by user (or approval timed out)");
                        return result;
                    }
                    if (outsideWriteApproval) {
                        ctx.approveOutsideWrite(c.getId());
                    }
                }
            }
        }
        return null;
    }

    /** Human interactions from a child agent belong to the visible root thread, not its hidden child transcript. */
    private static String interactionThreadId(ToolContext ctx) {
        if (ctx == null || ctx.getThread() == null) return null;
        if (ctx.getThread().getSubagentDepth() > 0 && ctx.getRootThreadId() != null
            && !ctx.getRootThreadId().isBlank()) {
            return ctx.getRootThreadId();
        }
        return ctx.getThread().getId();
    }

    private static boolean stopBeforeDispatch(AgentCtx agent) {
        return Thread.currentThread().isInterrupted() || agent != null && (agent.isCancelled()
            || agent.hasPendingSteering()
            || agent.controlDecision() != RunControlSignal.Decision.CONTINUE
            || agent.getStopReason() == StepResult.Reason.NEEDS_INPUT);
    }

    @SuppressWarnings("unchecked")
    private void executeParallelPhase(List<Integer> indices, List<ToolCall> calls, ToolResult[] slot,
        boolean[] completionPublished, ToolContext ctx, AgentCtx agentCtx, Consumer<ToolResult> onCompleted) {
        int count = indices.size();
        if (count == 0) return;
        Future<ToolResult>[] futures = new Future[count];
        long[] timeoutMs = new long[count];
        AtomicLongArray startedAtNanos = new AtomicLongArray(count);
        long maxTimeout = 0L;

        for (int phaseIndex = 0; phaseIndex < count; phaseIndex++) {
            int callIndex = indices.get(phaseIndex);
            ToolCall call = calls.get(callIndex);
            int startedIndex = phaseIndex;
            Tool tool = registry.get(call.getName());
            int cap = tool == null ? Integer.MAX_VALUE : Math.max(1, tool.maxParallel());
            Semaphore permit = cap >= Integer.MAX_VALUE ? null : perToolSemaphore(call.getName(), cap);
            try {
                ExecutorService target = tool != null && tool.capabilities().contains(ToolCapability.ORCHESTRATES_AGENT)
                    ? orchestrationPool : pool;
                futures[phaseIndex] = target.submit(ProviderCallScope.capture(() -> {
                    if (permit != null) {
                        try {
                            permit.acquire();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return errResult(call, "interrupted while waiting for per-tool concurrency permit");
                        }
                    }
                    try {
                        startedAtNanos.set(startedIndex, System.nanoTime());
                        return run(call, ctx, agentCtx);
                    } finally {
                        if (permit != null) permit.release();
                    }
                }));
            } catch (RejectedExecutionException rejected) {
                metrics.toolErrors.increment();
                log.warn("tool pool saturated; rejecting {}", call.getName());
                futures[phaseIndex] = CompletableFuture.completedFuture(errResult(call,
                    "tool pool saturated (workers busy on long-running calls). Wait and retry, or raise "
                        + "harnex.tool-queue-capacity."));
                startedAtNanos.set(phaseIndex, System.nanoTime());
            }
            timeoutMs[phaseIndex] = timeoutFor(call, tool);
            maxTimeout = Math.max(maxTimeout, timeoutMs[phaseIndex]);
        }

        int waves = Math.max(1, (count + toolParallelism - 1) / toolParallelism);
        long ceilingMs = Math.min(MAX_TIMEOUT_MS, (long)waves * Math.max(1L, maxTimeout)) + 10_000L;
        long batchDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ceilingMs);
        int remaining = count;
        boolean cancelRequested = false;
        while (remaining > 0) {
            boolean cancelNow = cancelRequested || Thread.currentThread().isInterrupted()
                || (agentCtx != null && agentCtx.isCancelled());
            boolean deadlineHit = System.nanoTime() - batchDeadlineNanos >= 0;
            if (cancelNow || deadlineHit) {
                String reason = cancelNow ? "cancelled mid-batch" : "batch deadline exceeded";
                for (int phaseIndex = 0; phaseIndex < count; phaseIndex++) {
                    int callIndex = indices.get(phaseIndex);
                    if (slot[callIndex] != null) continue;
                    Future<ToolResult> future = futures[phaseIndex];
                    if (future.isDone()) {
                        slot[callIndex] = harvest(future, calls.get(callIndex));
                    } else {
                        boolean cancelled = future.cancel(true);
                        slot[callIndex] = !cancelled && future.isDone()
                            ? harvest(future, calls.get(callIndex))
                            : errResult(calls.get(callIndex), reason, startedAtNanos.get(phaseIndex));
                    }
                    publishCompletion(slot, completionPublished, callIndex, onCompleted);
                    remaining--;
                }
                break;
            }

            boolean progressed = false;
            long now = System.nanoTime();
            for (int phaseIndex = 0; phaseIndex < count; phaseIndex++) {
                int callIndex = indices.get(phaseIndex);
                if (slot[callIndex] != null) continue;
                Future<ToolResult> future = futures[phaseIndex];
                if (future.isDone()) {
                    slot[callIndex] = harvest(future, calls.get(callIndex));
                } else {
                    long startedAt = startedAtNanos.get(phaseIndex);
                    if (startedAt == 0L
                        || now - (startedAt + TimeUnit.MILLISECONDS.toNanos(timeoutMs[phaseIndex] + 5_000L)) < 0) {
                        continue;
                    }
                    boolean cancelled = future.cancel(true);
                    slot[callIndex] = !cancelled && future.isDone()
                        ? harvest(future, calls.get(callIndex))
                        : errResult(calls.get(callIndex), "tool deadline exceeded", startedAt);
                }
                publishCompletion(slot, completionPublished, callIndex, onCompleted);
                remaining--;
                progressed = true;
            }
            if (remaining > 0 && !progressed) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    cancelRequested = true;
                }
            }
        }
    }

    private void executeSerialCall(int index, List<ToolCall> calls, ToolResult[] slot,
        boolean[] completionPublished, ToolContext ctx, AgentCtx agentCtx, Consumer<ToolResult> onCompleted) {
        ToolCall call = calls.get(index);
        Tool tool = registry.get(call.getName());
        ExecutorService target = tool != null && tool.awaitsUserInput() ? waiterPool : pool;
        try {
            AtomicLong startedAt = new AtomicLong();
            Future<ToolResult> future = target.submit(ProviderCallScope.capture(() -> {
                startedAt.set(System.nanoTime());
                return run(call, ctx, agentCtx);
            }));
            slot[index] = awaitWithDeadline(future, call, timeoutFor(call, tool), agentCtx, startedAt);
        } catch (RejectedExecutionException rejected) {
            metrics.toolErrors.increment();
            log.warn("tool pool saturated; rejecting serial {}", call.getName());
            slot[index] = errResult(call, "tool pool saturated (workers busy on long-running calls). Wait and retry, "
                + "or raise harnex.tool-queue-capacity.");
        }
        publishCompletion(slot, completionPublished, index, onCompleted);
    }

    private long timeoutFor(ToolCall call, Tool tool) {
        if (tool == null) return 60_000L;
        Map<String, Object> probe = parseArgsBestEffort(call.getArguments());
        try {
            return clampTimeout(tool.timeoutMillis(probe));
        } catch (RuntimeException failure) {
            return clampTimeout(tool.timeoutMillis());
        }
    }

    private static void publishCompletion(ToolResult[] slot, boolean[] published, int index,
        Consumer<ToolResult> callback) {
        if (slot[index] == null || published[index]) return;
        published[index] = true;
        try {
            callback.accept(slot[index]);
        } catch (RuntimeException failure) {
            log.warn("tool completion callback failed for {}: {}", slot[index].getCallId(), failure.getMessage());
        }
    }

    private ToolResult run(ToolCall call, ToolContext ctx, AgentCtx agentCtx) {
        if (stopBeforeDispatch(agentCtx)) return errResult(call, "execution stopped before dispatch");
        if (ctx.getBudget() != null && !ctx.getBudget().tryAcquireToolCall()) {
            return errResult(call, "[BUDGET_EXHAUSTED] " + ctx.getBudget().getExhaustionReason());
        }
        Tool t = registry.get(call.getName());
        long t0 = System.currentTimeMillis();
        // This is the authoritative transition from queued -> running. It deliberately lives inside the worker entry
        // rather than AgentLoop's batch-admission loop, so a serial prerequisite waiting behind an earlier phase is
        // never shown as executing. The event is best-effort observability and cannot veto tool execution.
        if (ctx.getEmit() != null) {
            try {
                ctx.getEmit().accept(StreamEvent.of("tool_use_start", Map.of(
                    "id", call.getId() == null ? "" : call.getId(),
                    "name", call.getName() == null ? "" : call.getName(),
                    "arguments", call.getArguments() == null ? "" : call.getArguments())));
            } catch (RuntimeException eventFailure) {
                log.debug("tool start event failed for {}: {}", call.getId(), eventFailure.toString());
            }
        }
        Map<String, Object> input;
        try {
            input = call.getArguments() == null || call.getArguments().isBlank() ? new HashMap<>()
                : M.readValue(call.getArguments(), Map.class);
        } catch (Exception e) {
            // Structured, instructive error: tells the model what to fix on the next turn instead
            // of just echoing back the raw Jackson stack message. Including the offending arguments
            // (truncated) makes weak / fine-tuned models far more likely to self-correct rather
            // than re-emit the same broken JSON until max_iterations.
            String args = call.getArguments() == null ? "" : call.getArguments();
            if (args.length() > 200)
                args = args.substring(0, 200) + "\u2026(truncated)";
            return errResult(call,
                "Invalid JSON arguments for tool '" + call.getName() + "': " + e.getMessage()
                    + ". The tool expects a JSON object matching its schema; " + "you sent: " + args
                    + ". Fix the JSON syntax (quote keys with double quotes, escape strings) and retry.");
        }

        // Display metadata belongs to the Agent, never to built-in tools or strict MCP input schemas.
        input.remove(ToolPurpose.ARGUMENT);

        // --- preToolCall hook: middleware can inspect/modify args or veto ---
        ToolCallEvent event = null;
        if (chain != null && agentCtx != null) {
            event = new ToolCallEvent(agentCtx, call);
            event.setParsedArguments(input);
            if (chain.preToolCall(event)) {
                return ToolResult.builder().callId(call.getId()).toolName(call.getName()).error(true)
                    .content("Vetoed by middleware: " + event.getVetoReason())
                    .durationMs(System.currentTimeMillis() - t0).build();
            }
            input = event.getParsedArguments();
        }

        ToolResult res;
        // Use the input-aware overload so a user-passed `timeoutMs` (e.g. on bash) actually
        // governs both the outer watchdog AND the inner process budget. Falls back to the
        // static default for tools that don't override.
        // Defensive clamp: a misbehaving Tool override returning 0 / negative would make
        // ScheduledExecutorService.schedule fire immediately and produce a bogus
        // "Tool timed out after 0ms"; an absurdly large value would push the watchdog delay
        // out beyond any practical bound and make Future.cancel arithmetic explode. The
        // clamp also keeps third-party Tools in the same envelope as the outer batch deadline
        // computed against {@link #clampTimeout}.
        long timeoutMs;
        try {
            timeoutMs = clampTimeout(t.timeoutMillis(input));
        } catch (RuntimeException ex) {
            log.warn("Tool {} timeoutMillis(input) threw — falling back to static default", call.getName(), ex);
            timeoutMs = clampTimeout(t.timeoutMillis());
        }
        final Tool tool = t;
        final ToolContext callContext = ctx.forToolCall(call.getId());
        // Control or manual Plan state can change while queued, waiting on a permit, or inside middleware.
        if (stopBeforeDispatch(agentCtx)) return errResult(call, "execution stopped before tool invocation");
        String tid = ctx.getThread() == null ? null : ctx.getThread().getId();
        if (planMode != null && planMode.isActive(tid)
            && !tool.capabilities().contains(ToolCapability.PLAN_MODE_SAFE)
            && !("bash".equals(tool.getName()) && ToolCallClassifier.isReadOnly(tool, call))) {
            return errResult(call, "Blocked: plan review is required before this tool can run");
        }
        try {
            // CRITICAL: execute the tool *inline* on the current worker thread instead of
            // submitting a nested task to the same `pool`. The previous nested-submit pattern
            // deadlocked when the LLM produced >= poolSize parallel calls — every worker
            // blocked on Future.get() while the inner tasks queued forever. The deadline is
            // enforced by the dedicated `timeoutWatch` scheduler interrupting this thread.
            final Thread worker = Thread.currentThread();
            // Coordination flag between worker and watchdog: the watchdog only fires the
            // interrupt while `done == false`, and the finally block flips `done = true`
            // BEFORE attempting to cancel + observe the killer. This eliminates the prior
            // spin-loop race where a watchdog could fire its interrupt *after* we'd cleared
            // the flag, poisoning the next tool run with a spurious InterruptedException.
            final AtomicBoolean done = new AtomicBoolean(false);
            ScheduledFuture<?> killer = timeoutWatch.schedule(() -> {
                if (done.get()) {
                    return;
                }
                if (!worker.isInterrupted()) {
                    worker.interrupt();
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            boolean timedOut = false;
            try {
                res = tool.execute(input, callContext);
            } catch (Exception e) {
                // Honour cooperative cancellation from the timeout interrupt regardless of
                // whether the tool let the InterruptedException bubble or wrapped it.
                Throwable cause = e.getCause();
                if (e instanceof InterruptedException || cause instanceof InterruptedException
                    || Thread.currentThread().isInterrupted()) {
                    timedOut = true;
                    res = errResult(call, "Tool timed out after " + timeoutMs + "ms");
                } else {
                    log.error("Tool {} failed", call.getName(), e);
                    res = errResult(call, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            } finally {
                // Order matters: flip `done` first so any watchdog firing concurrently with
                // cancel(false) sees the flag and skips the interrupt. Then cancel the killer
                // (best-effort: cannot abort an already-running task, but `done` covers that).
                done.set(true);
                killer.cancel(false);
                // Single deterministic clear of the interrupt flag: by the time we get here,
                // either (a) the watchdog ran before we set `done` and our catch above already
                // observed it, or (b) the watchdog will check `done` and not interrupt at all.
                // No spin loop needed — the flag, if set, is set exactly once for *this* call.
                Thread.interrupted();
            }
            if (timedOut && res == null) {
                res = errResult(call, "Tool timed out after " + timeoutMs + "ms");
            }

            if (res.getCallId() == null) {
                res.setCallId(call.getId());
            }
            if (res.getToolName() == null) {
                res.setToolName(call.getName());
            }
            res.setDurationMs(System.currentTimeMillis() - t0);
            budget.enforcePerTool(res, ctx);
            metrics.toolCalls.increment();
            if (res.isError()) {
                metrics.toolErrors.increment();
            }
            metrics.toolTimer.record(Duration.ofMillis(res.getDurationMs()));
        } catch (Exception e) {
            metrics.toolErrors.increment();
            log.error("Tool {} execution wrapper failed", call.getName(), e);
            res = errResult(call, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // --- postToolCall hook: middleware can inspect/modify result ---
        if (event != null) {
            event.setResult(res);
            chain.postToolCall(event);
            res = event.getResult();
        }

        return res;
    }

    private static ToolResult errResult(ToolCall c, String msg) {
        return ToolResult.builder().callId(c.getId()).toolName(c.getName()).error(true).content(msg).build();
    }

    private static ToolResult errResult(ToolCall c, String msg, long startedAtNanos) {
        ToolResult result = errResult(c, msg);
        if (startedAtNanos != 0L) {
            result.setDurationMs(Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)));
        }
        return result;
    }

    /**
     * Capability-derived read-only check for the task authority ceiling:
     * a call is admitted when its tool declares none of the mutating capabilities. {@code MUTATES_THREAD_STATE} alone
     * does NOT count as mutating — todo/goal/plan bookkeeping is safe. {@code bash} (SPAWNS_PROCESS) gets a
     * command-level exemption via the same read-only shell classifier plan-mode uses, so autonomous read-only runs can
     * still inspect state with {@code ls} / {@code git log} / {@code cat}.
     */
    private static boolean isReadOnlyCall(Tool t, ToolCall c) {
        return ToolCallClassifier.isReadOnly(t, c);
    }

    /** Drain a finished future into a ToolResult, mapping common failure modes to error results. */
    private ToolResult harvest(Future<ToolResult> f, ToolCall call) {
        try {
            return f.get();
        } catch (CancellationException ce) {
            return errResult(call, "cancelled");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return errResult(call, "cancelled (interrupted)");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            log.warn("parallel tool {} failed", call.getName(), cause);
            return errResult(call, "tool failed: " + cause.getMessage());
        }
    }

    /**
     * Best-effort JSON arg parse used ONLY for batch-deadline sizing — the per-tool watchdog inside {@link #run}
     * re-parses authoritatively. Returns an empty map on any parse failure so a malformed payload falls back to the
     * static timeout instead of throwing here.
     */
    private static Map<String, Object> parseArgsBestEffort(String args) {
        if (args == null || args.isBlank())
            return Collections.emptyMap();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = M.readValue(args, Map.class);
            return m;
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    /**
     * Single source of truth for "what is a sane per-tool timeout?". Both the per-future deadline math in the batch
     * loop and the {@code timeoutWatch} schedule call inside {@link #run} go through this so they cannot drift out of
     * agreement when a third-party Tool returns 0 / negative / {@link Integer#MAX_VALUE}.
     */
    static long clampTimeout(long ms) {
        if (ms <= 0)
            return MIN_TIMEOUT_MS;
        if (ms > MAX_TIMEOUT_MS)
            return MAX_TIMEOUT_MS;
        return Math.max(MIN_TIMEOUT_MS, ms);
    }

    /**
     * Wait for a single (serial) future with the same outer-deadline + cancel pattern the parallel batch uses. Honours
     * cooperative cancellation via {@code agentCtx} and the thread interrupt flag, and adds a +5s grace on top of the
     * per-tool budget so the inner watchdog inside {@link #run} fires first and produces the more precise "Tool timed
     * out after Xms" error.
     */
    private ToolResult awaitWithDeadline(Future<ToolResult> f, ToolCall call, long timeoutMs, AgentCtx agentCtx,
        AtomicLong startedAt) {
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs + 5_000L);
        final long pollIntervalMs = 50L;
        while (true) {
            if (Thread.currentThread().isInterrupted() || (agentCtx != null && agentCtx.isCancelled())) {
                boolean cancelled = f.cancel(true);
                if (!cancelled && f.isDone()) {
                    return harvest(f, call);
                }
                return errResult(call, "cancelled mid-batch", startedAt.get());
            }
            if ((System.nanoTime() - deadlineNanos) >= 0) {
                boolean cancelled = f.cancel(true);
                if (!cancelled && f.isDone()) {
                    return harvest(f, call);
                }
                return errResult(call, "tool deadline exceeded", startedAt.get());
            }
            if (f.isDone()) {
                return harvest(f, call);
            }
            try {
                return f.get(pollIntervalMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                // continue polling
            } catch (CancellationException ce) {
                return errResult(call, "cancelled");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                f.cancel(true);
                return errResult(call, "cancelled (interrupted)");
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                log.warn("serial tool {} failed", call.getName(), cause);
                return errResult(call, "tool failed: " + cause.getMessage());
            }
        }
    }
}
