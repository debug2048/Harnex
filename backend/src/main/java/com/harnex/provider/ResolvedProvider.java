package com.harnex.provider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.harnex.config.AgentConfig;

/**
 * Immutable runtime snapshot of one configured provider.
 *
 * <p>The public configuration DTO remains {@link AgentConfig.ProviderConfig}. A
 * {@link ProviderConfigurationResolver} combines that user-owned entry with a maintainer-owned profile and produces
 * this object. Mutable configuration fragments are copied both on input and output so adapters cannot accidentally
 * change configuration shared by another request or a later registry refresh.</p>
 */
public final class ResolvedProvider {

    private final String name;
    private final String label;
    private final String profile;
    private final String type;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final AgentConfig.ProviderCapabilities capabilities;
    private final List<AgentConfig.ModelCapabilityRule> modelCapabilityRules;
    private final List<AgentConfig.ModelDef> models;
    private final String proxy;
    private final Integer maxTokens;
    private final Integer contextWindow;
    private final String thinking;
    private final String effort;
    private final String reasoningHistory;
    private final Boolean promptCaching;
    private final Boolean promptCacheKey;
    private final Boolean promptCacheExplicit;
    private final List<String> betas;
    private final Boolean sendTemperature;
    private final boolean nativeWebSearch;
    private final boolean emulation;
    private final String emulationVersion;

