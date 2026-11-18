package com.harnex.provider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.harnex.config.AgentConfig;

/**
 * Immutable, configuration-owned provider/model capability registry.
 *
 * <p>Provider {@code type} selects only the HTTP/SSE protocol baseline. Every capability that varies by vendor or
 * model is supplied by {@code harnex.provider-profiles}, an ordered provider-local full-match rule, and an optional
 * exact catalog overlay. Java never embeds vendor/model-name heuristics; a provider must opt into every family rule in
 * configuration, so relay aliases do not inherit official capabilities accidentally.</p>
 */
public final class ProviderModelCapabilities {

    private static final Set<String> REASONING_EFFORTS =
        Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> INPUT_MODALITIES =
        Set.of("text", "image", "file", "pdf", "audio", "video");
    private static final Pattern REQUEST_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> RESERVED_REQUEST_OPTIONS = Set.of(
        "model", "messages", "input", "instructions", "system", "tools", "tool_choice", "stream",
        "stream_options", "max_tokens", "max_completion_tokens", "max_output_tokens", "reasoning_effort",
        "reasoning", "thinking", "enable_thinking", "temperature", "prompt_cache_key", "prompt_cache_options",
        "output_config", "generation_config", "system_instruction", "previous_interaction_id", "store", "include");

    private final Profile providerProfile;
    private final Map<String, Profile> modelProfiles;
    private final List<RuleProfile> modelRules;
    private final Map<String, String> exactModelRuleMatches;

    private ProviderModelCapabilities(Profile providerProfile, Map<String, Profile> modelProfiles,
        List<RuleProfile> modelRules, Map<String, String> exactModelRuleMatches) {
        this.providerProfile = Objects.requireNonNull(providerProfile, "providerProfile");
        this.modelProfiles = Collections.unmodifiableMap(new LinkedHashMap<>(modelProfiles));
        this.modelRules = List.copyOf(modelRules);
        this.exactModelRuleMatches = Collections.unmodifiableMap(new LinkedHashMap<>(exactModelRuleMatches));
    }

    /** Build a registry using {@link ResolvedProvider#getType()} as the protocol selector. */
    public static ProviderModelCapabilities from(AgentConfig config, ResolvedProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return from(config, provider, provider.getType());
    }

    /** Builder convenience for focused tests and programmatic composition; resolution always freezes a snapshot. */
    public static ProviderModelCapabilities from(AgentConfig config, ResolvedProvider.Builder provider) {
        Objects.requireNonNull(provider, "provider");
        return from(config, provider.build());
    }

    /**
     * Build immutable provider, rule, and exact-model profiles. Resolution composes the provider baseline, the first
     * ordered matching rule, and finally an exact-model overlay. Attachment modalities exist only on exact profiles.
     */
    public static ProviderModelCapabilities from(AgentConfig config, ResolvedProvider provider,
        String providerType) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(provider, "provider");

        Api expectedApi = apiForProviderType(providerType);
        Map<String, AgentConfig.ProviderProfileDef> profiles = indexProfiles(config.getProviderProfiles());
        MutableProfile providerBuilder = MutableProfile.baseline(expectedApi);
        providerBuilder.merge(provider.getCapabilities(), "provider capabilities");
        Profile providerProfile = providerBuilder.freeze(false, Set.of(), "provider");
        validateProfileForApi(providerProfile, expectedApi, "provider");

        List<RuleProfile> rules = new ArrayList<>();
        List<ConfiguredRule> compiledRules = new ArrayList<>();
        List<AgentConfig.ModelCapabilityRule> configuredRules = provider.getModelCapabilityRules();
        if (configuredRules != null) {
            int index = 0;
            for (AgentConfig.ModelCapabilityRule rule : configuredRules) {
                String context = "model-capability-rules[" + index + "]";
                index++;
                if (rule == null) {
                    throw invalid(context + " must not be null");
                }
                String expression = requiredText(rule.getMatch(), context + ".match");
                Pattern pattern;
                try {
                    pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                } catch (PatternSyntaxException ex) {
                    throw invalid(context + ".match has invalid regex '" + expression + "': "
                        + ex.getDescription());
                }
                MutableProfile ruleBuilder = MutableProfile.copyOf(providerBuilder);
                mergeProfile(ruleBuilder, rule.getProfile(), profiles, expectedApi, context + " profile");
                ruleBuilder.merge(rule.getCapabilities(), context + " capabilities");
                Profile profile = ruleBuilder.freeze(false, Set.of(), context);
                validateProfileForApi(profile, expectedApi, context);
                rules.add(new RuleProfile(pattern, profile, context));
                compiledRules.add(new ConfiguredRule(pattern, rule, context));
            }
        }

