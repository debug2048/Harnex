package com.harnex.core;

import java.util.Optional;
import com.harnex.todo.TodoStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.harnex.conversation.ContextEvidence;
import com.harnex.conversation.ConversationFactStore;
import com.harnex.conversation.HistoryContextService;
import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.model.StreamEvent;
import com.harnex.model.ToolCall;
import com.harnex.provider.ModelProvider;
import com.harnex.run.RunControlSignal;
import com.harnex.tool.ToolResult;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

/** Mutable state carried through the agent loop and middleware chain. */
@Data
public class AgentCtx {
    private final ConversationThread thread;
    private final Consumer<StreamEvent> emit;
    /** Bound by the Agent host; standalone projections may have no checklist source. */
    private TodoStore todoStore;

    public Optional<List<Map<String, Object>>> currentTodos() {
        return todoStore == null ? Optional.empty() : todoStore.snapshotForContext(thread);
    }

    public boolean hasStoredTodos() {
        return todoStore != null && todoStore.snapshotIfPresent(thread.getId()).isPresent();
    }
    /** Tool calls produced by most recent LLM response (may be null). */
    private List<ToolCall> lastToolCalls;
    /** Results from most recent tool execution (may be null). */
    private List<ToolResult> lastToolResults;
    /** Iteration counter. */
    private int iteration;
    /** Extra payload bag. */
    private Object extras;
    /**
     * Runtime-owned instructions queued for the next Provider request only. They deliberately do not live in
     * {@link ConversationThread#getMessages()}: framework reminders must never become a durable human turn, affect
     * title/human-turn selection, or survive history compression as apparent user intent.
     */
    private final List<String> requestReminders = new CopyOnWriteArrayList<>();
    /** Per-run idempotency keys for request-only reminders (cadence retries can revisit the same iteration). */
    private final Set<String> requestReminderKeys = ConcurrentHashMap.newKeySet();
    /** Rebuilds the pending request without consuming reminders or mutating durable history. Run-local only. */
    private Supplier<ModelProvider.ChatRequest> contextRequestProjection;
    /** Detached successful request, used only while this run can still reuse its exact provider prefix. */
    private ModelProvider.ChatRequest lastTaskRequest;
    private final com.harnex.skill.ActiveSkillContext activeSkills = new com.harnex.skill.ActiveSkillContext();
    /** Immutable active-fact snapshot captured at a safe point; rendering/compaction previews perform no archive I/O. */
    private Function<ContextEvidence.Collector,
        ConversationFactStore.Projection> registeredFactProjection;
    /** Refresh after framework-owned summary registration, outside the pure request preview. */
    private Runnable refreshRegisteredFacts;
    /** Current recall selection is request state; historical selections replay in their original message overlays. */
    private HistoryContextService.Recall requestHistoryRecall;
    /** Definitions for the next request; empty on a no-tool finalization/checkpoint. */
    private List<Map<String, Object>> requestToolDefinitions = List.of();
    /** Set when this execution is explicitly interrupted; subscriber disconnects do not cancel runs. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    /** Shared root-run control intent; rebound by RunLease.onContextReady before provider/tool work begins. */
    @Setter(AccessLevel.NONE)
    private volatile RunControlSignal controlSignal = new RunControlSignal();
    /** Read-only ancestor control. A child must never close or acknowledge its parent's step signal. */
    @Setter(AccessLevel.NONE)
    private volatile Supplier<RunControlSignal.Decision> parentControl =
        () -> RunControlSignal.Decision.CONTINUE;
    private final AtomicBoolean pauseAcknowledged = new AtomicBoolean(false);
    private final AtomicBoolean stepCancelAcknowledged = new AtomicBoolean(false);
    /**
     * Set whenever an {@code error} {@link StreamEvent} is emitted through {@link #emit(StreamEvent)}. Used by
     * {@link AgentLoop}'s C2 success-path lesson append to suppress the {@code task_completed} entry on runs that ended
     * in any reported error.
     */
    private final AtomicBoolean errored = new AtomicBoolean(false);
    /** Best-effort callbacks fired exactly once on the first {@link #cancel()} invocation. */
    private final List<Runnable> cancelHooks = new CopyOnWriteArrayList<>();
    /**
     * Set by {@link AgentLoop} once the loop has returned naturally (finally block). Late cancels (e.g. the heartbeat
     * scheduler's 750 ms delayed cancel after SSE close) consult this flag and become no-ops, so they cannot interrupt
     * a worker thread that has already been recycled into the executor pool and is now running an unrelated task.
     */
    private final AtomicBoolean completed = new AtomicBoolean(false);
    /**
     * Shared token / family-run budget for this agent run. Sub-agents reuse the parent's tracker so the family-wide
     * sub-agent count and token total apply to the whole family for a single user turn. Iterations are NOT shared —
     * each agent bounds its own loop locally. {@code null} for legacy callers that bypass {@link AgentLoop} — most code
     * should NOT need to read this directly; {@link AgentLoop} owns the lifecycle.
     */
    private SubAgentBudget budget;
    /** Local execution deadline, inherited by descendants; null for an unbounded top-level chat. */
    private AgentRuntimeBudget runtimeBudget;
    /** Durable Steering remains separate from pause/cancel; workers stop admitting the superseded tool batch. */
    private volatile BooleanSupplier pendingSteering = () -> false;

