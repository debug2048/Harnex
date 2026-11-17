package com.harnex.provider.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.net.OutboundProxyResolver;

/**
 * Perplexity backend ({@code https://api.perplexity.ai/chat/completions}). Perplexity is a search+synthesis API rather
 * than a pure results API: we send the query to a {@code sonar} model and map the returned {@code search_results} (or
 * {@code citations} fallback) into our {@link SearchResult} list of candidate URLs. Auth via {@code Authorization:
 * Bearer}.
 */
@Component
@ProjectRuntime
public class PerplexityBackend extends AbstractHttpSearchBackend {

    private static final String DEFAULT_ENDPOINT = "https://api.perplexity.ai/chat/completions";
    /** Cheapest search-grounded model; good enough to surface candidate sources. */
    private static final String MODEL = "sonar";

    public PerplexityBackend(AgentConfig cfg, OutboundProxyResolver proxyResolver) {
        super(cfg, proxyResolver);
    }

    @Override
    public String name() {
        return "perplexity";
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) throws SearchException {
        String key = apiKey();
        if (key.isBlank()) {
            throw new SearchException("no API key configured (harnex.web-search.api-key)", true);
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", MODEL);
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", query);

        JsonNode root = postJson(endpointOr(DEFAULT_ENDPOINT), Map.of("Authorization", "Bearer " + key), body);
        int max = clampMax(maxResults);
        List<SearchResult> out = new ArrayList<>();

        // Preferred: structured search_results [{title, url, date}].
        JsonNode sr = root.get("search_results");
        if (sr != null && sr.isArray()) {
            for (JsonNode r : sr) {
                if (out.size() >= max) {
                    break;
                }
                String title = textOf(r, "title");
                String url = textOf(r, "url");
                if (url.isBlank()) {
                    continue;
                }
                out.add(new SearchResult(title.isBlank() ? url : title, url, clip(textOf(r, "date"), SNIPPET_MAX)));
            }
        }
        // Fallback: bare citations [url, ...].
        if (out.isEmpty()) {
            JsonNode cites = root.get("citations");
            if (cites != null && cites.isArray()) {
                for (JsonNode c : cites) {
                    if (out.size() >= max) {
                        break;
                    }
                    String url = c.isTextual() ? c.asText("") : "";
                    if (!url.isBlank()) {
                        out.add(new SearchResult(url, url, ""));
                    }
                }
            }
        }
        return out;
    }
}
