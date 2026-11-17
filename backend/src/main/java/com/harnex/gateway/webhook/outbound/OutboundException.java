package com.harnex.gateway.webhook.outbound;

/**
 * Thrown by {@link ChannelOutboundClient#push} on any non-2xx response or I/O failure. Checked-style by extending
 * {@link Exception} so callers (the dispatcher reply-callback) are forced to handle the "platform push failed" case
 * explicitly rather than silently dropping the agent's final answer.
 */
public class OutboundException extends Exception {
    public OutboundException(String message) {
        super(message);
    }

    public OutboundException(String message, Throwable cause) {
        super(message, cause);
    }
}
