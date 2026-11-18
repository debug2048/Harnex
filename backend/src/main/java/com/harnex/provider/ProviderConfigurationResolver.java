package com.harnex.provider;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;

/**
 * Pure resolver from the user-facing provider map and internal profiles to immutable runtime provider snapshots.
 *
 * <p>This class deliberately owns all cross-field provider validation. {@link AgentConfig} remains a binding DTO and
 * has no dependency on protocol adapters or model capability logic.</p>
 */
@Component
public final class ProviderConfigurationResolver {

    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    public Resolution resolve(AgentConfig config) {
        Objects.requireNonNull(config, "config");
        LinkedHashMap<String, AgentConfig.ProviderProfileDef> profiles = indexProviderProfiles(config);
        List<ResolvedProvider> resolved = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> seenProviders = new LinkedHashSet<>();

        Map<String, AgentConfig.ProviderConfig> configuredProviders = config.getProviders();
        if (configuredProviders != null) {
            for (Map.Entry<String, AgentConfig.ProviderConfig> entry : configuredProviders.entrySet()) {
                String name = requiredConfigText(entry.getKey(), "providers key");
                validateStableId(name, "harnex.providers key");
                if (ProviderRouter.FALLBACK_PROVIDER.equalsIgnoreCase(name)
                    && !ProviderRouter.FALLBACK_PROVIDER.equals(name)) {
                    throw configError("reserved provider id 'fallback' must use that exact lowercase spelling");
                }
                String key = normaliseConfigKey(name);
                if (!seenProviders.add(key)) {
                    throw configError("Duplicate provider id (case-insensitive): '" + name + "'");
                }
                AgentConfig.ProviderConfig configured = Objects.requireNonNull(entry.getValue(),
                    "harnex.providers." + name + " must not be null");
                if (!configured.isEnabled()) {
                    diagnostics.add(new Diagnostic(DiagnosticLevel.INFO, "provider-disabled",
                        "harnex.providers." + name,
                        "Provider is disabled and excluded from the runtime registry"));
                    continue;
                }

                ResolvedProvider provider = resolveProvider(config, profiles, name, configured);
                resolved.add(provider);
                diagnostics.add(new Diagnostic(DiagnosticLevel.INFO, "provider-resolved",
                    "harnex.providers." + name,
                    "Resolved profile '" + provider.getProfile() + "' into provider type '" + provider.getType() + "'"));
            }
        }
        validateRouterReferences(config, resolved, diagnostics);
        validateProviderConsumers(config, resolved);
        return new Resolution(resolved, diagnostics);
    }

    /** Resolve one unsaved provider candidate without applying router or unrelated consumer validation. */
    public ResolvedProvider resolveCandidate(AgentConfig config, String providerId,
        AgentConfig.ProviderConfig configured) {
        Objects.requireNonNull(config, "config");
        String name = requiredConfigText(providerId, "providers key");
        validateStableId(name, "harnex.providers key");
        Objects.requireNonNull(configured, "harnex.providers." + name + " must not be null");
        // A disabled draft is still probeable before the user chooses to enable/save it.
        // enabled controls registry publication; it does not change the endpoint wire contract.
        return resolveProvider(config, indexProviderProfiles(config), name, configured);
    }

    private static ResolvedProvider resolveProvider(AgentConfig config,
        Map<String, AgentConfig.ProviderProfileDef> profiles, String name,
        AgentConfig.ProviderConfig configured) {
        String profileName = requiredConfigText(configured.getProfile(),
            "harnex.providers." + name + ".profile");
        AgentConfig.ProviderProfileDef profile = profiles.get(normaliseConfigKey(profileName));
        if (profile == null) {
            throw configError("harnex.providers." + name + ".profile references unknown profile '"
                + profileName + "'; available profiles=" + profiles.keySet());
        }
        ResolvedProvider provider = providerFromProfile(name, profileName, profile);
        provider = applyProviderOverrides(provider, configured, name);
        provider = normaliseProviderAddressing(provider, name);
        provider = resolveSelectableModels(provider, configured.getModels(), profile.getModels(), name);
        validateProviderEndpoint(provider, name);
        return applyProviderFeatures(config, provider, configured.getFeatures(), name);
    }

    private static void validateProviderConsumers(AgentConfig config, List<ResolvedProvider> providers) {
        // An unconfigured text catalogue is a supported setup/image-only state. These consumers cannot execute
        // until a provider is available, and must not prevent configuration or independent image services starting.
        if (providers.isEmpty()) return;
        Map<String, ResolvedProvider> available = providers.stream().filter(Objects::nonNull)
            .filter(provider -> provider.getName() != null)
            .collect(Collectors.toMap(
                provider -> provider.getName().toLowerCase(Locale.ROOT), provider -> provider,
                (left, right) -> left, LinkedHashMap::new));

        AgentConfig.Summarizer summarizer = config.getSummarizer();
        if (summarizer != null && summarizer.isEnabled()) {
            String providerId = summarizer.getProvider();
            if (providerId == null || providerId.isBlank()) {
                providerId = config.getRouter() == null ? null : config.getRouter().getDefaultProvider();
                if ((providerId == null || providerId.isBlank()) && !available.isEmpty()) {
                    providerId = available.values().stream()
                        .map(ResolvedProvider::getName)
                        .filter(name -> !ProviderRouter.FALLBACK_PROVIDER.equals(name))
                        .findFirst().orElse(null);
                }
            }
            ResolvedProvider provider = requireAvailableProvider("harnex.summarizer.provider", providerId, available);
            requireDeclaredModel("harnex.summarizer.model", provider, summarizer.getModel());
        }

        AgentConfig.Moa moa = config.getMoa();
        if (moa == null || !moa.isEnabled()) {
            return;
        }
        if (moa.getReferenceModels() == null || moa.getReferenceModels().isEmpty()) {
            throw configError("harnex.moa.reference-models must not be empty when MoA is enabled");
        }
        for (int i = 0; i < moa.getReferenceModels().size(); i++) {
            validateQualifiedModelReference("harnex.moa.reference-models[" + i + "]",
                moa.getReferenceModels().get(i), available);
        }
        validateQualifiedModelReference("harnex.moa.aggregator-model", moa.getAggregatorModel(), available);
    }

