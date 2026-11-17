package com.harnex.provider;

import java.util.Locale;
import java.util.Map;

import com.harnex.net.HttpExchangeState;
import com.harnex.provider.ProviderModelCapabilities.Profile;
import com.harnex.provider.ProviderModelCapabilities.ReasoningHistory;
import com.harnex.provider.ProviderModelCapabilities.ReasoningReplay;
import com.harnex.provider.ProviderModelCapabilities.SamplingPolicy;
import com.harnex.provider.ProviderModelCapabilities.ThinkingSpec;
import com.harnex.provider.ProviderModelCapabilities.ThinkingState;

/** Applies one resolved capability profile without inspecting the model identifier. */
public final class ProviderCapabilityRequest {

    private ProviderCapabilityRequest() {}

    /**
     * Apply the configured generic thinking toggle and return whether thinking is effective. An omitted operator
     * setting follows the profile's declared default; an unsupported or impossible explicit request fails locally
     * instead of relying on an upstream 400.
     */
    static boolean applyThinking(Map<String, Object> body, Profile profile, String configured, String model) {
        ThinkingSpec spec = profile.thinking();
        Boolean requested = parseThinking(configured);
        if (!spec.supported()) {
            if (Boolean.TRUE.equals(requested)) {
                throw config(model + " does not declare a supported thinking request field");
            }
            return false;
        }

        ThinkingState defaultState = spec.defaultState();
        if (defaultState == ThinkingState.ALWAYS_ON) {
            if (Boolean.FALSE.equals(requested)) {
                throw config(model + " declares thinking as always_on and cannot be disabled");
            }
            if (Boolean.TRUE.equals(requested) || requested == null && spec.sendDefault()) {
                body.put(spec.field(), spec.enabledValue());
            }
            return true;
        }

        boolean enabled = requested != null ? requested : defaultState == ThinkingState.ON;
        if (requested != null || spec.sendDefault()) {
            Object value = enabled ? spec.enabledValue() : spec.disabledValue();
            if (value == null) {
                throw config(model + " cannot be configured with thinking=" + (enabled ? "on" : "off"));
            }
            body.put(spec.field(), value);
        }
        return enabled;
    }

    /** Resolve the configured effort and apply any endpoint-specific function-tool combination constraint. */
    static String reasoningEffort(Profile profile, String configured, boolean hasFunctionTools, String model,
        String wireField) {
        String effort = normalise(configured);
        // `auto` is the cross-surface clear/default sentinel (WebUI, Goal defaults, provider feature intent). It is
        // not a wire effort and must never be validated against a model's explicit effort enum. A function-tool
        // constraint below may still select its required concrete value.
        if ("auto".equals(effort)) effort = null;
        String requiredForTools = hasFunctionTools ? normalise(profile.functionToolReasoningEffort()) : null;
        if (requiredForTools != null) {
            if (effort != null && !requiredForTools.equals(effort)) {
                throw config(model + " requires " + wireField + "='" + requiredForTools
                    + "' when function tools are present; select a provider/API profile that supports tools with "
                    + "deeper reasoning");
            }
            effort = requiredForTools;
        }
        if (effort == null) {
            return null;
        }
        if (!profile.reasoningEffortsDeclared()) {
            throw config(model + " does not declare support for " + wireField);
        }
        if (!profile.acceptsEffort(effort)) {
            throw config(wireField + " '" + configured + "' is not supported by " + model
                + "; allowed values: " + profile.reasoningEfforts());
        }
        return effort;
    }

    static void requireFunctionTools(Profile profile, boolean hasTools, String model) {
        if (hasTools && !profile.functionTools()) {
            throw config(model + " does not declare function-tool support");
        }
    }

    /** Model-local thinking/effort restriction, independent of older models' toggle semantics. */
    static void requireThinkingForEffort(Profile profile, boolean thinkingEnabled, String effort, String model) {
        String resolved = normalise(effort);
        if (!thinkingEnabled && resolved != null && profile.thinkingRequiredEfforts().contains(resolved)) {
            throw config(model + " requires thinking to be enabled for effort='" + effort
                + "'; enable thinking or choose a lower effort");
        }
    }

    static void requireReasoning(Profile profile, boolean requested, String model) {
        if (requested && !profile.reasoningSupported()) {
            throw config(model + " does not declare Responses reasoning support");
        }
    }

    /**
     * Resolve the advanced historical-reasoning intent. Profiles without an independently configurable history
     * contract retain their declared replay policy; optional/required contracts switch atomically between full replay
     * and no replay so a request can never claim preservation while omitting part of the reasoning trace.
     */
    static ReasoningReplay reasoningReplay(Profile profile, String configured, String model) {
        HistoryIntent intent = parseReasoningHistory(configured);
        ReasoningHistory capability = profile.reasoningHistory();
        if (capability == ReasoningHistory.NONE) {
            if (intent != HistoryIntent.AUTO) {
                throw config(model
                    + " does not expose a configurable historical-reasoning policy; use reasoning-history=auto");
            }
            return profile.reasoningReplay();
        }
        if (capability == ReasoningHistory.REQUIRED && intent == HistoryIntent.DISCARD) {
            throw config(model + " requires complete historical reasoning and cannot discard it");
        }
        boolean preserve = capability == ReasoningHistory.REQUIRED || intent == HistoryIntent.PRESERVE;
        return preserve ? ReasoningReplay.ALL : ReasoningReplay.NEVER;
    }

