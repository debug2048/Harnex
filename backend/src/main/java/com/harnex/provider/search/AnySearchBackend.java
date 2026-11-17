package com.harnex.provider.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.net.OutboundProxyResolver;

/** Authenticated AnySearch /v1/search. Anonymous quota registration is deliberately not used. */
@Component
@ProjectRuntime
public class AnySearchBackend extends AbstractHttpSearchBackend {
    private static final String DEFAULT_ENDPOINT = "https://api.anysearch.com/v1/search";

    public AnySearchBackend(AgentConfig cfg, OutboundProxyResolver proxyResolver) { super(cfg, proxyResolver); }

    @Override public String name() { return "anysearch"; }

    @Override public List<SearchResult> search(String query, int maxResults) throws SearchException {
        if (apiKey().isBlank()) throw new SearchException("no API key configured (harnex.web-search.api-key)", true);
        int max = clampMax(maxResults);
        var body = MAPPER.createObjectNode();
        body.put("query", query); body.put("max_results", max); body.put("format", "json");
        String language = ws().getLanguage();
        if (language != null && !language.isBlank()) body.put("language", "zh".equalsIgnoreCase(language) ? "zh-CN" : language);
        JsonNode root;
        try { root = postJson(endpointOr(DEFAULT_ENDPOINT), Map.of("Authorization", "Bearer " + apiKey()), body); }
        catch (SearchException failure) { throw sanitizedFailure(failure); }
        if (root == null || !root.path("code").isIntegralNumber())
            throw new SearchException("AnySearch returned an invalid response envelope", false);
        if (!root.path("code").canConvertToInt() || root.path("code").intValue() != 0)
            throw new SearchException("AnySearch reported an unsuccessful search", true);
        JsonNode hits = root.path("data").path("results");
        if (!hits.isArray()) throw new SearchException("AnySearch response has no results array", false);
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode hit : hits) {
            if (results.size() >= max) break;
            String url = textOf(hit, "url");
            if (url.isBlank()) continue;
            String snippet = textOf(hit, "snippet");
            if (snippet.isBlank()) snippet = textOf(hit, "content");
            results.add(new SearchResult(textOf(hit, "title"), url, clip(snippet, SNIPPET_MAX)));
        }
        return results;
    }

    @Override protected SearchException httpError(int code, String body) {
        // AnySearch error bodies may contain generated credentials. Never expose them in tool results or health logs.
        return super.httpError(code, null);
    }
}
