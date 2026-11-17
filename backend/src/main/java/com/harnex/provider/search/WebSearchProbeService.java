package com.harnex.provider.search;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ConfigControlService;
import com.harnex.net.OutboundProxyResolver;

/** A single search using draft settings. Available in both runtimes; never registers tools or changes live health. */
@Service
public final class WebSearchProbeService {
    private final AgentConfig config;
    private final ConfigControlService control;
    private final Environment environment;
    private static final ObjectMapper JSON = new ObjectMapper();

    public WebSearchProbeService(AgentConfig config, ConfigControlService control, Environment environment) {
        this.config = config; this.control = control; this.environment = environment;
    }

    public Map<String, Object> probe(Map<String, Object> values) {
        long started = System.nanoTime();
        try {
            AgentConfig.WebSearch search = control.resolveWebSearchSettings(values.get("web-search"));
            search.setApiKey(resolve(search.getApiKey()));
            search.setEndpoint(resolve(search.getEndpoint()));
            search = control.resolveWebSearchSettings(JSON.convertValue(search, Map.class));
            AgentConfig candidate = new AgentConfig();
            candidate.setWebSearch(search);
            candidate.setHttp(config.getHttp());
            if (values.get("http") instanceof Map<?, ?> http) {
                Map<String, Object> merged = JSON.convertValue(config.getHttp(), Map.class);
                http.forEach((key, value) -> merged.put(camel(String.valueOf(key)), value));
                candidate.setHttp(JSON.convertValue(merged, AgentConfig.HttpConfig.class));
            }
            OutboundProxyResolver proxy = new OutboundProxyResolver(candidate);
            SearchBackend backend = switch (search.getProvider()) {
                case "tavily" -> new TavilyBackend(candidate, proxy);
                case "exa" -> new ExaBackend(candidate, proxy);
                case "brave" -> new BraveBackend(candidate, proxy);
                case "searxng" -> new SearxngBackend(candidate, proxy);
                case "perplexity" -> new PerplexityBackend(candidate, proxy);
                case "anysearch" -> new AnySearchBackend(candidate, proxy);
                case "tinyfish" -> new TinyFishBackend(candidate, proxy);
                default -> throw new IllegalArgumentException("Unknown search service");
            };
            if (!backend.isConfigured(search)) return result(false, "not_configured", started, List.of());
            List<SearchResult> hits = backend.search("web search", search.getMaxResults());
            // A remote endpoint must never cause the draft credential to be reflected back into the UI.
            String key = search.getApiKey();
            List<SearchResult> preview = hits.stream().limit(3).map(hit -> new SearchResult(
                safe(hit.title(), key), safeUrl(hit.url(), key), safe(hit.snippet(), key))).toList();
            return result(true, "success", started, preview);
        } catch (SearchException failure) {
            String detail = String.valueOf(failure.getMessage());
            String code = detail.startsWith("HTTP 401") || detail.startsWith("HTTP 403") ? "auth_failed"
                : detail.startsWith("HTTP 402") ? "quota_exhausted"
                : detail.startsWith("HTTP 429") ? "rate_limited"
                : detail.toLowerCase(Locale.ROOT).contains("timeout") ? "timeout" : "search_failed";
            // Raw upstream errors may contain keys, proxy credentials or reflected request bodies.
            return result(false, code, started, List.of());
        } catch (IllegalArgumentException failure) {
            return result(false, "invalid_config", started, List.of());
        }
    }

    private String resolve(String value) {
        return environment.resolveRequiredPlaceholders(value == null ? "" : value);
    }

    private static Map<String, Object> result(boolean success, String code, long started, List<SearchResult> hits) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success); result.put("code", code);
        result.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
        result.put("results", hits);
        return result;
    }

    private static String safe(String value, String key) {
        String text = value == null ? "" : value;
        if (key != null && !key.isBlank()) text = text.replace(key, "[redacted]");
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static String safeUrl(String value, String key) {
        try {
            var uri = URI.create(value);
            if ((key != null && !key.isBlank() && value.contains(key)) || uri.getUserInfo() != null) return "";
            return Set.of("https", "http").contains(uri.getScheme()) && uri.getHost() != null ? value : "";
        } catch (RuntimeException invalid) { return ""; }
    }

    private static String camel(String key) {
        StringBuilder result = new StringBuilder(); boolean upper = false;
        for (char ch : key.toCharArray()) {
            if (ch == '-') upper = true;
            else { result.append(upper ? Character.toUpperCase(ch) : ch); upper = false; }
        }
        return result.toString();
    }
}
