package com.harnex.mcp;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

/**
 * MCP transport over SSE (Server-Sent Events).
 * <p>
 * Protocol flow: 1. Client opens GET to SSE url → receives event stream 2. Server sends {@code event: endpoint} with
 * the POST url for JSON-RPC 3. Client sends JSON-RPC requests via POST to that endpoint 4. Server sends JSON-RPC
 * responses via {@code event: message} on the SSE stream
 */
@Slf4j
public class SseMcpTransport implements McpTransport {

    private static final ObjectMapper M = new ObjectMapper();
    private static final MediaType JSON_TYPE = MediaType.get("application/json");

    private final String sseUrl;
    private final Map<String, String> headers;

    private OkHttpClient http;
    private EventSource eventSource;
    private volatile String postEndpoint;
    private final CountDownLatch endpointReady = new CountDownLatch(1);

    /**
     * Pending requests keyed by JSON-RPC id. Long (was Integer) to eliminate the 2.1G-request rollover window that
     * could otherwise return a stale response to the wrong caller on long-lived servers.
     */
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /**
     * IDs whose future was removed because of timeout / disconnect. A late response on such an ID is logged at debug
     * instead of being silently swallowed (which used to mask race bugs). Bounded by simple FIFO eviction at 1024
     * entries.
     */
    private final Set<Long> abandoned = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicLong idSeq = new AtomicLong(1);

    public SseMcpTransport(String sseUrl, Map<String, String> headers) {
        this.sseUrl = sseUrl;
        this.headers = headers != null ? headers : Map.of();
    }

    @Override
    public void start() throws Exception {
        http = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(15)).readTimeout(Duration.ofMinutes(30))
            .build();

        Request.Builder reqBuilder = new Request.Builder().url(sseUrl);
        headers.forEach(reqBuilder::addHeader);
        Request request = reqBuilder.build();

        EventSource.Factory factory = EventSources.createFactory(http);
        eventSource = factory.newEventSource(request, new EventSourceListener() {
            @Override
            public void onOpen(EventSource es, Response response) {
                log.debug("MCP SSE connection opened to {}", sseUrl);
            }

            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                if ("endpoint".equals(type)) {
                    String resolved = resolveUrl(sseUrl, data.trim());
                    if (resolved == null) {
                        log.error("MCP SSE: refusing endpoint '{}' from server (cross-origin or unsafe scheme)", data);
                        // Leave postEndpoint null so sendRequest fails fast with a clear message.
                        endpointReady.countDown();
                        return;
                    }
                    postEndpoint = resolved;
                    log.debug("MCP SSE received POST endpoint: {}", postEndpoint);
                    endpointReady.countDown();
                } else if ("message".equals(type)) {
                    try {
                        JsonNode msg = M.readTree(data);
                        if (msg.has("id") && !msg.get("id").isNull()) {
                            long msgId = msg.get("id").asLong();
                            CompletableFuture<JsonNode> fut = pending.remove(msgId);
                            if (fut != null) {
                                fut.complete(msg);
                            } else if (abandoned.remove(msgId)) {
                                log.debug("MCP SSE: late response for abandoned id {} (timed-out earlier)", msgId);
                            } else {
                                log.debug("MCP SSE: response for unknown id {} (no pending request)", msgId);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse MCP SSE message: {}", e.getMessage());
                    }
                }
            }

            @Override
            public void onClosed(EventSource es) {
                log.info("MCP SSE connection closed for {}", sseUrl);
                pending.forEach((k, fut) -> fut.completeExceptionally(new IOException("SSE connection closed")));
                pending.clear();
            }

            @Override
            public void onFailure(EventSource es, Throwable t, Response response) {
                log.warn("MCP SSE connection failure for {}: {}", sseUrl,
                    t != null ? t.getMessage() : (response != null ? response.code() : "unknown"));
                pending.forEach((k, fut) -> fut.completeExceptionally(t != null ? t : new IOException("SSE failure")));
                pending.clear();
            }
        });

        if (!endpointReady.await(15, TimeUnit.SECONDS)) {
            close();
            throw new IOException("Timed out waiting for MCP SSE endpoint event from " + sseUrl);
        }
    }

