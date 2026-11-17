package com.harnex.provider;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ConfigControlService;
import com.harnex.model.ChatMessage;
import com.harnex.net.HttpClients;
import com.harnex.net.HttpExchangeState;
import com.harnex.net.OutboundProxyResolver;
import com.harnex.net.ProxyResolution;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.safety.SecretRedactor;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

/** Tests the exact draft Provider with normal streaming inference and no routing fallback. */
@Service
@Slf4j
public final class ProviderProbeService {

    static final long PROBE_TIMEOUT_MS = Duration.ofSeconds(45).toMillis();
    private static final long PROBE_CLIENT_IDLE_NANOS = Duration.ofMinutes(5).toNanos();
    private static final int MAX_PROBE_CLIENTS = 8;
    private static final Set<String> RETRYABLE = Set.of("timeout", "network", "stream_ttft_timeout", "429", "500", "502", "503", "504", "529");
    static final long CATALOG_TIMEOUT_MS = Duration.ofSeconds(10).toMillis();
    static final long MAX_CATALOG_BYTES = 2L * 1024 * 1024;
    private static final String CATALOGUE_MODEL_PLACEHOLDER = "__harnex_catalogue_discovery__";
    private static final String CATALOGUE_USER_AGENT = "Harnex/1.0 (model-catalogue)";
    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final AgentConfig config;
    private final ConfigControlService configControl;
    private final ProviderConfigurationResolver resolver;
    private final ProviderFactory factory;
    private final Map<String, ProbeClient> probeClients = new LinkedHashMap<>(16, 0.75f, true);

    public ProviderProbeService(AgentConfig config, ConfigControlService configControl,
        ProviderConfigurationResolver resolver, ProviderFactory factory) {
        this.config = config;
        this.configControl = configControl;
        this.resolver = resolver;
        this.factory = factory;
    }

    public Map<String, Object> probe(String providerId, String requestedModel, Map<String, Object> submittedValues) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        Map<String, Object> values = configControl.resolveCandidateValues(submittedValues);
        Map<String, Object> providerMap = mapValue(values.get("providers"));
        Object rawProvider = providerEntry(providerMap, providerId);
        if (!(rawProvider instanceof Map<?, ?> candidate)) {
            throw new IllegalArgumentException("provider '" + providerId + "' is not present in the candidate settings");
        }

        AgentConfig.ProviderConfig providerConfig = JSON.convertValue(camelize(candidate), AgentConfig.ProviderConfig.class);
        ResolvedProvider provider = resolver.resolveCandidate(config, providerId, providerConfig);
        String model = requestedModel == null || requestedModel.isBlank()
            ? provider.getDefaultModel() : requestedModel.trim();
        requireSelectableModel(provider, model);

