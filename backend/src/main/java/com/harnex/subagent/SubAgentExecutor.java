package com.harnex.subagent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.harnex.config.AgentConfig;
import com.harnex.observability.HarnexMetrics;
import com.harnex.config.ProjectRuntime;
import com.harnex.config.RuntimeConfiguration;
import com.harnex.core.AgentCtx;
import com.harnex.core.AgentExecutionContext;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.core.AgentExecutionPort;
import com.harnex.core.AgentRuntimeBudget;
import com.harnex.core.PermissionContext;
import com.harnex.core.StepLimits;
import com.harnex.core.StepRequest;
import com.harnex.core.StepResult;
import com.harnex.core.SubAgentBudget;
import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.model.StreamEvent;
import com.harnex.run.RunControlSignal;
import com.harnex.session.SessionStore;
import com.harnex.snapshot.SnapshotService;

import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/** Run a headless sub-agent with its own (ephemeral) thread. */
@Slf4j
@Service
@ProjectRuntime
public class SubAgentExecutor {

    private final AgentExecutionPort loop;
    private final SessionStore store;
    private final AgentConfig cfg;
    private final HarnexMetrics metrics;

    /** Separate bounded pools per depth prevent parents waiting on descendants from starving their workers. */
    private final ConcurrentMap<Integer, ThreadPoolExecutor> delegatePools = new ConcurrentHashMap<>();
    private final AtomicBoolean closing = new AtomicBoolean();

    /**
     * Per-parent-thread counter for in-flight sub-agent delegations. Combined with
     * {@code harnex.subagent.max-per-thread}, this prevents a runaway agent from spinning up dozens of concurrent
     * sub-agents under one conversation. Cleaned up lazily on decrement.
     */
    private final ConcurrentMap<String, AtomicInteger> perThreadInFlight = new ConcurrentHashMap<>();

    public SubAgentExecutor(@Lazy AgentExecutionPort loop, SessionStore store, AgentConfig cfg) {
        this(loop, store, cfg, null);
    }

    @Autowired
    public SubAgentExecutor(@Lazy AgentExecutionPort loop, SessionStore store, AgentConfig cfg, HarnexMetrics metrics) {
        this.loop = loop;
        this.store = store;
        this.cfg = cfg;
        this.metrics = metrics;
    }

