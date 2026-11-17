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
 * Exa search backend ({@code https://api.exa.ai/search}, exa.ai). Neural / embeddings-based search; good for semantic /
 * research discovery. We request inline {@code text} content so each hit carries a snippet. Auth via {@code x-api-key}.
 */
@Component
@ProjectRuntime
public class ExaBackend extends AbstractHttpSearchBackend {

    private static final String DEFAULT_ENDPOINT = "https://api.exa.ai/search";

    public ExaBackend(AgentConfig cfg, OutboundProxyResolver proxyResolver) {
        super(cfg, proxyResolver);
    }

    @Override
    public String name() {
        return "exa";
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) throws SearchException {
        String key = apiKey();
        if (key.isBlank()) {
            throw new SearchException("no API key configured (harnex.web-search.api-key)", true);
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        body.put("numResults", clampMax(maxResults));
        // Ask for a bounded text excerpt per result so the hit carries a usable snippet.
        ObjectNode contents = body.putObject("contents");
        contents.putObject("text").put("maxCharacters", SNIPPET_MAX);

        JsonNode root = postJson(endpointOr(DEFAULT_ENDPOINT), Map.of("x-api-key", key), body);
        List<SearchResult> out = new ArrayList<>();
        JsonNode results = root.get("results");
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                String title = textOf(r, "title");
                String url = textOf(r, "url");
                if (url.isBlank() && title.isBlank()) {
                    continue;
                }
                out.add(new SearchResult(title, url, clip(snippetOf(r), SNIPPET_MAX)));
            }
        }
        return out;
    }

    /** Prefer the joined highlights, else the text excerpt. */
    private static String snippetOf(JsonNode r) {
        JsonNode hl = r.get("highlights");
        if (hl != null && hl.isArray() && hl.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode h : hl) {
                if (h.isTextual()) {
                    if (sb.length() > 0) {
                        sb.append(" … ");
                    }
                    sb.append(h.asText());
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        return textOf(r, "text");
    }
}