    private ResolvedProvider(Builder source) {
        name = source.name;
        label = source.label;
        profile = source.profile;
        type = source.type;
        baseUrl = source.baseUrl;
        apiKey = source.apiKey;
        defaultModel = source.defaultModel;
        capabilities = copyCapabilities(source.capabilities);
        modelCapabilityRules = immutableRules(source.modelCapabilityRules);
        models = immutableModels(source.models);
        proxy = source.proxy;
        maxTokens = source.maxTokens;
        contextWindow = source.contextWindow;
        thinking = source.thinking;
        effort = source.effort;
        reasoningHistory = source.reasoningHistory;
        promptCaching = source.promptCaching;
        promptCacheKey = source.promptCacheKey;
        promptCacheExplicit = source.promptCacheExplicit;
        betas = source.betas == null ? List.of() : List.copyOf(source.betas);
        sendTemperature = source.sendTemperature;
        nativeWebSearch = source.nativeWebSearch;
        emulation = source.emulation;
        emulationVersion = source.emulationVersion;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public String getName() {
        return name;
    }

    public String getLabel() {
        return label;
    }

    public String getProfile() {
        return profile;
    }

    public String getType() {
        return type;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public AgentConfig.ProviderCapabilities getCapabilities() {
        return copyCapabilities(capabilities);
    }

    public List<AgentConfig.ModelCapabilityRule> getModelCapabilityRules() {
        return immutableRules(modelCapabilityRules);
    }

    public List<AgentConfig.ModelDef> getModels() {
        return immutableModels(models);
    }

    public String getProxy() {
        return proxy;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public Integer getContextWindow() { return contextWindow; }

    public String getThinking() {
        return thinking;
    }

    public String getEffort() {
        return effort;
    }

    public String getReasoningHistory() {
        return reasoningHistory;
    }

    public Boolean getPromptCaching() {
        return promptCaching;
    }

    public Boolean getPromptCacheKey() {
        return promptCacheKey;
    }

    public Boolean getPromptCacheExplicit() {
        return promptCacheExplicit;
    }

    public List<String> getBetas() {
        return betas;
    }

    public Boolean getSendTemperature() {
        return sendTemperature;
    }

    public boolean isNativeWebSearch() {
        return nativeWebSearch;
    }

    public boolean isEmulation() {
        return emulation;
    }

    public String getEmulationVersion() {
        return emulationVersion;
    }

    /** Mutable construction object. Only {@link #build()} results are published by the runtime registry. */
    public static final class Builder {
        private String name;
        private String label;
        private String profile;
        private String type;
        private String baseUrl;
        private String apiKey;
        private String defaultModel;
        private AgentConfig.ProviderCapabilities capabilities = new AgentConfig.ProviderCapabilities();
        private List<AgentConfig.ModelCapabilityRule> modelCapabilityRules = new ArrayList<>();
        private List<AgentConfig.ModelDef> models = new ArrayList<>();
        private String proxy;
        private Integer maxTokens;
        private Integer contextWindow;
        private String thinking;
        private String effort;
        private String reasoningHistory = "auto";
        private Boolean promptCaching;
        private Boolean promptCacheKey;
        private Boolean promptCacheExplicit;
        private List<String> betas = new ArrayList<>();
        private Boolean sendTemperature;
        private boolean nativeWebSearch;
        private boolean emulation;
        private String emulationVersion;

        private Builder() {}

        private Builder(ResolvedProvider source) {
            name = source.name;
            label = source.label;
            profile = source.profile;
            type = source.type;
            baseUrl = source.baseUrl;
            apiKey = source.apiKey;
            defaultModel = source.defaultModel;
            capabilities = copyCapabilities(source.capabilities);
            modelCapabilityRules = copyRules(source.modelCapabilityRules);
            models = copyModels(source.models);
            proxy = source.proxy;
            maxTokens = source.maxTokens;
            contextWindow = source.contextWindow;
            thinking = source.thinking;
            effort = source.effort;
            reasoningHistory = source.reasoningHistory;
            promptCaching = source.promptCaching;
            promptCacheKey = source.promptCacheKey;
            promptCacheExplicit = source.promptCacheExplicit;
            betas = new ArrayList<>(source.betas);
            sendTemperature = source.sendTemperature;
            nativeWebSearch = source.nativeWebSearch;
            emulation = source.emulation;
            emulationVersion = source.emulationVersion;
        }

        public ResolvedProvider build() {
            return new ResolvedProvider(this);
        }

        public Builder setName(String value) {
            name = value;
            return this;
        }

        public Builder setLabel(String value) {
            label = value;
            return this;
        }

        public Builder setProfile(String value) {
            profile = value;
            return this;
        }

        public Builder setType(String value) {
            type = value;
            return this;
        }

        public Builder setBaseUrl(String value) {
            baseUrl = value;
            return this;
        }

        public Builder setApiKey(String value) {
            apiKey = value;
            return this;
        }

        public Builder setDefaultModel(String value) {
            defaultModel = value;
            return this;
        }

        public Builder setCapabilities(AgentConfig.ProviderCapabilities value) {
            capabilities = copyCapabilities(value);
            return this;
        }

        public AgentConfig.ProviderCapabilities getCapabilities() {
            return capabilities;
        }

        public Builder setModelCapabilityRules(List<AgentConfig.ModelCapabilityRule> value) {
            modelCapabilityRules = copyRules(value);
            return this;
        }

        public Builder setModels(List<AgentConfig.ModelDef> value) {
            models = copyModels(value);
            return this;
        }

        public Builder setProxy(String value) {
            proxy = value;
            return this;
        }

        public Builder setMaxTokens(Integer value) {
            maxTokens = value;
            return this;
        }

        public Builder setContextWindow(Integer value) {
            contextWindow = value;
            return this;
        }

        public Builder setThinking(String value) {
            thinking = value;
            return this;
        }

        public Builder setEffort(String value) {
            effort = value;
            return this;
        }

        public Builder setReasoningHistory(String value) {
            reasoningHistory = value;
            return this;
        }

        public Builder setPromptCaching(Boolean value) {
            promptCaching = value;
            return this;
        }

        public Builder setPromptCacheKey(Boolean value) {
            promptCacheKey = value;
            return this;
        }

        public Builder setPromptCacheExplicit(Boolean value) {
            promptCacheExplicit = value;
            return this;
        }

        public Builder setBetas(List<String> value) {
            betas = value == null ? new ArrayList<>() : new ArrayList<>(value);
            return this;
        }

        public Builder setSendTemperature(Boolean value) {
            sendTemperature = value;
            return this;
        }

        public Builder setNativeWebSearch(boolean value) {
            nativeWebSearch = value;
            return this;
        }

        public Builder setEmulation(boolean value) {
            emulation = value;
            return this;
        }

        public Builder setEmulationVersion(String value) {
            emulationVersion = value;
            return this;
        }
    }

    static AgentConfig.ProviderCapabilities copyCapabilities(AgentConfig.ProviderCapabilities source) {
        if (source == null) {
            return null;
        }
        AgentConfig.ProviderCapabilities copy = new AgentConfig.ProviderCapabilities();
        copy.setInstructionRole(source.getInstructionRole());
        copy.setMaxTokensField(source.getMaxTokensField());
        copy.setDefaultMaxTokens(source.getDefaultMaxTokens());
        copy.setOutputReserveTokens(source.getOutputReserveTokens());
        copy.setMaxInputTokens(source.getMaxInputTokens());
        copy.setAudioInputEncoding(source.getAudioInputEncoding());
        copy.setStreaming(source.getStreaming());
        copy.setStreamUsage(source.getStreamUsage());
        copy.setFunctionTools(source.getFunctionTools());
        copy.setSamplingPolicy(source.getSamplingPolicy());
        copy.setReasoningEfforts(source.getReasoningEfforts() == null
            ? null : new ArrayList<>(source.getReasoningEfforts()));
        copy.setReasoningReplay(source.getReasoningReplay());
        copy.setReasoningHistory(source.getReasoningHistory());
        copy.setReasoningHistoryField(source.getReasoningHistoryField());
        copy.setFunctionToolReasoningEffort(source.getFunctionToolReasoningEffort());
        copy.setThinkingRequiredEfforts(source.getThinkingRequiredEfforts() == null
            ? null : new ArrayList<>(source.getThinkingRequiredEfforts()));
        copy.setPromptCacheMode(source.getPromptCacheMode());
        copy.setStrictTerminal(source.getStrictTerminal());
        copy.setThinkingPrefixBound(source.getThinkingPrefixBound());
        copy.setNativeWebSearch(source.getNativeWebSearch());
        copy.setNativeWebSearchType(source.getNativeWebSearchType());
        copy.setThinking(copyThinking(source.getThinking()));
        copy.setRequestOptions(copyMap(source.getRequestOptions()));
        return copy;
    }

    static List<AgentConfig.ModelCapabilityRule> copyRules(List<AgentConfig.ModelCapabilityRule> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        List<AgentConfig.ModelCapabilityRule> copy = new ArrayList<>(source.size());
        for (AgentConfig.ModelCapabilityRule rule : source) {
            if (rule == null) {
                copy.add(null);
                continue;
            }
            AgentConfig.ModelCapabilityRule item = new AgentConfig.ModelCapabilityRule();
            item.setMatch(rule.getMatch());
            item.setProfile(rule.getProfile());
            item.setCapabilities(copyCapabilities(rule.getCapabilities()));
            copy.add(item);
        }
        return copy;
    }

    static List<AgentConfig.ModelDef> copyModels(List<AgentConfig.ModelDef> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        List<AgentConfig.ModelDef> copy = new ArrayList<>(source.size());
        for (AgentConfig.ModelDef model : source) {
            if (model == null) {
                copy.add(null);
                continue;
            }
            AgentConfig.ModelDef item = new AgentConfig.ModelDef();
            item.setId(model.getId());
            item.setLabel(model.getLabel());
            item.setTier(model.getTier());
            item.setCostHint(model.getCostHint());
            item.setInputCostPerMillion(model.getInputCostPerMillion());
            item.setOutputCostPerMillion(model.getOutputCostPerMillion());
            item.setCachedInputCostPerMillion(model.getCachedInputCostPerMillion());
            item.setCacheWriteInputCostPerMillion(model.getCacheWriteInputCostPerMillion());
            item.setContextWindow(model.getContextWindow());
            item.setMaxOutputTokens(model.getMaxOutputTokens());
            item.setDescription(model.getDescription());
            item.setProfile(model.getProfile());
            item.setCapabilityModel(model.getCapabilityModel());
            item.setCapabilities(copyCapabilities(model.getCapabilities()));
            item.setInputModalities(model.getInputModalities() == null
                ? new ArrayList<>() : new ArrayList<>(model.getInputModalities()));
            copy.add(item);
        }
        return copy;
    }

    private static List<AgentConfig.ModelCapabilityRule> immutableRules(
        List<AgentConfig.ModelCapabilityRule> source) {
        return Collections.unmodifiableList(copyRules(source));
    }

    private static List<AgentConfig.ModelDef> immutableModels(List<AgentConfig.ModelDef> source) {
        return Collections.unmodifiableList(copyModels(source));
    }

    private static AgentConfig.ThinkingCapabilities copyThinking(AgentConfig.ThinkingCapabilities source) {
        if (source == null) {
            return null;
        }
        AgentConfig.ThinkingCapabilities copy = new AgentConfig.ThinkingCapabilities();
        copy.setField(source.getField());
        copy.setDefaultState(source.getDefaultState());
        copy.setEnabledValue(copyValue(source.getEnabledValue()));
        copy.setDisabledValue(copyValue(source.getDisabledValue()));
        copy.setEnabledObject(copyMap(source.getEnabledObject()));
        copy.setDisabledObject(copyMap(source.getDisabledObject()));
        copy.setSendDefault(source.getSendDefault());
        return copy;
    }

    private static Map<String, Object> copyMap(Map<String, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> copy.put(key, copyValue(value)));
        }
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, copyValue(item)));
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(item -> copy.add(copyValue(item)));
            return copy;
        }
        return value;
    }
}
