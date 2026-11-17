package com.harnex.gateway.webhook;

import com.harnex.config.ProjectRuntime;
import com.harnex.gateway.GatewayDispatcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.session.SessionStore;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Prometheus Alertmanager inbound webhook.
 *
 * <p>
 * Endpoint: {@code POST /api/gateway/alertmanager} accepts the standard
 * <a href="https://prometheus.io/docs/alerting/latest/configuration/#webhook_config">Alertmanager v4 payload</a> shape:
 *
 * <pre>
 * { "version": "4", "groupKey": "...", "status": "firing|resolved",
 *   "alerts": [ { "labels": {...}, "annotations": {...}, "startsAt": "...", ... } ], ... }
 * </pre>
 *
 * <p>
 * For each batch we mint (or re-use sticky) a thread keyed by {@code groupKey} and inject a single user message
 * containing a normalised summary. The agent then runs with the {@code inspect} toolset (A7) so an SRE chat history
 * shows the alert + the agent's triage in one place. The thread is reachable from the WebUI sidebar as
 * {@code "alert: <alertname>"}.
 *
 * <p>
 * Auth: same HMAC scheme as {@link WebhookController} ({@code X-Harnex-Signature} = lowercase hex of
 * {@code HmacSHA256(secret, ts + "." + nonce + "." + body)}). The {@code harnex.gateway.alertmanager-secret} key is
 * dedicated so an operator can rotate it independently of the generic webhook secret. Empty secret = endpoint returns
 * 401 (fail-closed).
 *
 * <p>
 * This is deliberately a thin shim — Alertmanager is the only platform B3 ships with. Other alerting tools (Datadog,
 * Grafana, OpsGenie, custom scripts) should normalise their payload to the generic {@link WebhookController} shape per
 * the documented "通用 webhook 文档化" decision.
 */
