package com.harnex.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.harnex.config.AgentConfig;
import com.harnex.observability.HarnexMetrics;
import com.harnex.model.ChatMessage;
import com.harnex.model.StreamEvent;
import com.harnex.net.OutboundProxyResolver;
import com.harnex.run.IdempotencyReceiptStore;

import lombok.extern.slf4j.Slf4j;

/** Provider resources for a settings snapshot. Unchanged clients and routing state remain warm across versions. */
@Slf4j
public final class ProviderRuntime implements AutoCloseable {
    private final ResolvedProviderRegistry registry;
    private final ProviderRouter router;
    private final Map<String, SharedClient> clients;
    private final String fingerprint;
    private final AtomicInteger owners = new AtomicInteger(1);

    private ProviderRuntime(ResolvedProviderRegistry registry, ProviderRouter router, Map<String, SharedClient> clients, String fingerprint) {
        this.registry = registry; this.router = router; this.clients = Map.copyOf(clients); this.fingerprint = fingerprint;
    }

    public static ProviderRuntime create(AgentConfig config, ProviderConfigurationResolver resolver, HarnexMetrics metrics,
        ProviderRuntime previous) {
        var resolution = resolver.resolve(config);
        String fingerprint = IdempotencyReceiptStore.requestHash(Map.of("providers", resolution.providers(),
            "profiles", config.getProviderProfiles(), "router", config.getRouter(), "http", config.getHttp(), "vision", config.getVision()));
        if (previous != null && previous.fingerprint.equals(fingerprint)) {
            previous.owners.incrementAndGet();
            return previous;
        }
        var registry = ResolvedProviderRegistry.fixed(resolution.providers(), resolution.diagnostics());
        var factory = new ProviderFactory(config, metrics, new OutboundProxyResolver(config));
        var router = new ProviderRouter(config, registry, metrics, factory);
        Map<String, SharedClient> clients = new LinkedHashMap<>();
        try {
            router.initializeWith(provider -> {
                String key = IdempotencyReceiptStore.requestHash(Map.of("provider", provider,
                    "profiles", config.getProviderProfiles(), "http", config.getHttp(), "vision", config.getVision()));
                String id = provider.getName().toLowerCase(Locale.ROOT);
                SharedClient existing = previous == null ? null : previous.clients.get(id);
                SharedClient shared = existing != null && existing.fingerprint.equals(key)
                    ? existing : new SharedClient(key, factory.create(provider));
                clients.put(id, shared);
                return shared.handle();
            });
            return new ProviderRuntime(registry, router, clients, fingerprint);
        } catch (RuntimeException failure) {
            router.close();
            throw failure;
        }
    }

    public ResolvedProviderRegistry registry() { return registry; }
    public ProviderRouter router() { return router; }
    @Override public void close() { if (owners.decrementAndGet() == 0) router.close(); }

    private static final class SharedClient {
        final String fingerprint;
        final ModelProvider provider;
        private int owners;
        SharedClient(String fingerprint, ModelProvider provider) { this.fingerprint = fingerprint; this.provider = provider; }
        synchronized Handle handle() { owners++; return new Handle(this); }
        void release() {
            synchronized (this) { if (--owners != 0) return; }
            if (provider instanceof AutoCloseable closeable) try { closeable.close(); }
            catch (Exception failure) { log.warn("Provider {} close failed ({})", provider.getName(), failure.getClass().getSimpleName()); }
        }
    }

    private static final class Handle implements ModelProvider, AutoCloseable {
        private final SharedClient shared;
        private final AtomicBoolean closed = new AtomicBoolean();
        Handle(SharedClient shared) { this.shared = shared; }
        @Override public String getName() { return shared.provider.getName(); }
        @Override public String getDefaultModel() { return shared.provider.getDefaultModel(); }
        @Override public void streamChat(ChatRequest request, Consumer<StreamEvent> events) { shared.provider.streamChat(request, events); }
        @Override public void streamChat(ChatRequest request, Consumer<StreamEvent> events, long budget) { shared.provider.streamChat(request, events, budget); }
        @Override public OneShotResult oneShot(String model, List<ChatMessage> messages, Float temperature) { return shared.provider.oneShot(model, messages, temperature); }
        @Override public OneShotResult oneShot(String model, List<ChatMessage> messages, Float temperature, long timeout) { return shared.provider.oneShot(model, messages, temperature, timeout); }
        @Override public OneShotResult oneShot(String model, List<ChatMessage> messages, Float temperature, long timeout, Integer tokens) { return shared.provider.oneShot(model, messages, temperature, timeout, tokens); }
        @Override public OneShotResult oneShot(ChatRequest request, long timeout) { return shared.provider.oneShot(request, timeout); }
        @Override public void close() { if (closed.compareAndSet(false, true)) shared.release(); }
    }
}
