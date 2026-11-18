package com.harnex.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.observability.HarnexMetrics;
import com.harnex.config.RuntimeConfiguration;
import com.harnex.model.ChatMessage;
import com.harnex.model.MessageAttachment;
import com.harnex.model.StreamEvent;
import com.harnex.net.HttpExchangeState;
import com.harnex.net.OutboundProxyResolver;
import com.harnex.usage.UsageLedger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Routes a selected/default provider with bounded same-target retries and one reserved disaster-recovery provider.
 * Every other enabled provider is a selectable catalogue entry, never an implicit failover hop.
 */
@Slf4j
@Component
public class ProviderRouter {

    public static final String FALLBACK_PROVIDER = "fallback";
    private static final Set<String> DEFAULT_FAILOVER_CODES =
        Set.of("429", "500", "502", "503", "504", "529", "timeout", "network", "stream_ttft_timeout");

    private final AgentConfig cfg;
    private final ResolvedProviderRegistry providerRegistry;
    private final HarnexMetrics metrics;
    private final ProviderFactory providerFactory;
    private final Map<String, ModelProvider> byName = new LinkedHashMap<>();
    private ModelProvider defaultProvider;
    private ProviderHealth health;
    @Autowired(required = false)
    private RuntimeConfiguration liveRuntime;
    private volatile Consumer<ModelCallRecord> recordSink;
    @Autowired(required = false)
    private ModelCallStore callTraces;
    @Autowired(required = false)
    private UsageLedger usageLedger;

    private void recordCall(ModelCallRecord record) {
        if (recordSink != null) { recordSink.accept(record); return; }
        try { if (usageLedger != null) usageLedger.record(record); }
        finally { if (callTraces != null) callTraces.record(record); }
    }

    @Autowired
    public ProviderRouter(AgentConfig cfg, ResolvedProviderRegistry providerRegistry, HarnexMetrics metrics,
        ProviderFactory providerFactory) {
        this.cfg = cfg;
        this.providerRegistry = providerRegistry;
        this.metrics = metrics;
        this.providerFactory = providerFactory;
    }

    ProviderRouter(AgentConfig cfg, ResolvedProviderRegistry providerRegistry, HarnexMetrics metrics,
        OutboundProxyResolver proxyResolver) {
        this(cfg, providerRegistry, metrics, new ProviderFactory(cfg, metrics, proxyResolver));
    }

