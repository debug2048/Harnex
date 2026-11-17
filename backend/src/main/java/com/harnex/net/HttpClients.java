package com.harnex.net;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

/**
 * Shared OkHttp client factory for every outbound HTTP component in the harness. Centralising construction means that:
 *
 * <ol>
 * <li>Proxy / timeout / connection-pool semantics are guaranteed identical across LLM providers, tools, and future
 * outbound subsystems &mdash; one place to audit, one place to harden.</li>
 * <li>The JVM system-wide {@link ProxySelector#getDefault()} is <em>always</em> blocked at the client level via
 * {@link NoDefaultProxySelector}, even when the caller passes a direct (no-proxy) {@link ProxyResolution.Resolved}.
 * Without that block, callers running under containers / CI with a {@code -Dhttps.proxyHost=...} JVM flag would
 * silently route through it &mdash; surprising both operators and our own audit expectations.</li>
 * <li>Adding new outbound concerns (TLS truststore, request decorators, etc.) is one method on this class instead of N
 * copy-pasted builders.</li>
 * </ol>
 */
public final class HttpClients {

    private HttpClients() {}

    /**
     * Model calls and catalogue probes use HTTP/1.1 from the first request. SSE does not require HTTP/2, and
     * avoiding its stream-reset failures here keeps transport compatibility independent of runtime history.
     */
    public static OkHttpClient.Builder modelBuilder(ProxyResolution.Resolved resolved) {
        return outboundBuilder(resolved).protocols(List.of(Protocol.HTTP_1_1));
    }

    /**
     * Build an OkHttp client for an outbound component. Honours the resolved proxy decision and applies the historical
     * provider timeout/pool profile; callers with tighter needs can override those builder settings before
     * {@code build()}.
     *
     * @param resolved result of {@link ProxyResolution#resolve}. Must not be null; pass the explicit
     *            {@code Resolved.direct(...)} for no-proxy.
     */
    public static OkHttpClient.Builder outboundBuilder(ProxyResolution.Resolved resolved) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
            .eventListenerFactory(HttpExchangeState::eventsFor)
            // Inherit the historical provider profile: see {@code OpenAiCompatibleProvider}
            // constructor comment for the rationale on each value.
            .connectTimeout(Duration.ofSeconds(15)).readTimeout(Duration.ofMinutes(10)).callTimeout(Duration.ZERO)
            .connectionPool(new ConnectionPool(20, 5, TimeUnit.MINUTES))
            // The selector is replaced below only for the explicit `proxy: system` mode.
            .proxySelector(NoDefaultProxySelector.INSTANCE);
        if (resolved == null || !resolved.useProxy()) {
            b.proxy(Proxy.NO_PROXY);
            return b;
        }
        if (resolved.useSystemProxy()) {
            // macOS system/PAC proxy discovery is opt-in. Never silently inherit a JVM-wide
            // proxy: the operator must choose `harnex.http.proxy: system` in Settings/YAML.
            System.setProperty("java.net.useSystemProxies", "true");
            ProxySelector selector = ProxySelector.getDefault();
            b.proxySelector(selector == null ? NoDefaultProxySelector.INSTANCE : selector);
            return b;
        }
        b.proxy(resolved.proxy());
        String user = resolved.username();
        if (user != null && !user.isBlank()) {
            String cred = Credentials.basic(user, resolved.password() == null ? "" : resolved.password());
            // OkHttp gotcha: returning a new request unconditionally yields an infinite
            // 407 → re-send loop. Guard with a "already attempted" check so a wrong
            // password fails after one attempt instead of pinning a worker thread.
            Authenticator auth = (route, response) -> {
                if (response.request().header("Proxy-Authorization") != null)
                    return null;
                return response.request().newBuilder().header("Proxy-Authorization", cred).build();
            };
            b.proxyAuthenticator(auth);
        }
        return b;
    }

    /**
     * A {@link ProxySelector} that never delegates to the JVM default. OkHttp consults the selector for every request
     * to discover candidate proxies; returning a single-element list with {@link Proxy#NO_PROXY} effectively
     * short-circuits any {@code -Dhttp.proxyHost=...} / {@code java.net.useSystemProxies=true} configuration the JVM
     * may have inherited from the host.
     *
     * <p>
     * This is paired with an explicit {@link OkHttpClient.Builder#proxy(Proxy)} call so direct requests really are
     * direct (without the explicit proxy call, OkHttp would still pass through to the selector, which is the right
     * thing for systems that DO want a system proxy &mdash; just not for us).
     * </p>
     */
    static final class NoDefaultProxySelector extends ProxySelector {
        static final NoDefaultProxySelector INSTANCE = new NoDefaultProxySelector();
        private static final List<Proxy> NO_PROXY = Collections.singletonList(Proxy.NO_PROXY);

        @Override
        public List<Proxy> select(URI uri) {
            return NO_PROXY;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // intentionally empty — there is no fallback proxy to record a failure against
        }
    }
}
