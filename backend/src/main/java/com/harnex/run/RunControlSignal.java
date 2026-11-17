package com.harnex.run;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared, thread-safe control intent for one root run and its current AgentCtx.
 *
 * <p>Run cancellation is terminal, pause is level-triggered until a future resume clears it, and step cancellation is
 * generation-based so one request is acknowledged at most once. Priority is always RunCancel &gt; Pause &gt;
 * StepCancel.</p>
 */
public final class RunControlSignal {

    private final AtomicBoolean runCancelRequested = new AtomicBoolean();
    private final AtomicBoolean pauseRequested = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean stepClosed = new AtomicBoolean();
    private final AtomicLong stepCancelRequested = new AtomicLong();
    private final AtomicLong stepCancelAcknowledged = new AtomicLong();
    private final Set<Runnable> cancellationListeners = ConcurrentHashMap.newKeySet();

    public RunControlSignal() {}

    public static RunControlSignal restore(State state) {
        RunControlSignal signal = new RunControlSignal();
        State restored = state == null ? State.NONE : state;
        switch (restored) {
            case RUN_CANCEL_REQUESTED -> signal.runCancelRequested.set(true);
            case PAUSE_REQUESTED -> signal.pauseRequested.set(true);
            case PAUSED -> {
                signal.pauseRequested.set(true);
                signal.paused.set(true);
            }
            case STEP_CANCEL_REQUESTED -> signal.stepCancelRequested.set(1L);
            case NONE -> { }
        }
        return signal;
    }

    public boolean requestRunCancel() {
        if (!runCancelRequested.compareAndSet(false, true)) return false;
        for (Runnable listener : cancellationListeners) {
            if (cancellationListeners.remove(listener)) invokeCancellation(listener);
        }
        return true;
    }

    /** Register against the same root intent used by Agent controls, including cancellation before registration. */
    public AutoCloseable onRunCancel(Runnable listener) {
        Objects.requireNonNull(listener);
        // A wrapper makes two registrations of the same callback independent and guarantees at most one invocation.
        Runnable registration = () -> listener.run();
        cancellationListeners.add(registration);
        if (runCancelRequested.get() && cancellationListeners.remove(registration)) invokeCancellation(registration);
        return () -> cancellationListeners.remove(registration);
    }

    private static void invokeCancellation(Runnable listener) {
        try { listener.run(); } catch (RuntimeException ignored) { /* Other active calls still need cancellation. */ }
    }

    public boolean requestPause() {
        if (runCancelRequested.get() || stepClosed.get()) return false;
        paused.set(false);
        boolean requested = pauseRequested.compareAndSet(false, true);
        // Pause supersedes a lower-priority current-step cancellation; there is no old step to cancel after resume.
        if (pauseRequested.get()) acknowledgeStepCancel();
        return requested;
    }

    public boolean requestStepCancel() {
        if (runCancelRequested.get() || pauseRequested.get() || stepClosed.get()) return false;
        stepCancelRequested.incrementAndGet();
        return true;
    }

    public Decision decision() {
        if (runCancelRequested.get()) return Decision.RUN_CANCEL;
        if (pauseRequested.get()) return Decision.PAUSE;
        if (stepCancelRequested.get() > stepCancelAcknowledged.get()) return Decision.STEP_CANCEL;
        return Decision.CONTINUE;
    }

    public void acknowledgeStepCancel() {
        stepCancelAcknowledged.set(stepCancelRequested.get());
    }

    public void acknowledgePause() {
        if (pauseRequested.get() && !runCancelRequested.get()) paused.set(true);
    }

    public void clearPause() {
        if (runCancelRequested.get()) return;
        pauseRequested.set(false);
        paused.set(false);
    }

    /** Opens a new internal AgentLoop step without clearing root-run control intent. */
    public void beginStep() {
        stepClosed.set(false);
    }

    /** Closes the last control race before AgentLoop emits its terminal frame and returns its worker. */
    public void closeStep() {
        stepClosed.set(true);
    }

    public boolean isRunCancelRequested() {
        return runCancelRequested.get();
    }

    public boolean isPauseRequested() {
        return pauseRequested.get();
    }

    public State state() {
        if (runCancelRequested.get()) return State.RUN_CANCEL_REQUESTED;
        if (paused.get()) return State.PAUSED;
        if (pauseRequested.get()) return State.PAUSE_REQUESTED;
        if (stepCancelRequested.get() > stepCancelAcknowledged.get()) return State.STEP_CANCEL_REQUESTED;
        return State.NONE;
    }

    public enum Decision {
        CONTINUE,
        STEP_CANCEL,
        PAUSE,
        RUN_CANCEL
    }

    public enum State {
        NONE,
        STEP_CANCEL_REQUESTED,
        PAUSE_REQUESTED,
        PAUSED,
        RUN_CANCEL_REQUESTED
    }
}
