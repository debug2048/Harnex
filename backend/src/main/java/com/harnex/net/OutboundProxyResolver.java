package com.harnex.net;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;

/**
 * Resolves the effective proxy for Harnex-owned Java outbound HTTP calls.
 *
 * <p>
 * This deliberately does not configure child processes such as npx/npm, MCP stdio servers, bash/curl, or Chrome. Those
 * tools should use their own standard proxy configuration. This resolver is for Java HTTP clients owned by the backend:
 * providers, web_fetch, SSE MCP, IM outbound clients, marketplace downloads, etc.
 * </p>
 */
@Component
public class OutboundProxyResolver {

    private static final String[] PROXY_ENV_ORDER =
        {"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"};
    private static final String[] NO_PROXY_ENV_ORDER = {"NO_PROXY", "no_proxy"};

    private final AgentConfig cfg;

    public OutboundProxyResolver(AgentConfig cfg) {
        this.cfg = cfg;
    }

    /** Resolve using only the global {@code harnex.http.*} policy plus conventional proxy env fallback. */
    public ProxyResolution.Resolved resolveGlobal(String targetHost, int targetPort) {
        return resolve(null, targetHost, targetPort);
    }

    /**
     * Resolve with a component-level proxy override. Passing a non-null value, including blank/off, intentionally wins
     * over the global proxy; this preserves per-provider override semantics.
     */
    public ProxyResolution.Resolved resolve(String componentProxy, String targetHost, int targetPort) {
        AgentConfig.HttpConfig httpCfg =
            cfg == null || cfg.getHttp() == null ? new AgentConfig.HttpConfig() : cfg.getHttp();
        String resolvedComponentProxy = resolveEnv(componentProxy);
        String globalProxyRaw = resolveEnv(httpCfg.getProxy());
        String globalSource = "yaml";

        if (globalProxyRaw == null || globalProxyRaw.isBlank()) {
            for (String name : PROXY_ENV_ORDER) {
                String envVal = System.getenv(name);
                if (envVal != null && !envVal.isBlank()) {
                    globalProxyRaw = envVal;
                    globalSource = "env:" + name;
                    break;
                }
            }
        }

        List<String> bypass = new ArrayList<>(httpCfg.getProxyBypass() == null ? List.of() : httpCfg.getProxyBypass());
        for (String name : NO_PROXY_ENV_ORDER) {
            String envVal = System.getenv(name);
            if (envVal != null && !envVal.isBlank()) {
                bypass.addAll(NoProxyEnvParser.parse(envVal));
                break;
            }
        }

        return ProxyResolution.resolve(resolvedComponentProxy, globalProxyRaw, globalSource, bypass,
            httpCfg.getProxySupport(), targetHost, targetPort);
    }

    public static String resolveEnv(String v) {
        if (v == null) {
            return null;
        }
        if (v.startsWith("${") && v.endsWith("}")) {
            String inside = v.substring(2, v.length() - 1);
            String def = "";
            int idx = inside.indexOf(':');
            if (idx >= 0) {
                def = inside.substring(idx + 1);
                inside = inside.substring(0, idx);
            }
            String env = System.getenv(inside);
            return env != null ? env : def;
        }
        if (v.startsWith("$")) {
            String env = System.getenv(v.substring(1));
            return env != null ? env : v;
        }
        return v;
    }
}