    private static void validateQualifiedModelReference(String path, String reference,
        Map<String, ResolvedProvider> available) {
        String value = requiredConfigText(reference, path);
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) {
            throw configError(path + " must use provider/model so a model id is never sent to the wrong endpoint: "
                + value);
        }
        ResolvedProvider provider = requireAvailableProvider(path + " provider", value.substring(0, slash), available);
        requireDeclaredModel(path + " model", provider, value.substring(slash + 1));
    }

    private static ResolvedProvider requireAvailableProvider(String path, String configured,
        Map<String, ResolvedProvider> available) {
        String provider = requiredConfigText(configured, path);
        ResolvedProvider resolved = available.get(provider.toLowerCase(Locale.ROOT));
        if (resolved == null) {
            throw configError(path + " references disabled or unknown provider '" + provider
                + "'; available providers=" + available.keySet());
        }
        return resolved;
    }

    private static void requireDeclaredModel(String path, ResolvedProvider provider, String configuredModel) {
        if (configuredModel == null || configuredModel.isBlank()) return;
        boolean declared = provider.getModels().stream().filter(Objects::nonNull)
            .map(AgentConfig.ModelDef::getId).filter(Objects::nonNull)
            .anyMatch(model -> model.equalsIgnoreCase(configuredModel.trim()));
        if (!declared) {
            throw configError(path + " references model '" + configuredModel + "' not declared by provider '"
                + provider.getName() + "'");
        }
    }

    private static void validateProviderEndpoint(ResolvedProvider provider, String name) {
        if ("openai-compatible".equals(provider.getType())
            && (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank())) {
            throw configError("harnex.providers." + name
                + ".base-url is required for profile type openai-compatible");
        }
    }

    private static ResolvedProvider normaliseProviderAddressing(ResolvedProvider provider, String name) {
        String path = "harnex.providers." + name;
        String apiKey = provider.getApiKey();
        if (apiKey != null && apiKey.trim().startsWith("$") && !apiKey.trim().startsWith("${")) {
            throw configError(path + ".api-key uses unsupported $VAR syntax; use ${VAR:} instead");
        }
        String model = requiredConfigText(resolveEnvironmentValue(provider.getDefaultModel()),
            path + ".default-model");
        String baseUrl = resolveEnvironmentValue(provider.getBaseUrl());
        if (baseUrl != null) {
            baseUrl = baseUrl.trim();
            if (!baseUrl.isEmpty()) {
                validateHttpUrl(baseUrl, path + ".base-url");
            }
        }
        return provider.toBuilder().setDefaultModel(model).setBaseUrl(baseUrl).build();
    }

    private static void validateHttpUrl(String value, String path) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                throw configError(path
                    + " must be an absolute http(s) URL without credentials, query, or fragment");
            }
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Invalid harnex configuration:")) {
                throw ex;
            }
            throw configError(path + " is not a valid URL");
        }
    }

    private static String resolveEnvironmentValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String expression = trimmed.substring(2, trimmed.length() - 1);
            int separator = expression.indexOf(':');
            String variable = separator < 0 ? expression : expression.substring(0, separator);
            String fallback = separator < 0 ? "" : expression.substring(separator + 1);
            String environment = System.getenv(variable);
            return environment == null ? fallback : environment;
        }
        return trimmed;
    }

    private static void validateRouterReferences(AgentConfig config, List<ResolvedProvider> providers,
        List<Diagnostic> diagnostics) {
        if (config.getRouter() == null) {
            return;
        }
        String configured = config.getRouter().getDefaultProvider();
        if (configured == null || configured.isBlank()) return;
        Map<String, ResolvedProvider> available = providers.stream().filter(Objects::nonNull)
            .filter(provider -> provider.getName() != null)
            .collect(Collectors.toMap(
                provider -> provider.getName().toLowerCase(Locale.ROOT), provider -> provider,
                (left, right) -> left, LinkedHashMap::new));
        ResolvedProvider selected = requireAvailableProvider("harnex.router.default-provider", configured, available);
        if (ProviderRouter.FALLBACK_PROVIDER.equals(selected.getName())) {
            throw configError("harnex.router.default-provider cannot be the reserved provider 'fallback'");
        }
    }

    private static LinkedHashMap<String, AgentConfig.ProviderProfileDef> indexProviderProfiles(AgentConfig config) {
        LinkedHashMap<String, AgentConfig.ProviderProfileDef> indexed = new LinkedHashMap<>();
        Map<String, AgentConfig.ProviderProfileDef> profiles = config.getProviderProfiles();
        if (profiles == null) {
            return indexed;
        }
        for (Map.Entry<String, AgentConfig.ProviderProfileDef> entry : profiles.entrySet()) {
            String name = requiredConfigText(entry.getKey(), "provider-profiles key");
            validateStableId(name, "harnex.provider-profiles key");
            String key = normaliseConfigKey(name);
            AgentConfig.ProviderProfileDef profile = Objects.requireNonNull(entry.getValue(),
                "harnex.provider-profiles." + name + " must not be null");
            String type = requiredConfigText(profile.getType(),
                "harnex.provider-profiles." + name + ".type").toLowerCase(Locale.ROOT);
            if (!Set.of("openai-compatible", "openai-responses", "anthropic", "gemini").contains(type)) {
                throw configError("harnex.provider-profiles." + name + ".type must be "
                    + "openai-compatible|openai-responses|anthropic|gemini, got: " + profile.getType());
            }
            if (indexed.putIfAbsent(key, profile) != null) {
                throw configError("Duplicate provider profile id (case-insensitive): '" + name + "'");
            }
        }
        return indexed;
    }

    private static ResolvedProvider providerFromProfile(String name, String profileName,
        AgentConfig.ProviderProfileDef profile) {
        return ResolvedProvider.builder()
            .setName(name.trim())
            .setProfile(profileName.trim())
            .setType(profile.getType().trim().toLowerCase(Locale.ROOT))
            .setBaseUrl(profile.getBaseUrl())
            .setDefaultModel(profile.getDefaultModel())
            .setCapabilities(profile)
            .setModelCapabilityRules(profile.getModelCapabilityRules())
            .setProxy(profile.getProxy())
            .setMaxTokens(profile.getMaxTokens())
            .setThinking(profile.getDefaultThinking())
            .setEffort(profile.getEffort())
            .setPromptCaching(profile.getPromptCaching())
            .setPromptCacheKey(profile.getPromptCacheKey())
            .setPromptCacheExplicit(profile.getPromptCacheExplicit())
            .setBetas(profile.getBetas())
            .setSendTemperature(profile.getSendTemperature())
            .setNativeWebSearch(profile.isDefaultNativeWebSearch())
            .build();
    }

    private static ResolvedProvider applyProviderOverrides(ResolvedProvider provider,
        AgentConfig.ProviderConfig configured, String name) {
        ResolvedProvider.Builder builder = provider.toBuilder().setLabel(configured.getLabel());
        Integer window = configured.getContextWindow();
        if (window != null && (window < 1_024 || window > 2_000_000)) {
            throw configError("harnex.providers." + name + ".context-window must be between 1024 and 2000000");
        }
        builder.setContextWindow(window);
        String configuredBaseUrl = resolveEnvironmentValue(configured.getBaseUrl());
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            builder.setBaseUrl(configuredBaseUrl);
        }
        if (configured.getApiKey() != null) {
            builder.setApiKey(configured.getApiKey());
        }
        String configuredDefaultModel = resolveEnvironmentValue(configured.getDefaultModel());
        if (configuredDefaultModel != null && !configuredDefaultModel.isBlank()) {
            builder.setDefaultModel(requiredConfigText(configuredDefaultModel,
                "harnex.providers." + name + ".default-model"));
        }
        if (configured.getProxy() != null) {
            builder.setProxy(configured.getProxy());
        }
        if (configured.getMaxTokens() != null) {
            builder.setMaxTokens(configured.getMaxTokens());
        }
        applyClientEmulation(builder, configured, provider, name);
        return builder.build();
    }

    /**
     * Resolve the user-visible model catalogue independently from the profile's exact capability catalogue.
     *
     * <p>A profile may describe every model whose wire contract Harnex knows, but a concrete endpoint/key often exposes
     * only one model or a small subset. Omitting {@code providers.<id>.models} therefore means "default model only",
     * not "publish the entire profile catalogue". Explicit provider entries inherit matching profile metadata and
     * capability fragments by id, and the effective default is always present.</p>
     */
    private static ResolvedProvider resolveSelectableModels(ResolvedProvider provider,
        List<AgentConfig.ModelDef> configuredModels, List<AgentConfig.ModelDef> profileModels, String providerName) {
        String path = "harnex.providers." + providerName + ".models";
        Map<String, AgentConfig.ModelDef> templates = indexProfileModels(profileModels, providerName);
        List<AgentConfig.ModelDef> requested = configuredModels == null || configuredModels.isEmpty()
            ? new ArrayList<>() : ResolvedProvider.copyModels(configuredModels);

        if (requested.isEmpty()) {
            AgentConfig.ModelDef defaultOnly = new AgentConfig.ModelDef();
            defaultOnly.setId(provider.getDefaultModel());
            requested.add(defaultOnly);
        }

        boolean containsDefault = requested.stream().filter(Objects::nonNull)
            .map(AgentConfig.ModelDef::getId)
            .map(ProviderConfigurationResolver::resolveEnvironmentValue)
            .filter(Objects::nonNull)
            .anyMatch(id -> id.trim().equalsIgnoreCase(provider.getDefaultModel()));
        if (!containsDefault) {
            AgentConfig.ModelDef defaultModel = new AgentConfig.ModelDef();
            defaultModel.setId(provider.getDefaultModel());
            requested.add(defaultModel);
        }

        List<AgentConfig.ModelDef> resolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AgentConfig.ModelDef configured : requested) {
            if (configured == null) {
                continue;
            }
            String id = resolveEnvironmentValue(configured.getId());
            if (id == null || id.isBlank()) {
                continue;
            }
            id = id.trim();
            String key = normaliseConfigKey(id);
            if (!seen.add(key)) {
                throw configError(path + " contains duplicate model id (case-insensitive): '" + id + "'");
            }

            String capabilityModel = resolveEnvironmentValue(configured.getCapabilityModel());
            if (capabilityModel != null && !capabilityModel.isBlank()) {
                capabilityModel = capabilityModel.trim();
            } else {
                capabilityModel = null;
            }
            AgentConfig.ModelDef template = templates.get(key);
            resolved.add(mergeModelDefinition(template, configured, id, capabilityModel));
        }
        return provider.toBuilder().setModels(resolved).build();
    }

    private static Map<String, AgentConfig.ModelDef> indexProfileModels(List<AgentConfig.ModelDef> profileModels,
        String providerName) {
        Map<String, AgentConfig.ModelDef> indexed = new LinkedHashMap<>();
        if (profileModels == null) {
            return indexed;
        }
        for (AgentConfig.ModelDef model : profileModels) {
            if (model == null) {
                continue;
            }
            String id = resolveEnvironmentValue(model.getId());
            if (id == null || id.isBlank()) {
                continue;
            }
            String key = normaliseConfigKey(id);
            if (indexed.putIfAbsent(key, model) != null) {
                throw configError("profile selected by harnex.providers." + providerName
                    + " contains duplicate model id (case-insensitive): '" + id.trim() + "'");
            }
        }
        return indexed;
    }

    private static AgentConfig.ModelDef mergeModelDefinition(AgentConfig.ModelDef template,
        AgentConfig.ModelDef configured, String id, String capabilityModel) {
        AgentConfig.ModelDef merged = new AgentConfig.ModelDef();
        merged.setId(id);

        if (template != null) {
            merged.setLabel(template.getLabel());
            merged.setTier(template.getTier());
            merged.setCostHint(template.getCostHint());
            merged.setInputCostPerMillion(template.getInputCostPerMillion());
            merged.setOutputCostPerMillion(template.getOutputCostPerMillion());
            merged.setCachedInputCostPerMillion(template.getCachedInputCostPerMillion());
            merged.setCacheWriteInputCostPerMillion(template.getCacheWriteInputCostPerMillion());
            merged.setContextWindow(template.getContextWindow());
            merged.setMaxOutputTokens(template.getMaxOutputTokens());
            merged.setDescription(template.getDescription());
            merged.setInputModalities(template.getInputModalities() == null
                ? new ArrayList<>() : new ArrayList<>(template.getInputModalities()));
            merged.setProfile(template.getProfile());
            merged.setCapabilities(overlayCapabilities(null, template.getCapabilities()));
        }

        if (configured.getLabel() != null && !configured.getLabel().isBlank()) {
            merged.setLabel(configured.getLabel().trim());
        }
        if (configured.getTier() != null && !configured.getTier().isBlank()) {
            merged.setTier(configured.getTier().trim());
        }
        if (configured.getCostHint() != null && !configured.getCostHint().isBlank()) {
            merged.setCostHint(configured.getCostHint().trim());
        }
        if (configured.getInputCostPerMillion() != null) {
            merged.setInputCostPerMillion(configured.getInputCostPerMillion());
        }
        if (configured.getOutputCostPerMillion() != null) {
            merged.setOutputCostPerMillion(configured.getOutputCostPerMillion());
        }
        if (configured.getCachedInputCostPerMillion() != null) {
            merged.setCachedInputCostPerMillion(configured.getCachedInputCostPerMillion());
        }
        if (configured.getCacheWriteInputCostPerMillion() != null) {
            merged.setCacheWriteInputCostPerMillion(configured.getCacheWriteInputCostPerMillion());
        }
        if (configured.getContextWindow() != null) {
            if (configured.getContextWindow() < 1_024 || configured.getContextWindow() > 2_000_000) {
                throw configError("model " + id + " context-window must be between 1024 and 2000000");
            }
            merged.setContextWindow(configured.getContextWindow());
        }
        if (configured.getMaxOutputTokens() != null) merged.setMaxOutputTokens(configured.getMaxOutputTokens());
        if (merged.getMaxOutputTokens() != null && (merged.getMaxOutputTokens() < 1 || merged.getMaxOutputTokens() > 2_000_000)) {
            throw configError("model " + id + " max-output-tokens must be between 1 and 2000000");
        }
        if (configured.getDescription() != null && !configured.getDescription().isBlank()) {
            merged.setDescription(configured.getDescription().trim());
        }
        if (configured.getProfile() != null && !configured.getProfile().isBlank()) {
            merged.setProfile(configured.getProfile().trim());
        }
        if (capabilityModel != null) {
            merged.setCapabilityModel(capabilityModel);
        } else if (template != null && template.getCapabilityModel() != null
            && !template.getCapabilityModel().isBlank()) {
            merged.setCapabilityModel(template.getCapabilityModel().trim());
        }
        merged.setCapabilities(overlayCapabilities(merged.getCapabilities(), configured.getCapabilities()));
        if (configured.getInputModalities() != null && !configured.getInputModalities().isEmpty()) {
            merged.setInputModalities(new ArrayList<>(configured.getInputModalities()));
        }
        return merged;
    }

    private static AgentConfig.ProviderCapabilities overlayCapabilities(AgentConfig.ProviderCapabilities base,
        AgentConfig.ProviderCapabilities overlay) {
        AgentConfig.ProviderCapabilities merged = ResolvedProvider.copyCapabilities(base);
        if (merged == null) {
            merged = new AgentConfig.ProviderCapabilities();
        }
        if (overlay == null) {
            return merged;
        }
        if (overlay.getInstructionRole() != null) {
            merged.setInstructionRole(overlay.getInstructionRole());
        }
        if (overlay.getMaxTokensField() != null) {
            merged.setMaxTokensField(overlay.getMaxTokensField());
        }
        if (overlay.getDefaultMaxTokens() != null) {
            merged.setDefaultMaxTokens(overlay.getDefaultMaxTokens());
        }
        if (overlay.getOutputReserveTokens() != null) {
            merged.setOutputReserveTokens(overlay.getOutputReserveTokens());
        }
        if (overlay.getMaxInputTokens() != null) {
            merged.setMaxInputTokens(overlay.getMaxInputTokens());
        }
        if (overlay.getAudioInputEncoding() != null) {
            merged.setAudioInputEncoding(overlay.getAudioInputEncoding());
        }
        if (overlay.getStreaming() != null) {
            merged.setStreaming(overlay.getStreaming());
        }
        if (overlay.getStreamUsage() != null) {
            merged.setStreamUsage(overlay.getStreamUsage());
        }
        if (overlay.getFunctionTools() != null) {
            merged.setFunctionTools(overlay.getFunctionTools());
        }
        if (overlay.getSamplingPolicy() != null) {
            merged.setSamplingPolicy(overlay.getSamplingPolicy());
        }
        if (overlay.getReasoningEfforts() != null) {
            merged.setReasoningEfforts(new ArrayList<>(overlay.getReasoningEfforts()));
        }
        if (overlay.getReasoningReplay() != null) {
            merged.setReasoningReplay(overlay.getReasoningReplay());
        }
        if (overlay.getReasoningHistory() != null) {
            merged.setReasoningHistory(overlay.getReasoningHistory());
        }
        if (overlay.getReasoningHistoryField() != null) {
            merged.setReasoningHistoryField(overlay.getReasoningHistoryField());
        }
        if (overlay.getFunctionToolReasoningEffort() != null) {
            merged.setFunctionToolReasoningEffort(overlay.getFunctionToolReasoningEffort());
        }
        if (overlay.getThinkingRequiredEfforts() != null) {
            merged.setThinkingRequiredEfforts(new ArrayList<>(overlay.getThinkingRequiredEfforts()));
        }
        if (overlay.getPromptCacheMode() != null) {
            merged.setPromptCacheMode(overlay.getPromptCacheMode());
        }
        if (overlay.getStrictTerminal() != null) {
            merged.setStrictTerminal(overlay.getStrictTerminal());
        }
        if (overlay.getThinkingPrefixBound() != null) {
            merged.setThinkingPrefixBound(overlay.getThinkingPrefixBound());
        }
        if (overlay.getNativeWebSearch() != null) {
            merged.setNativeWebSearch(overlay.getNativeWebSearch());
        }
        if (overlay.getNativeWebSearchType() != null) {
            merged.setNativeWebSearchType(overlay.getNativeWebSearchType());
        }
        merged.setThinking(overlayThinking(merged.getThinking(), overlay.getThinking()));
        Map<String, Object> requestOptions = merged.getRequestOptions() == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(merged.getRequestOptions());
        if (overlay.getRequestOptions() != null) {
            requestOptions.putAll(overlay.getRequestOptions());
        }
        merged.setRequestOptions(requestOptions);
        return merged;
    }

    private static AgentConfig.ThinkingCapabilities overlayThinking(AgentConfig.ThinkingCapabilities base,
        AgentConfig.ThinkingCapabilities overlay) {
        AgentConfig.ThinkingCapabilities merged = new AgentConfig.ThinkingCapabilities();
        if (base != null) {
            merged.setField(base.getField());
            merged.setDefaultState(base.getDefaultState());
            merged.setEnabledValue(base.getEnabledValue());
            merged.setDisabledValue(base.getDisabledValue());
            merged.setEnabledObject(base.getEnabledObject() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(base.getEnabledObject()));
            merged.setDisabledObject(base.getDisabledObject() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(base.getDisabledObject()));
            merged.setSendDefault(base.getSendDefault());
        }
        if (overlay == null) {
            return merged;
        }
        if (overlay.getField() != null) {
            merged.setField(overlay.getField());
        }
        if (overlay.getDefaultState() != null) {
            merged.setDefaultState(overlay.getDefaultState());
        }
        if (overlay.getEnabledValue() != null) {
            merged.setEnabledValue(overlay.getEnabledValue());
        }
        if (overlay.getDisabledValue() != null) {
            merged.setDisabledValue(overlay.getDisabledValue());
        }
        if (overlay.getEnabledObject() != null && !overlay.getEnabledObject().isEmpty()) {
            merged.setEnabledObject(new LinkedHashMap<>(overlay.getEnabledObject()));
        }
        if (overlay.getDisabledObject() != null && !overlay.getDisabledObject().isEmpty()) {
            merged.setDisabledObject(new LinkedHashMap<>(overlay.getDisabledObject()));
        }
        if (overlay.getSendDefault() != null) {
            merged.setSendDefault(overlay.getSendDefault());
        }
        return merged;
    }

    private static void applyClientEmulation(ResolvedProvider.Builder builder,
        AgentConfig.ProviderConfig configured, ResolvedProvider inherited, String name) {
        String path = "harnex.providers." + name + ".client-emulation";
        String rawMode = configured.getClientEmulation();
        String mode = rawMode == null || rawMode.isBlank() ? "none" : rawMode.trim().toLowerCase(Locale.ROOT);
        boolean enabled = false;

        switch (mode) {
            case "none" -> {
                builder.setEmulation(false);
                builder.setEmulationVersion(null);
            }
            case "codex" -> {
                if (!Set.of("openai-compatible", "openai-responses").contains(inherited.getType())) {
                    throw configError(path + "=codex requires an openai-compatible or openai-responses profile");
                }
                builder.setEmulation(true);
                enabled = true;
            }
            case "claude-code" -> {
                if (!"anthropic".equals(inherited.getType())) {
                    throw configError(path + "=claude-code requires an anthropic profile");
                }
                builder.setEmulation(true);
                enabled = true;
            }
            default -> throw configError(path + " must be none|codex|claude-code, got: " + rawMode);
        }

        String version = configured.getClientVersion();
        if (version != null) {
            version = requiredConfigText(version, "harnex.providers." + name + ".client-version");
            if (!enabled) {
                throw configError("harnex.providers." + name
                    + ".client-version requires client-emulation=codex|claude-code");
            }
            builder.setEmulationVersion(version);
        }
    }

    private static ResolvedProvider applyProviderFeatures(AgentConfig config, ResolvedProvider provider,
        AgentConfig.ProviderFeatures configuredFeatures, String name) {
        AgentConfig.ProviderFeatures features =
            configuredFeatures == null ? new AgentConfig.ProviderFeatures() : configuredFeatures;
        FeatureMode cache = FeatureMode.parse(features.getPromptCache(),
            "harnex.providers." + name + ".features.prompt-cache");
        if (cache == FeatureMode.ON) {
            provider = provider.toBuilder()
                .setPromptCaching(true)
                .setPromptCacheKey(true)
                .setPromptCacheExplicit(true)
                .build();
            validateProviderPromptCache(config, provider, name);
        } else if (cache == FeatureMode.OFF) {
            provider = provider.toBuilder()
                .setPromptCaching(false)
                .setPromptCacheKey(false)
                .setPromptCacheExplicit(false)
                .build();
        }

        FeatureMode web = FeatureMode.parse(features.getWebSearch(),
            "harnex.providers." + name + ".features.web-search");
        // AUTO and ON both express "prefer the provider-native tool when the selected model declares it".
        // The adapter resolves that capability again for the request's actual model.  When it is absent, the native
        // tool is simply omitted and any configured local web_search function remains available as the fallback.
        // This is intentionally not validated against the provider's default model here: a provider catalogue may
        // contain a mix of native-search and function-only models, and users may switch between them per thread.
        provider = provider.toBuilder().setNativeWebSearch(web != FeatureMode.OFF).build();

        provider = applyProviderReasoning(config, provider, features, name);
        provider = applyProviderReasoningHistory(config, provider, features, name);

        FeatureMode attachments = FeatureMode.parse(features.getAttachments(),
            "harnex.providers." + name + ".features.attachments");
        if (attachments == FeatureMode.OFF) {
            List<AgentConfig.ModelDef> models = provider.getModels();
            for (AgentConfig.ModelDef model : models) {
                if (model != null) {
                    model.setInputModalities(model.getInputModalities() != null
                        && model.getInputModalities().stream().anyMatch("text"::equalsIgnoreCase)
                            ? new ArrayList<>(List.of("text")) : new ArrayList<>());
                }
            }
            provider = provider.toBuilder().setModels(models).build();
        } else if (attachments == FeatureMode.ON) {
            if (config.getVision() == null || !config.getVision().isEnabled()) {
                throw configError("harnex.providers." + name
                    + ".features.attachments=on requires harnex.vision.enabled=true");
            }
            AgentConfig.ModelDef selected = findExactModel(provider, provider.getDefaultModel());
            boolean nativeAttachment = selected != null && selected.getInputModalities() != null
                && selected.getInputModalities().stream().filter(Objects::nonNull)
                    .map(modality -> modality.trim().toLowerCase(Locale.ROOT))
                    .anyMatch(modality -> Set.of("image", "file", "pdf", "audio", "video").contains(modality));
            if (!nativeAttachment) {
                throw configError("harnex.providers." + name
                    + ".features.attachments=on requires the selected model to have an exact catalog entry declaring "
                    + "image, file, pdf, audio, or video; use auto to retain safe local fallback");
            }
        }
        return provider;
    }

    private static ResolvedProvider applyProviderReasoning(AgentConfig config, ResolvedProvider provider,
        AgentConfig.ProviderFeatures features, String name) {
        String reasoningPath = "harnex.providers." + name + ".features.reasoning";
        String effortPath = "harnex.providers." + name + ".features.effort";
        FeatureMode reasoning = FeatureMode.parse(features.getReasoning(), reasoningPath);
        String explicitEffort = normaliseEffort(features.getEffort(), effortPath);
        if (reasoning == FeatureMode.AUTO && explicitEffort == null) {
            return provider;
        }
        if (reasoning == FeatureMode.OFF && explicitEffort != null) {
            throw configError(reasoningPath + "=off conflicts with an explicit effort");
        }
        if (reasoning == FeatureMode.ON && "none".equals(explicitEffort)) {
            throw configError(reasoningPath + "=on conflicts with effort=none");
        }

        String model = requiredConfigText(provider.getDefaultModel(),
            "harnex.providers." + name + ".default-model");
        ProviderModelCapabilities.Profile profile = capabilityProfile(config, provider, model,
            "reasoning capability for harnex.providers." + name);

        if (explicitEffort != null) {
            requireSupportedEffort(profile, explicitEffort, effortPath, model);
            requireToolCompatibleEffort(profile, explicitEffort, effortPath, model);
        }
        if (reasoning == FeatureMode.AUTO) {
            return provider.toBuilder().setEffort(explicitEffort).build();
        }

        if (reasoning == FeatureMode.OFF) {
            if (profile.thinking().supported()) {
                if (profile.thinking().alwaysOn()) {
                    throw configError(reasoningPath + "=off is not supported by always-on reasoning model " + model);
                }
                return provider.toBuilder().setEffort(null).setThinking("off").build();
            }
            if (profile.reasoningSupported()) {
                requireSupportedEffort(profile, "none", reasoningPath, model);
                return provider.toBuilder().setThinking(null).setEffort("none").build();
            }
            return provider.toBuilder().setEffort(null).setThinking(null).build();
        }

        if (profile.thinking().supported()) {
            String thinkingEffort = explicitEffort;
            if (thinkingEffort == null && provider.getEffort() != null && !provider.getEffort().isBlank()) {
                thinkingEffort = normaliseEffort(provider.getEffort(), effortPath + " (profile)");
            }
            if ("none".equals(thinkingEffort)) {
                throw configError(reasoningPath + "=on conflicts with profile effort=none");
            }
            ResolvedProvider.Builder builder = provider.toBuilder().setThinking("adaptive");
            if (thinkingEffort != null) {
                requireSupportedEffort(profile, thinkingEffort, effortPath, model);
                requireToolCompatibleEffort(profile, thinkingEffort, effortPath, model);
                builder.setEffort(thinkingEffort);
            }
            return builder.build();
        }
        if (!profile.reasoningSupported()) {
            throw configError(reasoningPath + "=on is not supported by model " + model);
        }

        String enabledEffort = explicitEffort;
        if (enabledEffort == null && provider.getEffort() != null && !provider.getEffort().isBlank()) {
            enabledEffort = normaliseEffort(provider.getEffort(), effortPath + " (profile)");
        }
        if (enabledEffort == null) {
            enabledEffort = defaultEnabledEffort(profile, reasoningPath, model);
        }
        requireSupportedEffort(profile, enabledEffort, effortPath, model);
        requireToolCompatibleEffort(profile, enabledEffort, effortPath, model);
        return provider.toBuilder().setThinking(null).setEffort(enabledEffort).build();
    }

    private static ResolvedProvider applyProviderReasoningHistory(AgentConfig config, ResolvedProvider provider,
        AgentConfig.ProviderFeatures features, String name) {
        String path = "harnex.providers." + name + ".features.reasoning-history";
        ReasoningHistoryMode mode = ReasoningHistoryMode.parse(features.getReasoningHistory(), path);
        if (mode == ReasoningHistoryMode.AUTO) {
            return provider.toBuilder().setReasoningHistory("auto").build();
        }

        String model = requiredConfigText(provider.getDefaultModel(),
            "harnex.providers." + name + ".default-model");
        ProviderModelCapabilities.Profile profile =
            capabilityProfile(config, provider, model, "historical reasoning capability for " + path);
        if (profile.reasoningHistory() == ProviderModelCapabilities.ReasoningHistory.NONE) {
            throw configError(path + "=" + mode.configValue()
                + " is not configurable for model " + model + "; use auto");
        }
        if (mode == ReasoningHistoryMode.DISCARD
            && profile.reasoningHistory() == ProviderModelCapabilities.ReasoningHistory.REQUIRED) {
            throw configError(path + "=discard is not supported by model " + model
                + " because complete historical reasoning is required");
        }
        return provider.toBuilder().setReasoningHistory(mode.configValue()).build();
    }

    private static void validateProviderPromptCache(AgentConfig config, ResolvedProvider provider, String name) {
        String path = "harnex.providers." + name + ".features.prompt-cache";
        String model = requiredConfigText(provider.getDefaultModel(),
            "harnex.providers." + name + ".default-model");
        ProviderModelCapabilities.Profile profile =
            capabilityProfile(config, provider, model, "prompt-cache capability for " + path);
        if (profile.promptCacheMode() == ProviderModelCapabilities.PromptCacheMode.NONE) {
            throw configError(path + "=on is not declared by model " + model
                + "; use auto until the endpoint cache contract is verified");
        }
    }

    private static ProviderModelCapabilities.Profile capabilityProfile(AgentConfig config,
        ResolvedProvider provider, String model, String context) {
        try {
            return ProviderModelCapabilities.from(config, provider).profile(model);
        } catch (IllegalArgumentException ex) {
            throw configError("cannot resolve " + context + ": " + ex.getMessage());
        }
    }

    private static void requireSupportedEffort(ProviderModelCapabilities.Profile profile, String effort, String path,
        String model) {
        if (!profile.reasoningEffortsDeclared() || !profile.acceptsEffort(effort)) {
            throw configError(path + "='" + effort + "' is not supported by model " + model
                + "; allowed values=" + profile.reasoningEfforts());
        }
    }

    private static void requireToolCompatibleEffort(ProviderModelCapabilities.Profile profile, String effort,
        String path, String model) {
        String required = profile.functionToolReasoningEffort();
        if (required != null && !required.equals(effort)) {
            throw configError(path + "='" + effort + "' conflicts with function tools on " + model
                + "; this endpoint currently requires effort='" + required
                + "'. Use reasoning=auto/effort=" + required + " or a provider/API profile supporting deeper reasoning");
        }
    }

    private static String defaultEnabledEffort(ProviderModelCapabilities.Profile profile, String path, String model) {
        String toolRequired = profile.functionToolReasoningEffort();
        if (toolRequired != null) {
            if ("none".equals(toolRequired)) {
                throw configError(path + "=on cannot be combined with function tools on " + model
                    + " because this endpoint currently requires effort=none; use the Responses provider or "
                    + "reasoning=auto");
            }
            return toolRequired;
        }
        for (String candidate : List.of("medium", "high", "low", "minimal", "xhigh", "max")) {
            if (profile.acceptsEffort(candidate) && profile.reasoningEfforts().contains(candidate)) {
                return candidate;
            }
        }
        throw configError(path + "=on cannot select a non-zero reasoning effort for model " + model
            + "; allowed values=" + profile.reasoningEfforts());
    }

    private static AgentConfig.ModelDef findExactModel(ResolvedProvider provider, String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        return provider.getModels().stream().filter(Objects::nonNull)
            .filter(model -> model.getId() != null && model.getId().trim().equalsIgnoreCase(modelId.trim()))
            .findFirst().orElse(null);
    }

    private static String requiredConfigText(String value, String path) {
        if (value == null || value.isBlank()) {
            throw configError(path + " must not be blank");
        }
        return value.trim();
    }

    private static String normaliseConfigKey(String value) {
        return requiredConfigText(value, "configuration key").toLowerCase(Locale.ROOT);
    }

    private static void validateStableId(String value, String path) {
        if (!STABLE_ID.matcher(value).matches()) {
            throw configError(path + " '" + value
                + "' must match [A-Za-z0-9][A-Za-z0-9_-]{0,63}; use label for display text");
        }
    }

    private static String normaliseEffort(String configured, String path) {
        if (configured == null || configured.isBlank() || "auto".equalsIgnoreCase(configured.trim())) {
            return null;
        }
        String effort = configured.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max").contains(effort)) {
            throw configError(path + " must be auto|none|minimal|low|medium|high|xhigh|max, got: " + configured);
        }
        return effort;
    }

    private static IllegalArgumentException configError(String message) {
        return new IllegalArgumentException("Invalid harnex configuration: " + message);
    }

    public enum DiagnosticLevel {
        INFO, WARNING
    }

    public record Diagnostic(DiagnosticLevel level, String code, String path, String message) {}

    public record Resolution(List<ResolvedProvider> providers, List<Diagnostic> diagnostics) {
        public Resolution {
            providers = List.copyOf(providers);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private enum FeatureMode {
        AUTO, ON, OFF;

        private static FeatureMode parse(String configured, String path) {
            if (configured == null || configured.isBlank()) {
                return AUTO;
            }
            return switch (configured.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "on" -> ON;
                case "off" -> OFF;
                default -> throw configError(path + " must be auto|on|off, got: " + configured);
            };
        }
    }

    private enum ReasoningHistoryMode {
        AUTO("auto"), PRESERVE("preserve"), DISCARD("discard");

        private final String configValue;

        ReasoningHistoryMode(String configValue) {
            this.configValue = configValue;
        }

        private String configValue() {
            return configValue;
        }

        private static ReasoningHistoryMode parse(String configured, String path) {
            if (configured == null || configured.isBlank()) {
                return AUTO;
            }
            return switch (configured.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "preserve" -> PRESERVE;
                case "discard" -> DISCARD;
                default -> throw configError(path + " must be auto|preserve|discard, got: " + configured);
            };
        }
    }
}