    public boolean hasPendingSteering() { return pendingSteering.getAsBoolean(); }

    /**
     * Provider name that actually produced the most recent LLM stream. {@link AgentLoop} seeds it from the selected or
     * default provider, refreshes it from {@code message_start}, and replaces it when {@code provider_switched} reports
     * a real transition to the reserved fallback. The audit log therefore attributes persisted assistant messages to
     * the endpoint that served them. Volatile so the router callback and loop thread see a consistent value without
     * explicit synchronisation.
     */
    private volatile String lastProvider;
    /** Model id that actually produced the latest task-model stream; used by token counting and summary fallback. */
    private volatile String lastModel;
    private volatile StepResult.Reason stopReason = StepResult.Reason.RETURNED;
    private AgentExecutionOptions options;
    private volatile String stopDetail;
    private volatile String failureCode;
    private volatile ChatMessage stepAnswer;
    private volatile boolean resultPersisted;

    public synchronized void needsInput(String detail) {
        if (stopReason == StepResult.Reason.RETURNED) {
            stopReason = StepResult.Reason.NEEDS_INPUT;
            stopDetail = detail;
        }
    }

    public synchronized void clearRequiredInput() {
        if (stopReason == StepResult.Reason.NEEDS_INPUT) {
            stopReason = StepResult.Reason.RETURNED;
            stopDetail = null;
        }
    }

    public StepResult result(String runId, String stepId, long tokensBefore) {
        StepResult.Reason reason = stopReason;
        if (controlDecision() == RunControlSignal.Decision.RUN_CANCEL) reason = StepResult.Reason.CANCELLED;
        else if (!resultPersisted) reason = StepResult.Reason.FAILED;
        else if (budget != null && budget.isExhausted()) reason = StepResult.Reason.RUN_BUDGET;
        else if (isPauseAcknowledged()) reason = StepResult.Reason.PAUSED;
        else if (isStepCancelAcknowledged()) reason = StepResult.Reason.SUPERSEDED;
        else if (isCancelled()) reason = StepResult.Reason.CANCELLED;
        return new StepResult(runId, stepId, reason, stepAnswer == null ? "" : stepAnswer.getContent(),
            stopDetail, failureCode, budget == null ? 0 : Math.max(0, budget.getTokensUsed() - tokensBefore), resultPersisted);
    }

    public AgentCtx(ConversationThread thread, Consumer<StreamEvent> emit) {
        this.thread = thread;
        this.emit = emit;
    }

    public void emit(StreamEvent e) {
        if (e != null && "error".equals(e.getType())) {
            errored.set(true);
            Map<String, Object> data = e.getData() == null ? Map.of() : e.getData();
            failureCode = String.valueOf(data.getOrDefault("reason", "agent_error"));
            stopDetail = String.valueOf(data.getOrDefault("message", data.getOrDefault("detail", failureCode)));
            stopReason = switch (failureCode) {
                case "max_iterations" -> StepResult.Reason.STEP_LIMIT;
                case "budget_exhausted", "runtime_finalization" -> StepResult.Reason.RUN_BUDGET;
                default -> StepResult.Reason.FAILED;
            };
        }
        if (emit != null) {
            emit.accept(e);
        }
    }

    /** Queue one instruction for the next model request. Returns false when the same logical reminder already fired. */
    public synchronized boolean queueRequestReminder(String key, String text) {
        if (text == null || text.isBlank()) return false;
        String stableKey = key == null || key.isBlank() ? "anonymous:" + text : key;
        if (!requestReminderKeys.add(stableKey)) return false;
        requestReminders.add(text.strip());
        return true;
    }

