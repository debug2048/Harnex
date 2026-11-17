package com.harnex.provider.search;

import java.util.List;

import com.harnex.config.AgentConfig;

/**
 * Pluggable web-search provider. Mirrors the {@code ModelProvider} pattern: each backend is a Spring {@code @Component}
 * keyed by {@link #name()}, and {@code WebSearchTool} picks the one matching {@code harnex.web-search.provider}.
 * Additional backends implement this SPI without changing the tool execution layer.
 */
public interface SearchBackend {

    /** Provider key matched (case-insensitively) against {@code harnex.web-search.provider}. */
    String name();

    /**
     * Static configuration gate used by {@code WebSearchTool.available()}. Most hosted providers require an API key;
     * keyless backends such as SearXNG override this to validate their endpoint instead.
     */
    default boolean isConfigured(AgentConfig.WebSearch ws) {
        return ws != null && ws.getApiKey() != null && !ws.getApiKey().isBlank();
    }

    /**
     * Execute a query.
     *
     * @param query the search query (non-blank — caller validates)
     * @param maxResults soft cap on returned hits
     * @return ordered list of hits (possibly empty, never null)
     * @throws SearchException on any failure; {@link SearchException#isTerminal()} tells the tool whether to advise the
     *             model against retrying
     */
    List<SearchResult> search(String query, int maxResults) throws SearchException;
}
