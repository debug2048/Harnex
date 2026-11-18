package com.harnex.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.conversation.ContextTokens;
import com.harnex.model.ChatMessage;
import com.harnex.model.StreamEvent;
import com.harnex.net.HttpExchangeState;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

/** Shared utility execution for Agent and conversation summaries; callers own prompts and semantic validation. */
@Slf4j
@Component
public final class SummaryExecutionService implements AutoCloseable {
    private static final String HEALTH_NAMESPACE = "utility:summarizer";
    private final AgentConfig config;
    private final ProviderRouter router;
    private final ContextTokens tokens;

    public SummaryExecutionService(AgentConfig config, ProviderRouter router, ContextTokens tokens) {
        this.config = config;
        this.router = router;
        this.tokens = tokens;
    }

    /** The caller supplies its existing admission/accounting policy; this service adds no task limits. */
    public record CallContext(BooleanSupplier cancelled, Function<Runnable, AutoCloseable> onCancel,
        Runnable admitModelCall, LongConsumer addTokens, Consumer<StreamEvent> events) {
        public CallContext {
            Objects.requireNonNull(cancelled);
            Objects.requireNonNull(onCancel);
            Objects.requireNonNull(admitModelCall);
            Objects.requireNonNull(addTokens);
            Objects.requireNonNull(events);
        }
    }

    /** Resources follow the frozen runtime configuration, including across legacy caller constructors. */
    private Resources resources() { return config.derivedSettings(SummaryExecutionService.class, Resources::new); }

    private SummaryTargets.Target canonical(SummaryTargets.Target target) {
        return router.summaryTarget(target.provider(), target.model()).orElse(target);
    }

    public boolean isCoolingDown(SummaryTargets.Target target) {
        FailureGate gate = resources().failures.get(canonical(target).key());
        return gate != null && gate.blockedUntilMs() > System.currentTimeMillis();
    }

    public void recordSuccess(SummaryTargets.Target target) {
        resources().failures.remove(canonical(target).key());
    }

    public void recordInvalidSummary(SummaryTargets.Target target) { recordFailure(target, "invalid_summary"); }

    public void recordFailure(SummaryTargets.Target target, String code) {
        recordFailure(resources(), canonical(target).key(), code);
    }

    public SummaryBudget.ModelCapacity capacity(SummaryTargets.Target requested) {
        SummaryTargets.Target target = canonical(requested);
        var capacity = router.summaryCapacity(target.provider(), target.model());
        if (capacity != null) return capacity;
        return new SummaryBudget.ModelCapacity(config.getSummarizer().getContextWindow(),
            config.getSummarizer().getSafetyReserveTokens(), null, false);
    }

    public SummaryBudget.Generation plan(SummaryTargets.Target requested, List<ChatMessage> messages,
        int outputLimit, int retainedBodyTokens, boolean facts) {
        SummaryTargets.Target target = canonical(requested);
        return SummaryBudget.generation(capacity(target), tokens.messages(messages, target.model()),
            outputLimit, retainedBodyTokens, facts);
    }

    public SummaryBudget.Generation plan(SummaryTargets.Target requested, ModelProvider.ChatRequest request,
        int outputLimit, int retainedBodyTokens) {
        SummaryTargets.Target target = canonical(requested);
        return SummaryBudget.generation(capacity(target), tokens.request(request, target.model()),
            outputLimit, retainedBodyTokens, false);
    }

    private static void recordFailure(Resources resources, String key, String code) {
        if (code != null && List.of("cancelled", "budget_exhausted", "summarizer_busy", "summarizer_cooling_down",
            "incomplete", "summary_capacity", "summary_prefix_unsupported", "native_compaction_unavailable").contains(code)) return;
        resources.failures.compute(key, (ignored, previous) -> {
            int failures = previous == null ? 1 : Math.min(100, previous.failures() + 1);
            boolean configuration = "config".equals(code) || "401".equals(code) || "403".equals(code);
            long delay = configuration ? TimeUnit.MINUTES.toMillis(10)
                : Math.min(TimeUnit.MINUTES.toMillis(5), TimeUnit.SECONDS.toMillis(30L << Math.min(3, failures - 1)));
            return new FailureGate(failures, System.currentTimeMillis() + delay);
        });
    }

    /**
     * Execute one summary attempt within the caller's remaining summary budget. Cancellation
     * cancels the future as well as the inherited provider scope, so the waiter does not depend on provider cooperation.
     * A full worker pool fails immediately; there is no unbounded queue behind an uncooperative provider.
     */
    public OneShotResult execute(SummaryTargets.Target requested, List<ChatMessage> messages,
        long timeoutMs, int maxOutputTokens, CallContext context) {
        return execute(requested, messages, timeoutMs, maxOutputTokens, context, null);
    }

