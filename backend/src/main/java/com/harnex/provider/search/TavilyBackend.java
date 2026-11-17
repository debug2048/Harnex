package com.harnex.provider.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.net.OutboundProxyResolver;

/**
 * Tavily search backend ({@code https://api.tavily.com/search}). LLM-optimised search API: ranked results with clean
 * {@code content} snippets. API key is passed in the request body ({@code api_key}).
 */
@Component
@ProjectRuntime
public class TavilyBackend extends AbstractHttpSearchBackend {

    private static final String DEFAULT_ENDPOINT = "https://api.tavily.com/search";

    public TavilyBackend(AgentConfig cfg, OutboundProxyResolver proxyResolver) {
        super(cfg, proxyResolver);
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) throws SearchException {
        String key = apiKey();
        if (key.isBlank()) {
            throw new SearchException("no API key configured (harnex.web-search.api-key)", true);
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("api_key", key);
        body.put("query", query);
        body.put("max_results", clampMax(maxResults));

        JsonNode root = postJson(endpointOr(DEFAULT_ENDPOINT), Map.of(), body);
        List<SearchResult> out = new ArrayList<>();
        JsonNode results = root.get("results");
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                String title = textOf(r, "title");
                String url = textOf(r, "url");
                if (url.isBlank() && title.isBlank()) {
                    continue;
                }
                out.add(new SearchResult(title, url, clip(textOf(r, "content"), SNIPPET_MAX)));
            }
        }
        return out;
    }
}
