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
 * Brave Search backend ({@code https://api.search.brave.com/res/v1/web/search}). Independent index (Brave's own
 * crawler, not a Google/Bing reseller), privacy-focused, with a usable free tier. Auth via
 * {@code X-Subscription-Token}.
 */
@Component
@ProjectRuntime
public class BraveBackend extends AbstractHttpSearchBackend {

    private static final String DEFAULT_ENDPOINT = "https://api.search.brave.com/res/v1/web/search";

    public BraveBackend(AgentConfig cfg, OutboundProxyResolver proxyResolver) {
        super(cfg, proxyResolver);
    }

    @Override
    public String name() {
        return "brave";
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) throws SearchException {
        String key = apiKey();
        if (key.isBlank()) {
            throw new SearchException("no API key configured (harnex.web-search.api-key)", true);
        }
        String url = endpointOr(DEFAULT_ENDPOINT) + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&count="
            + clampMax(maxResults);
        JsonNode root = getJson(url, Map.of("X-Subscription-Token", key, "Accept", "application/json"));
        List<SearchResult> out = new ArrayList<>();
        JsonNode results = root.path("web").get("results");
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                String title = textOf(r, "title");
                String u = textOf(r, "url");
                if (u.isBlank() && title.isBlank()) {
                    continue;
                }
                out.add(new SearchResult(title, u, clip(textOf(r, "description"), SNIPPET_MAX)));
            }
        }
        return out;
    }
}