    public OneShotResult execute(SummaryTargets.Target requested, ModelProvider.ChatRequest request,
        long timeoutMs, int maxOutputTokens, CallContext context) {
        return execute(requested, request.getMessages(), timeoutMs, maxOutputTokens, context,
            request.toBuilder().maxTokens(maxOutputTokens).build());
    }

    private OneShotResult execute(SummaryTargets.Target requested, List<ChatMessage> messages,
        long timeoutMs, int maxOutputTokens, CallContext context, ModelProvider.ChatRequest request) {
        if (context.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancelled();
        if (timeoutMs <= 0) throw notSent("budget_exhausted", "summary time budget exhausted");
        SummaryTargets.Target target = canonical(requested);
        Resources selected = resources();
        FailureGate gate = selected.failures.get(target.key());
        if (gate != null && gate.blockedUntilMs() > System.currentTimeMillis()) {
            throw notSent("summarizer_cooling_down", "summary target is cooling down");
        }
        ProviderCallScope.Context owner = ProviderCallScope.current();
        var utilityOwner = new ProviderCallScope.Context(owner == null ? null : owner.runId(),
            owner == null ? null : owner.threadId(), owner == null ? null : owner.stepId(), HEALTH_NAMESPACE,
            () -> context.cancelled().getAsBoolean() || owner != null && owner.isCancelled(), context.onCancel(),
            context.events(), false, owner == null ? null : owner.targetGuard());
        Callable<OneShotResult> call;
        try (var ignored = ProviderCallScope.open(utilityOwner)) {
            call = ProviderCallScope.capture(() -> {
                if (utilityOwner.isCancelled()) throw cancelled();
                try { context.admitModelCall().run(); }
                catch (CancellationException cancellation) { throw cancelled(); }
                catch (IllegalStateException exhausted) {
                    throw notSent("budget_exhausted", exhausted.getMessage());
                }
                context.events().accept(StreamEvent.of("utility_model_start", Map.of("kind", "summarizer")));
                // Summaries use the model's native sampling default, including when falling back to another model.
                OneShotResult result;
                try {
                    result = request == null ? router.oneShotOn(target.provider(), target.model(), messages, null,
                        timeoutMs, maxOutputTokens, HEALTH_NAMESPACE)
                        : router.oneShotRequestOn(target.provider(), target.model(), request, timeoutMs, HEALTH_NAMESPACE);
                } catch (ProviderException failure) {
                    // A rejected/truncated answer can still be billable. Never invent usage for transport failures.
                    if (failure.usage != null) recordUsage(context, target, messages,
                        new OneShotResult("", target.provider(), target.model(), failure.usage), request);
                    throw failure;
                }
                recordUsage(context, target, messages, result, request);
                return result;
            });
        }
        if (!selected.slots.tryAcquire()) throw notSent("summarizer_busy", "summariser workers are occupied");
        var future = new AdmittedTask(call, selected);
        AutoCloseable cancellation = null;
        try {
            cancellation = context.onCancel().apply(() -> future.cancel(true));
            if (!future.isCancelled()) {
                selected.pool.execute(future);
                // Cover cancellation between the pre-submit check and queue insertion.
                if (future.isCancelled()) selected.pool.remove(future);
            }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException busy) {
            future.cancel(true);
            throw notSent("summarizer_busy", "summariser workers are occupied");
        } catch (TimeoutException timeout) {
            future.cancel(true);
            recordFailure(selected, target.key(), "timeout");
            throw new ProviderException("timeout", "summariser timed out after " + timeoutMs + "ms");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw cancelled();
        } catch (CancellationException cancellationException) {
            future.cancel(true);
            throw cancelled();
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            if (cause instanceof CancellationException) throw cancelled();
            if (cause instanceof ProviderException failure) {
                // A replay rejected for request shape may still work with the bounded, tool-free summary prompt.
                if (request == null || !List.of("config", "400", "413", "422", "context_length_exceeded",
                    "unsupported_attachment", "agent_oversize", "unexpected_tool_use", "parse").contains(failure.code))
                    recordFailure(selected, target.key(), failure.code);
                throw failure;
            }
            recordFailure(selected, target.key(), "runtime");
            if (cause instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException(cause);
        } finally {
            // Also releases an admitted task if cancellation registration itself failed before submission.
            if (!future.hasStarted()) future.cancel(false);
            try { if (cancellation != null) cancellation.close(); }
            catch (Exception failure) { log.debug("summary cancellation registration could not be closed", failure); }
        }
    }

    private void recordUsage(CallContext context, SummaryTargets.Target target, List<ChatMessage> messages,
        OneShotResult result, ModelProvider.ChatRequest request) {
        boolean interrupted = Thread.interrupted();
        try { recordUsageUninterrupted(context, target, messages, result, request); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void recordUsageUninterrupted(CallContext context, SummaryTargets.Target target, List<ChatMessage> messages,
        OneShotResult result, ModelProvider.ChatRequest request) {
        String model = result != null && result.model() != null ? result.model() : target.model();
        String provider = result != null && result.provider() != null ? result.provider() : target.provider();
        ProviderTokenUsage reported = result == null ? null : result.usage();
        long prompt = reported != null ? reported.prompt()
            : request == null ? tokens.messages(messages, model) : tokens.request(request, model);
        long completion = reported == null ? tokens.text(result == null ? "" : result.content(), model) : reported.completion();
        long total = reported == null ? prompt + completion : reported.total();
        if (total > 0) context.addTokens().accept(total);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt", prompt);
        usage.put("completion", completion);
        usage.put("total", total);
        usage.put("cachedInput", reported == null ? 0 : reported.cachedInput());
        usage.put("cacheWriteInput", reported == null ? 0 : reported.cacheWriteInput());
        usage.put("reasoning", reported == null ? 0 : reported.reasoning());
        usage.put("kind", "summarizer");
        usage.put("strategy", request == null ? "rendered" : request.isNativeCompaction() ? "native" : "prefix");
        usage.put("estimated", reported == null);
        if (provider != null) usage.put("provider", provider);
        if (model != null) usage.put("model", model);
        try { context.events().accept(StreamEvent.of("usage", usage)); }
        catch (RuntimeException failure) { log.debug("summarizer usage event could not be emitted", failure); }
    }

    private static ProviderException cancelled() { return new ProviderException("cancelled", "summary cancelled"); }
    private static ProviderException notSent(String code, String detail) {
        return new ProviderException(code, detail, HttpExchangeState.Acceptance.NOT_SENT, null, 0);
    }

    private record FailureGate(int failures, long blockedUntilMs) {}

    private static final class Resources implements AutoCloseable {
        final ThreadPoolExecutor pool;
        final Semaphore slots;
        final ConcurrentMap<String, FailureGate> failures = new ConcurrentHashMap<>();
        Resources(AgentConfig config) {
            int workers = Math.max(1, Math.min(8, config.getSummarizer().getMaxConcurrent()));
            slots = new Semaphore(workers);
            AtomicInteger sequence = new AtomicInteger();
            // Slots reject work while providers are occupied. This bounded queue only bridges the tiny interval
            // between a completed Future waking its waiter and the pool worker accepting the next repair attempt.
            pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(workers), task -> {
                Thread worker = new Thread(task, "summariser-" + sequence.incrementAndGet());
                worker.setDaemon(true);
                return worker;
            }, new ThreadPoolExecutor.AbortPolicy());
        }
        // The final runtime-snapshot lease may be released by this worker; never await our own termination.
        @Override public void close() { pool.shutdown(); }
    }

    private static final class AdmittedTask extends FutureTask<OneShotResult> {
        private final Resources resources;
        // 0 = not started; 1 = running; 2 = cancelled before running.
        private final AtomicInteger state = new AtomicInteger();
        private final AtomicBoolean released = new AtomicBoolean();
        AdmittedTask(Callable<OneShotResult> call, Resources resources) { super(call); this.resources = resources; }
        boolean hasStarted() { return state.get() == 1; }
        private void release() { if (released.compareAndSet(false, true)) resources.slots.release(); }
        @Override public void run() {
            if (!state.compareAndSet(0, 1)) return;
            try { super.run(); }
            finally { release(); }
        }
        // Release only after the real callable returns, before get() wakes the next sequential attempt.
        @Override protected void set(OneShotResult result) { release(); super.set(result); }
        @Override protected void setException(Throwable failure) { release(); super.setException(failure); }
        @Override protected void done() {
            if (isCancelled() && state.compareAndSet(0, 2)) {
                release();
                resources.pool.remove(this);
            }
            // A cancelled running task keeps its slot until the provider really exits, even if get() returned.
        }
    }

    @Override @PreDestroy
    public void close() { config.closeDerivedSettings(SummaryExecutionService.class); }
}
