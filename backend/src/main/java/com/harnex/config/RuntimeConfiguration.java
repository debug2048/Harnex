package com.harnex.config;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.observability.HarnexMetrics;
import com.harnex.provider.ProviderConfigurationResolver;
import com.harnex.provider.ProviderException;
import com.harnex.provider.ProviderRouter;
import com.harnex.provider.ProviderRuntime;
import com.harnex.provider.ResolvedProviderRegistry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/** A task pins one settings version. New versions publish atomically; retired resources drain after their last user. */
@Component
@Slf4j
public final class RuntimeConfiguration implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final ThreadLocal<Generation> BOUND = new ThreadLocal<>();
    private static final ThreadLocal<Generation> INHERITED = new ThreadLocal<>();
    private final AgentConfig binding;
    private final AgentConfig startup;
    private final ProviderConfigurationResolver resolver;
    private final HarnexMetrics metrics;
    private final ObjectProvider<ConfigControlService> controls;
    private volatile Generation current;
    private String observedRevision;
    private Set<String> reconnectSections = Set.of();
    private Set<String> deferredSections = Set.of();
    private String reloadError = "";
    private boolean lastFailureWasRead;
    private volatile boolean closed;

    public RuntimeConfiguration(AgentConfig binding, ProviderConfigurationResolver resolver, HarnexMetrics metrics,
        ObjectProvider<ConfigControlService> controls) {
        this.binding = binding;
        this.startup = copyConfiguration(binding);
        this.resolver = resolver;
        this.metrics = metrics;
        this.controls = controls;
    }

    @PostConstruct
    public void initialize() {
        current = build(copyConfiguration(startup), null);
        binding.attachRuntimeView(this);
    }

    /** Each process checks the shared user file at API/task boundaries; no host-specific broadcast is required. */
    public synchronized void refresh(boolean force) {
        if (closed) return;
        boolean read = false;
        try {
            var source = controls.getObject().runtimeSource(force ? null : observedRevision);
            read = true;
            if (source == null) {
                if (lastFailureWasRead) reloadError = "";
                lastFailureWasRead = false;
                return;
            }
            lastFailureWasRead = false;
            observedRevision = source.revision();
            var plan = RuntimeSettingsPolicy.stage(startup, current.config, source.configuration(), source.credentialDeferrals());
            reconnectSections = plan.reconnectSections();
            deferredSections = plan.deferredSections();
            reloadError = "";
            if (RuntimeSettingsPolicy.fingerprint(plan.configuration()).equals(current.fingerprint)) return;
            Generation next = build(plan.configuration(), current);
            Generation previous = current;
            current = next;
            previous.retire();
            log.info("Runtime settings applied for new tasks: revision={}, reconnect={}, deferred={}",
                source.revision(), reconnectSections, deferredSections);
        } catch (RuntimeException failure) {
            lastFailureWasRead = !read;
            if (reloadError.isEmpty()) log.warn("Runtime settings could not be applied ({})", failure.getClass().getSimpleName());
            reloadError = "Saved settings could not be applied; the previous configuration remains active.";
        }
    }

    public Scope acquire() {
        Generation inherited = bound();
        if (inherited != null && inherited.owner == this) return bind(inherited);
        refresh(false);
        while (true) {
            if (closed) throw new ProviderException("config", "Runtime configuration is closing");
            Generation selected = current;
            if (selected.retain()) return new Scope(selected);
        }
    }

    private static Generation bound() { Generation value = BOUND.get(); return value != null ? value : INHERITED.get(); }
    private static Scope bind(Generation generation) {
        if (!generation.retain()) throw new ProviderException("configuration_changed", "The task's configuration has been released");
        return new Scope(generation);
    }
    public static Supplier<InheritedScope> captureContext(boolean inherit) {
        Generation captured = inherit ? bound() : null;
        return () -> new InheritedScope(captured);
    }
    private Generation selected() {
        Generation generation = bound();
        return generation != null && generation.owner == this ? generation : current;
    }

    public AgentConfig configuration() { return selected().config; }
    public ResolvedProviderRegistry registry() { return selected().providers.registry(); }
    public AgentConfig publishedConfiguration() { return current.config; }
    public synchronized Map<String, Object> status() {
        boolean reconnect = !reconnectSections.isEmpty() || !deferredSections.isEmpty();
        return Map.of("configurationRevision", current.fingerprint, "restartRequired", reconnect,
            "reconnectSections", reconnectSections, "deferredSections", deferredSections,
            "reloadError", reloadError, "reloadPolicy", reconnect ? "mixed" : "live");
    }

    private Generation build(AgentConfig config, Generation previous) {
        config.validateConfiguration();
        config.getSummarizer().validate(log);
        String fingerprint = RuntimeSettingsPolicy.fingerprint(config);
        ProviderRuntime providers = ProviderRuntime.create(config, resolver, metrics, previous == null ? null : previous.providers);
        return new Generation(this, config, providers, fingerprint);
    }

    public static AgentConfig copyConfiguration(AgentConfig source) { return JSON.convertValue(source, AgentConfig.class); }

    @Override @PreDestroy
    public synchronized void close() {
        closed = true;
        if (current != null) current.retire();
        binding.closeDerivedSettings();
    }

    public static final class Scope implements AutoCloseable {
        private final Generation generation;
        private final Generation previous;
        private boolean closed;
        private Scope(Generation generation) {
            this.generation = generation;
            previous = BOUND.get();
            if (generation != null) BOUND.set(generation);
        }
        public static Scope empty() { return new Scope(null); }
        public ProviderRouter router() { return generation.providers.router(); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) BOUND.remove(); else BOUND.set(previous);
            if (generation != null) generation.release();
        }
    }

    /** Workers retain their inherited snapshot until completion, including cancellation cleanup. */
    public static final class InheritedScope implements AutoCloseable {
        private final Generation previous = INHERITED.get();
        private final Generation generation;
        private InheritedScope(Generation generation) {
            if (generation != null && !generation.retain()) throw new ProviderException("configuration_changed", "The task's configuration has been released");
            this.generation = generation;
            if (generation == null) INHERITED.remove(); else INHERITED.set(generation);
        }
        @Override public void close() {
            if (previous == null) INHERITED.remove(); else INHERITED.set(previous);
            if (generation != null) generation.release();
        }
    }

    private static final class Generation {
        final RuntimeConfiguration owner;
        final AgentConfig config;
        final ProviderRuntime providers;
        final String fingerprint;
        private int users;
        private boolean retired;
        private boolean closed;
        Generation(RuntimeConfiguration owner, AgentConfig config, ProviderRuntime providers, String fingerprint) {
            this.owner = owner; this.config = config; this.providers = providers; this.fingerprint = fingerprint;
        }
        synchronized boolean retain() { if (closed) return false; users++; return true; }
        void release() { synchronized (this) { users--; } drain(); }
        void retire() { synchronized (this) { retired = true; } drain(); }
        private void drain() {
            synchronized (this) {
                if (!retired || users != 0 || closed) return;
                closed = true;
            }
            config.closeDerivedSettings();
            providers.close();
        }
    }
}