    public Map<String, Double> healthSnapshot() {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) { return scope.router().healthSnapshot(); }
        return health == null ? Map.of() : health.snapshot();
    }

    public RuntimeConfiguration.Scope openConfigurationScope() {
        return liveRuntime == null ? RuntimeConfiguration.Scope.empty() : liveRuntime.acquire();
    }

    @PreDestroy
    public void close() {
        for (ModelProvider provider : byName.values()) {
            if (provider instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.warn("Provider {} close failed: {}", provider.getName(), e.getMessage());
                }
            }
        }
    }

    @PostConstruct
    public void init() {
        if (liveRuntime != null) return;
        initializeWith(providerFactory::create);
    }

    void initializeWith(Function<ResolvedProvider, ModelProvider> factory) {
        for (ResolvedProvider configured : providerRegistry.providers()) {
            if (configured.getName() == null) continue;
            String base = ProviderFactory.resolveEnv(configured.getBaseUrl());
            String type = ProviderFactory.normaliseProviderType(configured.getType());
            if ((base == null || base.isBlank()) && !Set.of("anthropic", "openai-responses", "gemini").contains(type)) {
                log.warn("Provider '{}' has no baseUrl; skipping", configured.getName());
                continue;
            }
            if (isBareSecret(configured.getApiKey())) {
                log.warn("Provider '{}' api-key appears hard-coded — consider using an environment reference",
                    configured.getName());
            }
            ModelProvider replacement = factory.apply(configured);
            ModelProvider previous = byName.put(configured.getName(), replacement);
            if (previous != null && previous instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.warn("Failed to close replaced provider '{}': {}", configured.getName(), e.getMessage());
                }
            }
        }

        String configuredDefault = cfg.getRouter().getDefaultProvider();
        if (configuredDefault != null && !configuredDefault.isBlank()) {
            defaultProvider = configuredProvider(configuredDefault);
            if (defaultProvider == null) {
                throw new IllegalArgumentException("Invalid harnex configuration: router.default-provider references "
                    + "disabled or unknown provider '" + configuredDefault + "'");
            }
            if (FALLBACK_PROVIDER.equals(defaultProvider.getName())) {
                throw new IllegalArgumentException("Invalid harnex configuration: reserved provider 'fallback' cannot "
                    + "be router.default-provider");
            }
        } else {
            defaultProvider = byName.values().stream()
                .filter(provider -> !FALLBACK_PROVIDER.equals(provider.getName()))
                .findFirst().orElse(null);
        }

        AgentConfig.Router router = cfg.getRouter();
        health = new ProviderHealth(router.isEwmaEnabled(), router.isCircuitBreakerEnabled(), router.getEwmaAlpha(),
            router.getEwmaDegradeThreshold(), router.getCircuitFailureThreshold(), router.getCircuitOpenMs());
        log.info("ProviderRouter initialised: default={}, fallback={}, catalogue={}", safeName(defaultProvider),
            configuredProvider(FALLBACK_PROVIDER) != null,
            byName.values().stream().map(ModelProvider::getName).toList());
    }

    public List<String> listAvailableProviderNames() {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) { return scope.router().listAvailableProviderNames(); }
        return byName.values().stream().map(ModelProvider::getName).toList();
    }

    public Optional<String> defaultProviderName() {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) { return scope.router().defaultProviderName(); }
        return Optional.ofNullable(defaultProvider).map(ModelProvider::getName);
    }

    public Optional<SummaryTargets.Target> summaryTarget(String provider, String model) {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) {
            return scope.router().summaryTarget(provider, model);
        }
        return SummaryTargets.resolve(providerRegistry, defaultProviderName().orElse(null), provider, model);
    }

    /** Only prefix-bound Claude models participate; the adapter still verifies the actual endpoint capability. */
    public boolean canCompactNatively(String provider, String model) {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) {
            return scope.router().canCompactNatively(provider, model);
        }
        ResolvedProvider configured = providerRegistry.find(provider).orElse(null);
        String selected = model == null || model.isBlank() ? configured == null ? null : configured.getDefaultModel() : model;
        return configured != null && configuredProvider(provider) instanceof AnthropicProvider anthropic
            && anthropic.canCompactNatively(selected);
    }

    public boolean thinkingPrefixBound(String provider, String model) {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) {
            return scope.router().thinkingPrefixBound(provider, model);
        }
        ResolvedProvider configured = providerRegistry.find(provider).orElse(null);
        if (configured == null) return false;
        String selected = model == null || model.isBlank() ? configured.getDefaultModel() : model;
        return ProviderModelCapabilities.from(cfg, configured).forModel(selected).thinkingPrefixBound();
    }

    /** Non-billable capability discovery, performed only when an actual compaction is due. */
    public boolean confirmNativeCompaction(String provider, String model, long timeoutMs) {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) {
            return scope.router().confirmNativeCompaction(provider, model, timeoutMs);
        }
        return canCompactNatively(provider, model) && configuredProvider(provider) instanceof AnthropicProvider anthropic
            && anthropic.supportsCompaction(model, timeoutMs);
    }

    /** The same model/output capacity used by request planning; 0 when no target is configured. */
    public int inputBudget(String providerName, String model, Integer outputOverride) {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) {
            return scope.router().inputBudget(providerName, model, outputOverride);
        }
        String name = providerName == null || providerName.isBlank()
            ? defaultProviderName().orElse(null) : providerName;
        ResolvedProvider provider = providerRegistry.find(name).orElse(null);
        if (provider == null) return 0;
        return ProviderContextBudget.resolve(cfg, provider, model, outputOverride).inputTokens();
    }

    /** Raw summary-model capacity, without reserving a possibly oversized interactive output default. */
    public SummaryBudget.ModelCapacity summaryCapacity(String providerName, String model) {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) {
            return scope.router().summaryCapacity(providerName, model);
        }
        String name = providerName == null || providerName.isBlank() ? defaultProviderName().orElse(null) : providerName;
        ResolvedProvider provider = providerRegistry.find(name).orElse(null);
        if (provider == null) return null;
        String selected = model == null || model.isBlank() ? provider.getDefaultModel() : model;
        var budget = ProviderContextBudget.resolve(cfg, provider, selected, 1);
        var definition = provider.getModels().stream().filter(m -> m.getId().equalsIgnoreCase(selected)).findFirst().orElse(null);
        var profile = ProviderModelCapabilities.from(cfg, provider).forModel(selected);
        boolean reasoning = profile.reasoningSupported() || profile.thinking().supported()
            && profile.thinking().defaultState() != ProviderModelCapabilities.ThinkingState.OFF;
        if ("none".equalsIgnoreCase(provider.getEffort()) || "off".equalsIgnoreCase(provider.getThinking())) reasoning = false;
        int window = profile.maxInputTokens() == null ? budget.contextWindow()
            : Math.min(budget.contextWindow(), profile.maxInputTokens() + budget.safetyReserve());
        return new SummaryBudget.ModelCapacity(window, budget.safetyReserve(),
            definition == null ? null : definition.getMaxOutputTokens(), reasoning);
    }

    /** Enabled reserved fallback provider id, exposed for non-Chat utility routing such as history summaries. */
    public Optional<String> fallbackProviderName() {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) { return scope.router().fallbackProviderName(); }
        ModelProvider fallback = configuredProvider(FALLBACK_PROVIDER);
        return fallback == null ? Optional.empty() : Optional.ofNullable(fallback.getName());
    }

    private ModelProvider configuredProvider(String name) {
        if (name == null || name.isBlank()) return null;
        ModelProvider exact = byName.get(name);
        if (exact != null) return exact;
        return byName.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name.trim()))
            .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    static Set<String> imageInputModels(ResolvedProvider provider) {
        return ProviderFactory.modelsWithInputModality(provider, "image");
    }

    static Set<String> modelsWithInputModality(ResolvedProvider provider, String modality) {
        return ProviderFactory.modelsWithInputModality(provider, modality);
    }

    /**
     * Execute Chat as selected/default target -> optional reserved fallback. Same-target retries are folded into the
     * eventual provider_switched event so the UI can show one stable card with the actual attempt count.
     */
    public void streamChat(ModelProvider.ChatRequest request, Consumer<StreamEvent> onEvent) {
        if (liveRuntime != null) {
            try (var scope = liveRuntime.acquire()) {
                scope.router().recordSink = this::recordCall;
                scope.router().streamChat(request, onEvent);
            }
            return;
        }
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onEvent, "onEvent");
        ModelCallTrace trace = new ModelCallTrace(request.getProvider(), request.getModel(), null, onEvent,
            this::recordCall);
        try {
            routeChat(request, trace::event, trace);
            trace.finish(null);
        } catch (RuntimeException failure) {
            trace.finish(failure);
            throw failure;
        }
    }

    private void routeChat(ModelProvider.ChatRequest request, Consumer<StreamEvent> onEvent, ModelCallTrace trace) {
        ModelProvider selected = request.getProvider() == null || request.getProvider().isBlank()
            ? defaultProvider : configuredProvider(request.getProvider());
        if (selected == null) {
            if (request.getProvider() != null && !request.getProvider().isBlank()) {
                throw new ProviderException("config", "Preferred provider '" + request.getProvider()
                    + "' is not enabled");
            }
            throw new ProviderException("config", "No default provider is configured and enabled");
        }

        String originalModel = request.getModel();
        String originalEffort = request.getReasoningEffort();
        FallbackDecision fallback = automaticFallbackFor(selected, request);
        long budgetMs = cfg.getRouter().getTotalBudgetMs();
        long deadlineNanos = budgetMs > 0
            ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs) : Long.MAX_VALUE;
        String selectedModel = effectiveModel(selected, originalModel);
        String selectedHealthKey = healthKey(selected, selectedModel);
        ProviderHealth.Admission selectedAdmission = health.acquire(selectedHealthKey);

        try {
            if (selectedAdmission == ProviderHealth.Admission.OPEN) {
                if (fallback.plan() == null) {
                    emitFallbackUnavailable(onEvent, selected, fallback.reason());
                    throw circuitOpen(selected, selectedModel, fallback.reason());
                }
                ProviderHealth.Admission fallbackAdmission = health.acquire(fallback.plan().healthKey());
                if (fallbackAdmission == ProviderHealth.Admission.OPEN) {
                    throw circuitOpen(selected, selectedModel, "fallback circuit is also open");
                }
                emitProviderSwitch(onEvent, selected, fallback.plan(), "circuit_open", 0, "OPEN");
                request.setModel(fallback.plan().model());
                request.setReasoningEffort(fallback.plan().reasoningEffort());
                int attempts = fallbackAdmission == ProviderHealth.Admission.HALF_OPEN ? 1 : selectedAttempts();
                AttemptOutcome outcome = executeTarget(fallback.plan().provider(), request, onEvent, attempts,
                    fallback.plan().healthKey(), fallbackAdmission, deadlineNanos, budgetMs, trace);
                if (!outcome.success()) throw outcome.failure();
                return;
            }

            request.setModel(originalModel);
            request.setReasoningEffort(originalEffort);
            int attempts = selectedAdmission == ProviderHealth.Admission.HALF_OPEN ? 1 : selectedAttempts();
            AttemptOutcome selectedOutcome = executeTarget(selected, request, onEvent, attempts, selectedHealthKey,
                selectedAdmission, deadlineNanos, budgetMs, trace);
            if (selectedOutcome.success()) return;

            if (fallback.plan() == null) {
                emitFallbackUnavailable(onEvent, selected, fallback.reason());
                throw selectedOutcome.failure();
            }
            sleepBackoff(selectedOutcome.attempts(), deadlineNanos);
            ProviderHealth.Admission fallbackAdmission = health.acquire(fallback.plan().healthKey());
            if (fallbackAdmission == ProviderHealth.Admission.OPEN) {
                emitFallbackUnavailable(onEvent, selected, "fallback circuit is open");
                throw selectedOutcome.failure();
            }
            emitProviderSwitch(onEvent, selected, fallback.plan(), selectedOutcome.failure().code,
                selectedOutcome.attempts(),
                fallbackAdmission.name());
            request.setModel(fallback.plan().model());
            request.setReasoningEffort(fallback.plan().reasoningEffort());
            AttemptOutcome fallbackOutcome = executeTarget(fallback.plan().provider(), request, onEvent, 1,
                fallback.plan().healthKey(), fallbackAdmission, deadlineNanos, budgetMs, trace);
            if (!fallbackOutcome.success()) throw fallbackOutcome.failure();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderException("cancelled", "Provider retry interrupted");
        } finally {
            request.setModel(originalModel);
            request.setReasoningEffort(originalEffort);
        }
    }

    private AttemptOutcome executeTarget(ModelProvider provider, ModelProvider.ChatRequest request,
        Consumer<StreamEvent> onEvent, int maxAttempts, String healthKey, ProviderHealth.Admission admission,
        long deadlineNanos, long budgetMs, ModelCallTrace trace) throws InterruptedException {
        ProviderException last = null;
        int executedAttempts = 0;
        for (int index = 0; index < maxAttempts; index++) {
            executedAttempts = index + 1;
            long remainingTtftMs;
            try {
                if (index > 0) sleepBackoff(index, deadlineNanos);
                remainingTtftMs = remainingBudget(deadlineNanos, budgetMs, last);
                checkCancelled();
            } catch (InterruptedException | RuntimeException stopped) {
                if (admission == ProviderHealth.Admission.HALF_OPEN) health.abandonProbe(healthKey);
                throw stopped;
            }
            AtomicBoolean emitted = new AtomicBoolean(false);
            Consumer<StreamEvent> guarded = event -> {
                if (Set.of("message_start", "text_delta", "thinking_delta", "message_end", "tool_call_delta").contains(event.getType())) {
                    emitted.set(true);
                }
                onEvent.accept(event);
            };
            trace.beginAttempt(provider.getName(), effectiveModel(provider, request.getModel()),
                providerRegistry.find(provider.getName()).map(ResolvedProvider::getType).orElse("unknown"));
            try (var callScope = trace.callScope(); var observation = ProviderCallScope.observe(trace::exchange)) {
                ProviderCallScope.validateTarget(provider.getName(),effectiveModel(provider,request.getModel()));
                request.setContextPlanId(null);
                request.setNativeWebSearchActive(false);
                try {
                    if (request.getTargetPreparation() != null) request.getTargetPreparation().prepare(provider.getName(), effectiveModel(provider, request.getModel()), request);
                } finally { trace.contextPlan(request.getContextPlanId()); }
                trace.dispatching();
                provider.streamChat(request, guarded, remainingTtftMs);
                trace.finishAttempt(null);
                health.recordSuccess(healthKey);
                return AttemptOutcome.ok(executedAttempts);
            } catch (ProviderException failure) {
                if (emitted.get() && failure.safeToRetry()) {
                    failure = new ProviderException(failure.code, failure.getMessage(),
                        HttpExchangeState.Acceptance.ACCEPTED, failure.upstreamRequestId, failure.retryAfterMillis);
                }
                trace.finishAttempt(failure);
                boolean transientFailure = isFailoverCode(failure.code);
                if (transientFailure) health.recordFailure(healthKey);
                if ("429".equals(failure.code)) health.recordRateLimit(healthKey, failure.retryAfterMillis);
                if (!transientFailure || !failure.safeToRetry() || emitted.get()) {
                    if (!transientFailure || admission == ProviderHealth.Admission.HALF_OPEN) {
                        health.abandonProbe(healthKey);
                    }
                    throw failure;
                }
                last = failure;
                log.warn("Provider {} target {} failed ({}) on attempt {}/{}: {}", provider.getName(),
                    effectiveModel(provider, request.getModel()), failure.code, index + 1, maxAttempts,
                    failure.getMessage());
                long cooldown = health.cooldownRemainingMillis(healthKey);
                long available = deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE
                    : Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
                if (cooldown > 0 && index + 1 < maxAttempts && cooldown + 100 < available) {
                    sleepCancellable(cooldown + 1);
                    ProviderHealth.Admission next = health.acquire(healthKey);
                    if (next == ProviderHealth.Admission.OPEN) break;
                    // Only one request wins the post-cooldown probe; it receives one attempt, never another burst.
                    admission = next;
                    if (next == ProviderHealth.Admission.HALF_OPEN) maxAttempts = index + 2;
                    continue;
                }
                // A configurable threshold smaller than the retry budget takes effect immediately instead of allowing
                // the current burst to stampede through the remaining retries.
                if (health.isOpen(healthKey)) break;
            } catch (RuntimeException unexpected) {
                trace.finishAttempt(new ProviderException("unexpected", unexpected.getMessage()));
                health.abandonProbe(healthKey);
                throw unexpected;
            }
        }
        return AttemptOutcome.failed(last == null
            ? new ProviderException("network", "Provider target failed without a classified response") : last,
            executedAttempts);
    }

    private static long remainingBudget(long deadlineNanos, long budgetMs, RuntimeException last) {
        if (deadlineNanos == Long.MAX_VALUE) return 0L;
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remaining <= 0) {
            if (last != null) throw last;
            throw new ProviderException("timeout", "Provider routing budget exhausted (" + budgetMs + "ms)");
        }
        return remaining;
    }

    private int selectedAttempts() {
        return Math.max(1, Math.min(10, cfg.getRouter().getSelectedProviderMaxAttempts()));
    }

    private FallbackDecision automaticFallbackFor(ModelProvider selected, ModelProvider.ChatRequest request) {
        ModelProvider fallback = configuredProvider(FALLBACK_PROVIDER);
        if (fallback == null || fallback == selected) return new FallbackDecision(null, "fallback is disabled");
        String fallbackModel = supportsModel(fallback, request.getModel())
            ? request.getModel() : fallback.getDefaultModel();
        if (fallbackModel == null || fallbackModel.isBlank()) {
            return new FallbackDecision(null, "fallback has no default model");
        }
        ResolvedProvider resolved = providerRegistry.find(fallback.getName()).orElse(null);
        if (resolved == null) return new FallbackDecision(null, "fallback is not in the runtime registry");
        ProviderModelCapabilities.Profile profile = ProviderModelCapabilities.from(cfg, resolved).forModel(fallbackModel);
        if (request.getTools() != null && !request.getTools().isEmpty() && !profile.functionTools()) {
            return new FallbackDecision(null, "fallback model does not support function tools");
        }
        for (String modality : requiredModalities(request.getMessages())) {
            if (!profile.supportsModality(modality)) {
                return new FallbackDecision(null, "fallback model does not support " + modality + " input");
            }
        }
        String fallbackEffort = supportsModel(fallback, request.getModel()) ? request.getReasoningEffort() : null;
        if (fallbackEffort != null && !fallbackEffort.isBlank()
            && (!profile.reasoningEffortsDeclared() || !profile.acceptsEffort(fallbackEffort))) {
            return new FallbackDecision(null, "fallback model does not support reasoning effort " + fallbackEffort);
        }
        return new FallbackDecision(new FallbackPlan(fallback, fallbackModel, fallbackEffort,
            healthKey(fallback, fallbackModel)), "");
    }

    private static Set<String> requiredModalities(List<ChatMessage> messages) {
        LinkedHashSet<String> modalities = new LinkedHashSet<>();
        if (messages == null) return modalities;
        for (ChatMessage message : messages) {
            if (message == null || message.getAttachments() == null) continue;
            for (MessageAttachment attachment : message.getAttachments()) {
                if (attachment == null) continue;
                String mime = attachment.getMime() == null ? "" : attachment.getMime().toLowerCase(Locale.ROOT);
                if (mime.startsWith("image/")) modalities.add("image");
                else if (mime.equals("application/pdf")) modalities.add("pdf");
                else if (mime.startsWith("audio/")) modalities.add("audio");
                else if (mime.startsWith("video/")) modalities.add("video");
                else modalities.add("file");
            }
        }
        return modalities;
    }

    private void emitProviderSwitch(Consumer<StreamEvent> onEvent, ModelProvider from, FallbackPlan to,
        String reason, int selectedAttempts, String circuitState) {
        metrics.providerFailovers.increment();
        try {
            onEvent.accept(StreamEvent.of("provider_switched", Map.of(
                "from", safeName(from), "to", to.provider().getName(), "reason", reason == null ? "" : reason,
                "selectedAttempts", selectedAttempts, "circuitState", circuitState, "actualModel", to.model(),
                "actualProvider", to.provider().getName())));
        } catch (Throwable ignored) {
            // Observability must not break the model request.
        }
    }

    private static void emitFallbackUnavailable(Consumer<StreamEvent> onEvent, ModelProvider selected, String reason) {
        if (reason == null || reason.isBlank() || "fallback is disabled".equals(reason)) return;
        try {
            onEvent.accept(StreamEvent.of("notice", Map.of("kind", "fallback_unavailable",
                "provider", safeName(selected), "reason", reason)));
        } catch (Throwable ignored) {
            // Observability must not break the model request.
        }
    }

    private static ProviderException circuitOpen(ModelProvider provider, String model, String detail) {
        return new ProviderException("circuit_open", "Circuit is open for " + safeName(provider) + "/" + model
            + (detail == null || detail.isBlank() ? "" : "; " + detail));
    }

    private static String healthKey(ModelProvider provider, String model) {
        return safeName(provider) + "/" + (model == null ? "" : model.trim());
    }

    private static String effectiveModel(ModelProvider provider, String requested) {
        return requested == null || requested.isBlank() ? provider.getDefaultModel() : requested;
    }

    private static long sleepBackoff(int attempt, long deadlineNanos) throws InterruptedException {
        long base = Math.min(2_000L, 100L * (1L << Math.min(Math.max(0, attempt - 1), 5)));
        long want = base + (long)(base * 0.1 * Math.random());
        if (deadlineNanos != Long.MAX_VALUE) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) return 0L;
            want = Math.min(want, remainingMs);
        }
        sleepCancellable(want);
        return want;
    }

    private static void checkCancelled() {
        ProviderCallScope.Context context = ProviderCallScope.current();
        if (Thread.currentThread().isInterrupted() || context != null && context.isCancelled()) {
            throw new ProviderException("cancelled", "Provider call cancelled");
        }
    }

    private static void sleepCancellable(long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) {
            checkCancelled();
            Thread.sleep(Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()))));
        }
        checkCancelled();
    }

    private boolean supportsModel(ModelProvider provider, String model) {
        if (provider == null || model == null || model.isBlank()) return false;
        return providerRegistry.find(provider.getName()).stream()
            .flatMap(configured -> configured.getModels().stream())
            .filter(Objects::nonNull)
            .map(AgentConfig.ModelDef::getId)
            .filter(Objects::nonNull)
            .map(ProviderFactory::resolveEnv)
            .anyMatch(id -> id != null && id.trim().equalsIgnoreCase(model.trim()));
    }

    /** Execute a side-channel task on one explicit provider, or on router.default-provider when blank. No failover. */
    public OneShotResult oneShotOn(String providerName, String model, List<ChatMessage> messages, Float temperature) {
        return oneShotOn(providerName, model, messages, temperature, 0L);
    }

    public OneShotResult oneShotOn(String providerName, String model, List<ChatMessage> messages, Float temperature,
        long timeoutMs) {
        return oneShotOn(providerName, model, messages, temperature, timeoutMs, "");
    }

    /**
     * Namespaced one-shot health. Utility failures must not open the normal Chat circuit for the same provider/model.
     */
    public OneShotResult oneShotOn(String providerName, String model, List<ChatMessage> messages, Float temperature,
        long timeoutMs, String healthNamespace) {
        return oneShotOn(providerName, model, messages, temperature, timeoutMs, null, healthNamespace);
    }

    /** Execute a namespaced utility request with a request-specific output-token ceiling. */
    public OneShotResult oneShotOn(String providerName, String model, List<ChatMessage> messages, Float temperature,
        long timeoutMs, Integer maxOutputTokens, String healthNamespace) {
        return oneShotOn(providerName, model, messages, temperature, timeoutMs, maxOutputTokens, healthNamespace, null);
    }

    public OneShotResult oneShotRequestOn(String providerName, String model, ModelProvider.ChatRequest request,
        long timeoutMs, String healthNamespace) {
        var utility = request.toBuilder().provider(providerName).model(model)
            .webSearchAllowed(request.isNativeCompaction() && request.isWebSearchAllowed())
            .targetPreparation(null).idempotencyKey(null).build();
        return oneShotOn(providerName, model, utility.getMessages(), utility.getTemperature(), timeoutMs,
            utility.getMaxTokens(), healthNamespace, utility);
    }

    private OneShotResult oneShotOn(String providerName, String model, List<ChatMessage> messages, Float temperature,
        long timeoutMs, Integer maxOutputTokens, String healthNamespace, ModelProvider.ChatRequest request) {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) {
            scope.router().recordSink = this::recordCall;
            return scope.router().oneShotOn(providerName, model, messages, temperature, timeoutMs, maxOutputTokens, healthNamespace, request);
        }
        ProviderCallScope.Context owner = ProviderCallScope.current();
        ModelCallTrace trace = new ModelCallTrace(providerName, model,
            healthNamespace == null || healthNamespace.isBlank() ? "utility" : healthNamespace,
            owner == null ? ignored -> {} : owner.events(), this::recordCall);
        // All one-shot calls are internal utility work. They inherit run accounting/cancellation, but never the main
        // interactive request's hosted-tool permission; summaries, titles and aggregations must not browse the web.
        try (var ignored = ProviderCallScope.suppressNativeTools()) {
            OneShotResult result = oneShotTarget(providerName, model, messages, temperature, timeoutMs, maxOutputTokens, healthNamespace, trace, request);
            trace.finish(null);
            return result;
        } catch (RuntimeException failure) {
            trace.finish(failure);
            throw failure;
        }
    }

    private OneShotResult oneShotTarget(String providerName, String model, List<ChatMessage> messages, Float temperature,
        long timeoutMs, Integer maxOutputTokens, String healthNamespace, ModelCallTrace trace, ModelProvider.ChatRequest request) {
        checkCancelled();
        ModelProvider provider = providerName == null || providerName.isBlank()
            ? defaultProvider : configuredProvider(providerName);
        if (provider == null) {
            throw new ProviderException("config", providerName == null || providerName.isBlank()
                ? "No default provider is configured for one-shot task"
                : "Provider '" + providerName + "' is not configured for one-shot task");
        }
        String targetModel = effectiveModel(provider, model);
        String baseTarget = healthKey(provider, targetModel);
        String target = healthNamespace == null || healthNamespace.isBlank() ? baseTarget
            : healthNamespace.strip() + "/" + baseTarget;
        ProviderHealth.Admission admission = health.acquire(target);
        if (admission == ProviderHealth.Admission.OPEN) throw circuitOpen(provider, targetModel, null);
        trace.beginAttempt(provider.getName(), targetModel, providerRegistry.find(provider.getName()).map(ResolvedProvider::getType).orElse("unknown"));
        try (var callScope = trace.callScope(); var observation = ProviderCallScope.observe(trace::exchange)) {
            ProviderCallScope.validateTarget(provider.getName(),targetModel);
            trace.dispatching();
            OneShotResult result = request == null ? provider.oneShot(model, messages, temperature, timeoutMs, maxOutputTokens)
                : request.isNativeCompaction() ? provider.compact(request, timeoutMs) : provider.oneShot(request, timeoutMs);
            trace.usage(result.usage());
            trace.finishAttempt(null);
            health.recordSuccess(target);
            return result;
        } catch (ProviderException failure) {
            trace.finishAttempt(failure);
            if (isFailoverCode(failure.code)) health.recordFailure(target);
            else health.abandonProbe(target);
            if ("429".equals(failure.code)) health.recordRateLimit(target, failure.retryAfterMillis);
            throw failure;
        } catch (RuntimeException unexpected) {
            trace.finishAttempt(new ProviderException("unexpected", unexpected.getMessage()));
            health.abandonProbe(target);
            throw unexpected;
        }
    }

    private boolean isFailoverCode(String code) {
        List<String> configured = cfg.getRouter().getFailoverOn();
        return configured == null || configured.isEmpty()
            ? DEFAULT_FAILOVER_CODES.contains(code)
            : configured.stream().anyMatch(code::equals);
    }

    public static boolean isBareSecret(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        return !raw.trim().startsWith("${");
    }

    public List<String> bareKeyProviders() {
        if (liveRuntime != null) try (var scope = liveRuntime.acquire()) { return scope.router().bareKeyProviders(); }
        List<String> bad = new ArrayList<>();
        for (ResolvedProvider provider : providerRegistry.providers()) {
            if (provider.getName() != null && isBareSecret(provider.getApiKey())) bad.add(provider.getName());
        }
        return bad;
    }

    private static String safeName(ModelProvider provider) {
        return provider == null || provider.getName() == null ? "" : provider.getName();
    }

    private record FallbackPlan(ModelProvider provider, String model, String reasoningEffort, String healthKey) {}
    private record FallbackDecision(FallbackPlan plan, String reason) {}
    private record AttemptOutcome(boolean success, ProviderException failure, int attempts) {
        private static AttemptOutcome ok(int attempts) { return new AttemptOutcome(true, null, attempts); }
        private static AttemptOutcome failed(ProviderException failure, int attempts) {
            return new AttemptOutcome(false, failure, attempts);
        }
    }
}
