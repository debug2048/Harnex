package com.harnex.provider;

import com.harnex.config.AgentConfig;
import com.harnex.net.HttpExchangeState;

/** One capacity policy for task requests, summary requests and the model information shown in the UI. */
public record ProviderContextBudget(int contextWindow, String source, int safetyReserve,
    ProviderCapabilityRequest.OutputBudget output) {

    public int inputTokens() { return contextWindow - output.reserve() - safetyReserve; }

    public static ProviderContextBudget resolve(AgentConfig config, ResolvedProvider provider, String model,
        Integer outputOverride) {
        String selected = model == null || model.isBlank() ? provider.getDefaultModel() : model;
        var definition = provider.getModels().stream().filter(m -> m.getId().equalsIgnoreCase(selected))
            .findFirst().orElse(null);
        Integer modelWindow = definition == null ? null : definition.getContextWindow();
        Integer providerWindow = provider.getContextWindow();
        int window = modelWindow != null ? modelWindow : providerWindow != null ? providerWindow
            : config.getSummarizer().getContextWindow();
        String source = modelWindow != null ? "model" : providerWindow != null ? "provider" : "fallback";
        int safety = Math.max(256, config.getSummarizer().getSafetyReserveTokens());
        if (window < 1_024 || window > 2_000_000 || (long)safety + 1 >= window) {
            throw new ProviderException("context_length_exceeded", "The configured context window cannot fit the safety reserve",
                HttpExchangeState.Acceptance.NOT_SENT, null, 0);
        }
        var profile = ProviderModelCapabilities.from(config, provider).forModel(selected);
        var output = ProviderCapabilityRequest.outputBudget(profile, outputOverride,
            provider.getMaxTokens() == null ? 0 : provider.getMaxTokens(),
            config.getSummarizer().getOutputReserveTokens(), window, safety);
        return new ProviderContextBudget(window, source, safety, output);
    }
}