        Map<String, Profile> models = new LinkedHashMap<>();
        Map<String, String> exactRuleMatches = new LinkedHashMap<>();
        List<AgentConfig.ModelDef> configuredModels = provider.getModels();
        if (configuredModels != null) {
            for (AgentConfig.ModelDef model : configuredModels) {
                if (model == null || model.getId() == null || model.getId().isBlank()) {
                    continue;
                }
                String resolvedId = resolveEnv(model.getId());
                if (resolvedId == null || resolvedId.isBlank()) {
                    continue;
                }
                String key = normaliseId(resolvedId);
                if (models.containsKey(key)) {
                    throw invalid("Duplicate exact model id (case-insensitive): '" + resolvedId.trim() + "'");
                }

                MutableProfile modelBuilder = MutableProfile.copyOf(providerBuilder);
                String capabilityModel = resolvedId;
                boolean explicitCapabilityModel = model.getCapabilityModel() != null;
                if (explicitCapabilityModel) {
                    capabilityModel = requiredText(resolveEnv(model.getCapabilityModel()),
                        "model '" + resolvedId.trim() + "'.capability-model");
                }
                ConfiguredRule matchingRule = firstMatchingRule(compiledRules, capabilityModel);
                if (explicitCapabilityModel && matchingRule == null) {
                    throw invalid("model '" + resolvedId.trim() + "'.capability-model '" + capabilityModel
                        + "' does not match any rule in the selected provider profile");
                }
                if (matchingRule != null) {
                    AgentConfig.ModelCapabilityRule rule = matchingRule.rule();
                    mergeProfile(modelBuilder, rule.getProfile(), profiles, expectedApi,
                        "model '" + resolvedId.trim() + "' capability-model '" + capabilityModel
                            + "' inherited " + matchingRule.context() + " profile");
                    modelBuilder.merge(rule.getCapabilities(),
                        "model '" + resolvedId.trim() + "' capability-model '" + capabilityModel
                            + "' inherited " + matchingRule.context() + " capabilities");
                    exactRuleMatches.put(key, matchingRule.pattern().pattern());
                }
                mergeProfile(modelBuilder, model.getProfile(), profiles, expectedApi,
                    "model '" + resolvedId.trim() + "' profile");
                modelBuilder.merge(model.getCapabilities(), "model '" + resolvedId.trim() + "' capabilities");
                Set<String> modalities = normaliseModalities(model.getInputModalities());
                Profile profile = modelBuilder.freeze(true, modalities, "model '" + resolvedId.trim() + "'");
                validateProfileForApi(profile, expectedApi, "model '" + resolvedId.trim() + "'");
                models.put(key, profile);
            }
        }
        return new ProviderModelCapabilities(providerProfile, models, rules, exactRuleMatches);
    }

    /** A baseline-only registry for constructor defaults and tests. It never guesses from model ids. */
    public static ProviderModelCapabilities conservative(String providerType) {
        MutableProfile baseline = MutableProfile.baseline(apiForProviderType(providerType));
        return new ProviderModelCapabilities(baseline.freeze(false, Set.of(), "protocol baseline"), Map.of(),
            List.of(), Map.of());
    }

    /** Resolve the provider profile, first full-match rule, and exact overlay. */
    public Profile profile(String model) {
        return resolution(model).profile();
    }

    /** Explain the profile source selected for one model without exposing mutable configuration fragments. */
    public Resolution resolution(String model) {
        if (model == null || model.isBlank()) {
            return new Resolution(providerProfile, ResolutionSource.PROVIDER, null);
        }
        String normalised = normaliseId(model);
        Profile exact = modelProfiles.get(normalised);
        if (exact != null) {
            return new Resolution(exact, ResolutionSource.EXACT, exactModelRuleMatches.get(normalised));
        }
        for (RuleProfile rule : modelRules) {
            if (rule.pattern().matcher(model.trim()).matches()) {
                return new Resolution(rule.profile(), ResolutionSource.RULE, rule.pattern().pattern());
            }
        }
        return new Resolution(providerProfile, ResolutionSource.PROVIDER, null);
    }

    /** Alias suited to adapter call sites. */
    public Profile forModel(String model) {
        return profile(model);
    }

    public Profile providerProfile() {
        return providerProfile;
    }

    /** Exact, normalised model ids present in the provider catalog. */
    public Set<String> configuredModels() {
        return modelProfiles.keySet();
    }

    public enum ResolutionSource {
        PROVIDER, RULE, EXACT
    }

    /**
     * Resolution diagnostic. {@code matchedRule} is populated for a rule result and for an exact model that inherited
     * a matching rule before applying its exact overlay.
     */
    public record Resolution(Profile profile, ResolutionSource source, String matchedRule) {}

    private record RuleProfile(Pattern pattern, Profile profile, String context) {}

    private record ConfiguredRule(Pattern pattern, AgentConfig.ModelCapabilityRule rule, String context) {}

    /**
     * Provider 内部使用的协议分类。它由 provider/profile 的 {@code type}
     * 单向推导，不作为独立 YAML 配置项暴露给用户。
     */
    public enum Api {
        CHAT("chat"), RESPONSES("responses"), ANTHROPIC("anthropic"), GEMINI("gemini");

        private final String wireName;

        Api(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum MaxTokensField {
        MAX_TOKENS("max_tokens"),
        MAX_COMPLETION_TOKENS("max_completion_tokens"),
        MAX_OUTPUT_TOKENS("max_output_tokens");

        private final String wireName;

        MaxTokensField(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum AudioInputEncoding {
        BASE64, DATA_URL
    }

    public enum ReasoningReplay {
        NEVER("never"), TOOL_CALLS("tool_calls"), ALL("all"), NATIVE_ITEMS("native_items");

        private final String configValue;

        ReasoningReplay(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }

    public enum ReasoningHistory {
        NONE("none"), OPTIONAL("optional"), REQUIRED("required");

        private final String configValue;

        ReasoningHistory(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }

    public enum PromptCacheMode {
        NONE("none"),
        AUTO_PREFIX("auto_prefix"),
        KEY("key"),
        OPENAI_EXPLICIT("openai_explicit"),
        CONTENT_CACHE_CONTROL("content_cache_control");

        private final String configValue;

        PromptCacheMode(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }

    public enum SamplingPolicy {
        ALWAYS("always"), WHEN_THINKING_OFF("when_thinking_off"), OMIT("omit");

        private final String configValue;

        SamplingPolicy(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }

    public enum ThinkingState {
        OFF("off"), ON("on"), ALWAYS_ON("always_on");

        private final String configValue;

        ThinkingState(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }

    /** Generic top-level thinking field/value contract. */
    public record ThinkingSpec(String field, ThinkingState defaultState, Object enabledValue, Object disabledValue,
        boolean sendDefault) {

        public ThinkingSpec {
            enabledValue = immutableValue(enabledValue);
            disabledValue = immutableValue(disabledValue);
        }

        public static ThinkingSpec none() {
            return new ThinkingSpec(null, null, null, null, false);
        }

        public boolean supported() {
            return field != null;
        }

        public boolean alwaysOn() {
            return defaultState == ThinkingState.ALWAYS_ON;
        }
    }

    /**
     * Effective capabilities for one request model. Collection-valued components and nested configuration values are
     * immutable snapshots.
     */
    public record Profile(boolean declared, boolean exactModel, Api api, boolean streaming, boolean streamUsage,
        boolean functionTools, SamplingPolicy samplingPolicy, Set<String> reasoningEfforts,
        boolean reasoningEffortsDeclared, ReasoningReplay reasoningReplay, String functionToolReasoningEffort,
        Set<String> thinkingRequiredEfforts,
        ReasoningHistory reasoningHistory, String reasoningHistoryField,
        String instructionRole, MaxTokensField maxTokensField, Integer defaultMaxTokens, Integer outputReserveTokens,
        AudioInputEncoding audioInputEncoding,
        PromptCacheMode promptCacheMode, boolean strictTerminal, boolean nativeWebSearch, String nativeWebSearchType,
        ThinkingSpec thinking, Set<String> inputModalities, Map<String, Object> requestOptions,
        boolean thinkingPrefixBound, Integer maxInputTokens) {

        public Profile {
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(samplingPolicy, "samplingPolicy");
            Objects.requireNonNull(reasoningReplay, "reasoningReplay");
            Objects.requireNonNull(reasoningHistory, "reasoningHistory");
            Objects.requireNonNull(maxTokensField, "maxTokensField");
            Objects.requireNonNull(audioInputEncoding, "audioInputEncoding");
            Objects.requireNonNull(promptCacheMode, "promptCacheMode");
            thinking = thinking == null ? ThinkingSpec.none() : thinking;
            reasoningEfforts = immutableStringSet(reasoningEfforts);
            thinkingRequiredEfforts = immutableStringSet(thinkingRequiredEfforts);
            inputModalities = immutableStringSet(inputModalities);
            requestOptions = immutableStringObjectMap(requestOptions);
        }

        /** Compatibility alias: "known" means capabilities were explicitly declared, not that Java knows a model. */
        public boolean known() {
            return declared;
        }

        public boolean responsesApi() {
            return api == Api.RESPONSES;
        }

        public boolean chatCompletionsApi() {
            return api == Api.CHAT;
        }

        public boolean anthropicApi() {
            return api == Api.ANTHROPIC;
        }

        public boolean geminiApi() {
            return api == Api.GEMINI;
        }

        public boolean acceptsEffort(String effort) {
            if (effort == null || effort.isBlank() || !reasoningEffortsDeclared) {
                return true;
            }
            return reasoningEfforts.contains(effort.trim().toLowerCase(Locale.ROOT));
        }

        /** Whether this profile explicitly declares a reasoning-capable model. */
        public boolean reasoningSupported() {
            return reasoningEffortsDeclared && !reasoningEfforts.isEmpty();
        }

        public boolean supportsModality(String modality) {
            return modality != null && inputModalities.contains(modality.trim().toLowerCase(Locale.ROOT));
        }

        public boolean imageInput() {
            return supportsModality("image");
        }

        public boolean fileInput() {
            return supportsModality("file") || supportsModality("pdf");
        }

        public boolean promptCacheKey() {
            return promptCacheMode == PromptCacheMode.KEY || promptCacheMode == PromptCacheMode.OPENAI_EXPLICIT;
        }

        public boolean promptCacheExplicit() {
            return promptCacheMode == PromptCacheMode.OPENAI_EXPLICIT;
        }

        public String maxTokensFieldName() {
            return maxTokensField.wireName();
        }

    }

    private static final class MutableProfile {
        private boolean declared;
        private Api api;
        private boolean streaming;
        private boolean streamUsage;
        private boolean functionTools;
        private SamplingPolicy samplingPolicy;
        private Set<String> reasoningEfforts = Set.of();
        private boolean reasoningEffortsDeclared;
        private ReasoningReplay reasoningReplay;
        private String functionToolReasoningEffort;
        private Set<String> thinkingRequiredEfforts = Set.of();
        private ReasoningHistory reasoningHistory;
        private String reasoningHistoryField;
        private String instructionRole;
        private MaxTokensField maxTokensField;
        private Integer defaultMaxTokens;
        private Integer outputReserveTokens;
        private Integer maxInputTokens;
        private AudioInputEncoding audioInputEncoding = AudioInputEncoding.BASE64;
        private PromptCacheMode promptCacheMode;
        private boolean strictTerminal;
        private boolean thinkingPrefixBound;
        private boolean nativeWebSearch;
        private String nativeWebSearchType;
        private String thinkingField;
        private ThinkingState thinkingDefaultState;
        private Object thinkingEnabledValue;
        private Object thinkingDisabledValue;
        private Boolean thinkingSendDefault;
        private final Map<String, Object> requestOptions = new LinkedHashMap<>();

        private static MutableProfile baseline(Api api) {
            MutableProfile p = new MutableProfile();
            p.api = api;
            p.streaming = true;
            p.streamUsage = false;
            p.functionTools = true;
            p.reasoningReplay = api == Api.CHAT ? ReasoningReplay.NEVER : ReasoningReplay.NATIVE_ITEMS;
            p.reasoningHistory = ReasoningHistory.NONE;
            p.promptCacheMode = PromptCacheMode.NONE;
            p.strictTerminal = false;
            p.thinkingSendDefault = false;
            switch (api) {
                case CHAT -> {
                    p.samplingPolicy = SamplingPolicy.ALWAYS;
                    p.instructionRole = "system";
                    p.maxTokensField = MaxTokensField.MAX_TOKENS;
                }
                case RESPONSES -> {
                    p.samplingPolicy = SamplingPolicy.OMIT;
                    p.instructionRole = null;
                    p.maxTokensField = MaxTokensField.MAX_OUTPUT_TOKENS;
                }
                case ANTHROPIC -> {
                    p.samplingPolicy = SamplingPolicy.ALWAYS;
                    p.instructionRole = null;
                    p.maxTokensField = MaxTokensField.MAX_TOKENS;
                    // Messages requires this field. Preserve the legacy budget for undeclared models;
                    // named model profiles supply their own required default.
                    p.defaultMaxTokens = 16384;
                }
                case GEMINI -> {
                    p.samplingPolicy = SamplingPolicy.OMIT;
                    p.instructionRole = null;
                    p.maxTokensField = MaxTokensField.MAX_OUTPUT_TOKENS;
                }
            }
            return p;
        }

        private static MutableProfile copyOf(MutableProfile source) {
            MutableProfile copy = new MutableProfile();
            copy.declared = source.declared;
            copy.api = source.api;
            copy.streaming = source.streaming;
            copy.streamUsage = source.streamUsage;
            copy.functionTools = source.functionTools;
            copy.samplingPolicy = source.samplingPolicy;
            copy.reasoningEfforts = source.reasoningEfforts;
            copy.reasoningEffortsDeclared = source.reasoningEffortsDeclared;
            copy.reasoningReplay = source.reasoningReplay;
            copy.functionToolReasoningEffort = source.functionToolReasoningEffort;
            copy.thinkingRequiredEfforts = source.thinkingRequiredEfforts;
            copy.reasoningHistory = source.reasoningHistory;
            copy.reasoningHistoryField = source.reasoningHistoryField;
            copy.instructionRole = source.instructionRole;
            copy.maxTokensField = source.maxTokensField;
            copy.defaultMaxTokens = source.defaultMaxTokens;
            copy.outputReserveTokens = source.outputReserveTokens;
            copy.maxInputTokens = source.maxInputTokens;
            copy.audioInputEncoding = source.audioInputEncoding;
            copy.promptCacheMode = source.promptCacheMode;
            copy.strictTerminal = source.strictTerminal;
            copy.thinkingPrefixBound = source.thinkingPrefixBound;
            copy.nativeWebSearch = source.nativeWebSearch;
            copy.nativeWebSearchType = source.nativeWebSearchType;
            copy.thinkingField = source.thinkingField;
            copy.thinkingDefaultState = source.thinkingDefaultState;
            copy.thinkingEnabledValue = source.thinkingEnabledValue;
            copy.thinkingDisabledValue = source.thinkingDisabledValue;
            copy.thinkingSendDefault = source.thinkingSendDefault;
            copy.requestOptions.putAll(source.requestOptions);
            return copy;
        }

        private void merge(AgentConfig.ProviderCapabilities fragment, String context) {
            if (fragment == null) {
                return;
            }
            if (hasExplicitCapability(fragment)) {
                declared = true;
            }
            if (fragment.getInstructionRole() != null) {
                instructionRole = parseInstructionRole(fragment.getInstructionRole(), context + ".instruction-role");
            }
            if (fragment.getMaxTokensField() != null) {
                maxTokensField = parseMaxTokensField(fragment.getMaxTokensField(), context + ".max-tokens-field");
            }
            if (fragment.getDefaultMaxTokens() != null) {
                if (fragment.getDefaultMaxTokens() <= 0) {
                    throw invalid(context + ".default-max-tokens must be > 0");
                }
                defaultMaxTokens = fragment.getDefaultMaxTokens();
            }
            if (fragment.getOutputReserveTokens() != null) {
                if (fragment.getOutputReserveTokens() <= 0) throw invalid(context + ".output-reserve-tokens must be > 0");
                outputReserveTokens = fragment.getOutputReserveTokens();
            }
            if (fragment.getMaxInputTokens() != null) {
                if (fragment.getMaxInputTokens() <= 0 || fragment.getMaxInputTokens() > 2_000_000)
                    throw invalid(context + ".max-input-tokens must be between 1 and 2000000");
                maxInputTokens = fragment.getMaxInputTokens();
            }
            if (fragment.getAudioInputEncoding() != null) {
                if (api != Api.CHAT) {
                    throw invalid(context + ".audio-input-encoding is supported only by the Chat adapter");
                }
                audioInputEncoding = switch (policy(fragment.getAudioInputEncoding(), context + ".audio-input-encoding")) {
                    case "base64" -> AudioInputEncoding.BASE64;
                    case "data_url" -> AudioInputEncoding.DATA_URL;
                    default -> throw invalidEnum(context + ".audio-input-encoding", fragment.getAudioInputEncoding(),
                        "base64|data_url");
                };
            }
            if (fragment.getStreaming() != null) {
                streaming = fragment.getStreaming();
            }
            if (fragment.getStreamUsage() != null) {
                streamUsage = fragment.getStreamUsage();
            }
            if (fragment.getFunctionTools() != null) {
                functionTools = fragment.getFunctionTools();
            }
            if (fragment.getSamplingPolicy() != null) {
                samplingPolicy = parseSamplingPolicy(fragment.getSamplingPolicy(), context + ".sampling-policy");
            }
            if (fragment.getReasoningEfforts() != null) {
                reasoningEfforts = normaliseReasoningEfforts(fragment.getReasoningEfforts(),
                    context + ".reasoning-efforts");
                reasoningEffortsDeclared = true;
            }
            if (fragment.getReasoningReplay() != null) {
                reasoningReplay = parseReasoningReplay(fragment.getReasoningReplay(),
                    context + ".reasoning-replay");
            }
            if (fragment.getReasoningHistory() != null) {
                reasoningHistory = parseReasoningHistory(fragment.getReasoningHistory(),
                    context + ".reasoning-history");
            }
            if (fragment.getReasoningHistoryField() != null) {
                reasoningHistoryField = requiredText(fragment.getReasoningHistoryField(),
                    context + ".reasoning-history-field");
            }
            if (fragment.getFunctionToolReasoningEffort() != null) {
                functionToolReasoningEffort = parseFunctionToolReasoningEffort(
                    fragment.getFunctionToolReasoningEffort(), context + ".function-tool-reasoning-effort");
            }
            if (fragment.getThinkingRequiredEfforts() != null) {
                thinkingRequiredEfforts = normaliseReasoningEfforts(fragment.getThinkingRequiredEfforts(),
                    context + ".thinking-required-efforts");
            }
            if (fragment.getPromptCacheMode() != null) {
                promptCacheMode = parsePromptCacheMode(fragment.getPromptCacheMode(), context + ".prompt-cache-mode");
            }
            if (fragment.getStrictTerminal() != null) {
                strictTerminal = fragment.getStrictTerminal();
            }
            if (fragment.getThinkingPrefixBound() != null) {
                thinkingPrefixBound = fragment.getThinkingPrefixBound();
            }
            if (fragment.getNativeWebSearch() != null) {
                nativeWebSearch = fragment.getNativeWebSearch();
            }
            if (fragment.getNativeWebSearchType() != null) {
                nativeWebSearchType = requiredText(fragment.getNativeWebSearchType(),
                    context + ".native-web-search-type");
            }
            mergeThinking(fragment.getThinking(), context + ".thinking");
            mergeRequestOptions(fragment.getRequestOptions(), context + ".request-options");
        }

        private void mergeThinking(AgentConfig.ThinkingCapabilities thinking, String context) {
            if (thinking == null) {
                return;
            }
            boolean hasEnabledObject = thinking.getEnabledObject() != null && !thinking.getEnabledObject().isEmpty();
            boolean hasDisabledObject = thinking.getDisabledObject() != null && !thinking.getDisabledObject().isEmpty();
            if (thinking.getEnabledValue() != null && hasEnabledObject) {
                throw invalid(context + " cannot declare both enabled-value and enabled-object");
            }
            if (thinking.getDisabledValue() != null && hasDisabledObject) {
                throw invalid(context + " cannot declare both disabled-value and disabled-object");
            }
            if (thinking.getField() != null) {
                thinkingField = requiredText(thinking.getField(), context + ".field");
            }
            if (thinking.getDefaultState() != null) {
                thinkingDefaultState = parseThinkingState(thinking.getDefaultState(), context + ".default-state");
            }
            if (thinking.getEnabledValue() != null) {
                thinkingEnabledValue = immutableValue(thinking.getEnabledValue());
            } else if (hasEnabledObject) {
                thinkingEnabledValue = immutableValue(thinking.getEnabledObject());
            }
            if (thinking.getDisabledValue() != null) {
                thinkingDisabledValue = immutableValue(thinking.getDisabledValue());
            } else if (hasDisabledObject) {
                thinkingDisabledValue = immutableValue(thinking.getDisabledObject());
            }
            if (thinking.getSendDefault() != null) {
                thinkingSendDefault = thinking.getSendDefault();
            }
        }

        private void mergeRequestOptions(Map<String, Object> options, String context) {
            if (options == null || options.isEmpty()) {
                return;
            }
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                String key = requiredText(entry.getKey(), context + " key");
                if (RESERVED_REQUEST_OPTIONS.contains(key.toLowerCase(Locale.ROOT))) {
                    throw invalid(context + " cannot override protocol-owned field '" + key + "'");
                }
                requestOptions.put(key, immutableValue(entry.getValue()));
            }
        }

        private Profile freeze(boolean exactModel, Set<String> modalities, String context) {
            if (functionToolReasoningEffort != null) {
                if (!functionTools) {
                    throw invalid(context + " function-tool-reasoning-effort requires function-tools=true");
                }
                if (!reasoningEffortsDeclared || !reasoningEfforts.contains(functionToolReasoningEffort)) {
                    throw invalid(context + " function-tool-reasoning-effort '" + functionToolReasoningEffort
                        + "' is not present in reasoning-efforts " + reasoningEfforts);
                }
            }
            ThinkingSpec thinking = buildThinking(context);
            if (!thinkingRequiredEfforts.isEmpty()) {
                if (api != Api.ANTHROPIC || !thinking.supported()) {
                    throw invalid(context + " thinking-required-efforts requires Anthropic thinking support");
                }
                if (!reasoningEffortsDeclared || !reasoningEfforts.containsAll(thinkingRequiredEfforts)
                    || thinkingRequiredEfforts.contains("none")) {
                    throw invalid(context + " thinking-required-efforts must be non-zero values in reasoning-efforts");
                }
            }
            validateReasoningHistory(context, thinking);
            if (nativeWebSearch && (nativeWebSearchType == null || nativeWebSearchType.isBlank())) {
                throw invalid(context + " native-web-search-type is required when native-web-search=true");
            }
            boolean capabilityDeclared = declared || (modalities != null && !modalities.isEmpty());
            return new Profile(capabilityDeclared, exactModel, api, streaming, streamUsage, functionTools,
                samplingPolicy, reasoningEfforts, reasoningEffortsDeclared, reasoningReplay,
                functionToolReasoningEffort, thinkingRequiredEfforts, reasoningHistory, reasoningHistoryField,
                instructionRole, maxTokensField, defaultMaxTokens, outputReserveTokens, audioInputEncoding, promptCacheMode, strictTerminal,
                nativeWebSearch, nativeWebSearchType, thinking, modalities, requestOptions, thinkingPrefixBound, maxInputTokens);
        }

        private void validateReasoningHistory(String context, ThinkingSpec thinking) {
            if (reasoningHistory == ReasoningHistory.NONE) {
                if (reasoningHistoryField != null) {
                    throw invalid(context
                        + " reasoning-history-field requires reasoning-history=optional|required");
                }
                return;
            }
            if (api != Api.CHAT) {
                throw invalid(context + " reasoning-history is currently supported only by the Chat adapter");
            }
            if (reasoningReplay != ReasoningReplay.ALL) {
                throw invalid(context
                    + " reasoning-history=optional|required requires reasoning-replay=all");
            }
            if (reasoningHistoryField == null) {
                return;
            }
            if (!REQUEST_FIELD.matcher(reasoningHistoryField).matches()) {
                throw invalid(context + " reasoning-history-field must be a top-level JSON field name");
            }
            if (thinking.supported() && reasoningHistoryField.equalsIgnoreCase(thinking.field())) {
                throw invalid(context + " reasoning-history-field cannot reuse thinking.field '"
                    + reasoningHistoryField + "'");
            }
            for (String option : requestOptions.keySet()) {
                if (reasoningHistoryField.equalsIgnoreCase(option)) {
                    throw invalid(context + " request-options cannot override reasoning-history-field '"
                        + reasoningHistoryField + "'");
                }
            }
        }

        private ThinkingSpec buildThinking(String context) {
            boolean configured = thinkingField != null || thinkingDefaultState != null || thinkingEnabledValue != null
                || thinkingDisabledValue != null || Boolean.TRUE.equals(thinkingSendDefault);
            if (!configured) {
                return ThinkingSpec.none();
            }
            if (thinkingField == null) {
                throw invalid(context + " thinking.field is required when thinking is configured");
            }
            if (thinkingDefaultState == null) {
                throw invalid(context + " thinking.default-state is required when thinking is configured");
            }
            if (thinkingEnabledValue == null) {
                throw invalid(context + " thinking.enabled-value is required when thinking is configured");
            }
            String normalisedField = thinkingField.toLowerCase(Locale.ROOT);
            if (RESERVED_REQUEST_OPTIONS.contains(normalisedField)
                && !"thinking".equals(normalisedField) && !"enable_thinking".equals(normalisedField)) {
                throw invalid(context + " thinking.field cannot override protocol-owned field '"
                    + thinkingField + "'");
            }
            for (String option : requestOptions.keySet()) {
                if (thinkingField.equalsIgnoreCase(option)) {
                    throw invalid(context + " request-options cannot override configured thinking field '"
                        + thinkingField + "'");
                }
            }
            return new ThinkingSpec(thinkingField, thinkingDefaultState, thinkingEnabledValue,
                thinkingDefaultState == ThinkingState.ALWAYS_ON ? null : thinkingDisabledValue,
                Boolean.TRUE.equals(thinkingSendDefault));
        }
    }

    private static void mergeProfile(MutableProfile target, String name,
        Map<String, AgentConfig.ProviderProfileDef> profiles, Api expectedApi, String context) {
        if (name == null || name.isBlank()) {
            return;
        }
        String key = normaliseId(name);
        if (!profiles.containsKey(key)) {
            throw invalid(context + " references missing harnex.provider-profiles entry '" + name.trim() + "'");
        }
        AgentConfig.ProviderProfileDef profile = profiles.get(key);
        if (profile == null) {
            throw invalid(context + " references null harnex.provider-profiles entry '" + name.trim() + "'");
        }
        Api actualApi = apiForProviderType(profile.getType());
        if (actualApi != expectedApi) {
            throw invalid(context + " '" + name.trim() + "' uses " + actualApi
                + " but the provider adapter requires " + expectedApi);
        }
        target.merge(profile, context + " '" + name.trim() + "'");
    }

    private static ConfiguredRule firstMatchingRule(List<ConfiguredRule> rules, String model) {
        if (rules == null || rules.isEmpty() || model == null) {
            return null;
        }
        for (ConfiguredRule rule : rules) {
            if (rule.pattern().matcher(model.trim()).matches()) {
                return rule;
            }
        }
        return null;
    }

    private static Map<String, AgentConfig.ProviderProfileDef> indexProfiles(
        Map<String, AgentConfig.ProviderProfileDef> configured) {
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, AgentConfig.ProviderProfileDef> indexed = new LinkedHashMap<>();
        for (Map.Entry<String, AgentConfig.ProviderProfileDef> entry : configured.entrySet()) {
            String name = requiredText(entry.getKey(), "provider-profiles key");
            String key = normaliseId(name);
            if (indexed.containsKey(key)) {
                throw invalid("Duplicate provider profile name (case-insensitive): '" + name + "'");
            }
            indexed.put(key, entry.getValue());
        }
        return indexed;
    }

    private static boolean hasExplicitCapability(AgentConfig.ProviderCapabilities c) {
        if (c.getInstructionRole() != null || c.getMaxTokensField() != null
            || c.getDefaultMaxTokens() != null || c.getOutputReserveTokens() != null || c.getMaxInputTokens() != null || c.getAudioInputEncoding() != null
            || c.getStreaming() != null || c.getStreamUsage() != null || c.getFunctionTools() != null
            || c.getSamplingPolicy() != null || c.getReasoningEfforts() != null
            || c.getReasoningReplay() != null || c.getFunctionToolReasoningEffort() != null
            || c.getThinkingRequiredEfforts() != null
            || c.getReasoningHistory() != null || c.getReasoningHistoryField() != null
            || c.getPromptCacheMode() != null || c.getStrictTerminal() != null || c.getThinkingPrefixBound() != null
            || c.getNativeWebSearch() != null || c.getNativeWebSearchType() != null
            || (c.getRequestOptions() != null && !c.getRequestOptions().isEmpty())) {
            return true;
        }
        AgentConfig.ThinkingCapabilities t = c.getThinking();
        return t != null && (t.getField() != null || t.getDefaultState() != null || t.getEnabledValue() != null
            || t.getDisabledValue() != null || t.getEnabledObject() != null && !t.getEnabledObject().isEmpty()
            || t.getDisabledObject() != null && !t.getDisabledObject().isEmpty() || t.getSendDefault() != null);
    }

    private static Api apiForProviderType(String providerType) {
        String type = providerType == null || providerType.isBlank()
            ? "openai-compatible" : providerType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "anthropic" -> Api.ANTHROPIC;
            case "gemini" -> Api.GEMINI;
            case "openai-responses" -> Api.RESPONSES;
            case "openai-compatible" -> Api.CHAT;
            default -> throw invalid("unsupported provider type '" + providerType
                + "' (expected openai-compatible|openai-responses|anthropic|gemini)");
        };
    }

    private static void validateProfileForApi(Profile profile, Api expected, String context) {
        if (profile.thinkingPrefixBound() && expected != Api.ANTHROPIC) {
            throw invalid(context + " thinking-prefix-bound is supported only by the Anthropic adapter");
        }
        if (profile.api() != expected) {
            throw invalid(context + " resolved internal protocol '" + profile.api().wireName()
                + "' but provider type requires '" + expected.wireName() + "'");
        }
        switch (expected) {
            case CHAT -> {
                if (profile.strictTerminal()) {
                    throw invalid(context + " strict-terminal is supported only by the Anthropic adapter");
                }
                if (profile.nativeWebSearch() || profile.nativeWebSearchType() != null) {
                    throw invalid(context + " native-web-search is not supported by the Chat adapter");
                }
                if (profile.maxTokensField() == MaxTokensField.MAX_OUTPUT_TOKENS) {
                    throw invalid(context + " Chat API cannot use max_output_tokens");
                }
                if (profile.reasoningReplay() == ReasoningReplay.NATIVE_ITEMS) {
                    throw invalid(context + " Chat API cannot use reasoning-replay=native_items");
                }
            }
            case RESPONSES -> {
                if (profile.strictTerminal()) {
                    throw invalid(context + " strict-terminal is supported only by the Anthropic adapter");
                }
                if (profile.instructionRole() != null) {
                    throw invalid(context + " instruction-role is supported only by the Chat adapter");
                }
                if (profile.maxTokensField() != MaxTokensField.MAX_OUTPUT_TOKENS) {
                    throw invalid(context + " Responses API requires max_output_tokens");
                }
                if (profile.reasoningReplay() != ReasoningReplay.NATIVE_ITEMS
                    && profile.reasoningReplay() != ReasoningReplay.NEVER) {
                    throw invalid(context + " Responses API supports reasoning-replay only as native_items or never");
                }
                if (profile.thinking().supported()) {
                    throw invalid(context + " Responses API does not consume generic thinking fields");
                }
                if (profile.streamUsage()) {
                    throw invalid(context + " Responses API does not use Chat stream_options.include_usage");
                }
                if (profile.promptCacheMode() == PromptCacheMode.CONTENT_CACHE_CONTROL) {
                    throw invalid(context + " Responses API cannot use content_cache_control prompt caching");
                }
            }
            case ANTHROPIC -> {
                if (profile.instructionRole() != null) {
                    throw invalid(context + " instruction-role is supported only by the Chat adapter");
                }
                if (profile.maxTokensField() != MaxTokensField.MAX_TOKENS) {
                    throw invalid(context + " Anthropic API requires max_tokens");
                }
                if (profile.reasoningReplay() != ReasoningReplay.NATIVE_ITEMS
                    && profile.reasoningReplay() != ReasoningReplay.NEVER) {
                    throw invalid(context + " Anthropic API supports reasoning-replay only as native_items or never");
                }
                if (profile.streamUsage()) {
                    throw invalid(context + " Anthropic API does not use Chat stream_options.include_usage");
                }
                if (profile.promptCacheMode() != PromptCacheMode.NONE
                    && profile.promptCacheMode() != PromptCacheMode.CONTENT_CACHE_CONTROL) {
                    throw invalid(context + " Anthropic API supports prompt-cache-mode only as none or "
                        + "content_cache_control");
                }
            }
            case GEMINI -> {
                if (profile.strictTerminal()) {
                    throw invalid(context + " strict-terminal is supported only by the Anthropic adapter");
                }
                if (profile.instructionRole() != null) {
                    throw invalid(context + " instruction-role is supported only by the Chat adapter");
                }
                if (profile.maxTokensField() != MaxTokensField.MAX_OUTPUT_TOKENS) {
                    throw invalid(context + " Gemini Interactions API requires max_output_tokens");
                }
                if (profile.reasoningReplay() != ReasoningReplay.NATIVE_ITEMS
                    && profile.reasoningReplay() != ReasoningReplay.NEVER) {
                    throw invalid(context
                        + " Gemini Interactions API supports reasoning-replay only as native_items or never");
                }
                if (profile.thinking().supported()) {
                    throw invalid(context
                        + " Gemini Interactions API uses generation_config.thinking_level, not generic thinking fields");
                }
                if (profile.streamUsage()) {
                    throw invalid(context + " Gemini Interactions API does not use Chat stream_options.include_usage");
                }
                if (profile.promptCacheMode() != PromptCacheMode.NONE
                    && profile.promptCacheMode() != PromptCacheMode.AUTO_PREFIX) {
                    throw invalid(context
                        + " Gemini Interactions API supports prompt-cache-mode only as none or auto_prefix");
                }
            }
        }
    }

    private static MaxTokensField parseMaxTokensField(String raw, String context) {
        return switch (policy(raw, context)) {
            case "max_tokens" -> MaxTokensField.MAX_TOKENS;
            case "max_completion_tokens" -> MaxTokensField.MAX_COMPLETION_TOKENS;
            case "max_output_tokens" -> MaxTokensField.MAX_OUTPUT_TOKENS;
            default -> throw invalidEnum(context, raw,
                "max_tokens|max_completion_tokens|max_output_tokens");
        };
    }

    private static ReasoningReplay parseReasoningReplay(String raw, String context) {
        return switch (policy(raw, context)) {
            case "never" -> ReasoningReplay.NEVER;
            case "tool_calls" -> ReasoningReplay.TOOL_CALLS;
            case "all" -> ReasoningReplay.ALL;
            case "native_items" -> ReasoningReplay.NATIVE_ITEMS;
            default -> throw invalidEnum(context, raw, "never|tool_calls|all|native_items");
        };
    }

    private static ReasoningHistory parseReasoningHistory(String raw, String context) {
        return switch (policy(raw, context)) {
            case "none" -> ReasoningHistory.NONE;
            case "optional" -> ReasoningHistory.OPTIONAL;
            case "required" -> ReasoningHistory.REQUIRED;
            default -> throw invalidEnum(context, raw, "none|optional|required");
        };
    }

    private static PromptCacheMode parsePromptCacheMode(String raw, String context) {
        return switch (policy(raw, context)) {
            case "none" -> PromptCacheMode.NONE;
            case "auto_prefix" -> PromptCacheMode.AUTO_PREFIX;
            case "key" -> PromptCacheMode.KEY;
            case "openai_explicit" -> PromptCacheMode.OPENAI_EXPLICIT;
            case "content_cache_control" -> PromptCacheMode.CONTENT_CACHE_CONTROL;
            default -> throw invalidEnum(context, raw,
                "none|auto_prefix|key|openai_explicit|content_cache_control");
        };
    }

    private static SamplingPolicy parseSamplingPolicy(String raw, String context) {
        return switch (policy(raw, context)) {
            case "always" -> SamplingPolicy.ALWAYS;
            case "when_thinking_off" -> SamplingPolicy.WHEN_THINKING_OFF;
            case "omit" -> SamplingPolicy.OMIT;
            default -> throw invalidEnum(context, raw, "always|when_thinking_off|omit");
        };
    }

    private static ThinkingState parseThinkingState(String raw, String context) {
        return switch (policy(raw, context)) {
            case "off" -> ThinkingState.OFF;
            case "on" -> ThinkingState.ON;
            case "always_on" -> ThinkingState.ALWAYS_ON;
            default -> throw invalidEnum(context, raw, "off|on|always_on");
        };
    }

    private static String parseInstructionRole(String raw, String context) {
        String role = policy(raw, context);
        if (!"system".equals(role) && !"developer".equals(role)) {
            throw invalidEnum(context, raw, "system|developer");
        }
        return role;
    }

    private static Set<String> normaliseReasoningEfforts(Collection<String> values, String context) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            String effort = parseReasoningEffort(value, context);
            out.add(effort);
        }
        return Collections.unmodifiableSet(out);
    }

    private static String parseReasoningEffort(String raw, String context) {
        String effort = policy(raw, context);
        if (!REASONING_EFFORTS.contains(effort)) {
            throw invalidEnum(context, raw, "none|minimal|low|medium|high|xhigh|max");
        }
        return effort;
    }

    /** {@code unconstrained} is an overlay sentinel; {@code none} remains the real zero-reasoning wire value. */
    private static String parseFunctionToolReasoningEffort(String raw, String context) {
        String effort = policy(raw, context);
        if ("unconstrained".equals(effort)) {
            return null;
        }
        if (!REASONING_EFFORTS.contains(effort)) {
            throw invalidEnum(context, raw, "unconstrained|none|minimal|low|medium|high|xhigh|max");
        }
        return effort;
    }

    private static Set<String> normaliseModalities(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                String modality = value.trim().toLowerCase(Locale.ROOT);
                if (!INPUT_MODALITIES.contains(modality)) {
                    throw invalid("input-modalities has invalid value '" + value + "' (expected one of "
                        + INPUT_MODALITIES + ")");
                }
                out.add(modality);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static String policy(String raw, String context) {
        return requiredText(raw, context).toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String requiredText(String raw, String context) {
        if (raw == null || raw.isBlank()) {
            throw invalid(context + " must not be blank");
        }
        return raw.trim();
    }

    private static IllegalArgumentException invalidEnum(String context, String value, String allowed) {
        return invalid(context + " has invalid value '" + value + "' (expected " + allowed + ")");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid provider capabilities: " + message);
    }

    private static String normaliseId(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String resolveEnv(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("${") && value.endsWith("}")) {
            String inside = value.substring(2, value.length() - 1);
            String fallback = "";
            int separator = inside.indexOf(':');
            if (separator >= 0) {
                fallback = inside.substring(separator + 1);
                inside = inside.substring(0, separator);
            }
            String env = System.getenv(inside);
            return env != null ? env : fallback;
        }
        if (value.startsWith("$")) {
            String env = System.getenv(value.substring(1));
            return env != null ? env : value;
        }
        return value;
    }

    private static Set<String> immutableStringSet(Set<String> values) {
        return values == null || values.isEmpty() ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static Map<String, Object> immutableStringObjectMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
