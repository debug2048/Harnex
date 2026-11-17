package com.harnex.tool.builtin;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.provider.search.SearchBackend;
import com.harnex.provider.search.SearchException;
import com.harnex.provider.search.SearchResult;
import com.harnex.provider.search.WebSearchHealth;
import com.harnex.tool.*;

/**
 * Web search. Returns a numbered list of {@code title — url} + snippet so the model can pick a URL and follow up with
 * {@code web_fetch}. Backed by a pluggable {@link SearchBackend} (v1: Tavily).
 *
 * <p>
 * This is the first "external paid dependency" tool, so it carries the full degradation kit:
 * <ul>
 * <li><b>{@link #available()}</b> hides the tool entirely when there's no key / it's disabled / the provider name is
 * unresolvable — a no-key install never advertises it (cache-stable; see {@link Tool#available()}).</li>
 * <li><b>{@link WebSearchHealth} circuit breaker</b> fast-fails with terminal guidance once a configured key starts
 * failing terminally (lapsed / billing), without yanking the tool from the advertised set.</li>
 * <li><b>terminal vs transient failure phrasing</b> — the model is explicitly told whether to stop (answer from its own
 * knowledge / ask_user) or whether a retry might help, so it never loops on a 403.</li>
 * </ul>
 */
@Component
@ProjectRuntime
public class WebSearchTool implements Tool {

    private final AgentConfig cfg;
    private final WebSearchHealth health;
    private final Map<String, SearchBackend> backends;

    public WebSearchTool(AgentConfig cfg, WebSearchHealth health, List<SearchBackend> backendList) {
        this.cfg = cfg;
        this.health = health;
        this.backends = backendList.stream()
            .collect(Collectors.toMap(b -> b.name().toLowerCase(), b -> b, (a, b) -> a, LinkedHashMap::new));
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "Search the public web and return a numbered list of results (title, url, snippet). "
            + "Use this when you need recent / time-sensitive information or don't yet know which URL to read, "
            + "then call web_fetch on the most relevant result to read its full content.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_NETWORK, ToolCapability.PLAN_MODE_SAFE,
            ToolCapability.CONCURRENCY_SAFE);
    }

    @Override
    public int maxParallel() {
        return 4;
    }

    @Override
    public int timeoutMillis() {
        AgentConfig.WebSearch ws = cfg.getWebSearch();
        long t = ws == null ? 15_000L : ws.getTimeoutMs();
        return (int)Math.min(Integer.MAX_VALUE, Math.max(1_000L, t + 1_000L));
    }

    /**
     * Static capability gate: advertise only when enabled and the configured provider resolves to a registered,
     * configured backend. Pure function of config — never consults the runtime circuit breaker (that would bust the
     * prompt cache mid-session); see {@link Tool#available()}.
     */
    @Override
    public boolean available() {
        AgentConfig.WebSearch ws = cfg.getWebSearch();
        if (ws == null || !ws.isEnabled()) {
            return false;
        }
        SearchBackend backend = resolveBackend(ws.getProvider());
        return backend != null && backend.isConfigured(ws);
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "The search query."));
        props.put("maxResults", Map.of("type", "integer", "description",
            "Maximum number of results to return (optional; clamped to the configured cap)."));
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties", props, "required", new String[] {"query"})).build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String query = String.valueOf(input.getOrDefault("query", "")).trim();
        if (query.isEmpty()) {
            return ToolResult.builder().error(true).content("query must not be empty").build();
        }
        AgentConfig.WebSearch ws = cfg.getWebSearch();
        SearchBackend backend = ws == null ? null : resolveBackend(ws.getProvider());
        if (ws == null || !ws.isEnabled() || backend == null || !backend.isConfigured(ws)) {
            // Also guard direct callers. Missing configuration is not an upstream failure and must not trip health.
            return ToolResult.failure("tool_unavailable (retryable=false): web_search is disabled or not configured. "
                + "Do NOT retry or claim live verification; explain the limitation when relevant.");
        }
        int cap = ws.getMaxResults() <= 0 ? 10 : ws.getMaxResults();
        int requested = asInt(input.get("maxResults"), cap);
        int max = Math.max(1, Math.min(requested, cap));

        // Circuit breaker: when open, return terminal guidance instantly (no network, no latency).
        if (!health.usable()) {
            return ToolResult.ok("[web_search temporarily unavailable: the search provider has been failing "
                + "(likely an expired key or billing issue). Do NOT retry this call — answer from your own "
                + "knowledge or use ask_user to ask the user.]");
        }

        try {
            List<SearchResult> results = backend.search(query, max);
            health.recordResult(true, false, null);
            if (results.isEmpty()) {
                return ToolResult.ok("No results for: " + query);
            }
            return ToolResult.ok(format(query, results));
        } catch (SearchException e) {
            health.recordResult(false, e.isTerminal(), e.getMessage());
            if (e.isTerminal()) {
                return ToolResult.ok("[web_search unavailable: " + e.getMessage()
                    + " — this will not recover by retrying. Answer from your own knowledge or use ask_user.]");
            }
            return ToolResult.ok(
                "[web_search transient failure: " + e.getMessage() + " — you may retry once, or proceed without it.]");
        }
    }

    private String format(String query, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder("Search results for: ").append(query).append("\n\n");
        int i = 1;
        for (SearchResult r : results) {
            sb.append(i++).append(". ").append(r.title().isBlank() ? r.url() : r.title());
            if (!r.url().isBlank()) {
                sb.append(" — ").append(r.url());
            }
            sb.append('\n');
            if (!r.snippet().isBlank()) {
                sb.append("   ").append(r.snippet()).append('\n');
            }
        }
        sb.append("\nTo read a result, call web_fetch with its url.");
        return sb.toString();
    }

    private SearchBackend resolveBackend(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return backends.get(provider.toLowerCase());
    }

    private static int asInt(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                /* fall through */ }
        }
        return fallback;
    }
}
