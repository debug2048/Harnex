package com.harnex.gateway.webhook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight in-memory replay-protection cache shared by IM gateways (DingTalk, Feishu, WeCom, generic webhook). Holds
 * {@code (nonce \u2192 expiresAt)} entries; once a nonce has been accepted, the same nonce within its validity window
 * is rejected. Memory is bounded by opportunistic eviction of expired entries when the map crosses
 * {@link #MAX_ENTRIES}.
 *
 * <p>
 * Implementation notes:
 * <ul>
 * <li>Validity window is fixed at 5 minutes \u2014 same as the timestamp-skew limit every gateway enforces. A nonce can
 * only be replayed within that window because the timestamp check rejects it afterwards.</li>
 * <li>{@link #acceptOnce(String, long)} returns {@code true} when the nonce was previously unseen and {@code false} on
 * replay. Callers should respond with HTTP 401 on {@code false}.</li>
 * <li>This is intentionally not Spring-managed: each gateway owns its own instance so a misbehaving sender on one
 * channel can't poison the dedup set of another.</li>
 * </ul>
 */
final class NonceCache {

    private static final long VALIDITY_MS = 5L * 60_000L;
    private static final int MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    /**
     * Try to record a previously-unseen nonce. Returns {@code true} on first sight, {@code false} when the nonce has
     * already been accepted within its validity window.
     *
     * @param nonce caller-supplied unique identifier (timestamp+sign / X-*-Nonce / etc.)
     * @param requestEpochMs timestamp the platform attached to the request; used to compute expiry
     */
    public boolean acceptOnce(String nonce, long requestEpochMs) {
        if (nonce == null || nonce.isEmpty()) {
            // Fail closed. The previous default (return true on missing nonce) silently
            // disabled replay protection for any future caller that didn't pre-validate the
            // nonce parameter. All current call sites (DingTalk, Feishu, WeCom) already
            // require the nonce via @RequestParam, so they never reach here with empty input;
            // a new gateway integration that forgets the pre-check would inherit a SAFE
            // default instead of a silent bypass.
            return false;
        }
        long now = System.currentTimeMillis();
        // Opportunistic sweep of expired entries. The previous code stopped here \u2014 if every
        // entry inside the validity window was a NEW unique nonce (high legitimate volume,
        // bursty replay attempt with random nonces, etc.) the sweep would do nothing and the
        // map could continue to grow until OOM. Now: if the map is STILL above the cap after
        // sweeping expired entries, fall through to a deterministic eviction step (drop the
        // earliest-expiring entries until back under the cap) and, as a last resort, refuse
        // the request rather than admitting it into an unbounded set.
        if (seen.size() > MAX_ENTRIES) {
            seen.entrySet().removeIf(e -> e.getValue() < now);
        }
        if (seen.size() > MAX_ENTRIES) {
            // Force the size back down by evicting the entries closest to expiry. This is O(n)
            // but only runs when the map is actively under attack / overload, and keeps a hard
            // ceiling on heap usage. Snapshot first to avoid ConcurrentModificationException.
            int target = MAX_ENTRIES * 9 / 10; // drop 10% headroom so we don't re-trip immediately
            seen.entrySet().stream().sorted(Map.Entry.comparingByValue()).limit(Math.max(0, seen.size() - target))
                .map(Map.Entry::getKey).toList() // materialise before mutating the map
                .forEach(seen::remove);
        }
        if (seen.size() >= MAX_ENTRIES) {
            // Last-resort fail-closed: refuse new entries when even forced eviction couldn't
            // make room (concurrent floods racing with our sweep). Refusing here preserves
            // replay protection \u2014 a refused request is treated by callers as 401, which is
            // safer than silently admitting it to an unbounded cache.
            return false;
        }
        Long prev = seen.putIfAbsent(nonce, requestEpochMs + VALIDITY_MS);
        return prev == null;
    }
}
