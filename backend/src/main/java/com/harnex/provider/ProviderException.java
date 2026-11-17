package com.harnex.provider;

import java.io.EOFException;
import java.io.Serial;

import com.harnex.net.HttpExchangeState;

/**
 * Typed failure raised by every {@link ModelProvider} implementation.
 *
 * <p>
 * The {@link #code} is the routing-relevant classification the {@link ProviderRouter} switches on for failover (e.g.
 * {@code "429"}, {@code "500"}, {@code "timeout"}, {@code "network"}, {@code "stream_ttft_timeout"}, {@code "parse"},
 * {@code "cancelled"}, {@code "agent_oversize"}). It is a plain string rather than an enum so providers can surface
 * upstream-specific HTTP status codes verbatim without an exhaustive enum to maintain.
 *
 * <p>
 * Promoted to a top-level type (previously nested in {@code OpenAiCompatibleProvider}) so the three wire adapters
 * ({@link OpenAiCompatibleProvider}, {@code OpenAiResponsesProvider}, {@link AnthropicProvider}) and the router share
 * one exception class — a {@code catch (ProviderException)} in {@link ProviderRouter} must catch failures from ALL
 * providers, which a per-provider nested subclass could not guarantee.
 */
public class ProviderException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = -6541659251099839463L;
    public final String code;
    public final HttpExchangeState.Acceptance acceptance;
    public final String upstreamRequestId;
    public final long retryAfterMillis;
    /** Authoritative usage may be known even when the generated answer is unusable. */
    public final ProviderTokenUsage usage;

    public ProviderException(String code, String msg) {
        this(code, msg, code != null && code.matches("[45][0-9]{2}")
            ? HttpExchangeState.Acceptance.REJECTED
            : "config".equals(code) || "circuit_open".equals(code) ? HttpExchangeState.Acceptance.NOT_SENT
            : HttpExchangeState.Acceptance.UNKNOWN, null, 0);
    }

    public ProviderException(String code, String message, HttpExchangeState.Acceptance acceptance,
        String upstreamRequestId, long retryAfterMillis) {
        this(code, message, acceptance, upstreamRequestId, retryAfterMillis, null);
    }

    private ProviderException(String code, String message, HttpExchangeState.Acceptance acceptance,
        String upstreamRequestId, long retryAfterMillis, ProviderTokenUsage usage) {
        super(message == null || message.isBlank()
            ? "Provider request failed" + (code == null || code.isBlank() ? "" : " (" + code + ")") : message);
        this.code = code;
        this.acceptance = acceptance == null ? HttpExchangeState.Acceptance.UNKNOWN : acceptance;
        this.upstreamRequestId = upstreamRequestId;
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
        this.usage = usage;
    }

    /** Some transport failures (notably EOFException) have no message. Keep their identity and original cause. */
    public static ProviderException fromCause(String code, Throwable cause) {
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause instanceof EOFException
                ? "Upstream connection closed before the response completed (EOFException)"
                : "Provider request failed (" + cause.getClass().getSimpleName() + ")";
        }
        ProviderException failure = new ProviderException(code, message);
        failure.initCause(cause);
        return failure;
    }

    public static ProviderException generationLimit(Integer requestedLimit, ProviderTokenUsage usage) {
        String message = "Generation reached a token limit (finish_reason=length); the response is incomplete";
        message += requestedLimit == null ? "; output limit: server default" : "; requested output limit: " + requestedLimit;
        if (usage != null) {
            message += "; generated tokens: " + usage.completion();
            if (usage.reasoning() > 0) message += "; reasoning tokens: " + usage.reasoning();
        }
        return new ProviderException("incomplete", message).withUsage(usage);
    }

    public ProviderException withUsage(ProviderTokenUsage reported) {
        if (reported == null || reported.equals(usage)) return this;
        ProviderException failure = new ProviderException(code, getMessage(), acceptance,
            upstreamRequestId, retryAfterMillis, reported);
        failure.initCause(this);
        return failure;
    }

    public boolean safeToRetry() {
        return acceptance == HttpExchangeState.Acceptance.NOT_SENT
            || acceptance == HttpExchangeState.Acceptance.REJECTED;
    }

    public ProviderException withExchange(HttpExchangeState exchange) {
        if (exchange == null) return this;
        ProviderException failure = new ProviderException(code, getMessage(), exchange.acceptance(),
            exchange.requestId(), exchange.retryAfterMillis(), usage);
        failure.initCause(this);
        return failure;
    }
}
