package com.harnex.tool.builtin;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.net.HttpClients;
import com.harnex.net.OutboundProxyResolver;
import com.harnex.net.ProxyResolution;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;
import com.harnex.util.Utf8;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * HTTP GET with SSRF protection. Defences: - allow http(s) only - resolve *all* addresses via getAllByName, reject if
 * *any* is private/loopback/link-local - pin DNS to the vetted address list inside OkHttp (prevents DNS rebinding
 * between pre-flight check and actual connect) - disable redirects (would re-open the SSRF window) - bound response
 * size via okio so a malicious 10 GB body cannot OOM the JVM
 *
 */
@Component
@ProjectRuntime
public class WebFetchTool implements Tool {

    private static final int MAX_BYTES = 256_000;
    private static final Duration CONNECT = Duration.ofSeconds(10);
    private static final Duration READ = Duration.ofSeconds(30);

    /**
     * Shared base client — the per-request DNS pin is added via {@link OkHttpClient#newBuilder()}, which reuses the
     * underlying dispatcher executor + connection pool. Without this, every web_fetch invocation leaked a fresh
     * dispatcher thread pool and idle connection pool, slowly starving the JVM under sustained tool use.
     *
     * <p>
     * {@code Proxy.NO_PROXY} is mandatory for SSRF correctness — see class-level doc.
     */
    private static final OkHttpClient BASE = new OkHttpClient.Builder().connectTimeout(CONNECT).readTimeout(READ)
        .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY).build();

    private final OutboundProxyResolver proxyResolver;

    public WebFetchTool(OutboundProxyResolver proxyResolver) {
        this.proxyResolver = proxyResolver;
    }

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "HTTP GET a public URL and return the response body (SSRF-protected).";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_NETWORK,
            ToolCapability.PLAN_MODE_SAFE, ToolCapability.CONCURRENCY_SAFE);
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription()).parameters(Map.of("type",
            "object", "properties", Map.of("url", Map.of("type", "string")), "required", new String[] {"url"})).build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String url = String.valueOf(input.getOrDefault("url", ""));
        try {
            URI u = URI.create(url);
            if (!("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()))) {
                return ToolResult.builder().error(true).content("only http(s) allowed").build();
            }
            final String host = u.getHost();
            if (host == null) {
                return ToolResult.builder().error(true).content("bad url").build();
            }

            // Resolve ALL addresses once and validate each.
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress ia : all) {
                String why = disallowReason(ia);
                if (why != null) {
                    return ToolResult.builder().error(true).content("blocked " + ia.getHostAddress() + ": " + why)
                        .build();
                }
            }
            final List<InetAddress> pinned = Arrays.asList(all);

            OkHttpClient http = clientFor(u, host, pinned);

            try (Response resp = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
                ResponseBody rb = resp.body();
                String body = "";
                if (rb != null) {
                    BufferedSource src = rb.source();
                    // Read at most MAX_BYTES+1 to detect truncation without ever buffering the whole body.
                    src.request(MAX_BYTES + 1L);
                    long have = src.getBuffer().size();
                    long take = Math.min(have, MAX_BYTES);
                    // Read raw bytes (not directly to String) so we can roll back to the last
                    // complete UTF-8 sequence — otherwise a chunked HTTP response that lands the
                    // truncation cursor in the middle of a 2/3/4-byte char would silently turn
                    // into U+FFFD and feed garbled text to the model.
                    byte[] raw = src.getBuffer().readByteArray(take);
                    int safe = Utf8.safeEnd(raw, raw.length);
                    body = new String(raw, 0, safe, StandardCharsets.UTF_8);
                    if (have > MAX_BYTES) {
                        body += "\n…[truncated at " + MAX_BYTES + " bytes]";
                    }
                }
                return ToolResult.builder().content("HTTP " + resp.code() + "\n\n" + body).build();
            }
        } catch (Exception e) {
            return ToolResult.builder().error(true).content(e.getClass().getSimpleName() + ": " + e.getMessage())
                .build();
        }
    }

    private OkHttpClient clientFor(URI u, String host, List<InetAddress> pinned) {
        int port = u.getPort();
        if (port <= 0) {
            port = "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
        }
        ProxyResolution.Resolved proxy = resolveProxy(host, port);
        if (proxy.useProxy()) {
            // With a forward proxy, the proxy side may resolve the target hostname (especially
            // HTTPS CONNECT and SOCKS). We still pre-validated the local resolution above so
            // obvious private/link-local targets are rejected before reaching the proxy.
            return HttpClients.outboundBuilder(proxy).connectTimeout(CONNECT).readTimeout(READ).followRedirects(false)
                .followSslRedirects(false).build();
        }

        // Reuse the shared dispatcher / connection pool; only the DNS pin is per-request.
        return BASE.newBuilder().dns(hostname -> {
            if (!hostname.equalsIgnoreCase(host)) {
                throw new UnknownHostException("DNS pin: unexpected hostname " + hostname);
            }
            return Collections.unmodifiableList(pinned);
        }).build();
    }

    private ProxyResolution.Resolved resolveProxy(String targetHost, int targetPort) {
        return proxyResolver.resolveGlobal(targetHost, targetPort);
    }

    /** @return null if the address is allowed, else a reason string. */
    private static String disallowReason(InetAddress ia) {
        if (ia.isAnyLocalAddress()) {
            return "any-local";
        }
        if (ia.isLoopbackAddress()) {
            return "loopback";
        }
        if (ia.isLinkLocalAddress()) {
            return "link-local";
        }
        if (ia.isMulticastAddress()) {
            return "multicast";
        }
        if (ia.isSiteLocalAddress()) {
            return "site-local";
        }

        if (ia instanceof Inet4Address v4) {
            byte[] b = v4.getAddress();
            int a = b[0] & 0xff, c = b[1] & 0xff;
            // 172.16.0.0/12
            if (a == 172 && c >= 16 && c <= 31) {
                return "rfc1918-172";
            }
            // 100.64.0.0/10 (CGNAT)
            if (a == 100 && c >= 64 && c <= 127) {
                return "cgnat";
            }
            // 169.254.169.254 — AWS/GCP/Azure metadata (also caught by link-local).
            if (a == 169 && c == 254) {
                return "cloud-metadata";
            }
        }
        if (ia instanceof Inet6Address v6) {
            // Reject scoped IPv6 literals (e.g. fe80::1%eth0). The percent-suffix is a kernel
            // routing hint that effectively pins the connection to a specific local interface —
            // legitimate Internet hosts never use it, and parsing edge cases have historically
            // let the scope id slip past plain isLinkLocalAddress() checks on some JVMs.
            if (v6.getScopeId() != 0 || (v6.getScopedInterface() != null)) {
                return "ipv6-scoped";
            }
            byte[] b = v6.getAddress();
            // fc00::/7 unique-local
            if ((b[0] & 0xfe) == 0xfc) {
                return "ipv6-ula";
            }
            // IPv4-compatible IPv6 (deprecated ::a.b.c.d form).
            if (v6.isIPv4CompatibleAddress()) {
                return "ipv4-compat";
            }
            // IPv4-mapped IPv6 (::ffff:a.b.c.d) — Inet6Address.isIPv4CompatibleAddress() does
            // NOT cover this. Without this check, ::ffff:127.0.0.1 / ::ffff:169.254.169.254 etc.
            // would bypass our SSRF defence on dual-stack hosts.
            boolean v4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    v4Mapped = false;
                    break;
                }
            }
            if (v4Mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    byte[] v4 = new byte[] {b[12], b[13], b[14], b[15]};
                    InetAddress mapped = InetAddress.getByAddress(v4);
                    String inner = disallowReason(mapped);
                    return inner != null ? "ipv4-mapped:" + inner : null;
                } catch (Exception e) {
                    return "ipv4-mapped";
                }
            }
        }
        return null;
    }
}
