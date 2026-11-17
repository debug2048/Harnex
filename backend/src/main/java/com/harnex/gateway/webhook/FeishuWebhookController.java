package com.harnex.gateway.webhook;

import com.harnex.config.ProjectRuntime;
import com.harnex.gateway.GatewayDispatcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.gateway.webhook.outbound.ChannelOutboundClient;
import com.harnex.gateway.webhook.outbound.OutboundClientRegistry;
import com.harnex.gateway.webhook.outbound.OutboundException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Feishu / Lark event-subscription adapter — <b>async push-back mode</b>.
 *
 * <p>
 * <b>Two phases:</b>
 * <ol>
 * <li><b>url_verification</b> — first call when registering the URL on the Feishu 开发者后台. We echo the challenge back
 * verbatim within 1 second; no signature required.</li>
 * <li><b>event_callback</b> — every subsequent IM message:
 * 
 * <pre>
 * {"header":{...},"event":{"message":{"content":"{\"text\":\"...\"}", ...}}}
 * </pre>
 * 
 * Mandatory header {@code X-Lark-Signature} = plain SHA-256 (NOT HMAC) of
 * {@code timestamp + nonce + encrypt_key + raw_body}.</li>
 * </ol>
 *
 * <p>
 * <b>Flow.</b> Inbound webhook: verify signature → timestamp window → nonce dedup → submit to
 * {@link GatewayDispatcher#asyncDispatch} → 200 OK. Final agent reply is pushed via the configured Feishu custom
 * group-robot {@link ChannelOutboundClient} (out-of-band, after the webhook has already ACK'd).
 *
 * <p>
 * Configure: {@code harnex.gateway.feishu-secret} (the Encrypt Key, used for inbound signature verification — NOT the
 * verification token) AND {@code harnex.gateway.feishu-outbound.webhook-url} (group-robot push URL — required). If
 * either is missing, real {@code event_callback} POSTs are rejected with 503.
 *
 * <p>
 * <b>Encryption mode caveat:</b> if the Feishu console has "event encryption" turned on, the incoming body will be
 * {@code {"encrypt":"<base64>"}} and must be AES-CBC decrypted upstream — delegate that step to a thin bridge.
 */
@Slf4j
@RestController
@ProjectRuntime
@RequestMapping("/api/gateway/feishu")
public class FeishuWebhookController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentConfig cfg;
    private final GatewayDispatcher dispatcher;
    private final OutboundClientRegistry outboundRegistry;
    /** Nonce dedup: refuse the same nonce within the timestamp validity window. */
    private final NonceCache nonceCache = new NonceCache();

    public FeishuWebhookController(AgentConfig cfg, GatewayDispatcher dispatcher, OutboundClientRegistry outboundRegistry) {
        this.cfg = cfg;
        this.dispatcher = dispatcher;
        this.outboundRegistry = outboundRegistry;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> inbound(@RequestBody String rawBody,
        @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
        @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String ts,
        @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce, HttpServletRequest req) {
        JsonNode body;
        try {
            body = JSON.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "bad json"));
        }

        // Phase 1: URL verification handshake (no signature needed; outbound config not yet
        // required so an operator can register the URL before configuring the bot).
        if (body.has("type") && "url_verification".equals(body.get("type").asText())) {
            String configured = cfg.getGateway().getFeishuVerificationToken();
            String got = body.path("token").asText("");
            if (configured != null && !configured.isBlank() && !configured.equals(got)) {
                log.warn("Feishu url_verification token mismatch from {}", req.getRemoteAddr());
                return ResponseEntity.status(401).body(Map.of("error", "bad token"));
            }
            if (ts != null && !ts.isBlank()) {
                try {
                    long tsMs = Long.parseLong(ts) * 1000L;
                    if (Math.abs(System.currentTimeMillis() - tsMs) > 5L * 60_000L) {
                        log.warn("Feishu url_verification timestamp expired (drift {} ms)",
                            System.currentTimeMillis() - tsMs);
                        return ResponseEntity.status(401).body(Map.of("error", "timestamp expired"));
                    }
                } catch (NumberFormatException ignored) {
                    // verification token check above is the primary defence
                }
            }
            return ResponseEntity.ok(Map.of("challenge", body.path("challenge").asText("")));
        }

        // Phase 2: real event callback. Both inbound secret AND outbound URL are required —
        // without outbound, the reply has nowhere to go.
        String secret = cfg.getGateway().getFeishuSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("Feishu inbound rejected — harnex.gateway.feishu-secret not configured");
            return ResponseEntity.status(503).body(Map.of("error", "feishu gateway disabled"));
        }
        ChannelOutboundClient out = outboundRegistry.forChannel("feishu").orElse(null);
        if (out == null || !out.isConfigured()) {
            log.warn("Feishu inbound rejected — feishu-outbound.webhook-url not configured");
            return ResponseEntity.status(503).body(Map.of("error", "feishu-outbound webhook URL not configured"));
        }
        if (signature == null || ts == null || nonce == null || !verify(ts, nonce, secret, rawBody, signature)) {
            log.warn("Feishu bad signature from {}", req.getRemoteAddr());
            return ResponseEntity.status(401).body(Map.of("error", "bad signature"));
        }
        long tsMs;
        try {
            tsMs = Long.parseLong(ts) * 1000L;
            if (Math.abs(System.currentTimeMillis() - tsMs) > 5L * 60_000L) {
                log.warn("Feishu timestamp too skewed: {} ms drift", System.currentTimeMillis() - tsMs);
                return ResponseEntity.status(401).body(Map.of("error", "timestamp expired"));
            }
        } catch (NumberFormatException ignored) {
            return ResponseEntity.status(400).body(Map.of("error", "bad timestamp"));
        }
        if (!nonceCache.acceptOnce(nonce, tsMs)) {
            log.info("Feishu duplicate delivery suppressed from {} (nonce={})", req.getRemoteAddr(), nonce);
            return ResponseEntity.ok(Map.of("ok", true, "duplicate", true));
        }

        // Extract text / sender / chat from event_callback body.
        JsonNode event = body.path("event");
        JsonNode msg = event.path("message");
        String contentRaw = msg.path("content").asText("");
        String text = "";
        if (!contentRaw.isEmpty()) {
            try {
                JsonNode c = JSON.readTree(contentRaw);
                text = c.path("text").asText(c.path("content").asText("")).trim();
            } catch (Exception ignored) {
                text = contentRaw.trim();
            }
        }
        String user = event.path("sender").path("sender_id").path("open_id").asText("anon");
        String threadKey = msg.path("chat_id").asText(null);
        // Prefer header.event_id (truly unique per delivery) over message_id for the idemKey.
        String messageId = body.path("header").path("event_id").asText(null);
        if (messageId == null || messageId.isBlank()) {
            messageId = msg.path("message_id").asText(null);
        }

        int replyCap = cfg.getGateway().getReplyMaxChars();
        final String pushChatId = threadKey;
        dispatcher.asyncDispatch("feishu", user, threadKey, messageId, text, replyCap, (tid, reply, durationMs) -> {
            try {
                out.push(pushChatId, (reply == null || reply.isEmpty()) ? "(no reply)" : reply);
            } catch (OutboundException oe) {
                log.error("Feishu outbound push failed (thread={} duration={}ms): {}", tid, durationMs,
                    oe.getMessage());
            }
        });
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Feishu signature: plain SHA-256 (NOT HMAC) of {@code ts + nonce + secret + body}. */
    private boolean verify(String ts, String nonce, String secret, String body, String sigHex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(ts.getBytes(StandardCharsets.UTF_8));
            md.update(nonce.getBytes(StandardCharsets.UTF_8));
            md.update(secret.getBytes(StandardCharsets.UTF_8));
            md.update(body.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(md.digest());
            if (expected.length() != sigHex.length()) {
                return false;
            }
            int diff = 0;
            for (int i = 0; i < expected.length(); i++) {
                diff |= expected.charAt(i) ^ sigHex.charAt(i);
            }
            return diff == 0;
        } catch (Exception e) {
            log.warn("feishu sig compute failed", e);
            return false;
        }
    }
}
