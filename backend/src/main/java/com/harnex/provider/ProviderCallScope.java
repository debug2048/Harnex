package com.harnex.provider;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.harnex.config.RuntimeConfiguration;
import com.harnex.model.StreamEvent;
import com.harnex.net.HttpExchangeState;

/** Per-worker call ownership. Scopes are explicitly closed and never inherited by unrelated pool tasks. */
public final class ProviderCallScope implements AutoCloseable {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Consumer<HttpExchangeState>> OBSERVER = new ThreadLocal<>();
    private static final ThreadLocal<CallIdentity> CALL = new ThreadLocal<>();
    private static final ThreadLocal<QueueTiming> QUEUE = new ThreadLocal<>();
    private final Context previous;
    private ProviderCallScope(Context context) { previous = CURRENT.get(); CURRENT.set(context); }
    public static ProviderCallScope open(Context context) { return new ProviderCallScope(Objects.requireNonNull(context)); }
    public static Context current() { return CURRENT.get(); }
    /**
     * Open a nested utility scope that preserves ownership, cancellation, event routing and target guards while
     * explicitly disabling provider-hosted tools.  One-shot summarisation, title, aggregation and probe calls must not
     * inherit the interactive parent request's web-search permission.
     */
    static ProviderCallScope suppressNativeTools() {
        Context owner = CURRENT.get();
        Context restricted = owner == null
            ? new Context(null, null, null, "utility", () -> false, ignored -> () -> {}, ignored -> {}, false)
            : new Context(owner.runId(), owner.threadId(), owner.stepId(), owner.purpose(), owner.cancelled(),
                owner.onCancel(), owner.events(), false, owner.targetGuard());
        return new ProviderCallScope(restricted);
    }
    public record CallIdentity(String callId,String attemptId) {}
    public record QueueTiming(long queuedAt,long workerStartedAt,long queueMillis) {}
    public static CallIdentity currentCall() { return CALL.get(); }
    public static QueueTiming takeQueueTiming() { QueueTiming value = QUEUE.get(); QUEUE.remove(); return value; }
    public static CallBinding bindCall(String callId,String attemptId) { return new CallBinding(new CallIdentity(callId,attemptId)); }
    public static final class CallBinding implements AutoCloseable {
        private final CallIdentity previous;
        private CallBinding(CallIdentity identity) { previous = CALL.get(); CALL.set(identity); }
        @Override public void close() { if (previous == null) CALL.remove(); else CALL.set(previous); }
    }
    /** Capture at submission time, then restore the pool thread's previous owner even after failure. */
    public static <T> Callable<T> capture(Callable<T> task) {
        Context owner = current();
        var configuration = RuntimeConfiguration.captureContext(owner != null);
        long queuedAt = System.currentTimeMillis(), queuedNanos = System.nanoTime();
        return () -> {
            QueueTiming previous = QUEUE.get();
            QUEUE.set(new QueueTiming(queuedAt,System.currentTimeMillis(),Math.max(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-queuedNanos))));
            try (var inheritedSettings = configuration.get(); var scope = new ProviderCallScope(owner)) { return task.call(); }
            finally { if (previous == null) QUEUE.remove(); else QUEUE.set(previous); }
        };
    }
    public static <T> Supplier<T> captureSupplier(Supplier<T> task) {
        var captured = capture(task::get);
        return () -> { try { return captured.call(); } catch (RuntimeException failure) { throw failure; } catch (Exception impossible) { throw new IllegalStateException(impossible); } };
    }
    public static Runnable captureRunnable(Runnable task) { var captured = captureSupplier(() -> { task.run(); return null; }); return () -> captured.get(); }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }

    static Observation observe(Consumer<HttpExchangeState> observer) { return new Observation(observer); }
    static void exchange(HttpExchangeState exchange) {
        Consumer<HttpExchangeState> observer = OBSERVER.get();
        if (observer != null && exchange != null) observer.accept(exchange);
    }
    static final class Observation implements AutoCloseable {
        private final Consumer<HttpExchangeState> previous;
        Observation(Consumer<HttpExchangeState> observer) { previous = OBSERVER.get(); OBSERVER.set(observer); }
        @Override public void close() { if (previous == null) OBSERVER.remove(); else OBSERVER.set(previous); }
    }

    public record Context(String runId, String threadId, String stepId, String purpose, BooleanSupplier cancelled,
        Function<Runnable, AutoCloseable> onCancel, Consumer<StreamEvent> events, boolean allowNativeTools,
        BiConsumer<String,String> targetGuard) {
        public Context(String runId,String threadId,String stepId,String purpose,BooleanSupplier cancelled,Function<Runnable,AutoCloseable> onCancel,
            Consumer<StreamEvent> events,boolean allowNativeTools) { this(runId,threadId,stepId,purpose,cancelled,onCancel,events,allowNativeTools,null); }
        public Context(String runId, String threadId, String stepId, String purpose, BooleanSupplier cancelled,
            Function<Runnable, AutoCloseable> onCancel, Consumer<StreamEvent> events) {
            this(runId, threadId, stepId, purpose, cancelled, onCancel, events, true);
        }
        public Context {
            Objects.requireNonNull(cancelled); Objects.requireNonNull(onCancel); Objects.requireNonNull(events);
        }
        public boolean isCancelled() { return cancelled.getAsBoolean(); }
    }
    static void validateTarget(String provider,String model) {
        Context context = current(); if (context != null && context.targetGuard() != null) context.targetGuard().accept(provider,model);
    }
}