        long started = System.nanoTime();
        boolean streaming = ProviderModelCapabilities.from(config, provider).forModel(model).streaming();
        String mode = streaming ? "stream" : "one-shot";
        var exchange = new AtomicReference<HttpExchangeState>();
        int attempts = 0;
        try (var client = acquireProbeClient(provider, candidateHttp(values)); var deadline = new ProbeDeadline();
            var scope = ProviderCallScope.open(new ProviderCallScope.Context(null, null, null, "provider-probe",
                deadline::expired, deadline::onCancel, ignored -> {}, false));
            var observation = ProviderCallScope.observe(exchange::set)) {
            while (++attempts <= 3) {
                if (deadline.expired()) break;
                exchange.set(null);
                var outputSeen = new AtomicBoolean();
                try {
                    String content;
                    if (streaming) {
                        var end = new AtomicReference<Map<String, Object>>();
                        client.entry.adapter.streamChat(ModelProvider.ChatRequest.builder().provider(providerId).model(model)
                            .messages(List.of(ChatMessage.user("Reply with exactly OK. Do not add any other text.")))
                            .tools(List.of()).maxTokens(1024).build(), event -> {
                                if (Set.of("text_delta", "thinking_delta", "tool_call_delta").contains(event.getType())) outputSeen.set(true);
                                if ("message_end".equals(event.getType())) end.set(event.getData());
                            }, Math.min(deadline.remainingMillis(), AbstractHttpProvider.STREAM_TTFT_MS));
                        if (end.get() == null || Boolean.TRUE.equals(end.get().get("interrupted"))) {
                            throw new ProviderException("incomplete", "Provider did not return a complete streaming response");
                        }
                        if (end.get().get("toolCalls") instanceof List<?> calls && !calls.isEmpty()) {
                            throw new ProviderException("incomplete", "Provider returned a tool call to a test without tools");
                        }
                        content = end.get().get("content") instanceof String text ? text : "";
                    } else {
                        OneShotResult response = client.entry.adapter.oneShot(model,
                            List.of(ChatMessage.user("Reply with exactly OK. Do not add any other text.")),
                            null, deadline.remainingMillis(), 1024);
                        content = response == null || response.content() == null ? "" : response.content();
                    }
                    if (deadline.expired()) break;
                    return probeResult(!content.isBlank(), content.isBlank() ? "empty_response" : "success",
                        content.isBlank() ? "Provider returned no answer text" : "Provider inference succeeded",
                        provider, model, started, mode, attempts, exchange.get());
                } catch (ProviderException failure) {
                    if (deadline.expired()) break;
                    if (attempts < 3 && !outputSeen.get() && failure.safeToRetry() && RETRYABLE.contains(failure.code)) {
                        long delay = Math.max(150L * attempts, failure.retryAfterMillis);
                        if (delay < deadline.remainingMillis()) {
                            Thread.sleep(delay);
                            continue;
                        }
                    }
                    return probeResult(false, classify(failure), safeMessage(failure, provider), provider, model,
                        started, mode, attempts, exchange.get());
                }
            }
            return probeResult(false, "timeout", "The test exceeded its time budget; provider availability is unconfirmed",
                provider, model, started, mode, Math.min(attempts, 3), exchange.get());
        } catch (ProviderException exception) {
            return probeResult(false, classify(exception), safeMessage(exception, provider), provider, model, started, mode, attempts, exchange.get());
        } catch (IllegalArgumentException exception) {
            return probeResult(false, "config_invalid", safeMessage(exception, provider), provider, model, started, mode, attempts, exchange.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return probeResult(false, "cancelled", "Provider test was interrupted", provider, model, started, mode, attempts, exchange.get());
        } catch (RuntimeException exception) {
            return probeResult(false, "call_failed", safeMessage(exception, provider), provider, model, started, mode, attempts, exchange.get());
        }
    }

    private synchronized ProbeLease acquireProbeClient(ResolvedProvider provider, AgentConfig.HttpConfig http) {
        long now = System.nanoTime();
        var iterator = probeClients.values().iterator();
        while (iterator.hasNext()) {
            ProbeClient entry = iterator.next();
            if (entry.users == 0 && now - entry.lastUsed > PROBE_CLIENT_IDLE_NANOS) {
                iterator.remove(); entry.retired = true; close(entry.adapter);
            }
        }
        String key = IdempotencyReceiptStore.requestHash(Map.of("provider", provider, "http", http));
        ProbeClient entry = probeClients.get(key);
        if (entry == null) {
            AgentConfig proxy = new AgentConfig(); proxy.setHttp(http);
            entry = new ProbeClient(factory.create(provider, new OutboundProxyResolver(proxy)));
            probeClients.put(key, entry);
        }
        entry.users++;
        while (probeClients.size() > MAX_PROBE_CLIENTS) {
            ProbeClient removed = probeClients.remove(probeClients.keySet().iterator().next());
            removed.retired = true;
            if (removed.users == 0) close(removed.adapter);
        }
        return new ProbeLease(entry);
    }

    @PreDestroy
    public synchronized void closeProbeClients() {
        for (ProbeClient entry : probeClients.values()) {
            entry.retired = true;
            if (entry.users == 0) close(entry.adapter);
        }
        probeClients.clear();
    }

    private static final class ProbeClient {
        final ModelProvider adapter;
        long lastUsed = System.nanoTime();
        int users;
        boolean retired;
        ProbeClient(ModelProvider adapter) { this.adapter = adapter; }
    }

    private final class ProbeLease implements AutoCloseable {
        final ProbeClient entry;
        ProbeLease(ProbeClient entry) { this.entry = entry; }
        @Override public void close() {
            synchronized (ProviderProbeService.this) {
                entry.lastUsed = System.nanoTime();
                if (--entry.users == 0 && entry.retired) ProviderProbeService.close(entry.adapter);
            }
        }
    }

