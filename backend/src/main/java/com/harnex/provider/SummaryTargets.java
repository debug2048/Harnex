package com.harnex.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/** Shared summary routing policy. Resolve default aliases before ordering or comparing targets. */
public final class SummaryTargets {
    private SummaryTargets() {}

    public record Target(String provider, String model) {
        public String key() { return SummaryTargets.key(provider, model); }
    }

    public static String key(String provider, String model) {
        return (provider == null ? "<default>" : provider.trim().toLowerCase(Locale.ROOT)) + "/"
            + (model == null ? "<default>" : model.trim().toLowerCase(Locale.ROOT));
    }

    public static Optional<Target> resolve(ResolvedProviderRegistry registry, String defaultProvider,
        String provider, String model) {
        String name = provider == null || provider.isBlank() ? defaultProvider : provider;
        if (name == null || name.isBlank()) {
            name = registry.providers().stream().map(ResolvedProvider::getName)
                .filter(id -> !ProviderRouter.FALLBACK_PROVIDER.equalsIgnoreCase(id)).findFirst().orElse(null);
        }
        return registry.find(name).map(resolved -> {
            String requested = model == null || model.isBlank() ? resolved.getDefaultModel() : model.trim();
            String canonical = resolved.getModels().stream().map(definition -> definition.getId())
                .filter(id -> id != null && id.equalsIgnoreCase(requested)).findFirst().orElse(requested);
            return new Target(resolved.getName(), canonical);
        });
    }

    public static <T> List<T> ordered(T dedicated, T fallback, T task, Function<T, String> key) {
        var targets = new LinkedHashMap<String, T>();
        T first = dedicated == null ? task : dedicated;
        if (first != null) targets.putIfAbsent(key.apply(first), first);
        if (fallback != null) targets.putIfAbsent(key.apply(fallback), fallback);
        if (task != null) targets.putIfAbsent(key.apply(task), task);
        return List.copyOf(targets.values());
    }
}