    private synchronized ThreadPoolExecutor poolFor(int depth) {
        if (closing.get()) throw new RejectedExecutionException("sub-agent executor is shutting down");
        return delegatePools.computeIfAbsent(depth, key -> {
            AtomicInteger seq = new AtomicInteger();
            return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, cfg.getSubagent().getQueueCapacity())), r -> {
                    Thread t = new Thread(r, "subagent-" + key + "-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        });
    }

    @PreDestroy
    synchronized void shutdown() {
        closing.set(true);
        for (ThreadPoolExecutor pool : delegatePools.values()) {
            for (Runnable queued : pool.shutdownNow()) {
                if (queued instanceof Future<?> future) future.cancel(false);
            }
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (ThreadPoolExecutor pool : delegatePools.values()) {
            try {
                if (!pool.awaitTermination(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    log.warn("SubAgentExecutor pool did not terminate within shutdown grace");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public SubAgentResult delegate(DelegateRequest req) {
        int maxDepth = cfg.getSubagent().getMaxDepth();
        if (req.getDepth() < 0 || req.getDepth() >= maxDepth) {
            return new SubAgentResult(null,
                "[error] Sub-agent depth limit reached (" + maxDepth + "). Cannot delegate further.", true);
        }

        // Per-parent-thread fan-out cap. Tracked under the originating conversation id so that
        // ten different conversations CAN each have their own quota in flight, but a single
        // runaway agent cannot spawn unlimited siblings.
        String parentKey = req.getParentThreadId() == null ? "__anonymous__" : req.getParentThreadId();
        int maxPerThread = Math.max(1, cfg.getSubagent().getMaxPerThread());
        AtomicBoolean acquired = new AtomicBoolean(false);
        perThreadInFlight.compute(parentKey, (k, v) -> {
            AtomicInteger counter = v == null ? new AtomicInteger() : v;
            if (counter.get() >= maxPerThread) {
                return counter.get() <= 0 ? null : counter;
            }
            counter.incrementAndGet();
            acquired.set(true);
            return counter;
        });
        if (!acquired.get()) {
            return new SubAgentResult(null, "[error] Per-thread sub-agent fan-out cap reached (" + maxPerThread
                + " concurrent). Wait for an in-flight delegation to finish, or raise harnex.subagent.max-per-thread.",
                true);
        }
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable releasePermit = () -> {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            perThreadInFlight.compute(parentKey, (k, v) -> {
                if (v == null) {
                    return null;
                }
                int now = v.decrementAndGet();
                return now <= 0 ? null : v;
            });
        };

        // Family-wide total-run cap, acquired AFTER fan-out admission so a fan-out / queue rejection
        // does not burn a family run (the doc semantics are "sub-agents actually spawned this turn",
        // not "delegation attempts"). maxPerThread above only bounds CONCURRENT in-flight delegations
        // — the permit is released the moment a sub-agent finishes — so without this counter a parent
        // (and each descendant) could spawn an unbounded number of sub-agents by delegating
        // sequentially across its iterations. The counter lives on the shared parentBudget so it
        // spans the whole family for one user turn; null for direct /api/delegate calls (a single
        // root delegation — nothing to cap). On family-cap rejection we hand the fan-out permit back.
        SubAgentBudget familyBudget = req.getParentBudget();
        boolean familyReserved = false;
        AtomicBoolean submitted = new AtomicBoolean();
        try {
            if (familyBudget != null && !(familyReserved = familyBudget.tryAcquireSubagentRun())) {
                releasePermit.run();
                if (familyBudget.isExhausted()) {
                    return new SubAgentResult(null, "[BUDGET_EXHAUSTED] " + familyBudget.getExhaustionReason(), true);
                }
                return new SubAgentResult(null,
                    "[error] Family sub-agent run cap reached (" + familyBudget.getMaxSubagentRuns()
                        + " total this turn). Summarise with what you have, or raise harnex.subagent.max-runs-per-turn.",
                    true);
            }
            return delegateInner(req, releasePermit, submitted);
        } catch (RuntimeException | Error e) {
            releasePermit.run();
            // Only refund work that never reached the executor. Persistence failures after a real child
            // execution must not make that consumed family run available again.
            if (familyReserved && !submitted.get()) {
                familyBudget.releaseSubagentRun();
            }
            throw e;
        }
    }

    private SubAgentResult delegateInner(DelegateRequest req, Runnable releasePermit, AtomicBoolean submitted) {

        ConversationThread child = new ConversationThread();
        child.setTitle(
            "sub:" + (req.getGoal() == null ? "" : req.getGoal().substring(0, Math.min(24, req.getGoal().length()))));

        List<String> toolsets =
            req.getToolsets() == null || req.getToolsets().isEmpty() ? List.of("act") : req.getToolsets();
        child.setToolsets(toolsets);
        child.setModel(req.getModel());
        child.setProvider(req.getProvider());
        child.setReasoningEffort(req.getReasoningEffort());
        // Child work budget is independent from the root loop. The model/public API may request a
        // smaller cap for a simple task, but may not raise the operator's sub-agent ceiling.
        int configuredCeiling = cfg.getSubagent().getMaxIterations();
        int subagentCeiling = Math.max(1,
            configuredCeiling > 0
                ? Math.min(configuredCeiling, cfg.getMaxIterations())
                : cfg.getMaxIterations());
        int effectiveMaxIterations =
            req.getMaxIterations() > 0 ? Math.min(req.getMaxIterations(), subagentCeiling) : subagentCeiling;
        child.setMaxIterations(effectiveMaxIterations);

        // Propagate depth so nested sub-agents know their level.
        // At maxDepth-1 the delegate tool is still available but the next level will be rejected.
        child.setSubagentDepth(req.getDepth() + 1);
        String interactionRoot = req.getRootThreadId() == null || req.getRootThreadId().isBlank()
            ? req.getParentThreadId() : req.getRootThreadId();
        child.setInteractionRootThreadId(interactionRoot);

        // Inherit parent's permission mode so the sub-agent respects the same approval posture
        // (e.g. "yolo" when user selected full-auto). Defence-in-depth: ApprovalService also
        // short-circuits for subagentDepth > 0, but inheriting keeps the state consistent.
        if (req.getParentPermissionMode() != null && child.getState() != null) {
            child.getState().setPermissionMode(req.getParentPermissionMode());
        }

        long configuredTimeoutMs = cfg.getSubagent().getTimeoutMs();
        long runtimeMinutes = configuredTimeoutMs > 0
            ? configuredTimeoutMs / 60_000L + (configuredTimeoutMs % 60_000L == 0 ? 0 : 1) : 0L;
        String runtimeRule = configuredTimeoutMs > 0
            ? " Hard execution limit (excluding queue wait): " + runtimeMinutes + (runtimeMinutes == 1 ? " minute;" : " minutes;")
            : " Runtime is operator-bounded;";
        child.getMessages().add(ChatMessage.system("You are a sub-agent executing one bounded subtask. "
            + "This is NOT an autonomous /goal continuation loop." + runtimeRule
            + " if the scope is too broad, return a concrete split and a partial handoff.\n"
            + "Rules:\n"
            + "1. Treat the supplied completion criteria as the exact stopping condition. Re-evaluate them after "
            + "every tool result and STOP as soon as they are satisfied; do not add optional follow-up work.\n"
            + "2. A successful tool call is not automatically completion: stop only when its result satisfies the "
            + "criteria. Conversely, do not re-run or verify unless the criteria explicitly require verification.\n"
            + "3. If an approach fails, try at most ONE materially different alternative. Never repeat the same tool "
            + "call with identical arguments.\n"
            + "4. If completion requires unavailable user input/external state, stop and report the concrete blocker.\n"
            + "5. End with a concise result/evidence summary and a final line exactly "
            + "`SUBTASK_STATUS: COMPLETE`, `SUBTASK_STATUS: BLOCKED` (external dependency), or "
            + "`SUBTASK_STATUS: BUDGET_EXHAUSTED` (time/iterations insufficient). "
            + "Periodic runtime checkpoints preserve progress and allow continued work within the hard limit."));
        if (req.getContext() != null && !req.getContext().isBlank()) {
            child.getMessages().add(ChatMessage.user("Context:\n" + req.getContext()));
        }
        String completionCriteria = completionCriteria(req);
        child.getMessages().add(ChatMessage.user("Goal:\n<subtask_objective>\n" + safePromptText(req.getGoal())
            + "\n</subtask_objective>\n\nCompletion criteria:\n<completion_criteria>\n"
            + safePromptText(completionCriteria) + "\n</completion_criteria>"));

        // StringBuffer (not StringBuilder) — runner thread writes deltas under the timeout path;
        // the parent only appends terminal markers AFTER awaiting termination, so writes never overlap.
        StringBuffer captured = new StringBuffer();
        AtomicReference<StepResult> completedStep = new AtomicReference<>();
        List<String> toolTrace = new CopyOnWriteArrayList<>();
        AtomicBoolean loopErrored = new AtomicBoolean(false);
        // Wall-clock guard: a buggy sub-agent can otherwise hang the parent indefinitely.
        long timeoutMs = cfg.getSubagent().getTimeoutMs();
        // Capture the child AgentCtx so a timeout can cooperatively cancel the loop instead of
        // relying only on a worker interrupt; provider cancellation hooks must run too, otherwise
        // a blocked HTTP call could keep consuming resources after the
        // parent already returned with a "[sub-agent timed out]" string).
        AtomicReference<AgentCtx> childCtxRef = new AtomicReference<>();
        // Capture parent budget once outside the lambda so the runner closes over a final ref.
        final SubAgentBudget parentBudget = req.getParentBudget();
        CountDownLatch runnerDone = new CountDownLatch(1);
        long submittedAtNanos = System.nanoTime();
        AtomicBoolean runnerClaimed = new AtomicBoolean();
        AtomicBoolean stopRequested = new AtomicBoolean();
        AtomicReference<AgentRuntimeBudget> runtimeRef = new AtomicReference<>();
        var inheritedConfiguration = RuntimeConfiguration.captureContext(true);
        Runnable runner = () -> {
            // The caller can claim an unstarted task during cancellation, returning admission permits exactly once.
            if (!runnerClaimed.compareAndSet(false, true)) return;
            long runnerStartedAtNanos = System.nanoTime();
            try (var configuration = inheritedConfiguration.get()) {
                AgentCtx parentContext = req.getParentContext();
                AgentRuntimeBudget runtime = new AgentRuntimeBudget(
                    parentContext == null ? null : parentContext.getRuntimeBudget(), timeoutMs,
                    cfg.getSubagent().getSoftTimeoutMs());
                runtimeRef.set(runtime);
                if (metrics != null) {
                    metrics.subagentQueueTimer.record(Duration.ofNanos(runnerStartedAtNanos - submittedAtNanos));
                }
                emitParent(req, "subagent_started", Map.of(
                    "id", "subagent:" + child.getId(),
                    "subagentThreadId", child.getId(),
                    "delegateCallId", req.getDelegateCallId() == null ? "" : req.getDelegateCallId(),
                    "parentThreadId", req.getParentThreadId() == null ? "" : req.getParentThreadId(),
                    "goal", req.getGoal() == null ? "" : req.getGoal(),
                    "model", req.getModel() == null ? "" : req.getModel(),
                    "toolsets", toolsets,
                    "depth", child.getSubagentDepth(),
                    "maxIterations", effectiveMaxIterations,
                    "completionCriteria", completionCriteria));
                Consumer<StreamEvent> emitChild = ev -> {
                    forwardChildEvent(req, child, ev);
                    switch (ev.getType()) {
                        case "message_start" -> {
                            if (metrics != null) metrics.subagentModelCalls.increment();
                        }
                        case "tool_use_start" -> {
                            if (metrics != null) {
                                metrics.subagentToolCalls.increment();
                                if ("file_read".equals(String.valueOf(ev.getData().get("name")))) {
                                    metrics.subagentFileReads.increment();
                                }
                            }
                        }
                        case "text_delta" -> {
                            Object t = ev.getData().get("text");
                            if (t != null) {
                                captured.append(t);
                            }
                        }
                        case "tool_use_end" -> {
                            Object name = ev.getData().get("name");
                            Object err = ev.getData().get("error");
                            if (Boolean.TRUE.equals(err)) {
                                String detail = summariseToolError(ev.getData().get("content"));
                                toolTrace.add("- " + name + " (error)" + (detail.isBlank() ? "" : ": " + detail));
                            } else {
                                toolTrace.add("- " + name + " (ok)");
                            }
                        }
                        case "error" -> {
                            loopErrored.set(true);
                            if (metrics != null
                                && "no_progress_finalization".equals(String.valueOf(ev.getData().get("reason")))) {
                                metrics.subagentNoProgress.increment();
                            }
                            String detail = summariseLoopError(ev.getData());
                            captured.append("\n[sub-agent error")
                                .append(detail.isBlank() ? "" : ": " + detail)
                                .append("]");
                        }
                        default -> {
                            log.debug("sub-agent emitChild: ignoring event type '{}'", ev.getType());
                        }
                    }
                };
                AgentExecutionOptions inherited = req.getOptions() == null
                    ? new AgentExecutionOptions(PermissionContext.READ_ONLY,
                        new StepLimits(Math.max(1, child.getMaxIterations()), 2), null)
                    : req.getOptions();
                AgentExecutionOptions childOptions = inherited.withLimits(
                    new StepLimits(Math.max(1, child.getMaxIterations()),
                        inherited.limits().finalizationGraceIterations()));
                Consumer<AgentCtx> ready = context -> {
                    context.setRuntimeBudget(runtime);
                    context.inheritControl(parentContext);
                    if (stopRequested.get() || parentContext != null && parentContext.isCancelled()
                        || runtime.remainingMs() == 0 || parentBudget != null && parentBudget.isExhausted()) {
                        context.interruptExecution();
                    }
                };
                if (!stopRequested.get()) {
                    StepResult result = loop.executeStep(
                        new StepRequest(child, childOptions),
                        new AgentExecutionContext(req.getRunId(), null, emitChild, childCtxRef,
                            ready, parentBudget, req.getParentSnapshotContext()));
                    completedStep.set(result);
                    // A child's blocker is input to its parent, not proof that the root needs user input.
                    // The parent may already know the answer or choose an authorized alternative.
                    if (result.reason() != StepResult.Reason.RETURNED) {
                        loopErrored.set(true);
                        captured.append("\n[").append(result.reason()).append(": ").append(result.detail()).append("]");
                    }
                }

            } finally {
                // The fan-out cap counts actual in-flight sub-agent runners, not only callers
                // still blocked in delegate(). If a timeout returns to the parent while the child
                // is still unwinding, keep the permit held until this finally runs.
                releasePermit.run();
                runnerDone.countDown();
                if (metrics != null) {
                    metrics.subagentRunTimer.record(Duration.ofNanos(System.nanoTime() - runnerStartedAtNanos));
                }
            }
        };
        boolean runnerStopped = false;
        boolean errored = false;
        String stopReason = null;
        ThreadPoolExecutor pool;
        Future<?> future;
        try {
            pool = poolFor(req.getDepth());
            future = pool.submit(runner);
            submitted.set(true);
        } catch (RejectedExecutionException rejected) {
            releasePermit.run();
            if (parentBudget != null) parentBudget.releaseSubagentRun();
            return new SubAgentResult(null, "[QUEUE_REJECTED] sub-agent queue full or executor shutting down. "
                + "Wait for capacity before retrying.", true);
        }
        boolean interrupted = false;
        boolean cancelledBeforeStart = false;
        try {
            while (true) {
                // Check completion first: a result already published wins over a coincident deadline.
                if (future.isDone()) {
                    future.get();
                    runnerStopped = true;
                    break;
                }
                if (closing.get() || req.getParentContext() != null && req.getParentContext().isCancelled()) {
                    stopReason = "CANCELLED";
                    break;
                }
                AgentRuntimeBudget runtime = runtimeRef.get();
                if (runtime == null && req.getParentContext() != null) {
                    var control = req.getParentContext().controlDecision();
                    if (control != RunControlSignal.Decision.CONTINUE
                        && runnerClaimed.compareAndSet(false, true)) {
                        cancelledBeforeStart = true;
                        stopReason = control == RunControlSignal.Decision.PAUSE ? "PAUSED"
                            : control == RunControlSignal.Decision.RUN_CANCEL ? "CANCELLED" : "SUPERSEDED";
                        break;
                    }
                }
                AgentRuntimeBudget parentRuntime = req.getParentContext() == null
                    ? null : req.getParentContext().getRuntimeBudget();
                if (parentRuntime != null && parentRuntime.remainingMs() == 0) {
                    stopReason = "BUDGET_EXHAUSTED";
                    break;
                }
                long remaining = runtime == null
                    ? Math.max(1, cfg.getSubagent().getQueueTimeoutMs())
                        - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submittedAtNanos)
                    : runtime.remainingMs();
                if (remaining <= 0) {
                    if (runtime == null) {
                        // A worker winning admission at this instant owns the execution budget, not the queue budget.
                        if (!runnerClaimed.compareAndSet(false, true)) continue;
                        cancelledBeforeStart = true;
                    }
                    stopReason = runtime == null ? "QUEUE_TIMEOUT" : "TIMED_OUT";
                    break;
                }
                try {
                    future.get(Math.min(100L, remaining), TimeUnit.MILLISECONDS);
                    runnerStopped = true;
                    break;
                } catch (TimeoutException polling) {
                    // A short wait keeps parent cancellation responsive without interrupting healthy child work.
                }
            }
        } catch (InterruptedException ie) {
            interrupted = true;
            stopReason = "CANCELLED";
        } catch (CancellationException cancelled) {
            stopReason = "CANCELLED";
        } catch (ExecutionException failed) {
            runnerStopped = true;
            errored = true;
            captured.append("\n[sub-agent failed: ").append(failed.getCause()).append("]");
        } finally {
            if (!runnerStopped) {
                stopRequested.set(true);
                AgentCtx childCtx = childCtxRef.get();
                if (childCtx != null) childCtx.interruptExecution();
                future.cancel(true);
                pool.remove((Runnable) future);
                if (cancelledBeforeStart || runnerClaimed.compareAndSet(false, true)) {
                    // Cancelled while queued: its runner/finally will never run.
                    releasePermit.run();
                    if (parentBudget != null) parentBudget.releaseSubagentRun();
                    runnerDone.countDown();
                }
                try {
                    runnerStopped = runnerDone.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (stopReason != null) {
            errored = true;
            if ("TIMED_OUT".equals(stopReason) && metrics != null) metrics.subagentTimeouts.increment();
            captured.append("\n[").append(stopReason).append("] ");
            if ("TIMED_OUT".equals(stopReason)) {
                captured.append("[sub-agent timed out after ").append(timeoutMs).append("ms]");
            } else if ("BUDGET_EXHAUSTED".equals(stopReason)) {
                captured.append(parentBudget != null && parentBudget.getExhaustionReason() != null
                    ? parentBudget.getExhaustionReason() : "parent execution budget exhausted");
            } else if ("QUEUE_TIMEOUT".equals(stopReason)) {
                captured.append("No worker became available within the queue budget; execution did not start.");
            } else {
                captured.append("sub-agent interrupted");
            }
        }
        // Only persist the child when we are sure the runner has stopped touching it. Skipping
        // save() on a still-live runner is the lesser evil: the parent already received the
        // captured output, and forensic browsing of an aborted sub-agent thread is far less
        // valuable than NOT corrupting the next reload of the same thread id.
        if (runnerStopped) {
            store.save(child);
        } else {
            log.warn("sub-agent runner did not drain within grace window; skipping store.save to avoid "
                + "serialising a concurrently-mutated message list (thread={})", child.getId());
        }

        String output = captured.toString();
        if (output.isBlank() && runnerStopped) {
            for (int i = child.getMessages().size() - 1; i >= 0; i--) {
                ChatMessage m = child.getMessages().get(i);
                if ("assistant".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                    output = m.getContent();
                    break;
                }
            }
        }
        String terminalStatus = stopReason == null ? terminalStatus(output) : stopReason;
        if (stopReason == null && completedStep.get() != null) {
            terminalStatus = switch (completedStep.get().reason()) {
                case RETURNED -> terminalStatus;
                case NEEDS_INPUT -> "BLOCKED";
                case STEP_LIMIT, RUN_BUDGET -> "BUDGET_EXHAUSTED";
                case PAUSED -> "PAUSED";
                case SUPERSEDED -> "SUPERSEDED";
                case CANCELLED -> "CANCELLED";
                case FAILED -> "FAILED";
            };
        }
        String displayOutput = output;
        if (!toolTrace.isEmpty()) {
            output = output + "\n\n[sub-agent tool trace]\n" + String.join("\n", toolTrace);
        }
        errored = errored || loopErrored.get();
        // Completion is fail-closed: the child was given an explicit definition of done and a mandatory terminal
        // marker, so only COMPLETE is a successful delegation. BLOCKED and UNCONFIRMED both force the parent to
        // adapt/replan. The old UNCONFIRMED-as-success compatibility path produced false green tasks whose output was
        // merely a progress checkpoint (and let max/no-progress finalization failures masquerade as deliverables).
        errored = errored || !"COMPLETE".equals(terminalStatus);
        emitParent(req, "subagent_finished", Map.of(
            "id", "subagent:" + child.getId(),
            "subagentThreadId", child.getId(),
            "delegateCallId", req.getDelegateCallId() == null ? "" : req.getDelegateCallId(),
            "parentThreadId", req.getParentThreadId() == null ? "" : req.getParentThreadId(),
            "goal", req.getGoal() == null ? "" : req.getGoal(),
            "output", displayOutput,
            "terminalStatus", terminalStatus,
            "error", errored));
        return new SubAgentResult(child.getId(), output, errored);
    }

    private static String completionCriteria(DelegateRequest req) {
        if (req.getCompletionCriteria() != null && !req.getCompletionCriteria().isBlank()) {
            return req.getCompletionCriteria().trim();
        }
        return "Return the concrete deliverable requested by the objective with enough evidence for the parent agent "
            + "to use it. Stop once that deliverable is present. If required information or an action remains "
            + "unavailable after at most two distinct approaches, report the exact blocker instead of continuing.";
    }

    private static String safePromptText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("</subtask_objective>", "<\u200B/subtask_objective>")
            .replace("</completion_criteria>", "<\u200B/completion_criteria>");
    }

    private static String terminalStatus(String output) {
        if (output == null || output.isBlank()) {
            return "UNCONFIRMED";
        }
        Matcher matcher = Pattern
            .compile("(?im)^\\s*SUBTASK_STATUS\\s*:\\s*(COMPLETE|BLOCKED|BUDGET_EXHAUSTED)\\s*$").matcher(output);
        String status = "UNCONFIRMED";
        while (matcher.find()) {
            status = matcher.group(1).toUpperCase(Locale.ROOT);
        }
        return status;
    }

    private static void forwardChildEvent(DelegateRequest req, ConversationThread child, StreamEvent event) {
        if (event == null || event.getType() == null) return;
        // Usage and descendant lifecycle facts retain their identity through every ancestor. Wrapping them again
        // would hide their costs/calls from Goal accounting or attribute a grandchild to the wrong delegate.
        if ("usage".equals(event.getType()) || "utility_model_start".equals(event.getType())) {
            Map<String, Object> usage = new LinkedHashMap<>(event.getData() == null ? Map.of() : event.getData());
            usage.putIfAbsent("subagentThreadId", child.getId());
            emitParent(req, event.getType(), usage);
            return;
        }
        if (Set.of("subagent_started", "subagent_event", "subagent_finished").contains(event.getType())) {
            emitParent(req, event.getType(), event.getData() == null ? Map.of() : event.getData());
            return;
        }
        if ("approval_required".equals(event.getType())) {
            Map<String, Object> childData = event.getData() == null ? Map.of() : event.getData();
            Map<String, Object> data = new LinkedHashMap<>(childData);
            data.putIfAbsent("subagentThreadId", child.getId());
            data.putIfAbsent("delegateCallId", req.getDelegateCallId() == null ? "" : req.getDelegateCallId());
            data.putIfAbsent("parentThreadId", req.getParentThreadId() == null ? "" : req.getParentThreadId());
            emitParent(req, event.getType(), data);
            return;
        }
        if (!Set.of("message_start", "provider_switched", "tool_use_queued", "tool_use_start", "tool_use_end",
            "error", "notice", "context_compression_started", "context_compression_finished")
            .contains(event.getType())) {
            return;
        }
        Map<String, Object> childData = event.getData() == null ? Map.of() : event.getData();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "subagent:" + child.getId());
        data.put("subagentThreadId", child.getId());
        data.put("delegateCallId", req.getDelegateCallId() == null ? "" : req.getDelegateCallId());
        data.put("parentThreadId", req.getParentThreadId() == null ? "" : req.getParentThreadId());
        data.put("childEventType", event.getType());
        for (String key : List.of("id", "callId", "name", "toolName", "arguments", "content", "metadata", "durationMs", "error", "message", "reason",
            "provider", "model", "kind", "from", "to", "selectedAttempts", "circuitState", "actualProvider",
            "actualModel", "trigger", "outcome", "summarySource", "beforeTokens", "afterTokens",
            "reductionPercent", "sizeEffective", "purpose")) {
            if (childData.containsKey(key) && childData.get(key) != null) {
                data.put(key, childData.get(key));
            }
        }
        emitParent(req, "subagent_event", data);
    }

    private static void emitParent(DelegateRequest req, String type, Map<String, Object> data) {
        if (req.getEventSink() == null) {
            return;
        }
        try {
            req.getEventSink().accept(StreamEvent.of(type, data));
        } catch (RuntimeException e) {
            log.debug("sub-agent event forwarding failed: {}", e.toString());
        }
    }

    private static String summariseToolError(Object content) {
        if (content == null) {
            return "";
        }
        String s = String.valueOf(content).replaceAll("\\s+", " ").trim();
        return truncate(s, 240);
    }

    private static String summariseLoopError(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        Object message = data.get("message");
        if (message != null && !String.valueOf(message).isBlank()) {
            return truncate(String.valueOf(message).replaceAll("\\s+", " ").trim(), 240);
        }
        Object reason = data.get("reason");
        Object detail = data.get("detail");
        if (reason != null || detail != null) {
            String s = String.valueOf(reason == null ? "error" : reason);
            if (detail != null && !String.valueOf(detail).isBlank()) {
                s += " (" + detail + ")";
            }
            return truncate(s.replaceAll("\\s+", " ").trim(), 240);
        }
        return truncate(String.valueOf(data).replaceAll("\\s+", " ").trim(), 240);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "...";
    }

    @Data
    public static class DelegateRequest {
        private String goal;
        private String context;
        /**
         * Observable definition of done for this one bounded task. When omitted, the executor supplies a conservative
         * deliverable-or-blocker default; callers should provide it for tasks whose objective alone does not imply an
         * unambiguous stopping point.
         */
        private String completionCriteria;
        private List<String> toolsets;
        private String model;
        /** Parent conversation provider affinity; internal because the delegate tool owns inheritance. */
        @JsonIgnore
        private String provider;
        /** Parent conversation reasoning posture; internal because the delegate tool owns inheritance. */
        @JsonIgnore
        private String reasoningEffort;
        /** Per-sub-agent iteration cap; 0 = use harnex.subagent.max-iterations. May only lower that operator ceiling. */
        private int maxIterations;
        private int depth = 0;
        /** Parent conversation id; used for per-thread fan-out accounting. May be null for ad-hoc API calls. */
        private String parentThreadId;
        /**
         * Shared token / ask budget inherited from the calling agent. Populated by the {@code delegate} tool from its
         * parent {@link com.harnex.tool.ToolContext} so a cost-capped deployment observes the cost of every descendant.
         * Iterations are NOT shared — each agent bounds its own loop locally. Remains {@code null} for direct
         * {@code POST /api/delegate} calls so an external client cannot accidentally hijack a server-side budget. Never
         * serialised over the wire.
         */
        @JsonIgnore
        private SubAgentBudget parentBudget;
        @JsonIgnore
        private AgentCtx parentContext;
        @JsonIgnore
        private String runId;
        /** Exact parent delegate tool call that owns this child activity. */
        @JsonIgnore
        private String delegateCallId;
        @JsonIgnore
        private String rootThreadId;
        @JsonIgnore
        private SnapshotService.SnapshotCaptureContext parentSnapshotContext;
        /** Internal-only constraints and reply route inherited from the parent. */
        @JsonIgnore
        private AgentExecutionOptions options;
        /**
         * Parent thread's permission mode (yolo/accept-edits/ask). Inherited so that a sub-agent respects the same
         * approval posture the user configured on the parent session. Defence-in-depth alongside the subagentDepth
         * auto-approve in {@link com.harnex.tool.approval.ApprovalService#decide}.
         */
        @JsonIgnore
        private String parentPermissionMode;
        /** Parent run event sink; internal-only and never accepted from the HTTP delegate API. */
        @JsonIgnore
        private Consumer<StreamEvent> eventSink;
    }

    @Data
    @AllArgsConstructor
    public static class SubAgentResult {
        private String threadId;
        private String output;
        /**
         * True when the run did NOT complete cleanly — sub-agent timed out, was interrupted by the parent's outer-tool
         * watchdog, or threw. The output still contains whatever the runner managed to capture (so the user / parent
         * can see partial work) but {@link com.harnex.tool.builtin.DelegateTool} MUST surface this as an error to the
         * parent agent — otherwise the model treats "[sub-agent timed out]" as a real answer and feeds it forward as if
         * the delegation succeeded.
         */
        private boolean errored;

        /** Back-compat 2-arg constructor — assumes a clean run. */
        public SubAgentResult(String threadId, String output) {
            this(threadId, output, false);
        }
    }
}