@Slf4j
@RestController
@ProjectRuntime
@RequestMapping("/api/gateway")
public class AlertmanagerWebhookController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMESTAMP_SKEW_MS = 5L * 60_000L;

    private final AgentConfig cfg;
    private final GatewayDispatcher dispatcher;
    private final SessionStore store;
    /**
     * Replay-protection cache. Without this, a captured (ts, nonce, body, sig) tuple can be replayed indefinitely
     * within the {@link #TIMESTAMP_SKEW_MS} window because the HMAC alone does not change between replays. Same defence
     * pattern as {@link WebhookController#seenNonces}; reuses the shared {@link NonceCache} primitive.
     */
    private final NonceCache nonceCache = new NonceCache();

    public AlertmanagerWebhookController(AgentConfig cfg, GatewayDispatcher dispatcher, SessionStore store) {
        this.cfg = cfg;
        this.dispatcher = dispatcher;
        this.store = store;
    }

    @PostMapping(path = "/alertmanager", consumes = "application/json")
    public ResponseEntity<?> inbound(@RequestBody String rawBody,
        @RequestHeader(value = "X-Harnex-Signature", required = false) String sig,
        @RequestHeader(value = "X-Harnex-Timestamp", required = false) String tsHeader,
        @RequestHeader(value = "X-Harnex-Nonce", required = false) String nonce, HttpServletRequest req) {
        // Replay-bound HMAC: ts+nonce inside the signature, same rationale as WebhookController.
        if (tsHeader == null || nonce == null || nonce.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "missing replay headers"));
        }
        long ts;
        try {
            ts = Long.parseLong(tsHeader);
        } catch (NumberFormatException nfe) {
            return ResponseEntity.status(401).body(Map.of("error", "bad timestamp"));
        }
        if (!verify(tsHeader, nonce, rawBody, sig)) {
            log.warn("rejected alertmanager: bad signature from {}", req.getRemoteAddr());
            return ResponseEntity.status(401).body(Map.of("error", "bad signature"));
        }
        if (Math.abs(System.currentTimeMillis() - ts) > TIMESTAMP_SKEW_MS) {
            return ResponseEntity.status(401).body(Map.of("error", "stale or future timestamp"));
        }
        // Nonce dedup AFTER ts/sig pass — same ordering as WebhookController so an attacker
        // cannot mine our cache by spamming bad nonces. acceptOnce() is the dedup primitive;
        // false = replay or fail-closed (empty nonce was already rejected at the top).
        if (!nonceCache.acceptOnce(nonce, ts)) {
            log.warn("rejected alertmanager: replayed nonce {} from {}", nonce, req.getRemoteAddr());
            return ResponseEntity.status(401).body(Map.of("error", "nonce replay"));
        }
        try {
            Map<?, ?> payload = JSON.readValue(rawBody, Map.class);
            String groupKey = payload.get("groupKey") == null ? "" : String.valueOf(payload.get("groupKey"));
            String status = payload.get("status") == null ? "firing" : String.valueOf(payload.get("status"));
            Object alertsObj = payload.get("alerts");
            if (!(alertsObj instanceof List<?> alerts) || alerts.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "no alerts in payload"));
            }
            String summary = renderSummary(status, alerts);
            String firstAlertName = firstLabel(alerts, "alertname", "alert");
            // Stable per-group thread so re-firing alerts append into the same triage thread.
            // groupKey can be very long; hash it to keep gateway-bindings.json compact.
            String threadKey = groupKey.isEmpty() ? "alertmanager:" + firstAlertName
                : "alertmanager:" + sha1(groupKey).substring(0, 12);
            // Dispatch through the standard gateway pipeline so allowlist / idempotency / single-flight
            // permits all apply uniformly. The "user" identity is the alertname, which gates per-alert
            // allowlisting (e.g. allow only `KubeNodeNotReady` to drive automation; route others to humans).
            String virtualUser = "alert:" + firstAlertName;
            int replyCap = cfg.getGateway() == null ? 3500 : cfg.getGateway().getReplyMaxChars();
            var result = dispatcher.dispatch("alertmanager", virtualUser, threadKey, nonce, summary, replyCap);
            // Unauthorised sender: dispatcher returns threadId=null. Map.of(...) rejects null
            // values, so without this early return the downstream branch would NPE -> 500, and
            // Alertmanager would keep retrying the rejected alert (amplifying load). Use 403 so
            // the operator can distinguish "signature OK but sender denied by allowlist" from
            // signature/timestamp failures (401) above.
            if (result.threadId() == null) {
                log.warn("alertmanager dispatch refused by allowlist (alertname={} status={} from={})", firstAlertName,
                    status, req.getRemoteAddr());
                return ResponseEntity.status(403).body(Map.of("error", "sender not allowed", "alertname",
                    firstAlertName, "status", status, "reply", result.reply()));
            }
            // Ensure the thread title includes the alert name so it's discoverable in the WebUI sidebar.
            try {
                store.get(result.threadId()).ifPresent(t -> {
                    if (t.getTitle() == null || t.getTitle().isBlank() || t.getTitle().equals("新会话")) {
                        t.setTitle("alert: " + firstAlertName);
                        store.save(t);
                    }
                });
            } catch (Exception titleEx) {
                log.debug("failed to retitle alertmanager thread: {}", titleEx.getMessage());
            }
            return ResponseEntity.ok(Map.of("threadId", result.threadId(), "alertname", firstAlertName, "status",
                status, "reply", result.reply(), "durationMs", result.durationMs()));
        } catch (Exception e) {
            log.error("alertmanager dispatch failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Build the canonical summary text fed to the agent — instructive enough for triage in one turn. */
    private static String renderSummary(String status, List<?> alerts) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Alertmanager] ").append(status.toUpperCase(Locale.ROOT)).append(" — ").append(alerts.size())
            .append(" alert(s)\n\n");
        int idx = 0;
        for (Object a : alerts) {
            if (++idx > 10) {
                sb.append("…(").append(alerts.size() - 10).append(" more)\n");
                break;
            }
            if (!(a instanceof Map<?, ?> m))
                continue;
            Map<?, ?> labels = m.get("labels") instanceof Map<?, ?> ll ? ll : Map.of();
            Map<?, ?> annot = m.get("annotations") instanceof Map<?, ?> aa ? aa : Map.of();
            sb.append(idx).append(". ").append(labels.get("alertname") == null ? "(unnamed)" : labels.get("alertname"));
            Object sev = labels.get("severity");
            if (sev != null)
                sb.append("  severity=").append(sev);
            Object inst = labels.get("instance");
            if (inst != null)
                sb.append("  instance=").append(inst);
            sb.append('\n');
            Object summary = annot.get("summary");
            if (summary != null)
                sb.append("   summary: ").append(summary).append('\n');
            Object desc = annot.get("description");
            if (desc != null)
                sb.append("   description: ").append(desc).append('\n');
            Object url = m.get("generatorURL");
            if (url != null)
                sb.append("   generatorURL: ").append(url).append('\n');
            sb.append('\n');
        }
        sb.append("\nPlease triage: identify root cause via read-only tools first (logs / metrics / kubectl get …);"
            + " propose remediation; ask before any mutating action.");
        return sb.toString();
    }

    private static String firstLabel(List<?> alerts, String... preferredKeys) {
        for (Object a : alerts) {
            if (!(a instanceof Map<?, ?> m))
                continue;
            Object lblObj = m.get("labels");
            if (!(lblObj instanceof Map<?, ?> labels))
                continue;
            for (String k : preferredKeys) {
                Object v = labels.get(k);
                if (v != null && !String.valueOf(v).isBlank())
                    return String.valueOf(v);
            }
        }
        return "unknown";
    }

    private boolean verify(String ts, String nonce, String body, String sig) {
        String secret = cfg.getGateway() == null ? "" : cfg.getGateway().getAlertmanagerSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("Alertmanager webhook secret not configured — rejecting (fail-closed)");
            return false;
        }
        if (sig == null || sig.isBlank())
            return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(ts.getBytes(StandardCharsets.UTF_8));
            mac.update((byte)'.');
            mac.update(nonce.getBytes(StandardCharsets.UTF_8));
            mac.update((byte)'.');
            byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] candidate;
            try {
                candidate = HexFormat.of().parseHex(sig);
            } catch (IllegalArgumentException badHex) {
                return false;
            }
            return MessageDigest.isEqual(expected, candidate);
        } catch (Exception e) {
            log.error("Alertmanager HMAC verify failed", e);
            return false;
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
