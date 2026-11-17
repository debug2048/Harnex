package com.harnex.provider;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.observability.HarnexMetrics;
import com.harnex.net.ApiEndpoints;
import com.harnex.net.HttpClients;
import com.harnex.net.HttpExchangeState;
import com.harnex.net.ProxyResolution;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Shared HTTP/SSE plumbing for the wire adapters ({@link OpenAiCompatibleProvider}, {@code OpenAiResponsesProvider},
 * {@link AnthropicProvider}). Holds the OkHttp client + connection lifecycle, the shared call-cancel watchdog
 * scheduler, and the two pieces of logic that are identical-but-fiddly across every streaming provider:
 *
 * <ul>
 * <li>{@link #readSseStream} — open the call, run a time-to-first-content (TTFT) watchdog, read {@code data:} lines,
 * and map every failure mode to the right {@link ProviderException} code. The per-event grammar differs per provider,
 * so the caller supplies a {@link DataLineHandler}; the catch/cancel scaffolding lives here once.</li>
 * <li>{@link #runWithTimeout} — the non-streaming one-shot request with a wall-clock {@code Call.cancel()}
 * watchdog.</li>
 * </ul>
 *
 * Each provider's wire-specific translation (request body, frame parsing, response shape) stays in the subclass.
 */
@Slf4j
public abstract class AbstractHttpProvider implements ModelProvider, AutoCloseable {

    protected static final ObjectMapper M = new ObjectMapper();
    protected static final MediaType JSON = MediaType.get("application/json");
    /** Defensive cap against a malicious or broken upstream that streams unbounded text (~2 MB of UTF-16). */
    protected static final int MAX_ASSISTANT_CHARS = 2_000_000;
    /** Independent cap for visible/private reasoning accumulators; reasoning must not bypass the response memory bound. */
    protected static final int MAX_REASONING_CHARS = 2_000_000;
    /** Per tool-call argument accumulator cap. */
    protected static final int MAX_TOOL_ARGS_CHARS = 500_000;
    /** A terminal Responses event can legitimately contain the bounded 8 MiB native output plus JSON overhead. */
    protected static final int MAX_SSE_LINE_CHARS = 12 * 1024 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
    /** Non-streaming responses share the same defensive envelope; reject instead of parsing a truncated JSON body. */
    private static final int MAX_SUCCESS_BODY_BYTES = 16 * 1024 * 1024;

    /** Append one streamed field without allowing an unbounded multi-frame accumulator. */
    protected static void appendBounded(StringBuilder target, String addition, int maxChars, String field) {
        if (addition == null || addition.isEmpty()) {
            return;
        }
        if (target.length() > maxChars - addition.length()) {
            throw new ProviderException("agent_oversize", field + " exceeded " + maxChars + " chars");
        }
        target.append(addition);
    }

    protected final String name;
    protected final String baseUrl;
    protected final String apiKey;
    protected final String defaultModel;
    protected final OkHttpClient http;
    /** Optional metrics sink — null in test/one-shot constructors; resolved by {@link ProviderRouter} at boot. */
    protected final HarnexMetrics metrics;

    protected AbstractHttpProvider(String name, String baseUrl, String apiKey, String defaultModel,
        HarnexMetrics metrics, ProxyResolution.Resolved proxy) {
        this.name = name;
        this.baseUrl =
            baseUrl == null ? null : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.metrics = metrics;
        // HttpClients centralises proxy / no-default-selector / pool / timeout policy across every outbound component;
        // a null proxy is equivalent to an explicit "direct" Resolved.
        this.http = HttpClients.modelBuilder(proxy).retryOnConnectionFailure(false).build();
    }

    protected Call newCall(Request request) {
        return http.newCall(request.newBuilder().tag(HttpExchangeState.class,
            new HttpExchangeState()).build());
    }

    protected static ProviderException withExchange(ProviderException failure, Call call) {
        return failure == null ? null : failure.withExchange(call.request().tag(HttpExchangeState.class));
    }

    /** Direct cancellation plus a bounded poll covers inherited subtask cancellation without sharing mutable controls. */
    protected static final class CallCancellation implements AutoCloseable {
        private final ProviderCallScope.Context context = ProviderCallScope.current();
        private final AutoCloseable registration;
        private final ScheduledFuture<?> inheritedPoll;
        CallCancellation(Runnable cancel) {
            registration = context == null ? null : context.onCancel().apply(cancel);
            inheritedPoll = context == null ? null : ONESHOT_TIMEOUT_EXEC.scheduleWithFixedDelay(() -> {
                if (context.isCancelled()) cancel.run();
            }, 0, 100, TimeUnit.MILLISECONDS);
        }
        boolean cancelled() { return context != null && context.isCancelled(); }
        @Override public void close() {
            if (inheritedPoll != null) inheritedPoll.cancel(false);
            if (registration != null) try { registration.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * Ensure the base URL path ends with a version segment (e.g. {@code /v1}).
     * If it already ends with {@code /v1}, {@code /v2}, etc., return as-is; otherwise append {@code /v1}.
     * This allows users to configure base-url with or without the trailing {@code /v1} prefix.
     */
    protected static String ensureVersionPath(String url) {
        return ApiEndpoints.ensureVersionPath(url);
    }

    /**
     * Resolve the final endpoint URL, supporting both short baseUrls and full-path baseUrls.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>If {@code baseUrl} already ends with the given {@code endpoint} (e.g. the user configured the full
     *       {@code https://host/v1/chat/completions}), it is used verbatim — no version path or endpoint is appended.</li>
     *   <li>Otherwise the legacy behaviour applies: {@link #ensureVersionPath} normalises the version prefix and the
     *       {@code endpoint} suffix is appended.</li>
     * </ul>
     *
     * @param baseUrl  the configured base URL (may include a full endpoint path)
     * @param endpoint the endpoint suffix beginning with a slash, e.g. {@code "/chat/completions"}, {@code "/responses"},
     *                 {@code "/messages"}
     */
    protected static String resolveEndpoint(String baseUrl, String endpoint) {
        return ApiEndpoints.resolveEndpoint(baseUrl, endpoint);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    /**
     * Single shared scheduler for {@code Call.cancel()} watchdogs (oneShot timeouts AND streamChat TTFT) across ALL
     * provider instances. One daemon thread suffices because each scheduled task only calls {@code Call.cancel()} and
     * never blocks. Static so multiple providers don't each leak a private scheduler.
     */
    protected static final ScheduledExecutorService ONESHOT_TIMEOUT_EXEC =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "provider-call-watchdog");
            t.setDaemon(true);
            return t;
        });
    /** Reuse the call-watchdog scheduler for streamChat TTFT cancels — semantically identical task. */
    protected static final ScheduledExecutorService STREAM_TTFT_EXEC = ONESHOT_TIMEOUT_EXEC;

    /**
     * Per-streamChat TTFT (time-to-first-content) cap. {@code readTimeout=10min} only fires after long IDLE periods on
     * an already-open response; a provider that completes TLS + HTTP 200 but never writes a content chunk would
     * otherwise hang the AgentLoop for those 10 minutes before {@link ProviderRouter}'s {@code total-budget-ms}
     * failover fires. Allow for slower initial routing and reasoning-model warm-up. Override via
     * {@code -Dharnex.provider.streamTtftMs}.
     */
    protected static final long STREAM_TTFT_MS = parsePositiveLongProperty("harnex.provider.streamTtftMs", 90_000L);

    /**
     * Defensive parse of a {@code -Dharnex.…} long property so a typo cannot abort JVM startup inside a static
     * initialiser. Falls back to {@code defaultMs} on any parse failure or non-positive value, with a WARN.
     */
    protected static long parsePositiveLongProperty(String name, long defaultMs) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultMs;
        }
        try {
            long v = Long.parseLong(raw.trim());
            if (v <= 0) {
                log.warn("Ignoring -D{}={} (must be positive); using default {}ms", name, raw, defaultMs);
                return defaultMs;
            }
            return v;
        } catch (NumberFormatException nfe) {
            log.warn("Ignoring -D{}={} (not a long); using default {}ms", name, raw, defaultMs);
            return defaultMs;
        }
    }

    /** Handles one SSE {@code data:} payload (prefix stripped, trimmed, non-empty, not {@code [DONE]}). */
    @FunctionalInterface
    protected interface DataLineHandler {
        /**
         * @return {@code true} to stop reading the stream early; {@code false} to continue. May throw
         *         {@link JsonProcessingException} (mapped to {@code "parse"}) or {@link ProviderException}
         *         (propagated).
         */
        boolean onData(String payload) throws IOException;
    }

    /**
     * Open {@code call}, run the TTFT watchdog, and read the SSE body line-by-line, dispatching each {@code data:}
     * payload to {@code onData}. Returns the {@link ProviderException} that terminated the stream, or {@code null} on a
     * clean EOF. The caller is responsible for emitting {@code message_start} (via {@code onHttpOk}, invoked once HTTP
     * 200 is confirmed) and for assembling/emitting {@code message_end} afterwards.
     *
     * @param ioErrorCode failover code for a generic (non-cancel) {@link IOException} — {@code "timeout"} for Chat
     *            Completions, {@code "network"} for Anthropic/Responses (preserves each adapter's existing
     *            classification).
     */
    protected ProviderException readSseStream(Call call, long ttftMs, AtomicBoolean firstContentSeen,
        String ioErrorCode, Runnable onHttpOk, DataLineHandler onData) {
        ProviderException pendingErr = null;
        CallCancellation cancellation = new CallCancellation(call::cancel);
        AtomicBoolean watchdogFired = new AtomicBoolean();
        ScheduledFuture<?> ttftWatchdog = STREAM_TTFT_EXEC.schedule(() -> {
            if (firstContentSeen.get()) {
                return;
            }
            log.warn("Provider {} TTFT watchdog firing after {}ms — cancelling stalled stream", name, ttftMs);
            watchdogFired.set(true);
            call.cancel();
        }, ttftMs, TimeUnit.MILLISECONDS);
        try (Response resp = call.execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                String code = String.valueOf(resp.code());
                String msg = resp.body() != null ? readBoundedErrorBody(resp) : resp.message();
                throw new ProviderException(code, "HTTP " + code + ": " + msg);
            }
            onHttpOk.run();
            try (BufferedReader br =
                new BufferedReader(new InputStreamReader(resp.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readBoundedLine(br)) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break; // OpenAI sentinel; Anthropic/Responses never send it (harmless)
                    }
                    if (onData.onData(payload)) {
                        break;
                    }
                }
            }
        } catch (SocketTimeoutException ste) {
            pendingErr = ProviderException.fromCause("timeout", ste);
        } catch (InterruptedIOException iie) {
            // OkHttp/Okio surface Call.cancel() as InterruptedIOException on some socket primitives. Discriminate a
            // watchdog-cancel (failover-eligible stream_ttft_timeout) from a user STOP (cancelled, NOT failover).
            if (watchdogFired.get() && !cancellation.cancelled() && !firstContentSeen.get()) {
                pendingErr = new ProviderException("stream_ttft_timeout",
                    "no first content from " + name + " within " + ttftMs + "ms");
            } else {
                Thread.currentThread().interrupt();
                pendingErr = new ProviderException("cancelled", "agent cancelled");
            }
        } catch (JsonProcessingException jpe) {
            // Malformed SSE chunk: do NOT trigger failover — just abort this stream.
            pendingErr = ProviderException.fromCause("parse", jpe);
        } catch (ProviderException pe) {
            pendingErr = pe;
        } catch (IOException e) {
            if (cancellation.cancelled()) {
                pendingErr = new ProviderException("cancelled", "run cancelled");
            } else if (watchdogFired.get() && !firstContentSeen.get()) {
                pendingErr = new ProviderException("stream_ttft_timeout",
                    "no first content from " + name + " within " + ttftMs + "ms");
            } else {
                pendingErr = ProviderException.fromCause(ioErrorCode, e);
            }
        } finally {
            ttftWatchdog.cancel(false);
            cancellation.close();
        }
        if (firstContentSeen.get()) {
            var exchange = call.request().tag(HttpExchangeState.class);
            if (exchange != null) exchange.outputReceived();
        }
        ProviderCallScope.exchange(call.request().tag(HttpExchangeState.class));
        return withExchange(pendingErr, call);
    }

    protected static String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder(512);
        boolean readAny = false;
        while (true) {
            int ch = reader.read();
            if (ch < 0) {
                return readAny ? line.toString() : null;
            }
            readAny = true;
            if (ch == '\n') {
                return line.toString();
            }
            if (ch == '\r') {
                reader.mark(1);
                int next = reader.read();
                if (next >= 0 && next != '\n') {
                    reader.reset();
                }
                return line.toString();
            }
            if (line.length() >= MAX_SSE_LINE_CHARS) {
                throw new ProviderException("agent_oversize",
                    "SSE event exceeded " + MAX_SSE_LINE_CHARS + " characters");
            }
            line.append((char)ch);
        }
    }

    protected static String readBoundedErrorBody(Response response) throws IOException {
        byte[] bytes = readAtMost(response, MAX_ERROR_BODY_BYTES + 1);
        int length = Math.min(bytes.length, MAX_ERROR_BODY_BYTES);
        String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
        String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
        String prefix = text.stripLeading().toLowerCase(Locale.ROOT);
        boolean html = contentType.contains("text/html") || prefix.startsWith("<!doctype html") || prefix.startsWith("<html");
        if (html) {
            if (response.code() == 403) {
                return text.contains("网站防火墙")
                    ? "The upstream website firewall blocked this API request. Ask the provider to check its firewall rules and request limits."
                    : "The upstream returned an HTML access-denied page instead of an API response. Check access permissions and gateway/firewall rules with the provider.";
            }
            return "The upstream gateway returned an HTML error page instead of an API response. Check the provider endpoint and service status.";
        }
        return bytes.length > MAX_ERROR_BODY_BYTES ? text + "…[truncated]" : text;
    }

    /** Read a complete successful one-shot body within a fixed envelope; partial JSON must never reach a parser. */
    protected static String readBoundedSuccessBody(Response response) throws IOException {
        if (response.body() == null) {
            throw new IOException("response body is null");
        }
        byte[] bytes = readAtMost(response, MAX_SUCCESS_BODY_BYTES + 1);
        if (bytes.length > MAX_SUCCESS_BODY_BYTES) {
            throw new ProviderException("agent_oversize",
                "HTTP response exceeded " + MAX_SUCCESS_BODY_BYTES + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Read at most {@code limit} bytes; unlike Okio's fixed-count overload, normal short bodies do not throw EOF. */
    private static byte[] readAtMost(Response response, int limit) throws IOException {
        if (response.body() == null || limit <= 0) {
            return new byte[0];
        }
        try (var in = response.body().byteStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192))) {
            byte[] buffer = new byte[8192];
            int remaining = limit;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toByteArray();
        }
    }

    /** Parses a successful (2xx, non-null body) response into the one-shot return value. */
    @FunctionalInterface
    protected interface ResponseParser<T> {
        T parse(Response resp) throws IOException;
    }

    /**
     * Execute a non-streaming {@code call} with an optional wall-clock budget that actually {@code Call.cancel()}s on
     * timeout (the only thing that frees the OkHttp worker thread; interrupt-based cancellation does nothing). On a
     * non-2xx response throws {@link ProviderException} with the HTTP status code; on cancel throws code
     * {@code "timeout"}; on any other {@link IOException} throws code {@code "network"}.
     */
    protected <T> T runWithTimeout(Call call, long timeoutMs, ResponseParser<T> parser) {
        CallCancellation cancellation = new CallCancellation(call::cancel);
        ScheduledFuture<?> watchdog = null;
        if (timeoutMs > 0) {
            watchdog = ONESHOT_TIMEOUT_EXEC.schedule(call::cancel, timeoutMs, TimeUnit.MILLISECONDS);
        }
        try (Response resp = call.execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                String code = String.valueOf(resp.code());
                String msg = resp.body() != null ? readBoundedErrorBody(resp) : resp.message();
                throw new ProviderException(code, "HTTP " + code + ": " + msg);
            }
            return parser.parse(resp);
        } catch (ProviderException failure) {
            throw withExchange(failure, call);
        } catch (JsonProcessingException invalidJson) {
            throw withExchange(ProviderException.fromCause("parse", invalidJson), call);
        } catch (IOException e) {
            if (cancellation.cancelled()) throw withExchange(new ProviderException("cancelled", "run cancelled"), call);
            if (call.isCanceled()) {
                throw withExchange(new ProviderException("timeout", "oneShot exceeded " + timeoutMs + "ms budget"), call);
            }
            throw withExchange(ProviderException.fromCause("network", e), call);
        } finally {
            cancellation.close();
            ProviderCallScope.exchange(call.request().tag(HttpExchangeState.class));
            if (watchdog != null) {
                watchdog.cancel(false);
            }
        }
    }

    /**
     * Release the OkHttp connection pool + dispatcher executor so idle dispatcher threads don't block JVM exit past
     * Spring shutdown (observed as a ~30s hang under {@code docker stop}). Idempotent.
     */
    @Override
    public void close() {
        try {
            var es = http.dispatcher().executorService();
            es.shutdown();
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                es.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            http.dispatcher().executorService().shutdownNow();
        } catch (Exception ignored) {
            /* best-effort */ }
        try {
            http.connectionPool().evictAll();
        } catch (Exception ignored) {
            /* best-effort */ }
        try {
            if (http.cache() != null) {
                http.cache().close();
            }
        } catch (Exception ignored) {
            /* best-effort */ }
    }
}
