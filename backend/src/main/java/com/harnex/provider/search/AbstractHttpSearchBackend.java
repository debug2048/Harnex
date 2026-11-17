package com.harnex.provider.search;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.net.HttpClients;
import com.harnex.net.OutboundProxyResolver;
import com.harnex.net.ProxyResolution;

import okhttp3.*;

/**
 * Shared HTTP plumbing for the key-and-JSON search backends (Tavily / Exa / Brave / SearXNG / Perplexity). Centralises:
 * <ul>
 * <li>the outbound client build (proxy via {@link OutboundProxyResolver} + {@link HttpClients}, identical to every
 * other egress component; no SSRF DNS-pin since search hosts are fixed/operator-configured),</li>
 * <li>the request → status-classification → JSON-parse round-trip ({@link #postJson}/{@link #getJson}),</li>
 * <li>the terminal-vs-transient failure mapping ({@link #httpError}) — 429/5xx are transient, other 4xx are
 * terminal,</li>
 * <li>small JSON / string helpers.</li>
 * </ul>
 * Subclasses only build the URL + headers + body and map the provider's {@code results[]} shape to
 * {@link SearchResult}.
 *
 * <p>
 * Config note: backends share the single active {@code harnex.web-search.*} block ({@code api-key} / {@code endpoint} /
 * {@code max-results} / {@code timeout-ms}); only the backend named by {@code harnex.web-search.provider} is ever
 * invoked, so one key/endpoint at a time is sufficient.
 */
public abstract class AbstractHttpSearchBackend implements SearchBackend {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final MediaType JSON = MediaType.parse("application/json");
    /** Bound a single result's snippet so one verbose hit can't dominate the budget. */
    protected static final int SNIPPET_MAX = 500;

    protected final AgentConfig cfg;
    private final OutboundProxyResolver proxyResolver;

    protected AbstractHttpSearchBackend(AgentConfig cfg, OutboundProxyResolver proxyResolver) {
        this.cfg = cfg;
        this.proxyResolver = proxyResolver;
    }

    protected AgentConfig.WebSearch ws() {
        return cfg.getWebSearch();
    }

    /** Configured API key (may be blank for keyless backends like SearXNG). */
    protected String apiKey() {
        AgentConfig.WebSearch w = ws();
        return w == null || w.getApiKey() == null ? "" : w.getApiKey();
    }

    /** Per-request timeout, defaulting to 15s. */
    protected long timeoutMs() {
        AgentConfig.WebSearch w = ws();
        long t = w == null ? 15_000L : w.getTimeoutMs();
        return t <= 0 ? 15_000L : t;
    }

    /**
     * Configured endpoint, or {@code dflt} when blank (most backends have a fixed default; SearXNG requires its own).
     */
    protected String endpointOr(String dflt) {
        AgentConfig.WebSearch w = ws();
        String e = w == null ? null : w.getEndpoint();
        return e == null || e.isBlank() ? dflt : e;
    }

    /** Effective max results: caller's cap clamped to >=1. */
    protected int clampMax(int maxResults) {
        return Math.max(1, maxResults);
    }

    protected JsonNode postJson(String url, Map<String, String> headers, Object body) throws SearchException {
        return doRequest(url, "POST", headers, body);
    }

    protected JsonNode getJson(String url, Map<String, String> headers) throws SearchException {
        return doRequest(url, "GET", headers, null);
    }

    private JsonNode doRequest(String url, String method, Map<String, String> headers, Object body)
        throws SearchException {
        Request.Builder rb = new Request.Builder().url(url);
        if (headers != null) {
            headers.forEach(rb::header);
        }
        if ("POST".equals(method)) {
            byte[] payload;
            try {
                payload = MAPPER.writeValueAsBytes(body);
            } catch (Exception e) {
                throw new SearchException("failed to build request: " + e.getMessage(), true, e);
            }
            rb.post(RequestBody.create(payload, JSON));
        } else {
            rb.get();
        }
        OkHttpClient http = clientFor(url);
        try (Response resp = http.newCall(rb.build()).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw httpError(resp.code(), text);
            }
            try {
                return MAPPER.readTree(text);
            } catch (Exception e) {
                // A 200 with an unparseable body is unusual — treat as transient (provider hiccup).
                throw new SearchException("unparseable response: " + e.getMessage(), false, e);
            }
        } catch (SearchException se) {
            throw se;
        } catch (IOException e) {
            // Connect/read timeout, DNS, TLS, socket reset → transient.
            throw new SearchException(e.getClass().getSimpleName() + ": " + e.getMessage(), false, e);
        }
    }

    /** 429 + 5xx are transient (retry may help); other 4xx (auth / billing / malformed) are terminal. */
    protected SearchException httpError(int code, String body) {
        boolean transientFault = code == 429 || code >= 500;
        return new SearchException("HTTP " + code + (body == null || body.isBlank() ? "" : ": " + clip(body, 200)),
            !transientFault);
    }

    /** For backends that suppress error bodies, also hide parser/network details that might reflect credentials. */
    protected SearchException sanitizedFailure(SearchException failure) {
        String message = String.valueOf(failure.getMessage());
        if (message.matches("HTTP \\d{3}")) return failure;
        String lower = message.toLowerCase(Locale.ROOT);
        return new SearchException(name() + (lower.contains("timeout") || lower.contains("timed out")
            ? " timeout" : " request or response failed"), failure.isTerminal());
    }

    protected OkHttpClient clientFor(String url) {
        String host = "";
        int port = 443;
        try {
            URI u = URI.create(url);
            if (u.getHost() != null) {
                host = u.getHost();
            }
            if (u.getPort() > 0) {
                port = u.getPort();
            } else if ("http".equalsIgnoreCase(u.getScheme())) {
                port = 80;
            }
        } catch (Exception ignored) {
            /* fall back to defaults */ }
        ProxyResolution.Resolved proxy = proxyResolver.resolveGlobal(host, port);
        Duration t = Duration.ofMillis(timeoutMs());
        return HttpClients.outboundBuilder(proxy).connectTimeout(Duration.ofSeconds(10)).readTimeout(t).callTimeout(t)
            .followRedirects(false).build();
    }

    protected static String textOf(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    protected static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String trimmed = s.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
