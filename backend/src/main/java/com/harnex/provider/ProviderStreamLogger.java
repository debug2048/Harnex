package com.harnex.provider;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import com.harnex.model.ToolCall;

/**
 * DEBUG-only helper that records each provider's <b>complete</b> request and response for a single streaming call,
 * correlated by a short {@link #newRequestId() request id}. All string assembly is gated on
 * {@link Logger#isDebugEnabled()} at the call site (and defensively again here) so INFO and above pay zero cost.
 *
 * <p>
 * Enabled purely via the existing log-level mechanism ({@code com.harnex.provider=DEBUG} in logback / a
 * {@code -Dlogging.level.com.harnex.provider=DEBUG} JVM flag / {@code agent-config.yml logging-level: DEBUG}); there is
 * no dedicated config knob.
 *
 * <p>
 * <b>Secret safety:</b> request logging records URL + body only (never headers), so the {@code Authorization} /
 * {@code x-api-key} credentials — which live in headers — never reach the log at all. As a defence-in-depth guard,
 * {@link #scrubBody(String)} also masks any API-key-looking token that might appear inside the request body.
 */
public final class ProviderStreamLogger {

    private ProviderStreamLogger() {
    }

    /** Default per-field truncation guard so a pathological payload cannot swamp the log. */
    private static final int MAX_BODY_CHARS = 20_000;
    /** Thinking text is diagnostic-only; a tighter cap keeps the log readable. */
    private static final int MAX_THINKING_CHARS = 4_000;
    /** Tool-call arguments preview cap — enough to identify the call without dumping full payloads. */
    private static final int MAX_TOOL_ARGS_PREVIEW = 200;

    /**
     * Defence-in-depth: match an API-key-looking token ({@code sk-...}) that might accidentally appear inside a
     * request body so it can be masked. Credentials normally live in headers (which are no longer logged), so this is
     * a lightweight safety net rather than an exhaustive scrubber.
     */
    private static final Pattern SECRET_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{6,}");

    /** Short correlation id (UUID first 8 chars) tying a request log line to its response log line. */
    public static String newRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Mask a secret so it stays diagnosable without leaking the key: keep a short prefix and the last 4 chars, replace
     * the middle with {@code ***}. Handles a bare key ({@code sk-abc...***}) or a scheme-prefixed value
     * ({@code Bearer sk-abc...***}). Null / blank / very-short values are returned safely (never partially exposed).
     */
    public static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String scheme = "";
        String secret = value;
        int sp = value.indexOf(' ');
        if (sp > 0) {
            // Preserve a leading auth scheme ("Bearer ", "Basic ", …) untouched; mask only the credential.
            scheme = value.substring(0, sp + 1);
            secret = value.substring(sp + 1);
        }
        if (secret.length() <= 4) {
            // Too short to reveal any last-4 without exposing (most of) the whole secret.
            return scheme + "***";
        }
        int prefixLen = Math.min(6, secret.length() - 4);
        String prefix = secret.substring(0, prefixLen);
        String last4 = secret.substring(secret.length() - 4);
        return scheme + prefix + "***" + last4;
    }

    /** Mask any API-key-looking token inside a request body (defence-in-depth; keys normally live in headers). */
    static String scrubBody(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        Matcher m = SECRET_PATTERN.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(maskSecret(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * DEBUG-only: log the full outbound request — a correlation header, the URL and the complete body (truncated only
     * past {@link #MAX_BODY_CHARS} as a runaway-size guard). Headers are deliberately NOT logged, so credentials never
     * reach the log; the body is still scrubbed via {@link #scrubBody(String)} as a safety net.
     */
    public static void logRequest(Logger log, String requestId, String providerName, String url, String bodyJson) {
        if (log == null || !log.isDebugEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Provider Request [").append(requestId).append("] ").append(providerName).append(" ===");
        sb.append("\nURL: ").append(url);
        sb.append("\nBody: ").append(truncate(scrubBody(bodyJson), MAX_BODY_CHARS));
        log.debug("{}", sb);
    }

    /**
     * DEBUG-only: log the aggregated response for one streaming call — status, wall-clock duration, the full
     * assistant text, thinking text (smaller cap), a tool-call summary (name / id + short args preview, never the full
     * arguments) and the stop reason / error code.
     */
    public static void logResponse(Logger log, String requestId, String providerName, String assistantText,
        String thinkingText, List<?> toolCallsOrNull, String stopReasonOrErrorCode, boolean interrupted,
        long durationMs) {
        if (log == null || !log.isDebugEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Provider Response [").append(requestId).append("] ").append(providerName).append(" ===");
        sb.append("\nStatus: ").append(interrupted ? "interrupted" : "success");
        sb.append("\nDurationMs: ").append(durationMs);
        sb.append("\nText: ").append(truncate(assistantText, MAX_BODY_CHARS));
        if (thinkingText != null && !thinkingText.isEmpty()) {
            sb.append("\nThinking: ").append(truncate(thinkingText, MAX_THINKING_CHARS));
        }
        if (toolCallsOrNull != null && !toolCallsOrNull.isEmpty()) {
            sb.append("\nToolCalls (").append(toolCallsOrNull.size()).append("):");
            for (Object tc : toolCallsOrNull) {
                appendToolCall(sb, tc);
            }
        }
        sb.append(interrupted ? "\nErrorCode: " : "\nStopReason: ")
            .append(stopReasonOrErrorCode == null ? "" : stopReasonOrErrorCode);
        log.debug("{}", sb);
    }

    /** Render one tool call as name / id + a short arguments preview (never the full args, to avoid leak / bloat). */
    private static void appendToolCall(StringBuilder sb, Object tc) {
        if (tc instanceof ToolCall call) {
            sb.append("\n  - name=").append(call.getName()).append(", id=").append(call.getId());
            String args = call.getArguments();
            if (args != null && !args.isEmpty()) {
                sb.append(", args=").append(truncate(args, MAX_TOOL_ARGS_PREVIEW));
            }
        } else {
            sb.append("\n  - ").append(tc);
        }
    }

    /** Truncate a possibly-huge string, annotating how many chars were dropped. Null-safe (returns empty). */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...(truncated " + (s.length() - max) + " chars)";
    }
}
