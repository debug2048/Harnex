package com.harnex.provider.search;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.net.OutboundProxyResolver;

/**
 * SearXNG backend — a self-hosted metasearch aggregator. KEYLESS: it uses {@code harnex.web-search.endpoint} as the
 * instance base URL (e.g. {@code http://localhost:8888}) and queries {@code {endpoint}/search?q=...&format=json}. The
 * operator runs and configures the upstream engines; we just consume the aggregated JSON.
 */
@Component
@ProjectRuntime
public class SearxngBackend extends AbstractHttpSearchBackend {

    public SearxngBackend(AgentConfig cfg, OutboundProxyResolver proxyResolver) {
        super(cfg, proxyResolver);
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public boolean isConfigured(AgentConfig.WebSearch ws) {
        return ws != null && ws.getEndpoint() != null && !ws.getEndpoint().isBlank();
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) throws SearchException {
        String base = endpointOr("");
        if (base.isBlank()) {
            throw new SearchException(
                "SearXNG needs harnex.web-search.endpoint set to your instance URL (e.g. http://localhost:8888)", true);
        }
        // Strip a trailing slash so we don't produce "http://host//search".
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String url = root + "/search?format=json&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode resp = getJson(url, Map.of("Accept", "application/json"));
        List<SearchResult> out = new ArrayList<>();
        JsonNode results = resp.get("results");
        int max = clampMax(maxResults);
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                if (out.size() >= max) {
                    break;
                }
                String title = textOf(r, "title");
                String u = textOf(r, "url");
                if (u.isBlank() && title.isBlank()) {
                    continue;
                }
                out.add(new SearchResult(title, u, clip(textOf(r, "content"), SNIPPET_MAX)));
            }
        }
        return out;
    }
}
