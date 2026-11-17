package com.harnex.provider;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.observability.HarnexMetrics;
import com.harnex.net.OutboundProxyResolver;
import com.harnex.net.ProxyResolution;
import com.harnex.safety.SecretRedactor;

import lombok.extern.slf4j.Slf4j;

/**
 * Single construction path for production and ephemeral provider adapters.
 *
 * <p>Keeping protocol construction out of {@link ProviderRouter} ensures a settings probe exercises exactly the same
 * URL resolution, proxy policy, model capabilities and provider options as a real agent request. Routing/failover stays
 * in {@code ProviderRouter}; callers of this factory always receive one exact provider.</p>
 */
@Slf4j
@Component
public final class ProviderFactory {

    private final AgentConfig config;
    private final HarnexMetrics metrics;
    private final OutboundProxyResolver proxyResolver;

    public ProviderFactory(AgentConfig config, HarnexMetrics metrics, OutboundProxyResolver proxyResolver) {
        this.config = config;
        this.metrics = metrics;
        this.proxyResolver = proxyResolver;
    }

    /** Build a normal runtime adapter using the effective application proxy policy. */
    public ModelProvider create(ResolvedProvider provider) {
        return create(provider, proxyResolver);
    }

    /** Build an ephemeral adapter using a candidate HTTP policy (used by the unsaved settings probe). */
    public ModelProvider create(ResolvedProvider provider, OutboundProxyResolver candidateProxyResolver) {
        AgentConfig.Vision vision = config.getVision();
        Path uploadsRoot = Paths.get(config.getDataDir(), "uploads");
        String apiKey = resolveEnv(provider.getApiKey());
        String baseUrl = resolveEnv(provider.getBaseUrl());
        String type = normaliseProviderType(provider.getType());
        String model = resolveEnv(provider.getDefaultModel());
        String defaultBase = switch (type) {
            case "anthropic" -> "https://api.anthropic.com";
            case "openai-responses" -> "https://api.openai.com/v1";
            case "gemini" -> "https://generativelanguage.googleapis.com";
            default -> "";
        };
        String resolvedBase = (baseUrl == null || baseUrl.isBlank()) ? defaultBase : baseUrl;
        String[] hostPort = ProxyResolution.hostPortOf(resolvedBase);
        String targetHost = hostPort != null ? hostPort[0] : null;
        int targetPort = hostPort != null ? Integer.parseInt(hostPort[1]) : -1;
        ProxyResolution.Resolved proxy = candidateProxyResolver.resolve(provider.getProxy(), targetHost, targetPort);
        log.info("Provider '{}' http: proxy={} (source={}); baseUrl={}", provider.getName(),
            SecretRedactor.redact(proxy.describe()), proxy.source(), resolvedBase);

        ProviderModelCapabilities capabilities = ProviderModelCapabilities.from(config, provider, type);
        if ("anthropic".equals(type)) {
            Set<String> imageModels = vision.isEnabled() ? modelsWithInputModality(provider, "image") : Set.of();
            Set<String> pdfModels = vision.isEnabled() ? modelsWithInputModality(provider, "pdf") : Set.of();
            long requestCeiling = 20L * 1024 * 1024;
            long perAttachment = Math.max(0L, vision.getMaxAttachmentBytes());
            long attachmentCount = Math.max(1L, vision.getMaxAttachmentsPerMessage());
            long aggregateAttachmentBytes = perAttachment == 0 ? 0
                : perAttachment >= requestCeiling / attachmentCount ? requestCeiling : perAttachment * attachmentCount;
            return new AnthropicProvider(provider.getName(), resolvedBase, apiKey, model, metrics, proxy,
                anthropicOptions(provider), vision.isEnabled(), imageModels, pdfModels, uploadsRoot,
                vision.getMaxAttachmentBytes(), aggregateAttachmentBytes, vision.getMaxAttachmentsPerMessage(),
                capabilities);
        }
        if ("openai-responses".equals(type)) {
            return new OpenAiResponsesProvider(provider.getName(), resolvedBase, apiKey, model, vision.isEnabled(),
                modelsWithInputModality(provider, "image"), modelsWithInputModality(provider, "file"),
                vision.getMaxAttachmentBytes(), vision.getDetail(), vision.getMaxAttachmentsPerMessage(), uploadsRoot,
                metrics, proxy, responsesOptions(provider), capabilities);
        }
        if ("gemini".equals(type)) {
            return new GeminiProvider(provider.getName(), resolvedBase, apiKey, model, vision.isEnabled(),
                vision.getMaxAttachmentBytes(), vision.getMaxAttachmentsPerMessage(), uploadsRoot, metrics, proxy,
                geminiOptions(provider), capabilities);
        }
        return new OpenAiCompatibleProvider(provider.getName(), resolvedBase, apiKey, model, vision.isEnabled(),
            modelsWithInputModality(provider, "image"), modelsWithInputModality(provider, "file"),
            vision.getMaxAttachmentBytes(), vision.getDetail(), vision.getMaxAttachmentsPerMessage(), uploadsRoot,
            metrics, proxy, compatibleOptions(provider), provider.getMaxTokens() != null ? provider.getMaxTokens() : 0,
            capabilities);
    }