    @Override
    public JsonNode sendRequest(String method, JsonNode params) throws Exception {
        if (postEndpoint == null) {
            throw new IOException("MCP SSE not connected");
        }

        long id = idSeq.getAndIncrement();
        ObjectNode req = M.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }

        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);

        RequestBody body = RequestBody.create(M.writeValueAsString(req), JSON_TYPE);
        Request.Builder httpReq = new Request.Builder().url(postEndpoint).post(body);
        headers.forEach(httpReq::addHeader);

        // Wrap the POST so ANY failure path (network IOException, non-2xx response, JSON
        // serialisation throw above the call site) cleans up the pending entry. The previous
        // code only cleaned up on the timeout branch \u2014 a transport-level POST failure left
        // the entry in `pending` forever (or until close()), and a future late SSE response on
        // the same id would then complete a future no one was waiting on while the original
        // caller had already received an exception. {@link #markAbandoned} also gives debug
        // visibility on stragglers.
        try {
            try (Response resp = http.newCall(httpReq.build()).execute()) {
                if (!resp.isSuccessful()) {
                    throw new IOException("MCP SSE POST failed: HTTP " + resp.code());
                }
            }
        } catch (Throwable postFail) {
            pending.remove(id);
            markAbandoned(id);
            fut.completeExceptionally(postFail);
            if (postFail instanceof Exception ex) {
                throw ex;
            }
            throw new IOException("MCP SSE POST failed", postFail);
        }

        try {
            return fut.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            markAbandoned(id);
            throw new IOException("MCP SSE request timed out: " + method);
        } catch (Exception e) {
            // Reader may have completed exceptionally (SSE closed/failed) \u2014 still ensure
            // the entry doesn't linger past this caller's exit.
            pending.remove(id);
            throw e;
        }
    }

    @Override
    public void sendNotification(String method, JsonNode params) throws Exception {
        if (postEndpoint == null) {
            throw new IOException("MCP SSE not connected");
        }

        ObjectNode req = M.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }

        RequestBody body = RequestBody.create(M.writeValueAsString(req), JSON_TYPE);
        Request.Builder httpReq = new Request.Builder().url(postEndpoint).post(body);
        headers.forEach(httpReq::addHeader);

        try (Response resp = http.newCall(httpReq.build()).execute()) {
            if (!resp.isSuccessful()) {
                log.warn("MCP SSE notification POST failed: HTTP {}", resp.code());
            }
        }
    }

    @Override
    public void close() throws Exception {
        if (eventSource != null) {
            eventSource.cancel();
        }
        if (http != null) {
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        }
        pending.forEach((k, fut) -> fut.cancel(true));
        pending.clear();
        abandoned.clear();
    }

    /** Bounded record of timed-out ids so late SSE responses are explicit, not silent. */
    private void markAbandoned(long id) {
        if (abandoned.size() >= 1024) {
            // Evict the smallest id to keep the set bounded; cheap and good enough for forensic debug.
            abandoned.stream().min(Long::compareTo).ifPresent(abandoned::remove);
        }
        abandoned.add(id);
    }

    /**
     * Resolve a potentially relative URL against the SSE base URL, with strict same-origin enforcement to prevent SSRF:
     * a malicious or compromised MCP server cannot redirect subsequent JSON-RPC POSTs to {@code file://},
     * {@code http://127.0.0.1:6379}, or any other origin we did not opt into when configuring this transport.
     *
     * <p>
     * Returns null if the resolved URL is not same-origin with {@code base}; callers MUST treat null as "refuse to talk
     * to this server".
     */
    static String resolveUrl(String base, String relative) {
        try {
            URI baseUri = URI.create(base);
            URI resolved = baseUri.resolve(relative);
            String scheme = resolved.getScheme();
            // Only http(s) is acceptable; reject file://, jar:, ftp:, javascript: etc.
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                log.warn("MCP SSE endpoint scheme '{}' rejected (only http/https allowed)", scheme);
                return null;
            }
            // Same scheme + host + port as the base SSE URL. Anything else is SSRF.
            if (!sameOrigin(baseUri, resolved)) {
                log.warn("MCP SSE endpoint '{}' rejected: not same-origin as base '{}'", resolved, baseUri);
                return null;
            }
            return resolved.toString();
        } catch (Exception e) {
            log.warn("MCP SSE endpoint resolve failed for '{}' against '{}': {}", relative, base, e.getMessage());
            return null;
        }
    }

    private static boolean sameOrigin(URI a, URI b) {
        if (a == null || b == null)
            return false;
        if (a.getScheme() == null || !a.getScheme().equalsIgnoreCase(b.getScheme()))
            return false;
        if (a.getHost() == null || !a.getHost().equalsIgnoreCase(b.getHost()))
            return false;
        return effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI u) {
        int p = u.getPort();
        if (p != -1)
            return p;
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }
}
