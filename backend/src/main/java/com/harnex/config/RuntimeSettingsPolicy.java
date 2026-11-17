package com.harnex.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.harnex.run.IdempotencyReceiptStore;

/** One policy table for persistence, publication, dependency deferral, and the settings schema. */
public final class RuntimeSettingsPolicy {
    public enum ApplyMode { NEW_TASK, RECONNECT }
    public record Section(String name, ApplyMode mode, boolean editable, Function<AgentConfig, Object> value,
        BiConsumer<AgentConfig, AgentConfig> copy) {}

    private static Section live(String name, Function<AgentConfig, Object> value, BiConsumer<AgentConfig, AgentConfig> copy) {
        return new Section(name, ApplyMode.NEW_TASK, true, value, copy);
    }
    private static Section connection(String name, Function<AgentConfig, Object> value, BiConsumer<AgentConfig, AgentConfig> copy) {
        return new Section(name, ApplyMode.RECONNECT, true, value, copy);
    }
    public static final List<Section> SECTIONS = List.of(
        live("providers", AgentConfig::getProviders, (to, from) -> to.setProviders(from.getProviders())),
        live("router", AgentConfig::getRouter, (to, from) -> to.setRouter(from.getRouter())),
        live("summarizer", AgentConfig::getSummarizer, (to, from) -> to.setSummarizer(from.getSummarizer())),
        live("safety", AgentConfig::getSafety, (to, from) -> to.setSafety(from.getSafety())),
        live("workspace", c -> Map.of("outside-write-policy", c.getWorkspace().getOutsideWritePolicy()),
            (to, from) -> to.getWorkspace().setOutsideWritePolicy(from.getWorkspace().getOutsideWritePolicy())),
        live("toolsets", AgentConfig::getToolsets, (to, from) -> to.setToolsets(from.getToolsets())),
        live("hooks", AgentConfig::getHooks, (to, from) -> to.setHooks(from.getHooks())),
        live("web-search", AgentConfig::getWebSearch, (to, from) -> to.setWebSearch(from.getWebSearch())),
        live("image-services", AgentConfig::getImageServices, (to, from) -> to.setImageServices(from.getImageServices())),
        live("default-image-service", AgentConfig::getDefaultImageService, (to, from) -> to.setDefaultImageService(from.getDefaultImageService())),
        new Section("provider-profiles", ApplyMode.NEW_TASK, false, AgentConfig::getProviderProfiles,
            (to, from) -> to.setProviderProfiles(from.getProviderProfiles())),
        connection("http", AgentConfig::getHttp, (to, from) -> to.setHttp(from.getHttp())),
        connection("mcp", AgentConfig::getMcp, (to, from) -> to.setMcp(from.getMcp())),
        live("execution", AgentConfig::getExecution, (to, from) -> to.setExecution(from.getExecution()))
    );
    public static final Set<String> EDITABLE = SECTIONS.stream().filter(Section::editable)
        .map(Section::name).collect(Collectors.toUnmodifiableSet());

    private RuntimeSettingsPolicy() {}

    public static String reloadPolicy(String section) {
        return SECTIONS.stream().filter(item -> item.name().equals(section)).findFirst()
            .map(item -> item.mode() == ApplyMode.NEW_TASK ? "live" : "restart").orElse("restart");
    }

    public static Plan stage(AgentConfig startup, AgentConfig current, AgentConfig requested, Set<String> credentialDeferrals) {
        Set<String> reconnect = new LinkedHashSet<>();
        for (Section section : SECTIONS) if (section.mode() == ApplyMode.RECONNECT
            && !Objects.equals(section.value().apply(startup), section.value().apply(requested))) reconnect.add(section.name());
        Set<String> deferred = new LinkedHashSet<>(credentialDeferrals);
        if (reconnect.contains("http")) {
            deferred.addAll(List.of("providers", "provider-profiles", "router", "summarizer", "web-search", "image-services", "default-image-service"));
        }
        if (deferred.contains("providers")) deferred.addAll(List.of("provider-profiles", "router", "summarizer"));
        if (deferred.contains("image-services")) deferred.add("default-image-service");
        AgentConfig next = RuntimeConfiguration.copyConfiguration(current);
        for (Section section : SECTIONS) if (section.mode() == ApplyMode.NEW_TASK && !deferred.contains(section.name())) {
            section.copy().accept(next, requested);
        }
        return new Plan(next, Set.copyOf(reconnect), Set.copyOf(deferred));
    }

    public static String fingerprint(AgentConfig configuration) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Section section : SECTIONS) values.put(section.name(), section.value().apply(configuration));
        // With no explicit default, both catalogues choose the first enabled entry; map order is meaningful.
        values.put("provider-order", List.copyOf(configuration.getProviders().keySet()));
        values.put("image-service-order", List.copyOf(configuration.getImageServices().keySet()));
        return IdempotencyReceiptStore.requestHash(values);
    }

    public record Plan(AgentConfig configuration, Set<String> reconnectSections, Set<String> deferredSections) {
        public boolean restartRequired() { return !reconnectSections.isEmpty() || !deferredSections.isEmpty(); }
    }
}
