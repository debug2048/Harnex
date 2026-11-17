package com.harnex.gateway.webhook;

import com.harnex.config.ProjectRuntime;
import com.harnex.gateway.GatewayDispatcher;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.config.AgentConfig;
import com.harnex.gateway.webhook.outbound.ChannelOutboundClient;
import com.harnex.gateway.webhook.outbound.OutboundClientRegistry;
import com.harnex.gateway.webhook.outbound.OutboundException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * DingTalk (钉钉) outgoing-webhook adapter — <b>async push-back mode</b>.
 *
 * <p>
 * <b>Flow.</b>
 * <ol>
 * <li>DingTalk POSTs the outgoing-bot event with query params {@code timestamp} and {@code sign}.</li>
 * <li>We verify the HMAC ({@code "{ts}\n{secret}"} ⇒ base64) and the timestamp window (±5 minutes), then dedup on
 * {@code timestamp+sign+(msgId|sha1(text))}.</li>
 * <li>We submit the message to {@link GatewayDispatcher#asyncDispatch} with a callback that, on completion, calls the
 * configured DingTalk {@link ChannelOutboundClient} (custom group-robot webhook) to POST the final agent reply back to
 * the chat.</li>
 * <li>We respond <b>200 OK</b> to the inbound webhook <b>immediately</b>. DingTalk does not render anything from the
 * inbound response in async push-back mode — the final reply arrives through the outbound webhook instead.</li>
 * </ol>
 *
 * <p>
 * <b>Why this isn't sync any more.</b> The previous implementation echoed the reply in the inbound HTTP response and
 * relied on DingTalk's automatic redelivery to surface long-running agent answers. That assumed (a) the platform always
 * retries on no-ack — it doesn't, depending on bot type — and (b) the user understood to "resend the same message" to
 * fetch the result. Both proved unreliable in practice. Async push-back is what every mainstream IM bot (Slack apps,
 * ChatGPT-on-WeChat, FastGPT, LangChain adapters) uses.
 *
 * <p>
 * Configure: {@code harnex.gateway.dingtalk-secret} (inbound HMAC) AND
 * {@code harnex.gateway.dingtalk-outbound.webhook-url} (outbound push URL — required). If either is missing, this
 * endpoint returns 503.
 */
@Slf4j
@RestController
@ProjectRuntime
@RequestMapping("/api/gateway/dingtalk")
public class DingTalkWebhookController {

    private final AgentConfig cfg;
    private final GatewayDispatcher dispatcher;
    private final OutboundClientRegistry outboundRegistry;
    /**
     * Even in async push-back mode the platform may retry on a TCP-level drop; dedup the
     * {@code timestamp+sign+(msgId|hash)} tuple to prevent duplicate retries from launching parallel agent runs. The
     * dispatcher's per-user permit is the second line of defence.
     */
    private final NonceCache replayCache = new NonceCache();

    public DingTalkWebhookController(AgentConfig cfg, GatewayDispatcher dispatcher, OutboundClientRegistry outboundRegistry) {
        this.cfg = cfg;
        this.dispatcher = dispatcher;
        this.outboundRegistry = outboundRegistry;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> inbound(@RequestParam(value = "timestamp", required = false) String timestamp,
        @RequestParam(value = "sign", required = false) String sign, @RequestBody Map<String, Object> body,
        HttpServletRequest req) {
        // Native HTTP robot callbacks carry these in headers; bridges may use query parameters.
        if (timestamp == null) timestamp = req.getHeader("timestamp");
        if (sign == null) sign = req.getHeader("sign");
        String secret = cfg.getGateway().getDingtalkSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("DingTalk inbound rejected — harnex.gateway.dingtalk-secret not configured");
            return ResponseEntity.status(503).body(Map.of("errcode", 1, "errmsg", "dingtalk gateway disabled"));
        }
        // Outbound push is mandatory for the async flow — refuse early so a misconfiguration
        // is visible immediately, rather than after every agent run silently fails to deliver.
        ChannelOutboundClient out = outboundRegistry.forChannel("dingtalk").orElse(null);
        if (out == null || !out.isConfigured()) {
            log.warn("DingTalk inbound rejected — dingtalk-outbound.webhook-url not configured");
            return ResponseEntity.status(503)
                .body(Map.of("errcode", 1, "errmsg", "dingtalk-outbound webhook URL not configured"));
        }
        if (timestamp == null || sign == null || !verify(timestamp, sign, secret)) {
            log.warn("DingTalk bad signature from {} ts={} sign={}", req.getRemoteAddr(), timestamp, sign);
            return ResponseEntity.status(401).body(Map.of("errcode", 401, "errmsg", "bad signature"));
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() - ts) > 5L * 60_000L) {
                return ResponseEntity.status(401).body(Map.of("errcode", 401, "errmsg", "timestamp expired"));
            }
        } catch (NumberFormatException ignored) {
            return ResponseEntity.status(400).body(Map.of("errcode", 400, "errmsg", "bad timestamp"));
        }
        // Extract message fields per https://open.dingtalk.com/document/orgapp/receive-message
        String text = "";
        Object textObj = body.get("text");
        if (textObj instanceof Map<?, ?> tm && tm.get("content") != null) {
            text = String.valueOf(tm.get("content")).trim();
        }
        String user = strOr(body.get("senderStaffId"), strOr(body.get("senderId"), "anon"));
        String threadKey = strOr(body.get("conversationId"), null);
        String messageId = strOr(body.get("msgId"), null);

        // Replay dedup. DingTalk's `sign` is body-independent (HMAC of timestamp+secret), so two
        // distinct messages in the same millisecond would otherwise share the dedup key.
        // Discriminate by msgId (preferred — stable per delivery) or text hash (fallback).
        String discriminator = (messageId != null && !messageId.isBlank()) ? "m:" + messageId : "h:" + sha1Hex(text);
        String replayKey = timestamp + ":" + sign + ":" + discriminator;
        if (!replayCache.acceptOnce(replayKey, ts)) {
            log.info("DingTalk duplicate delivery suppressed from {} (msgId={})", req.getRemoteAddr(), messageId);
            // 200 (not 401) so the platform doesn't treat this as a delivery failure; we've
            // already accepted the original and the user will get one reply via outbound push.
            return ResponseEntity.ok(Map.of("errcode", 0, "errmsg", "duplicate ignored"));
        }
        // Async dispatch — the callback pushes the final reply via the outbound webhook.
        // The dispatcher caps the reply per `replyMaxChars` before our callback sees it.
        int replyCap = cfg.getGateway().getReplyMaxChars();
        final String pushChatId = threadKey;
        dispatcher.asyncDispatch("dingtalk", user, threadKey, messageId, text, replyCap, (tid, reply, durationMs) -> {
            try {
                out.push(pushChatId, (reply == null || reply.isEmpty()) ? "(no reply)" : reply);
            } catch (OutboundException oe) {
                log.error("DingTalk outbound push failed (thread={} duration={}ms): {}", tid, durationMs,
                    oe.getMessage());
            }
        });
        // Immediate ACK. Empty-ish body — DingTalk's outgoing-bot is happy with any 2xx; the
        // actual reply is delivered separately via the outbound webhook.
        return ResponseEntity.ok(Map.of("errcode", 0, "errmsg", "accepted"));
    }

    private boolean verify(String timestamp, String receivedSign, String secret) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String expectedRaw = Base64.getEncoder().encodeToString(sig);
            String expectedEncoded = URLEncoder.encode(expectedRaw, StandardCharsets.UTF_8);
            // HTTP-receive mode sends the signature WITHOUT URL-encoding; outgoing-bot bridges
            // typically URL-encode it. Accept either to keep both deployment shapes working.
            return constantTimeEquals(expectedRaw, receivedSign) || constantTimeEquals(expectedEncoded, receivedSign);
        } catch (Exception e) {
            log.warn("dingtalk sig compute failed", e);
            return false;
        }
    }

    /** Length-leak-free string compare; constant time once lengths match. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** SHA-1 hex of {@code s}; body-aware discriminator for the replay key when no msgId. */
    private static String sha1Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return "err:" + System.nanoTime();
        }
    }

    private static String strOr(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }
}
