package com.harnex.provider.search;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-settings circuit-breaker + health snapshot for {@code web_search}. Separate from the {@code Tool.available()}
 * capability gate on purpose:
 *
 * <ul>
 * <li><b>{@code Tool.available()}</b> is static (key present / enabled / provider resolvable) and decides whether the
 * tool is <em>advertised</em>. It is cache-stable — it never flips on transient runtime state.</li>
 * <li><b>This breaker</b> tracks <em>runtime</em> terminal failures (a key that lapsed, billing ran out). When too many
 * pile up it opens for a cooldown so the tool stops hitting the network and instead returns terminal guidance to the
 * model <em>instantly</em> (zero latency, no wasted requests). The tool stays advertised the whole time — the model
 * just gets a fast "unavailable, don't retry" answer. This is the "used it, then stopped paying" path.</li>
 * </ul>
 *
 * <p>
 * Thread-safe: a single shared bean is hit concurrently by every {@code web_search} invocation.
 */
@Slf4j
@Component
@ProjectRuntime
public class WebSearchHealth {

    private final AgentConfig config;
    public WebSearchHealth(AgentConfig config) { this.config = config; }
    private State state() { return config.derivedSettings(this, c -> new State(c.getWebSearch())); }
    public boolean usable() { return state().usable(); }
    public void recordResult(boolean success, boolean terminal, String error) { state().recordResult(success, terminal, error); }
    public Map<String, Object> snapshot() { return state().snapshot(); }

    private static final class State {
        private final int threshold;
        private final long cooldownMs;

        /** Consecutive terminal failures since the last success. */
        private final AtomicInteger consecutiveTerminal = new AtomicInteger(0);
        /** Epoch millis until which the circuit is open; 0 = closed. */
        private final AtomicLong openUntil = new AtomicLong(0L);
        /** Last terminal failure message, for the doctor / self_inspect snapshot. */
        private volatile String lastError = null;
        private volatile long lastSuccessAt = 0L;
        /** Whether we've logged the current open window's WARN (so we warn once per trip, not per call). */
        private volatile boolean warnedThisTrip = false;

        State(AgentConfig.WebSearch ws) {
            this.threshold = ws == null ? 3 : Math.max(1, ws.getCircuitThreshold());
            this.cooldownMs = ws == null ? 600_000L : Math.max(1_000L, ws.getCircuitCooldownMs());
        }

        /**
         * @return false while the breaker is open (cooldown not elapsed). When it returns true after having been open, the
         *         caller is the "half-open probe" — the next {@link #recordResult} decides whether to re-open or fully
         *         close.
         */
        public boolean usable() {
            long until = openUntil.get();
            if (until == 0L) {
                return true;
            }
            if (System.currentTimeMillis() >= until) {
                // Half-open: allow one probe through. Leave openUntil set; a fresh terminal failure
                // re-arms it via recordResult, a success clears it.
                return true;
            }
            return false;
        }

        /** Record the outcome of a search attempt. {@code terminal} is only meaningful when {@code success} is false. */
        public void recordResult(boolean success, boolean terminal, String error) {
            if (success) {
                consecutiveTerminal.set(0);
                openUntil.set(0L);
                warnedThisTrip = false;
                lastSuccessAt = System.currentTimeMillis();
                lastError = null;
                return;
            }
            if (!terminal) {
                // Transient faults don't trip the breaker — they're expected noise (rate limits, blips).
                lastError = error;
                return;
            }
            lastError = error;
            int n = consecutiveTerminal.incrementAndGet();
            if (n >= threshold) {
                openUntil.set(System.currentTimeMillis() + cooldownMs);
                if (!warnedThisTrip) {
                    warnedThisTrip = true;
                    log.warn("web_search circuit OPEN after {} consecutive terminal failures (cooldown {}ms); "
                        + "last error: {}. Check harnex.web-search.api-key / billing.", n, cooldownMs, error);
                }
            }
        }

        /** Read-only health view for {@code self_inspect} / {@code harnex doctor} / statusline. */
        public Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            long until = openUntil.get();
            boolean open = until != 0L && System.currentTimeMillis() < until;
            m.put("circuitOpen", open);
            m.put("consecutiveTerminalFailures", consecutiveTerminal.get());
            if (open) {
                m.put("cooldownRemainingMs", Math.max(0L, until - System.currentTimeMillis()));
            }
            if (lastError != null) {
                m.put("lastError", lastError);
            }
            if (lastSuccessAt > 0L) {
                m.put("lastSuccessAt", lastSuccessAt);
            }
            return m;
        }
    }
}
