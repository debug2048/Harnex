package com.harnex.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/** Transport facts, independent of displayed tokens. Sending a POST without a response has an unknown result. */
public final class HttpExchangeState {
    public enum Acceptance { NOT_SENT, SENT_UNKNOWN, ACCEPTED, REJECTED, OUTPUT_RECEIVED, UNKNOWN }
    private final AtomicReference<Acceptance> acceptance = new AtomicReference<>(Acceptance.NOT_SENT);
    private volatile String requestId;
    private volatile long retryAfterMillis;
    private volatile Protocol protocol;
    private final ConcurrentMap<String,Long> timings = new ConcurrentHashMap<>();

    public Acceptance acceptance() { return acceptance.get(); }
    public String requestId() { return requestId; }
    public String protocol() { return protocol == null ? null : protocol.toString(); }
    public long retryAfterMillis() { return retryAfterMillis; }
    public Map<String,Long> timings() { return Map.copyOf(timings); }
    private void mark(String phase) { timings.putIfAbsent(phase,System.currentTimeMillis()); }
    public void sent() { acceptance.set(Acceptance.SENT_UNKNOWN); }
    public void accepted(String upstreamRequestId) { acceptance.set(Acceptance.ACCEPTED); requestId = bounded(upstreamRequestId); }
    public void rejected(Response response) {
        acceptance.set(Acceptance.REJECTED);
        requestId = bounded(response.header("x-request-id", response.header("request-id")));
        retryAfterMillis = parseRetryAfter(response.header("Retry-After"), Instant.now());
    }
    public void outputReceived() { acceptance.set(Acceptance.OUTPUT_RECEIVED); }
    public static EventListener eventsFor(Call call) {
        HttpExchangeState state = call.request().tag(HttpExchangeState.class);
        if (state == null) return EventListener.NONE;
        return new EventListener() {
            @Override public void callStart(Call ignored) { state.mark("callStartedAt"); }
            @Override public void dnsStart(Call ignored,String host) { state.mark("dnsStartedAt"); }
            @Override public void dnsEnd(Call ignored,String host,List<InetAddress> addresses) { state.mark("dnsFinishedAt"); }
            @Override public void connectStart(Call ignored,InetSocketAddress address,Proxy proxy) { state.mark("connectStartedAt"); }
            @Override public void connectEnd(Call ignored,InetSocketAddress address,Proxy proxy,Protocol protocol) { state.mark("connectFinishedAt"); }
            @Override public void secureConnectStart(Call ignored) { state.mark("tlsStartedAt"); }
            @Override public void secureConnectEnd(Call ignored,Handshake handshake) { state.mark("tlsFinishedAt"); }
            @Override public void connectionAcquired(Call ignored,Connection connection) {
                state.mark("connectionAcquiredAt");
                state.protocol = connection.protocol();
            }
            @Override public void requestHeadersStart(Call ignored) { state.mark("requestHeadersStartedAt"); state.acceptance.set(Acceptance.SENT_UNKNOWN); }
            @Override public void requestHeadersEnd(Call ignored,Request request) { state.mark("requestHeadersFinishedAt"); }
            @Override public void requestBodyEnd(Call ignored,long bytes) { state.mark("requestBodyFinishedAt"); }
            @Override public void responseBodyStart(Call ignored) { state.mark("responseBodyStartedAt"); }
            @Override public void callEnd(Call ignored) { state.mark("transportFinishedAt"); }
            @Override public void callFailed(Call call,IOException failure) {
                state.mark("transportFinishedAt");
            }
            @Override public void responseHeadersEnd(Call ignored, Response response) {
                state.mark("responseHeadersAt");
                state.acceptance.set(response.isSuccessful() ? Acceptance.ACCEPTED : Acceptance.REJECTED);
                state.requestId = bounded(response.header("x-request-id", response.header("request-id")));
                state.retryAfterMillis = parseRetryAfter(response.header("Retry-After"), Instant.now());
            }
        };
    }
    /** RFC delay-seconds and HTTP-date, bounded to one day so malformed endpoints cannot overflow a scheduler. */
    public static long parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank() || value.length() > 128) return 0;
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds <= 0 ? 0 : Math.min(seconds, 86_400L) * 1000;
        } catch (NumberFormatException notSeconds) {
            try {
                Instant when = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0, Math.min(86_400_000L, Duration.between(now, when).toMillis()));
            } catch (RuntimeException malformed) { return 0; }
        }
    }
    private static String bounded(String value) { return value == null ? null : value.substring(0, Math.min(256, value.length())).replaceAll("[\\p{Cntrl}]", ""); }
}
