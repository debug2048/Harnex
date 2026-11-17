package com.harnex.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harnex.net.HttpClients;
import com.harnex.net.ProxyResolution;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/**
 * MCP 2025-03-26 Streamable HTTP: each POST owns its JSON or SSE response stream.
 * Supports optional sessions; standalone GET streams and SSE resumption are not implemented.
 */
@Slf4j
public class StreamableHttpMcpTransport implements McpTransport {

    private static final ObjectMapper M = new ObjectMapper();
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final int MAX_EVENT_CHARS = 16 * 1024 * 1024;

    private final HttpUrl endpoint;
    private final Map<String, String> headers;
    private final Duration callTimeout;
    private final AtomicLong idSeq = new AtomicLong(1);
    // Only cancellation handles are shared. Responses always stay with their originating POST.
    private final Set<Call> activeCalls = new HashSet<>();
    private OkHttpClient http;
    private String sessionId;
    private boolean closed;

    public StreamableHttpMcpTransport(String url, Map<String, String> headers) {
        this(url, headers, Duration.ofSeconds(120));
    }

    // Short deadlines keep timeout tests deterministic without a public configuration surface.
    StreamableHttpMcpTransport(String url, Map<String, String> headers, Duration callTimeout) {
        if (url == null || (!url.regionMatches(true, 0, "http://", 0, 7)
            && !url.regionMatches(true, 0, "https://", 0, 8))) {
            throw new IllegalArgumentException("MCP Streamable HTTP requires an http or https endpoint");
        }
        endpoint = HttpUrl.get(url);
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (callTimeout.isZero() || callTimeout.isNegative()) {
            throw new IllegalArgumentException("MCP Streamable HTTP call timeout must be positive");
        }
        this.callTimeout = callTimeout;
    }

    @Override
    public String protocolVersion() {
        return "2025-03-26";
    }

    @Override
    public synchronized void start() throws IOException {
        if (closed) throw new IOException("MCP Streamable HTTP transport is closed");
        if (http != null) return;
        http = HttpClients.outboundBuilder(
            ProxyResolution.resolve("off", null, List.of(), "off", endpoint.host(), endpoint.port()))
            .callTimeout(callTimeout)
            // A redirected POST could expose custom API-key headers to another origin.
            .followRedirects(false).followSslRedirects(false)
            // Tool calls may have side effects; never transparently replay a failed POST.
            .retryOnConnectionFailure(false).build();
    }

    @Override
    public JsonNode sendRequest(String method, JsonNode params) throws IOException {
        long id = idSeq.getAndIncrement();
        ObjectNode message = message(method, params);
        message.put("id", id);
        Call call = newPost(message);
        try (Response response = call.execute()) {
            requireSuccess(response);
            ResponseBody body = response.body();
            if (body == null) throw new IOException("MCP Streamable HTTP response has no body");
            MediaType type = body.contentType();
            JsonNode result;
            if (type != null && "application".equals(type.type()) && "json".equals(type.subtype())) {
                result = matchingResponse(M.readTree(body.byteStream()), id);
                if (result == null) throw new IOException("MCP Streamable HTTP response ID does not match request " + id);
            } else if (type != null && "text".equals(type.type()) && "event-stream".equals(type.subtype())) {
                result = readEventStream(body, id);
            } else {
                throw new IOException("MCP Streamable HTTP response has unsupported Content-Type");
            }
            if ("initialize".equals(method) && result.has("result")) {
                captureSession(response.header("Mcp-Session-Id"));
                if (!protocolVersion().equals(result.path("result").path("protocolVersion").asText())) {
                    throw new IOException("MCP initialize returned an unsupported protocol version (expected "
                        + protocolVersion() + ")");
                }
            }
            return result;
        } finally {
            release(call);
        }
    }

    @Override
    public void sendNotification(String method, JsonNode params) throws IOException {
        Call call = newPost(message(method, params));
        try (Response response = call.execute()) {
            // Notifications have no JSON-RPC reply, but a failed HTTP delivery must fail the handshake.
            requireSuccess(response);
        } finally {
            release(call);
        }
    }