    static Set<String> modelsWithInputModality(ResolvedProvider provider, String modality) {
        if (provider.getModels() == null || provider.getModels().isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (AgentConfig.ModelDef model : provider.getModels()) {
            if (model == null || model.getId() == null || model.getId().isBlank()
                || model.getInputModalities() == null) {
                continue;
            }
            boolean supported = model.getInputModalities().stream()
                .anyMatch(value -> value != null && modality != null && modality.equalsIgnoreCase(value.trim()));
            if (supported) {
                String id = resolveEnv(model.getId());
                if (id != null && !id.isBlank()) {
                    out.add(id.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(out);
    }

    static String normaliseProviderType(String raw) {
        String type = raw == null || raw.isBlank() ? "openai-compatible" : raw.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("openai-compatible", "openai-responses", "anthropic", "gemini").contains(type)) {
            throw new IllegalArgumentException("Unsupported provider type '" + raw
                + "' (expected openai-compatible|openai-responses|anthropic|gemini)");
        }
        return type;
    }

    public static String resolveEnv(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("${") && value.endsWith("}")) {
            String expression = value.substring(2, value.length() - 1);
            String fallback = "";
            int separator = expression.indexOf(':');
            if (separator >= 0) {
                fallback = expression.substring(separator + 1);
                expression = expression.substring(0, separator);
            }
            String environment = System.getenv(expression);
            return environment != null ? environment : fallback;
        }
        return value;
    }

    private static AnthropicProvider.Options anthropicOptions(ResolvedProvider provider) {
        return new AnthropicProvider.Options(provider.getMaxTokens() != null ? provider.getMaxTokens() : 0,
            provider.getThinking(), provider.getEffort(), provider.getPromptCaching() == null
                || provider.getPromptCaching(), provider.getBetas() == null ? List.of() : provider.getBetas(),
            Boolean.TRUE.equals(provider.getSendTemperature()), provider.isNativeWebSearch(), provider.isEmulation(),
            provider.getEmulationVersion());
    }

    private static OpenAiResponsesProvider.Options responsesOptions(ResolvedProvider provider) {
        return new OpenAiResponsesProvider.Options(provider.getMaxTokens() != null ? provider.getMaxTokens() : 0,
            provider.getEffort(), "adaptive".equalsIgnoreCase(provider.getThinking()),
            Boolean.TRUE.equals(provider.getSendTemperature()), provider.isNativeWebSearch(), provider.isEmulation(),
            provider.getEmulationVersion(), provider.getPromptCacheKey() == null || provider.getPromptCacheKey(),
            Boolean.TRUE.equals(provider.getPromptCacheExplicit()));
    }

    private static GeminiProvider.Options geminiOptions(ResolvedProvider provider) {
        return new GeminiProvider.Options(provider.getMaxTokens() != null ? provider.getMaxTokens() : 0,
            provider.getEffort(), provider.isNativeWebSearch());
    }

    private static OpenAiCompatibleProvider.Options compatibleOptions(ResolvedProvider provider) {
        return new OpenAiCompatibleProvider.Options(provider.isEmulation(), provider.getEmulationVersion(),
            provider.getEffort(), Boolean.TRUE.equals(provider.getPromptCacheKey()),
            Boolean.TRUE.equals(provider.getPromptCacheExplicit()), provider.getThinking(),
            Boolean.TRUE.equals(provider.getPromptCaching()), provider.getReasoningHistory());
    }
}
