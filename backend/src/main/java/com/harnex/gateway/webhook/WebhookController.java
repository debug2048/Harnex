package com.harnex.gateway.webhook;

import com.harnex.config.ProjectRuntime;
import com.harnex.gateway.GatewayDispatcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Provider-neutral inbound webhook. Single endpoint accepts JSON payloads from any channel — the external bridge is
 * expected to normalise the event into the shape:
 *
 * <pre>
 *   { "user": "u123", "channel": "feishu", "text": "hello", "threadKey": "optional" }
 * </pre>
 *
 * <p>
 * Required headers:
 * <ul>
 * <li>{@code X-Harnex-Timestamp} — ms since epoch</li>
 * <li>{@code X-Harnex-Nonce} — unique-per-request opaque string</li>
 * <li>{@code X-Harnex-Signature} — lowercase hex of
 * {@code HmacSHA256(secret, timestamp + "." + nonce + "." + raw_body)}</li>
 * </ul>
 *
 * <p>
 * <b>Why ts/nonce are inside the HMAC scope:</b> if the signature only covered {@code body}, an attacker who captured
 * one signed (body, sig) tuple could forever replay it by attaching a fresh timestamp + a never-before-seen nonce
 * header — our nonce-dedup cache would happily accept each request because the nonce really IS new. Binding ts+nonce
 * into the signature makes them part of the authenticated payload, so the dedup cache becomes meaningful.
 *
 * <p>
 * Empty {@code harnex.gateway.webhook-secret} = fail-closed (returns 401).
 *
 * <p>
 * For first-class signature handling per IM platform see {@link DingTalkWebhookController} / {@link FeishuWebhookController} /
 * {@link WeComWebhookController}. This generic endpoint stays for custom bridges (Slack / Telegram / proprietary protocols) —
 * adding new ones should be zero-backend-deploy.
 */
