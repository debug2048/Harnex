package com.harnex.gateway.webhook.outbound;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Shared retry / response helpers for {@link ChannelOutboundClient} implementations.
 *
 * <p>
 * Rationale:
 * <ul>
 * <li><b>Retry transient failures.</b> Because the inbound IM webhook already returned 200 ACK by the time the outbound
 * push runs, the platform will NOT redeliver — a single network blip or 5xx blip would otherwise permanently drop the
 * final agent reply. We try up to 3 times total with 250ms / 750ms backoff (~1s extra latency worst-case, well within
 * the IM user's tolerance window).</li>
 * <li><b>Parse JSON instead of string-matching.</b> Platforms (DingTalk / WeCom / Feishu) all return 200 + an error
 * code on logical failure (invalid token, bot kicked, rate limited). The earlier {@code body.contains("\"errcode\":0")}
 * check was fragile against whitespace (e.g. {@code "errcode": 0}) and substring collisions (e.g. an errmsg containing
 * the literal {@code "errcode":0}). Jackson parse is one allocation either way.</li>
 * </ul>
 */
final class OutboundHttp {

    private OutboundHttp() {}

    /** Max total attempts including the first try. */
    static final int MAX_ATTEMPTS = 3;

    /**
     * Execute the request and return its (body, code) tuple, retrying transient failures.
     *
     * <p>
     * Transient = {@link IOException} OR HTTP 408/429/500/502/503/504. Anything else surfaces immediately so we don't
     * hammer the platform with known-bad messages (oversized, bad token, malformed JSON, etc.).
     *
     * @return a populated {@link Outcome}; {@code success=false} when ALL attempts failed (the last error is in
     *         {@code lastError}).
     */
    static Outcome executeWithRetry(OkHttpClient http, Request req) {
        return executeWithRetry(http, req, Thread::sleep);
    }

    /** Test seam for exercising retries without real wall-clock backoff. */
    static Outcome executeWithRetry(OkHttpClient http, Request req, Backoff backoff) {
        IOException lastIo = null;
        int lastCode = -1;
        String lastBody = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (Response r = http.newCall(req).execute()) {
                int code = r.code();
                String body = r.body() == null ? "" : safeRead(r);
                if (r.isSuccessful()) {
                    return Outcome.ok(code, body);
                }
                lastCode = code;
                lastBody = body;
                if (!isTransientStatus(code) || attempt == MAX_ATTEMPTS) {
                    return Outcome.httpFail(code, body);
                }
                // fall through to backoff + retry
            } catch (IOException e) {
                lastIo = e;
                if (attempt == MAX_ATTEMPTS) {
                    return Outcome.ioFail(e);
                }
            }
            sleepBackoff(attempt, backoff);
        }
        // Loop always returns or sets lastIo/lastCode by the final attempt; this is defensive.
        if (lastIo != null) {
            return Outcome.ioFail(lastIo);
        }
        return Outcome.httpFail(lastCode, lastBody);
    }

    /**
     * Parse {@code body} and return the integer at {@code field}; {@code null} when the body is empty, not JSON, or the
     * field is missing / not numeric. Callers MUST treat {@code
     * null} as failure (a 200 HTML proxy error page would otherwise be silently accepted).
     */
    static Integer readIntField(ObjectMapper json, String body, String field) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(body);
            JsonNode v = root.path(field);
            if (v.isInt() || v.isLong()) {
                return v.asInt();
            }
            // Some platforms (rare) return code as a numeric string.
            if (v.isTextual()) {
                try {
                    return Integer.parseInt(v.asText());
                } catch (NumberFormatException ignored) {
                    /* fall through */ }
            }
        } catch (Exception ignored) {
            // Non-JSON body — caller will surface the raw text head as the failure reason.
        }
        return null;
    }

    /** Truncate {@code body} to {@code max} chars with an ellipsis marker; for safe inclusion in exception messages. */
    static String bodyHead(String body, int max) {
        if (body == null)
            return "<empty>";
        if (body.length() <= max)
            return body;
        return body.substring(0, max) + "…(truncated)";
    }

    private static boolean isTransientStatus(int code) {
        return code == 408 || code == 429 || (code >= 500 && code < 600);
    }

    private static void sleepBackoff(int attempt, Backoff backoff) {
        try {
            // 250ms, 750ms — bounded so the watchdog isn't surprised by a multi-second push.
            backoff.sleep(attempt == 1 ? 250L : 750L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface Backoff {
        void sleep(long millis) throws InterruptedException;
    }

    private static String safeRead(Response r) {
        try {
            return r.body() == null ? "" : r.body().string();
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    /** Wire-level outcome of one or more attempts; logical platform errcode is checked separately. */
    static final class Outcome {
        final boolean success;
        final int code;
        final String body;
        final IOException ioError;

        private Outcome(boolean success, int code, String body, IOException ioError) {
            this.success = success;
            this.code = code;
            this.body = body;
            this.ioError = ioError;
        }

        static Outcome ok(int code, String body) {
            return new Outcome(true, code, body, null);
        }

        static Outcome httpFail(int code, String body) {
            return new Outcome(false, code, body, null);
        }

        static Outcome ioFail(IOException e) {
            return new Outcome(false, -1, "", e);
        }
    }
}