    private static final class ProbeDeadline implements AutoCloseable {
        private final AtomicBoolean expired = new AtomicBoolean();
        private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
        private final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_TIMEOUT_MS);
        private final ScheduledFuture<?> timer = AbstractHttpProvider.ONESHOT_TIMEOUT_EXEC.schedule(() -> {
            expired.set(true);
            callbacks.forEach(Runnable::run);
        }, PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        boolean expired() { return expired.get() || System.nanoTime() - until >= 0; }
        long remainingMillis() { return Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())); }
        AutoCloseable onCancel(Runnable cancel) {
            callbacks.add(cancel);
            if (expired()) cancel.run();
            return () -> callbacks.remove(cancel);
        }
        @Override public void close() { timer.cancel(false); callbacks.clear(); }
    }

    private static Map<String, Object> probeResult(boolean success, String code, String message, ResolvedProvider provider,
        String model, long started, String mode, int attempts, HttpExchangeState exchange) {
        Map<String, Object> result = result(success, code, message, provider, model, started);
        result.put("outcome", success ? "success" : Set.of("auth_failed", "model_not_found", "config_invalid").contains(code)
            ? "failure" : "inconclusive");
        result.put("mode", mode);
        result.put("attempts", attempts);
        result.put("httpProtocol", exchange == null ? null : exchange.protocol());
        try {
            Map<String, Object> diagnostic = new LinkedHashMap<>(result);
            diagnostic.remove("message");
            if (exchange != null) { diagnostic.put("acceptance", exchange.acceptance()); diagnostic.put("timings", exchange.timings()); }
            log.info("Provider probe {}", JSON.writeValueAsString(diagnostic));
        } catch (Exception ignored) { /* Diagnostics do not affect the test result. */ }
        return result;
    }

    /** Discover the non-billable upstream model catalogue for an exact unsaved provider candidate. */
    public Map<String, Object> models(String providerId, Map<String, Object> submittedValues) {
        Candidate candidate = resolveCandidate(providerId, discoveryCandidateValues(providerId, submittedValues));
        ResolvedProvider provider = candidate.provider();
        String type = ProviderFactory.normaliseProviderType(provider.getType());
        String baseUrl = effectiveBaseUrl(provider, type);
        URI endpoint = modelCatalogueEndpoint(baseUrl, type);
        String apiKey = ProviderFactory.resolveEnv(provider.getApiKey());
        String[] hostPort = ProxyResolution.hostPortOf(endpoint.toString());
        ProxyResolution.Resolved proxy = candidate.proxy().resolve(provider.getProxy(),
            hostPort == null ? null : hostPort[0], hostPort == null ? -1 : Integer.parseInt(hostPort[1]));
        OkHttpClient client = HttpClients.modelBuilder(proxy)
            .connectTimeout(Duration.ofMillis(CATALOG_TIMEOUT_MS))
            .readTimeout(Duration.ofMillis(CATALOG_TIMEOUT_MS))
            .callTimeout(Duration.ofMillis(CATALOG_TIMEOUT_MS)).build();
        Request.Builder request = new Request.Builder().url(endpoint.toString()).get()
            .header("Accept", "application/json")
            // A number of self-hosted relay deployments put a WAF in front of /v1/models. Do not let OkHttp's
            // generic user-agent make this non-billable discovery request look like an unidentified scraper.
            .header("User-Agent", CATALOGUE_USER_AGENT)
            .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            if ("anthropic".equals(type)) {
                request.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
                addRelayBearerAuth(request, endpoint, type, apiKey);
            } else if ("gemini".equals(type)) {
                request.header("x-goog-api-key", apiKey);
                addRelayBearerAuth(request, endpoint, type, apiKey);
            } else {
                request.header("Authorization", "Bearer " + apiKey);
            }
        }
        try (Response response = client.newCall(request.build()).execute()) {
            if (response.body() != null && response.body().contentLength() > MAX_CATALOG_BYTES) {
                throw new IllegalArgumentException("model catalogue response is too large");
            }
            byte[] bytes = response.body() == null ? new byte[0]
                : response.body().byteStream().readNBytes((int)MAX_CATALOG_BYTES + 1);
            if (bytes.length > MAX_CATALOG_BYTES) {
                throw new IllegalArgumentException("model catalogue response is too large");
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (!response.isSuccessful()) {
                String message = "model catalogue request failed with HTTP " + response.code();
                String detail = catalogueErrorDetail(body, provider);
                if (!detail.isBlank()) message += ": " + detail;
                throw new IllegalArgumentException(safeMessage(new IllegalArgumentException(message), provider));
            }
            List<String> models = catalogueModelIds(type, body);
            return Map.of("provider", provider.getName(), "models", models, "source", "upstream");
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("model catalogue request failed: "
                + safeMessage(failure, provider), failure);
        } finally {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    /**
     * Native vendor APIs authenticate model discovery with their vendor header, while OpenAI-compatible gateways such
     * as New API commonly put the same route behind Bearer-token middleware. Send both only to a non-official host so
     * native endpoints retain their exact documented wire shape.
     */
    private static void addRelayBearerAuth(Request.Builder request, URI endpoint, String type, String apiKey) {
        String host = endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        boolean official = "anthropic".equals(type) && "api.anthropic.com".equals(host)
            || "gemini".equals(type) && "generativelanguage.googleapis.com".equals(host);
        if (!official) request.header("Authorization", "Bearer " + apiKey);
    }

    private static String catalogueErrorDetail(String body, ResolvedProvider provider) {
        if (body == null || body.isBlank()) return "";
        try {
            var root = JSON.readTree(body);
            String detail = root.path("error").path("message").asText("");
            if (detail.isBlank() && root.path("error").isTextual()) detail = root.path("error").asText("");
            if (detail.isBlank()) detail = root.path("message").asText("");
            if (detail.isBlank()) return "";
            detail = safeMessage(new IllegalArgumentException(detail), provider)
                .replaceAll("[\\r\\n]+", " ").strip();
            return detail.length() <= 500 ? detail : detail.substring(0, 500) + "…";
        } catch (Exception ignored) {
            // HTML WAF pages are intentionally not echoed into the settings UI.
            return "";
        }
    }

    private Candidate resolveCandidate(String providerId, Map<String, Object> submittedValues) {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("providerId is required");
        Map<String, Object> values = configControl.resolveCandidateValues(submittedValues);
        Object rawProvider = providerEntry(mapValue(values.get("providers")), providerId);
        if (!(rawProvider instanceof Map<?, ?> candidate)) {
            throw new IllegalArgumentException("provider '" + providerId + "' is not present in the candidate settings");
        }
        AgentConfig.ProviderConfig providerConfig = JSON.convertValue(camelize(candidate), AgentConfig.ProviderConfig.class);
        AgentConfig proxyConfig = new AgentConfig();
        proxyConfig.setHttp(candidateHttp(values));
        return new Candidate(resolver.resolveCandidate(config, providerId, providerConfig),
            new OutboundProxyResolver(proxyConfig));
    }

    private static Map<String, Object> discoveryCandidateValues(String providerId,
        Map<String, Object> submittedValues) {
        Map<String, Object> values = new LinkedHashMap<>(submittedValues == null ? Map.of() : submittedValues);
        Map<String, Object> providers = new LinkedHashMap<>(mapValue(values.get("providers")));
        Object raw = providerEntry(providers, providerId);
        if (!(raw instanceof Map<?, ?> provider)) return values;
        Map<String, Object> candidate = mapValue(provider);
        Object model = candidate.containsKey("default-model")
            ? candidate.get("default-model") : candidate.get("defaultModel");
        if (model == null || String.valueOf(model).isBlank()) {
            candidate.put("default-model", CATALOGUE_MODEL_PLACEHOLDER);
        }
        providers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(providerId))
            .findFirst().ifPresent(entry -> providers.put(entry.getKey(), candidate));
        values.put("providers", providers);
        return values;
    }

    private static String effectiveBaseUrl(ResolvedProvider provider, String type) {
        String configured = ProviderFactory.resolveEnv(provider.getBaseUrl());
        if (configured != null && !configured.isBlank()) return configured.strip().replaceAll("/+$", "");
        return switch (type) {
            case "anthropic" -> "https://api.anthropic.com";
            case "gemini" -> "https://generativelanguage.googleapis.com";
            default -> "https://api.openai.com/v1";
        };
    }

    static URI modelCatalogueEndpoint(String baseUrl, String type) {
        String base = stripInferenceEndpoint(baseUrl, type);
        if ("gemini".equals(type)) {
            String lower = base.toLowerCase(Locale.ROOT);
            if (lower.endsWith("/models")) return URI.create(base);
            return URI.create(lower.endsWith("/v1beta") ? base + "/models" : base + "/v1beta/models");
        }
        // Keep discovery addressing identical to real OpenAI/Anthropic inference calls: a bare host gains /v1,
        // while an explicit /v2, /v4, etc. remains intact (important for vendor-compatible endpoints).
        return URI.create(AbstractHttpProvider.resolveEndpoint(base, "/models"));
    }

    private static String stripInferenceEndpoint(String baseUrl, String type) {
        String base = baseUrl.strip().replaceAll("/+$", "");
        List<String> suffixes = switch (type) {
            case "anthropic" -> List.of("/messages");
            case "gemini" -> List.of("/interactions");
            case "openai-responses" -> List.of("/responses");
            default -> List.of("/chat/completions");
        };
        String lower = base.toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) {
                return base.substring(0, base.length() - suffix.length());
            }
        }
        return base;
    }

    static List<String> catalogueModelIds(String type, String body) {
        try {
            var root = JSON.readTree(body);
            var entries = "gemini".equals(type) ? root.path("models") : root.path("data");
            // Relays do not always preserve their selected upstream's envelope: Anthropic/Gemini-style lists and
            // Codex manifests use `models`, while the standard OpenAI shape uses `data`.
            if (!entries.isArray()) entries = root.path("data");
            if (!entries.isArray()) entries = root.path("models");
            if (!entries.isArray() && root.isArray()) entries = root;
            if (!entries.isArray()) throw new IllegalArgumentException("model catalogue response has no model list");
            Set<String> ids = new LinkedHashSet<>();
            for (var entry : entries) {
                String id = entry.path("id").asText("");
                if (id.isBlank()) id = entry.path("name").asText("");
                if (id.isBlank()) id = entry.path("slug").asText("");
                if ("gemini".equals(type) && id.startsWith("models/")) id = id.substring("models/".length());
                if (!id.isBlank()) ids.add(id.strip());
            }
            return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("model catalogue response is invalid", failure);
        }
    }

    private record Candidate(ResolvedProvider provider, OutboundProxyResolver proxy) {}

    private AgentConfig.HttpConfig candidateHttp(Map<String, Object> values) {
        Object raw = values.get("http");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return config.getHttp();
        }
        return JSON.convertValue(camelize(map), AgentConfig.HttpConfig.class);
    }

    private static void requireSelectableModel(ResolvedProvider provider, String model) {
        boolean selectable = provider.getModels() != null && provider.getModels().stream()
            .filter(Objects::nonNull)
            .anyMatch(definition -> definition.getId() != null
                && definition.getId().trim().equalsIgnoreCase(model));
        if (!selectable) {
            throw new IllegalArgumentException("model '" + model + "' is not selectable for provider '"
                + provider.getName() + "'");
        }
    }

    private static Object providerEntry(Map<String, Object> providers, String providerId) {
        for (Map.Entry<String, Object> entry : providers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(providerId.trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, Object> result(boolean success, String code, String message,
        ResolvedProvider provider, String model, long started) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", success);
        out.put("code", code);
        out.put("message", message);
        out.put("provider", provider.getName());
        out.put("profile", provider.getProfile());
        out.put("model", model);
        out.put("latencyMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
        out.put("billable", true);
        return out;
    }

    private static String classify(ProviderException exception) {
        String code = exception.code == null ? "" : exception.code.toLowerCase(Locale.ROOT);
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (code.equals("401") || code.equals("403")) return "auth_failed";
        if (code.equals("404") && message.contains("model")) return "model_not_found";
        if (code.equals("429")) return "rate_limited";
        if (code.equals("timeout") || code.contains("ttft")) return "timeout";
        if (code.equals("network")) return "network_error";
        if (code.equals("parse") || code.equals("incomplete")) return "protocol_mismatch";
        if (code.equals("config")) return "config_invalid";
        if (code.matches("5\\d\\d")) return "upstream_error";
        return "call_failed";
    }

    private static String safeMessage(Throwable throwable, ResolvedProvider provider) {
        String message = throwable.getMessage();
        String redacted = SecretRedactor.redact(message == null || message.isBlank()
            ? throwable.getClass().getSimpleName() : message);
        // Upstreams frequently echo an invalid credential in prose that does not match a
        // conventional "api_key=..." pattern. Redact the exact candidate key as well.
        String apiKey = ProviderFactory.resolveEnv(provider.getApiKey());
        return apiKey == null || apiKey.isBlank() ? redacted : redacted.replace(apiKey, "[REDACTED]");
    }

    private static void close(ModelProvider provider) {
        if (provider instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Probe result is authoritative; cleanup remains best effort.
            }
        }
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private static Object camelize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(toCamel(String.valueOf(key)), camelize(item)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ProviderProbeService::camelize).toList();
        }
        return value;
    }

    private static String toCamel(String key) {
        StringBuilder out = new StringBuilder(key.length());
        boolean upper = false;
        for (int index = 0; index < key.length(); index++) {
            char value = key.charAt(index);
            if (value == '-' || value == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(value));
                upper = false;
            } else {
                out.append(value);
            }
        }
        return out.toString();
    }
}