@Slf4j
@RestController
@ProjectRuntime
@RequestMapping("/api/gateway")
public class WebhookController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentConfig cfg;
    private final GatewayDispatcher dispatcher;

    public WebhookController(AgentConfig cfg, GatewayDispatcher dispatcher) {
        this.cfg = cfg;
        this.dispatcher = dispatcher;
    }

    /** Replay-protection: nonce → expiry epoch ms. Bounded; ageing entries are evicted on each request. */
    private final ConcurrentHashMap<String, Long> seenNonces = new ConcurrentHashMap<>();
    /** CAS guard so only one thread at a time runs the O(n) expiry sweep. */
    private final AtomicBoolean nonceSweepInProgress = new AtomicBoolean(false);
    /** Reject requests whose timestamp differs from server clock by more than this. */
    private static final long TIMESTAMP_SKEW_MS = 5L * 60_000L;
    /** Maximum nonces we'll remember; LRU-ish drop happens via expiry sweep below. */
    private static final int NONCE_CACHE_MAX = 10_000;

    @PostMapping(path = "/inbound", consumes = "application/json")
    public ResponseEntity<?> inbound(@RequestBody String rawBody,
        @RequestHeader(value = "X-Harnex-Signature", required = false) String sig,
        @RequestHeader(value = "X-Harnex-Timestamp", required = false) String tsHeader,
        @RequestHeader(value = "X-Harnex-Nonce", required = false) String nonce, HttpServletRequest req) {
        // Step 1: ts + nonce must be present BEFORE signature check, because they are part of the
        // signed payload (binding them prevents the "swap headers, replay body" attack).
        if (tsHeader == null || nonce == null || nonce.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "missing replay headers"));
        }
        long ts;
        try {
            ts = Long.parseLong(tsHeader);
        } catch (NumberFormatException nfe) {
            return ResponseEntity.status(401).body(Map.of("error", "bad timestamp"));
        }
        // Step 2: signature check (HMAC over ts.nonce.body — NOT body alone).
        if (!verify(tsHeader, nonce, rawBody, sig)) {
            log.warn("rejected webhook: bad signature from {}", req.getRemoteAddr());
            return ResponseEntity.status(401).body(Map.of("error", "bad signature"));
        }
        // Step 3: timestamp window check.
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > TIMESTAMP_SKEW_MS) {
            return ResponseEntity.status(401).body(Map.of("error", "stale or future timestamp"));
        }
        // Step 4: nonce dedup. Now that ts+nonce are inside the HMAC, an attacker can't forge a
        // "fresh" nonce against a captured body — every (ts,nonce,body) triple is a single
        // signature unit that becomes useless after first acceptance.
        // Lazy expiry sweep guarded by a CAS so only ONE thread runs the O(n) walk at a time
        // even under concurrent load (previously two threads could both see size>MAX and both
        // re-walk the full map). Replay safety doesn't depend on this — putIfAbsent below is
        // the actual dedup primitive — this only keeps the cache from briefly ballooning.
        if (seenNonces.size() > NONCE_CACHE_MAX && nonceSweepInProgress.compareAndSet(false, true)) {
            try {
                seenNonces.entrySet().removeIf(e -> e.getValue() < now);
                // If the sweep didn't reclaim enough (every entry was still in-window), drop
                // the entries closest to expiry until we're under a 90% high-water mark. A
                // pathological flood of distinct nonces inside the 5-min window could
                // otherwise grow the map without bound. The hard fail-closed below is the
                // last-line defence if even forced eviction can't keep up.
                if (seenNonces.size() > NONCE_CACHE_MAX) {
                    int target = NONCE_CACHE_MAX * 9 / 10;
                    seenNonces.entrySet().stream().sorted(Map.Entry.comparingByValue())
                        .limit(Math.max(0, seenNonces.size() - target)).map(Map.Entry::getKey).toList()
                        .forEach(seenNonces::remove);
                }
            } finally {
                nonceSweepInProgress.set(false);
            }
        }
        // Hard cap: refuse new admissions when even forced eviction has not made room. This
        // keeps heap bounded under sustained signed-flood attacks (or a misconfigured client
        // that never reuses a nonce). 503 with Retry-After lets a legitimate caller back off.
        if (seenNonces.size() >= NONCE_CACHE_MAX) {
            log.warn("rejecting webhook from {}: nonce cache full (anti-flood)", req.getRemoteAddr());
            return ResponseEntity.status(503).header("Retry-After", "5").body(Map.of("error", "service busy"));
        }
        Long prev = seenNonces.putIfAbsent(nonce, ts + TIMESTAMP_SKEW_MS);
        if (prev != null) {
            log.warn("rejected webhook: replayed nonce {} from {}", nonce, req.getRemoteAddr());
            return ResponseEntity.status(401).body(Map.of("error", "nonce replay"));
        }
        try {
            Map<?, ?> body = JSON.readValue(rawBody, Map.class);
            String user = str(body, "user", "anon");
            String channel = str(body, "channel", "unknown");
            String text = str(body, "text", "");
            String threadKey = str(body, "threadKey", null);
            // Optional client-supplied messageId for stronger idempotency than the short-TTL
            // text-hash fallback. A bridge that knows its own per-message id should pass it.
            String messageId = str(body, "messageId", null);
            if (text.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "empty text"));
            }

            var result =
                dispatcher.dispatch(channel, user, threadKey, messageId, text, cfg.getGateway().getReplyMaxChars());
            // Allowlist denial / blank-text fast-path returns threadId=null. Map.of() rejects
            // null values with NPE → would be caught below and become an opaque 500. Surface
            // a clean 403 (allowlist) or 400 (blank text) instead so the bridge can see what
            // happened. Mirror the AlertmanagerWebhookController early-return pattern.
            if (result.threadId() == null) {
                int code = GatewayDispatcher.UNAUTHORIZED_SENDER_REPLY.equals(result.reply()) ? 403 : 400;
                return ResponseEntity.status(code)
                    .body(Map.of("channel", channel, "reply", result.reply() == null ? "" : result.reply()));
            }
            return ResponseEntity.ok(Map.of("threadId", result.threadId(), "channel", channel, "reply", result.reply(),
                "durationMs", result.durationMs()));
        } catch (Exception e) {
            log.error("gateway dispatch failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean verify(String ts, String nonce, String body, String sig) {
        String secret = cfg.getGateway() == null ? "" : cfg.getGateway().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("Webhook secret not configured — rejecting request (fail-closed)");
            return false;
        }
        if (sig == null || sig.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            // Canonical signing string: ts + "." + nonce + "." + body. The dot separators are
            // unambiguous because timestamp is digits-only and the nonce is opaque — there is no
            // (ts, nonce) pair that can collide with another (ts', nonce') under string concat
            // attack (length-extension is irrelevant because we feed the digest with bytes).
            mac.update(ts.getBytes(StandardCharsets.UTF_8));
            mac.update((byte)'.');
            mac.update(nonce.getBytes(StandardCharsets.UTF_8));
            mac.update((byte)'.');
            byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            // Decode the candidate hex into bytes so we can compare on the raw 32-byte digest
            // with java.security.MessageDigest.isEqual (constant-time, length-agnostic). The
            // previous char-by-char loop on hex strings leaked the candidate length via an
            // early-return on length mismatch, and "constant-time within iteration" is fragile.
            byte[] candidate;
            try {
                candidate = HexFormat.of().parseHex(sig);
            } catch (IllegalArgumentException badHex) {
                return false;
            }
            return MessageDigest.isEqual(expected, candidate);
        } catch (Exception e) {
            log.error("HMAC verify failed", e);
            return false;
        }
    }

    private static String str(Map<?, ?> m, String k, String d) {
        Object v = m.get(k);
        return v == null ? d : String.valueOf(v);
    }
}
