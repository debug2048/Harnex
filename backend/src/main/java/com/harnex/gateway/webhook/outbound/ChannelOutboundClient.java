package com.harnex.gateway.webhook.outbound;

/**
 * Outbound push client for an IM channel. Each implementation knows how to deliver a single text message to its
 * platform's incoming-webhook / group-robot endpoint.
 *
 * <p>
 * <b>Why this exists.</b> The IM gateway moved from "reply inline in the webhook response" (sync, depends on the
 * platform's flaky automatic redelivery to fetch the final answer) to "ack the webhook immediately, push the answer
 * out-of-band" — the pattern used by every mainstream IM bot (Slack apps, DingTalk/Feishu/WeCom bots, LangChain
 * adapters, ChatGPT-on-WeChat, FastGPT). The outbound push is what closes that loop.
 *
 * <p>
 * <b>Single-URL scope.</b> The first release supports one webhook URL per channel (custom-robot / group-robot mode),
 * which matches the 99% IM-bot deployment: one Harnex instance answers in exactly one group. Multi-group fan-out
 * requires either separate deployments or a future {@code Map<chatId, webhookUrl>} routing layer; this interface
 * deliberately accepts a {@code chatId} parameter so that extension is non-breaking.
 */
public interface ChannelOutboundClient {

    /** Channel name this client serves — lowercase ("dingtalk" / "feishu" / "wecom"). */
    String channel();

    /**
     * True iff the operator has configured an outbound webhook URL for this channel. When false, the gateway will
     * refuse inbound messages with a 503 so an operator can spot the misconfiguration immediately (instead of silently
     * accepting requests whose answers can never be delivered).
     */
    boolean isConfigured();

    /**
     * Push {@code text} to the platform.
     *
     * @param chatId platform chat / group identifier from the inbound event. Ignored by single-URL implementations (the
     *            URL itself encodes the group); reserved for future per-chat routing.
     * @param text message body (already truncated to the per-channel reply cap).
     * @throws OutboundException on any non-2xx response or I/O failure — the caller logs and may retry depending on
     *             policy; we deliberately do NOT swallow failures because a missing reply is worse than a noisy log.
     */
    void push(String chatId, String text) throws OutboundException;
}