    private static ObjectNode message(String method, JsonNode params) {
        ObjectNode message = M.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) message.set("params", params);
        return message;
    }

    private synchronized Call newPost(ObjectNode message) throws IOException {
        if (closed) throw new IOException("MCP Streamable HTTP transport is closed");
        if (http == null) throw new IOException("MCP Streamable HTTP transport has not started");
        RequestBody json = RequestBody.create(message.toString(), JSON_TYPE);
        // retryOnConnectionFailure does not disable HTTP 503/421 follow-ups in OkHttp.
        // Mark every POST one-shot so a tool or notification cannot be replayed there either.
        RequestBody body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return json.contentType();
            }

            @Override
            public long contentLength() throws IOException {
                return json.contentLength();
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                json.writeTo(sink);
            }

            @Override
            public boolean isOneShot() {
                return true;
            }
        };
        Call call = http.newCall(request(sessionId)
            .post(body).build());
        activeCalls.add(call);
        return call;
    }

    private Request.Builder request(String session) {
        Request.Builder builder = new Request.Builder().url(endpoint);
        headers.forEach(builder::header);
        builder.header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", protocolVersion())
            .removeHeader("Mcp-Session-Id");
        if (session != null) builder.header("Mcp-Session-Id", session);
        return builder;
    }

    private synchronized void release(Call call) {
        activeCalls.remove(call);
    }

    private synchronized void captureSession(String session) throws IOException {
        if (session == null || closed) return;
        if (session.isEmpty() || session.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new IOException("MCP Streamable HTTP server returned an invalid session ID");
        }
        sessionId = session;
    }

    private static void requireSuccess(Response response) throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException("MCP Streamable HTTP request failed: HTTP " + response.code());
        }
    }

    private static JsonNode matchingResponse(JsonNode message, long id) throws IOException {
        if (message == null) return null;
        if (message.isArray()) {
            for (JsonNode item : message) {
                JsonNode match = matchingResponse(item, id);
                if (match != null) return match;
            }
            return null;
        }
        JsonNode responseId = message.get("id");
        if (responseId == null || !responseId.isIntegralNumber() || !responseId.canConvertToLong()
            || responseId.longValue() != id || message.has("method")) return null;
        if (!"2.0".equals(message.path("jsonrpc").asText()) || message.has("result") == message.has("error")) {
            throw new IOException("MCP Streamable HTTP received an invalid JSON-RPC response");
        }
        return message;
    }

    private static JsonNode readEventStream(ResponseBody body, long id) throws IOException {
        BufferedReader reader = new BufferedReader(body.charStream());
        StringBuilder data = new StringBuilder();
        String event = "";
        String line;
        boolean firstLine = true;
        while ((line = reader.readLine()) != null) {
            if (firstLine && line.startsWith("\uFEFF")) line = line.substring(1);
            firstLine = false;
            if (line.isEmpty()) {
                if (!data.isEmpty() && (event.isEmpty() || "message".equals(event))) {
                    JsonNode match = matchingResponse(M.readTree(data.toString()), id);
                    if (match != null) return match;
                }
                data.setLength(0);
                event = "";
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if ("event".equals(field)) event = value;
            if ("data".equals(field)) {
                if (data.length() + (long)value.length() + 1 > MAX_EVENT_CHARS) {
                    throw new IOException("MCP Streamable HTTP SSE event is too large");
                }
                data.append(value).append('\n');
            }
        }
        throw new IOException("MCP Streamable HTTP SSE stream ended before response " + id);
    }

    @Override
    public void close() {
        OkHttpClient client;
        String session;
        List<Call> calls;
        synchronized (this) {
            if (closed) return;
            closed = true;
            client = http;
            session = sessionId;
            sessionId = null;
            calls = List.copyOf(activeCalls);
            activeCalls.clear();
        }
        calls.forEach(Call::cancel);
        if (client == null) return;
        try {
            if (session != null) {
                Call delete = client.newCall(request(session).delete().build());
                delete.timeout().timeout(Math.min(callTimeout.toMillis(), 5_000), TimeUnit.MILLISECONDS);
                try (Response response = delete.execute()) {
                    if (!response.isSuccessful() && response.code() != 405) {
                        log.debug("MCP Streamable HTTP session deletion returned HTTP {}", response.code());
                    }
                } catch (IOException e) {
                    log.debug("MCP Streamable HTTP session deletion failed ({})", e.getClass().getSimpleName());
                }
            }
        } finally {
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }
}
