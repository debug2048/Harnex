package com.harnex.provider.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.net.OutboundProxyResolver;
import okhttp3.HttpUrl;

/** TinyFish's standalone Search API; no Browser/Agent automation requests or polling. */
@Component
@ProjectRuntime
public class TinyFishBackend extends AbstractHttpSearchBackend {
    private static final String DEFAULT_ENDPOINT = "https://api.search.tinyfish.ai";

    public TinyFishBackend(AgentConfig cfg, OutboundProxyResolver proxyResolver) { super(cfg, proxyResolver); }

    @Override public String name() { return "tinyfish"; }

    @Override public List<SearchResult> search(String query, int maxResults) throws SearchException {
        if (apiKey().isBlank()) throw new SearchException("no API key configured (harnex.web-search.api-key)", true);
        HttpUrl.Builder url = HttpUrl.get(endpointOr(DEFAULT_ENDPOINT)).newBuilder().addQueryParameter("query", query);
        String language = ws().getLanguage();
        if (language != null && !language.isBlank()) url.addQueryParameter("language", language);
        JsonNode root;
        try { root = getJson(url.build().toString(), Map.of("X-API-Key", apiKey(), "Accept", "application/json")); }
        catch (SearchException failure) { throw sanitizedFailure(failure); }
        if (root == null || !root.path("results").isArray())
            throw new SearchException("TinyFish response has no results array", false);
        // This endpoint has no max_results parameter; cap the returned first page locally without extra requests.
        int max = clampMax(maxResults);
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode hit : root.path("results")) {
            if (results.size() >= max) break;
            String target = textOf(hit, "url");
            if (target.isBlank()) continue;
            results.add(new SearchResult(textOf(hit, "title"), target, clip(textOf(hit, "snippet"), SNIPPET_MAX)));
        }
        return results;
    }

    @Override protected SearchException httpError(int code, String body) { return super.httpError(code, null); }
}