    /** Apply an optional vendor top-level preservation switch after resolving the same history contract as replay. */
    static void applyReasoningHistory(Map<String, Object> body, Profile profile, String configured, String model) {
        HistoryIntent intent = parseReasoningHistory(configured);
        ReasoningHistory capability = profile.reasoningHistory();
        // Validate impossible intent/model combinations even when this endpoint relies on model defaults rather than
        // exposing a dedicated boolean field.
        reasoningReplay(profile, configured, model);
        String field = profile.reasoningHistoryField();
        if (capability == ReasoningHistory.NONE || field == null) {
            return;
        }
        if (intent == HistoryIntent.PRESERVE || capability == ReasoningHistory.REQUIRED) {
            body.put(field, true);
        } else if (intent == HistoryIntent.DISCARD) {
            body.put(field, false);
        }
        // OPTIONAL + AUTO deliberately omits the field and follows the upstream's documented default.
    }

    /**
     * Resolve operator intent against the selected model's native server-tool capability.
     *
     * <p>A missing declaration is a normal capability outcome, not a configuration error. Returning {@code null}
     * lets adapters omit the unsupported hosted tool while retaining a configured local {@code web_search} function
     * as the fallback. Capability declarations remain fail-closed: only an explicit true flag plus a non-blank native
     * tool type can activate the provider-hosted path.</p>
     */
    static String nativeWebSearchType(Profile profile, boolean requested, String model,
        ModelProvider.ChatRequest request) {
        String type = nativeWebSearchType(profile, requested && request.isWebSearchAllowed(), model);
        request.setNativeWebSearchActive(type != null);
        return type;
    }

    static String nativeWebSearchType(Profile profile, boolean requested, String model) {
        if (!requested || ProviderCallScope.current() != null && !ProviderCallScope.current().allowNativeTools()) {
            return null;
        }
        if (!profile.nativeWebSearch()) {
            return null;
        }
        String declared = profile.nativeWebSearchType();
        if (declared == null || declared.isBlank()) {
            return null;
        }
        return declared;
    }

    static boolean sendSampling(Profile profile, boolean thinkingEnabled) {
        return profile.samplingPolicy() == SamplingPolicy.ALWAYS
            || profile.samplingPolicy() == SamplingPolicy.WHEN_THINKING_OFF && !thinkingEnabled;
    }

    /**
     * Only explicit positive limits or a declared model/protocol default go onto the wire. Null means the endpoint
     * chooses its own default, not zero tokens or a hidden cross-model cap.
     */
    public static Integer outputTokenLimit(Profile profile, Integer requestOverride, int providerOverride) {
        if (requestOverride != null && requestOverride > 0) {
            return requestOverride;
        }
        if (providerOverride > 0) {
            return providerOverride;
        }
        Integer modelDefault = profile.defaultMaxTokens();
        return modelDefault != null && modelDefault > 0 ? modelDefault : null;
    }

    public static Integer applyOutputTokenLimit(Map<String, Object> body, Profile profile,
        Integer requestOverride, int providerOverride) {
        Integer limit = outputTokenLimit(profile, requestOverride, providerOverride);
        if (limit != null) body.put(profile.maxTokensFieldName(), limit);
        return limit;
    }

    /** Planning estimates never become wire limits. Unknown server defaults remain explicitly estimated. */
    public static OutputBudget outputBudget(Profile profile, Integer requestOverride, int providerOverride,
        int fallbackReserve, int contextWindow, int safetyReserve) {
        Integer limit = outputTokenLimit(profile, requestOverride, providerOverride);
        int reserve = limit != null ? limit
            : profile.outputReserveTokens() != null ? profile.outputReserveTokens() : Math.max(1, fallbackReserve);
        if ((long)reserve + safetyReserve >= contextWindow) {
            if (limit != null) throw new ProviderException("context_length_exceeded",
                "The configured output limit leaves no room for model input",
                HttpExchangeState.Acceptance.NOT_SENT, null, 0);
            reserve = Math.max(1, (contextWindow - safetyReserve) / 2);
        }
        return new OutputBudget(limit, reserve, limit == null);
    }

    public record OutputBudget(Integer requestLimit, int reserve, boolean estimated) {}

    /** Static extension fields were screened for reserved protocol keys when the registry was built. */
    static void applyRequestOptions(Map<String, Object> body, Profile profile) {
        body.putAll(profile.requestOptions());
    }

    private static Boolean parseThinking(String configured) {
        String value = normalise(configured);
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "adaptive", "on", "enabled", "true" -> true;
            case "off", "disabled", "false" -> false;
            default -> throw config("thinking must be off/on/adaptive, got: " + configured);
        };
    }

    private static HistoryIntent parseReasoningHistory(String configured) {
        String value = normalise(configured);
        if (value == null || "auto".equals(value)) {
            return HistoryIntent.AUTO;
        }
        return switch (value) {
            case "preserve" -> HistoryIntent.PRESERVE;
            case "discard" -> HistoryIntent.DISCARD;
            default -> throw config("reasoning-history must be auto|preserve|discard, got: " + configured);
        };
    }

    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static ProviderException config(String message) {
        return new ProviderException("config", message);
    }

    private enum HistoryIntent {
        AUTO, PRESERVE, DISCARD
    }
}
