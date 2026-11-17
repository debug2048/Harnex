package com.harnex.provider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.RuntimeConfiguration;

import jakarta.annotation.PostConstruct;

/**
 * Atomic runtime view of resolved providers.
 *
 * <p>The live facade follows the configuration generation pinned to the current API operation or execution.
 * Standalone instances remain fixed snapshots for generation construction and component tests.</p>
 */
@Component
public final class ResolvedProviderRegistry {

    private final AgentConfig config;
    private final ProviderConfigurationResolver resolver;
    private volatile Snapshot snapshot = Snapshot.empty();
    @Autowired(required = false)
    private RuntimeConfiguration liveRuntime;

    @Autowired
    public ResolvedProviderRegistry(AgentConfig config, ProviderConfigurationResolver resolver) {
        this.config = config;
        this.resolver = resolver;
    }

    private ResolvedProviderRegistry(Collection<ResolvedProvider> providers) {
        config = null;
        resolver = null;
        snapshot = Snapshot.of(providers, List.of());
    }

    @PostConstruct
    void initialize() {
        if (liveRuntime != null) return;
        refresh();
    }

    /**
     * Re-resolve all providers and publish the new immutable snapshot only after every validation succeeds.
     *
     * <p>Live registries delegate publication and adapter lifetime to RuntimeConfiguration.</p>
     */
    synchronized void refresh() {
        if (liveRuntime != null) { liveRuntime.refresh(true); return; }
        if (config == null || resolver == null) {
            return;
        }
        ProviderConfigurationResolver.Resolution resolution = resolver.resolve(config);
        snapshot = Snapshot.of(resolution.providers(), resolution.diagnostics());
    }

    public List<ResolvedProvider> providers() {
        if (liveRuntime != null) return liveRuntime.registry().providers();
        return snapshot.providers();
    }

    public Optional<ResolvedProvider> find(String providerId) {
        if (liveRuntime != null) return liveRuntime.registry().find(providerId);
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.byId().get(providerId.trim().toLowerCase(Locale.ROOT)));
    }

    public List<ProviderConfigurationResolver.Diagnostic> diagnostics() {
        if (liveRuntime != null) return liveRuntime.registry().diagnostics();
        return snapshot.diagnostics();
    }

    /** Focused component-test factory; unlike a config setter, this cannot become a Spring binding surface. */
    public static ResolvedProviderRegistry forTesting(Collection<ResolvedProvider> providers) {
        return new ResolvedProviderRegistry(providers == null ? List.of() : providers);
    }

    static ResolvedProviderRegistry fixed(Collection<ResolvedProvider> providers,
        List<ProviderConfigurationResolver.Diagnostic> diagnostics) {
        var registry = new ResolvedProviderRegistry(providers);
        registry.snapshot = Snapshot.of(providers, diagnostics);
        return registry;
    }

    private record Snapshot(List<ResolvedProvider> providers, Map<String, ResolvedProvider> byId,
        List<ProviderConfigurationResolver.Diagnostic> diagnostics) {

        private static Snapshot empty() {
            return new Snapshot(List.of(), Map.of(), List.of());
        }

        private static Snapshot of(Collection<ResolvedProvider> configured,
            List<ProviderConfigurationResolver.Diagnostic> diagnostics) {
            List<ResolvedProvider> providers = configured == null ? List.of()
                : configured.stream().filter(Objects::nonNull).toList();
            Map<String, ResolvedProvider> byId = new LinkedHashMap<>();
            for (ResolvedProvider provider : providers) {
                if (provider.getName() != null && !provider.getName().isBlank()) {
                    byId.put(provider.getName().trim().toLowerCase(Locale.ROOT), provider);
                }
            }
            return new Snapshot(List.copyOf(providers), Map.copyOf(byId),
                diagnostics == null ? List.of() : List.copyOf(diagnostics));
        }
    }
}
