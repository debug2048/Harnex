package com.harnex.net;

import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Parses VSCode-style proxy URL strings and merges per-component / global configuration into a single {@link Resolved}
 * record consumable by {@link HttpClients}.
 *
 * <p>
 * This class is intentionally pure logic + a small in-memory probe cache so it can be unit-tested without Spring.
 * Callers include provider routing, WebFetch, and any future outbound subsystem.
 * </p>
 *
 * <h3>URL syntax (matches VSCode {@code http.proxy})</h3>
 * <ul>
 * <li>{@code http://host:port}</li>
 * <li>{@code http://user:pass@host:port} &mdash; userinfo carries basic-auth credentials</li>
 * <li>{@code https://host:port} &mdash; HTTPS-to-proxy (rare; treated identically to HTTP proxy)</li>
 * <li>{@code socks5://host:port} or {@code socks://host:port}</li>
 * <li>{@code null} / blank / {@code "off"} / {@code "false"} &mdash; explicit no-proxy</li>
 * </ul>
 *
 * <h3>Bypass matching</h3> Glob patterns ({@code *} matches any host chars). In addition the following are bypassed
 * unconditionally and need not be configured: loopback ({@code 127.0.0.0/8}, {@code ::1}), RFC1918 private IPv4 ranges,
 * link-local ({@code 169.254/16}, {@code fe80::/10}), IPv6 ULA ({@code fc00::/7}), {@code localhost} and
 * {@code *.local}/{@code *.internal} domains. This mirrors the WebFetch SSRF denyList minus the cloud-metadata entries
 * (which are too aggressive to bypass for a forward proxy).
 */
public final class ProxyResolution {

    private ProxyResolution() {}

    /** TCP-connect timeout for {@link Resolved#probeDirect()}. Aligned with VSCode's default. */
    private static final int PROBE_TIMEOUT_MS = 3_000;

    /** Cache of probe results keyed by {@code host:port}. Lives for the JVM lifetime. */
    private static final ConcurrentMap<String, Boolean> PROBE_CACHE = new ConcurrentHashMap<>();

    /** Visible for tests &mdash; lets the unit test reset cache between cases. */
    static void clearProbeCacheForTesting() {
        PROBE_CACHE.clear();
    }

    /**
     * Final, immutable per-call output. {@link #useProxy} is the boolean to consult when building the OkHttp client;
     * {@link #proxy} is null when {@code useProxy=false}. The {@link #describe()} string is what the startup log and
     * {@code harnex doctor} render.
     */
    public static final class Resolved {
        private final Proxy proxy;
        private final String username;
        private final String password;
        private final boolean useProxy;
        private final boolean useSystemProxy;
        private final String source;
        private final String reason;

        Resolved(Proxy proxy, String username, String password, boolean useProxy, boolean useSystemProxy,
            String source, String reason) {
            this.proxy = proxy;
            this.username = username;
            this.password = password;
            this.useProxy = useProxy;
            this.useSystemProxy = useSystemProxy;
            this.source = source;
            this.reason = reason;
        }

        public Proxy proxy() {
            return proxy;
        }

        public String username() {
            return username;
        }

        public String password() {
            return password;
        }

        public boolean useProxy() {
            return useProxy;
        }

        public boolean useSystemProxy() {
            return useSystemProxy;
        }

        public String source() {
            return source;
        }

        public String reason() {
            return reason;
        }

        /** Single-line, SecretRedactor-safe summary suitable for INFO logs and {@code harnex doctor}. */
        public String describe() {
            if (!useProxy) {
                return "OFF (" + reason + ")";
            }
            if (useSystemProxy) {
                return "SYSTEM [" + reason + "]";
            }
            InetSocketAddress addr = (InetSocketAddress)proxy.address();
            String hostPort = addr.getHostString() + ":" + addr.getPort();
            String authBit = username != null && !username.isBlank() ? " (auth)" : "";
            return "ON via " + proxy.type() + " " + hostPort + authBit + " [" + reason + "]";
        }

        static Resolved direct(String source, String reason) {
            return new Resolved(null, null, null, false, false, source, reason);
        }

        static Resolved system(String source) {
            return new Resolved(null, null, null, true, true, source, "explicit-system-proxy");
        }
    }

    /**
     * Resolve effective proxy for a single outbound target.
     *
     * @param providerProxy raw per-component URL or {@code null}/{@code "off"}/blank. For LLM providers this is the
     *            provider-specific override.
     * @param globalProxy raw global {@code harnex.http.proxy}
     * @param bypass global bypass glob list (may be {@code null}/empty)
     * @param support VSCode-style support mode: {@code on} / {@code off} / {@code fallback}
     * @param targetHost upstream hostname (e.g. {@code api.anthropic.com}); used for bypass matching and the
     *            {@code fallback} probe
     * @param targetPort upstream port (used for the probe; if &le;0, probe is skipped and {@code fallback} is treated
     *            as {@code on})
     */
    public static Resolved resolve(String providerProxy, String globalProxy, List<String> bypass, String support,
        String targetHost, int targetPort) {
        return resolve(providerProxy, globalProxy, "global", bypass, support, targetHost, targetPort);
    }

    /**
     * Overload that lets the caller stamp a more specific origin label on the global tier &mdash; e.g.
     * {@code "env:HTTPS_PROXY"} or {@code "yaml"} &mdash; so {@code harnex doctor} and the startup log can tell the
     * operator <em>where</em> a non-YAML proxy came from. The label is used verbatim as {@link Resolved#source()} only
     * when the global tier is the effective one (per-provider override still wins and reports {@code "provider"}).
     */
    public static Resolved resolve(String providerProxy, String globalProxy, String globalSourceLabel,
        List<String> bypass, String support, String targetHost, int targetPort) {
        // ── Pick which proxy URL wins ─────────────────────────────────────────────────
        String raw;
        String source;
        if (providerProxy != null) {
            // Per-provider field, even when explicitly empty/"off", wins over global.
            raw = providerProxy;
            source = "provider";
        } else {
            raw = globalProxy;
            source = globalSourceLabel == null || globalSourceLabel.isBlank() ? "global" : globalSourceLabel;
        }

        // ── Explicit "off" / blank → DIRECT ───────────────────────────────────────────
        if (isOff(raw)) {
            return Resolved.direct(source, raw == null || raw.isBlank() ? "unset" : "off");
        }
        if ("system".equalsIgnoreCase(raw.trim())) {
            return Resolved.system(source);
        }

        // ── support=off short-circuits everything ─────────────────────────────────────
        String mode = support == null ? "fallback" : support.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(mode)) {
            return Resolved.direct(source, "proxySupport=off");
        }

        // ── Parse URL ────────────────────────────────────────────────────────────────
        ParsedProxy parsed;
        try {
            parsed = parse(raw);
        } catch (IllegalArgumentException ex) {
            // Bad URL → fail loud-but-safe: direct, with the parse error in the reason so it
            // surfaces in `harnex doctor` and the startup log.
            return Resolved.direct(source, "invalid url: " + ex.getMessage());
        }

        // ── Built-in bypass: loopback / private / link-local / .local / .internal ────
        if (isBuiltInBypass(targetHost)) {
            return Resolved.direct(source, "builtin-bypass:" + targetHost);
        }
        // ── Operator-configured bypass list ──────────────────────────────────────────
        if (matchesBypass(targetHost, bypass)) {
            return Resolved.direct(source, "bypass-match");
        }

        // ── fallback: probe direct connection once, cache the result ────────────────
        if ("fallback".equals(mode) && targetHost != null && targetPort > 0) {
            String key = targetHost + ":" + targetPort;
            Boolean cached = PROBE_CACHE.get(key);
            if (cached == null) {
                cached = probeDirect(targetHost, targetPort);
                PROBE_CACHE.put(key, cached);
            }
            if (Boolean.TRUE.equals(cached)) {
                return Resolved.direct(source, "fallback-probe:direct-ok");
            }
        }

        // ── Use the parsed proxy ─────────────────────────────────────────────────────
        Proxy p = new Proxy(parsed.type, new InetSocketAddress(parsed.host, parsed.port));
        return new Resolved(p, parsed.username, parsed.password, true, false, source,
            "fallback".equals(mode) ? "fallback-probe:direct-fail" : "proxySupport=on");
    }

    /** Result of URL parsing; package-private so {@link HttpClients} can also reuse it. */
    static final class ParsedProxy {
        final Proxy.Type type;
        final String host;
        final int port;
        final String username;
        final String password;

        ParsedProxy(Proxy.Type type, String host, int port, String username, String password) {
            this.type = Objects.requireNonNull(type, "type");
            this.host = Objects.requireNonNull(host, "host");
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }

    static ParsedProxy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty proxy url");
        }
        String trimmed = raw.trim();
        // Be friendly: allow bare "host:port" (no scheme) — treat as HTTP, matching curl's behaviour.
        if (!trimmed.contains("://")) {
            trimmed = "http://" + trimmed;
        }
        URI u;
        try {
            u = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("not a URI: " + ex.getMessage(), ex);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        Proxy.Type type = switch (scheme) {
            case "http", "https" -> Proxy.Type.HTTP;
            case "socks", "socks5", "socks4" -> Proxy.Type.SOCKS;
            default -> throw new IllegalArgumentException("unsupported scheme: " + scheme);
        };
        String host = u.getHost();
        int port = u.getPort();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("missing host");
        }
        if (port <= 0) {
            // Sensible defaults so users can write `http://proxy.lan` without a port.
            port = type == Proxy.Type.SOCKS ? 1080 : 8080;
        }
        String user = null;
        String pass = null;
        String userInfo = u.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            if (colon < 0) {
                user = userInfo;
            } else {
                user = userInfo.substring(0, colon);
                pass = userInfo.substring(colon + 1);
            }
        }
        return new ParsedProxy(type, host, port, user, pass);
    }

    private static boolean isOff(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return "off".equals(t) || "false".equals(t) || "none".equals(t) || "direct".equals(t);
    }

    /**
     * Hosts that should always be reached directly, regardless of the operator's proxy configuration. Stops the proxy
     * from being asked to reach a host that, by definition, doesn't need (and likely cannot use) the proxy.
     */
    static boolean isBuiltInBypass(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || "ip6-localhost".equals(h) || "ip6-loopback".equals(h)) {
            return true;
        }
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") || h.endsWith(".intranet")
            || h.endsWith(".corp") || h.endsWith(".home.arpa")) {
            return true;
        }
        // IPv4 literal classification
        if (looksLikeIpv4(h)) {
            int[] o = parseIpv4(h);
            if (o == null) {
                return false;
            }
            int a = o[0], b = o[1];
            if (a == 127) {
                return true;
            }
            if (a == 10) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true;
            }
            if (a == 192 && b == 168) {
                return true;
            }
            if (a == 169 && b == 254) {
                return true;
            }
            if (a == 0) {
                return true;
            }
            return false;
        }
        // IPv6 literal: rough classification — loopback, link-local, ULA
        if (h.equals("::1")) {
            return true;
        }
        if (h.startsWith("fe80:") || h.startsWith("[fe80:")) {
            return true;
        }
        if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("[fc") || h.startsWith("[fd")) {
            return true;
        }
        return false;
    }

    private static boolean looksLikeIpv4(String h) {
        if (h.isEmpty() || !Character.isDigit(h.charAt(0))) {
            return false;
        }
        int dots = 0;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c == '.') {
                dots++;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return dots == 3;
    }

    private static int[] parseIpv4(String h) {
        String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                out[i] = v;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return out;
    }

    static boolean matchesBypass(String host, List<String> patterns) {
        if (host == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (globMatch(p.trim().toLowerCase(Locale.ROOT), h)) {
                return true;
            }
        }
        return false;
    }

    /** Minimal glob: {@code *} matches any chars (including dots). No {@code ?}, no {@code []}. */
    private static boolean globMatch(String pattern, String s) {
        // Translate to regex once. Patterns are operator-provided and short, so per-call cost is negligible.
        StringBuilder re = new StringBuilder(pattern.length() + 8);
        re.append('^');
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                re.append(".*");
            } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                re.append('\\').append(c);
            } else {
                re.append(c);
            }
        }
        re.append('$');
        return s.matches(re.toString());
    }

    /**
     * Single TCP connect attempt &mdash; the fallback probe. Returns {@code true} when the connect completes within
     * {@link #PROBE_TIMEOUT_MS} milliseconds. Any failure (timeout, refused, DNS) yields {@code false}.
     */
    private static boolean probeDirect(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Convenience for {@link ProviderRouter}: derive the host/port from a base URL string for the bypass/probe step.
     * Returns a 2-element array {@code {host, port}}; port is the URL's port or the scheme default (80/443). Returns
     * {@code null} on parse failure.
     */
    public static String[] hostPortOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            URI u = new URI(baseUrl.trim());
            String host = u.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = u.getPort();
            if (port <= 0) {
                port = "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
            }
            return new String[] {host, String.valueOf(port)};
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    /** Mirror of {@link java.util.List#copyOf(java.util.Collection)} that tolerates a null input. */
    public static List<String> nullSafeCopy(List<String> in) {
        return in == null ? List.of() : new ArrayList<>(in);
    }
}
