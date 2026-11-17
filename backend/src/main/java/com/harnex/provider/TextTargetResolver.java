package com.harnex.provider;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.harnex.config.RuntimeConfiguration;
import com.harnex.execution.TextTarget;

/** Freeze configured defaults before a Run is admitted, without probing or charging the upstream. */
@Component
public final class TextTargetResolver {
    private final ProviderRouter router;
    private final ResolvedProviderRegistry providers;
    public TextTargetResolver(ProviderRouter router, ResolvedProviderRegistry providers) { this.router = router; this.providers = providers; }
    public RuntimeConfiguration.Scope openConfigurationScope() { return router.openConfigurationScope(); }
    public TextTarget resolve(TextTarget requested) {
        try (var configuration = openConfigurationScope()) { return resolveCurrent(requested); }
    }
    private TextTarget resolveCurrent(TextTarget requested) {
        String provider = requested == null ? null : requested.provider();
        if (provider == null || provider.isBlank()) provider = router.defaultProviderName().orElseThrow(
            () -> new ProviderException("text_model_not_configured", "No enabled text model is configured"));
        ResolvedProvider configured = providers.find(provider).orElseThrow(
            () -> new ProviderException("text_provider_unavailable", "The selected text provider is not enabled"));
        if (router.listAvailableProviderNames().stream().noneMatch(name -> name.equalsIgnoreCase(configured.getName()))) {
            throw new ProviderException("text_provider_unavailable", "The selected text provider is unavailable");
        }
        String model = requested == null ? null : requested.model();
        if (model == null || model.isBlank()) model = configured.getDefaultModel();
        if (model == null || model.isBlank()) throw new ProviderException("text_model_not_configured", "The text provider has no default model");
        String selectedModel = model.trim();
        if (!selectedModel.equals(configured.getDefaultModel()) && configured.getModels().stream().noneMatch(item -> selectedModel.equals(item.getId()))) {
            throw new ProviderException("text_model_unavailable", "The selected text model is no longer configured for this provider");
        }
        String effort = requested == null ? null : requested.reasoningEffort();
        if (effort != null) {
            effort = effort.trim().toLowerCase(Locale.ROOT);
            if (effort.isBlank() || "auto".equals(effort)) effort = null;
            else if (!Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max").contains(effort)) {
                throw new ProviderException("invalid_reasoning_effort", "Unsupported reasoning effort");
            }
        }
        return new TextTarget(configured.getName(), model.trim(), effort);
    }
}