    /**
     * Atomically consume all pending request-only instructions. Provider-level retries reuse the already-built
     * ChatRequest, while a later agent iteration starts with a clean reminder slate.
     */
    public synchronized List<String> drainRequestReminders() {
        if (requestReminders.isEmpty()) return List.of();
        List<String> drained = List.copyOf(requestReminders);
        requestReminders.clear();
        return drained;
    }

    public boolean isCancelled() {
        return cancelled.get() || controlDecision() == RunControlSignal.Decision.RUN_CANCEL;
    }

    public void inheritControl(AgentCtx parent) {
        if (parent == this) throw new IllegalArgumentException("an agent cannot inherit its own control");
        parentControl = parent == null ? () -> RunControlSignal.Decision.CONTINUE : parent::controlDecision;
    }

    public void bindControlSignal(RunControlSignal signal) {
        if (signal == null || signal == controlSignal) return;
        controlSignal = signal;
        signal.beginStep();
        if (signal.isRunCancelRequested()) cancel();
    }

    public RunControlSignal.Decision controlDecision() {
        RunControlSignal.Decision local = controlSignal.decision();
        RunControlSignal.Decision inherited = parentControl.get();
        if (local == RunControlSignal.Decision.RUN_CANCEL || inherited == RunControlSignal.Decision.RUN_CANCEL)
            return RunControlSignal.Decision.RUN_CANCEL;
        if (local == RunControlSignal.Decision.PAUSE || inherited == RunControlSignal.Decision.PAUSE)
            return RunControlSignal.Decision.PAUSE;
        if (local == RunControlSignal.Decision.STEP_CANCEL || inherited == RunControlSignal.Decision.STEP_CANCEL)
            return RunControlSignal.Decision.STEP_CANCEL;
        return RunControlSignal.Decision.CONTINUE;
    }

    public void acknowledgePause() {
        controlSignal.acknowledgePause();
        pauseAcknowledged.set(true);
    }

    public void acknowledgeStepCancel() {
        controlSignal.acknowledgeStepCancel();
        stepCancelAcknowledged.set(true);
    }

    public boolean isPauseAcknowledged() {
        return pauseAcknowledged.get();
    }

    public boolean isStepCancelAcknowledged() {
        return stepCancelAcknowledged.get();
    }

    public void closeControlStep() {
        controlSignal.closeStep();
    }

    /** True once at least one {@code error} {@link StreamEvent} has flowed through this context. */
    public boolean hasErrored() {
        return errored.get();
    }

    /**
     * Register a callback to be invoked once when {@link #cancel()} fires. If the context is already cancelled the
     * callback runs synchronously on the registering thread \u2014 this means a late-registering provider whose call
     * started after the user clicked STOP will still abort cleanly instead of finishing the request and burning tokens.
     */
    public synchronized void onCancel(Runnable r) {
        if (r == null)
            return;
        if (completed.get()) return;
        if (cancelled.get()) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                /* hook failure must not propagate */ }
            return;
        }
        cancelHooks.add(r);
    }

    /** A provider removes its callback when the network call returns, without retaining completed calls in this step. */
    public synchronized AutoCloseable registerCancellation(Runnable callback) {
        onCancel(callback);
        return () -> { synchronized (AgentCtx.this) { cancelHooks.remove(callback); } };
    }

    public void cancel() {
        // P2 #6: if the loop has already returned, suppress the cancel entirely. Without this,
        // a delayed cancel firing after the worker thread was returned to the pool would
        // interrupt some innocent successor task. Hooks are also cleared by complete() so this
        // is a belt-and-braces guard.
        controlSignal.requestRunCancel();
        interruptExecution();
    }

    /**
     * Abort only this in-memory AgentLoop/provider/tool execution. Unlike {@link #cancel()}, this deliberately does
     * not persist a root RunCancel intent, so Supervisor DRAIN can leave a Goal Run resumable after the old worker
     * has stopped.
     */
    public synchronized void interruptExecution() {
        if (completed.get())
            return;
        if (!cancelled.compareAndSet(false, true))
            return;
        // Fire hooks in registration order, swallowing per-hook failures so one bad hook cannot
        // strand the others (e.g. provider HTTP cancel must still run if a TUI hook throws).
        for (Runnable r : cancelHooks) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                /* swallow */ }
        }
        cancelHooks.clear();
    }

    /**
     * Mark this context as having completed its agent loop. Called once from {@link AgentLoop}'s finally block.
     * Idempotent. After this returns, future {@link #cancel()} calls are no-ops and any registered cancel hooks are
     * dropped so they cannot fire against a thread that has been released back to the pool.
     */
    public synchronized void complete() {
        if (!completed.compareAndSet(false, true))
            return;
        cancelHooks.clear();
    }
}
