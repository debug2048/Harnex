package com.harnex.gateway.webhook;

import com.harnex.config.ProjectRuntime;
import com.harnex.gateway.GatewayDispatcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.gateway.webhook.outbound.ChannelOutboundClient;
import com.harnex.gateway.webhook.outbound.OutboundClientRegistry;
import com.harnex.gateway.webhook.outbound.OutboundException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * WeCom (企业微信) "智能机器人 / 自建应用回调" adapter — <b>async push-back mode</b>.
 *
 * <p>
 * <b>Flow.</b>
 * <ol>
 * <li>WeCom POSTs the callback with query params {@code msg_signature, timestamp, nonce} and a plaintext JSON body
 * (AES-XML decoding handled by a thin upstream bridge).</li>
 * <li>We verify the SHA-1 signature, the timestamp window (±5 min), and dedup on the inbound nonce.</li>
 * <li>We submit the message to {@link GatewayDispatcher#asyncDispatch} with a callback that pushes the final reply via
 * the configured WeCom group-robot {@link ChannelOutboundClient}.</li>
 * <li>We respond <b>200 OK</b> to the inbound webhook immediately.</li>
 * </ol>
 *
 * <p>
 * The {@code GET} verification handshake is unchanged — same {@code sha1(token+ts+nonce+echostr)} check, plaintext echo
 * back.
 *
 * <p>
 * Configure: {@code harnex.gateway.wecom-token} (inbound HMAC token) AND
 * {@code harnex.gateway.wecom-outbound.webhook-url} (group-robot push URL — required). If either is missing this
 * endpoint returns 503.
 *
 * <p>
 * For full AES-CBC + XML envelope decoding, run a small Node/Python bridge in front and have it POST the decoded
 * plaintext here (keeps the JVM image lean).
 */
@Slf4j
@RestController
@ProjectRuntime
@RequestMapping("/api/gateway/wecom")
public class WeComWebhookController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentConfig cfg;
    private final GatewayDispatcher dispatcher;
    private final OutboundClientRegistry outboundRegistry;
    /** Nonce dedup: refuse the same nonce within the 5-min timestamp window. */
    private final NonceCache nonceCache = new NonceCache();

    public WeComWebhookController(AgentConfig cfg, GatewayDispatcher dispatcher, OutboundClientRegistry outboundRegistry) {
        this.cfg = cfg;
        this.dispatcher = dispatcher;
        this.outboundRegistry = outboundRegistry;
    }

    @GetMapping
    public ResponseEntity<?> verify(@RequestParam("msg_signature") String sig, @RequestParam("timestamp") String ts,
        @RequestParam("nonce") String nonce, @RequestParam("echostr") String echo, HttpServletRequest req) {
        String token = cfg.getGateway().getWecomToken();
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(503).body("wecom gateway disabled");
        }
        if (!signatureMatches(sig, token, ts, nonce, echo)) {
            log.warn("WeCom verify bad sig from {}", req.getRemoteAddr());
            return ResponseEntity.status(401).body("bad signature");
        }
        // Plaintext mode — bridges have already decrypted the echostr.
        return ResponseEntity.ok(echo);
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> inbound(@RequestParam("msg_signature") String sig, @RequestParam("timestamp") String ts,
        @RequestParam("nonce") String nonce,
        @RequestBody(required = false) byte[] rawBytes,
        HttpServletRequest req) {
        String token = cfg.getGateway().getWecomToken();
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("errcode", 1, "errmsg", "wecom gateway disabled"));
        }
        ChannelOutboundClient out = outboundRegistry.forChannel("wecom").orElse(null);
        if (out == null || !out.isConfigured()) {
            log.warn("WeCom inbound rejected — wecom-outbound.webhook-url not configured");
            return ResponseEntity.status(503)
                .body(Map.of("errcode", 1, "errmsg", "wecom-outbound webhook URL not configured"));
        }
        // Sign over the raw HTTP body bytes the client actually sent (no JSON-field override —
        // closes the earlier "_raw can tamper signature surface" gap).
        String payloadForSig = rawBytes == null ? "" : new String(rawBytes, StandardCharsets.UTF_8);
        if (!signatureMatches(sig, token, ts, nonce, payloadForSig)) {
            log.warn("WeCom inbound bad sig from {}", req.getRemoteAddr());
            return ResponseEntity.status(401).body(Map.of("errcode", 401, "errmsg", "bad signature"));
        }
        long tsMs;
        try {
            tsMs = Long.parseLong(ts) * 1000L;
            if (Math.abs(System.currentTimeMillis() - tsMs) > 5L * 60_000L) {
                log.warn("WeCom timestamp expired (drift {} ms)", System.currentTimeMillis() - tsMs);
                return ResponseEntity.status(401).body(Map.of("errcode", 401, "errmsg", "timestamp expired"));
            }
        } catch (NumberFormatException ignored) {
            return ResponseEntity.status(400).body(Map.of("errcode", 400, "errmsg", "bad timestamp"));
        }
        if (!nonceCache.acceptOnce(nonce, tsMs)) {
            log.info("WeCom duplicate delivery suppressed from {} (nonce={})", req.getRemoteAddr(), nonce);
            // 200 OK so the platform doesn't treat this as a delivery failure.
            return ResponseEntity.ok(Map.of("errcode", 0, "errmsg", "duplicate ignored"));
        }
        // Parse body once.
        Map<String, Object> body;
        try {
            body = rawBytes == null || rawBytes.length == 0 ? Map.of() : JSON.readValue(rawBytes, Map.class);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("errcode", 400, "errmsg", "bad json: " + e.getMessage()));
        }
        String text = strOr(body.get("text"), "");
        String user = strOr(body.get("user"), "anon");
        String threadKey = strOr(body.get("conversationId"), strOr(body.get("chat_id"), null));
        String messageId = strOr(body.get("MsgId"), strOr(body.get("msgid"), strOr(body.get("msgId"), null)));

        int replyCap = cfg.getGateway().getReplyMaxChars();
        final String pushChatId = threadKey;
        dispatcher.asyncDispatch("wecom", user, threadKey, messageId, text, replyCap, (tid, reply, durationMs) -> {
            try {
                out.push(pushChatId, (reply == null || reply.isEmpty()) ? "(no reply)" : reply);
            } catch (OutboundException oe) {
                log.error("WeCom outbound push failed (thread={} duration={}ms): {}", tid, durationMs, oe.getMessage());
            }
        });
        return ResponseEntity.ok(Map.of("errcode", 0, "errmsg", "accepted"));
    }

    /** WeCom signature: sha1 of the lexicographically sorted concatenation of (token, ts, nonce, data). */
    private static boolean signatureMatches(String sig, String token, String ts, String nonce, String data) {
        try {
            String[] arr = {token, ts, nonce, data};
            Arrays.sort(arr);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(String.join("", arr).getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(md.digest());
            if (expected.length() != sig.length()) {
                return false;
            }
            int diff = 0;
            for (int i = 0; i < expected.length(); i++) {
                diff |= expected.charAt(i) ^ sig.charAt(i);
            }
            return diff == 0;
        } catch (Exception e) {
            log.warn("wecom sig compute failed", e);
            return false;
        }
    }

    private static String strOr(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }
}
