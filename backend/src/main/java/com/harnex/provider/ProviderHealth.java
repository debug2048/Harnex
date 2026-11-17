package com.harnex.provider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Independent EWMA diagnostics and CLOSED/OPEN/HALF_OPEN admission for provider/model targets.
 *
 * <p>EWMA is deliberately observational: it never reorders a user's selected provider. The circuit opens only after
 * a configurable consecutive transient-failure burst, waits for the cooldown, then admits exactly one half-open
 * probe. A complete success closes the circuit and resets the burst.</p>
 */
public final class ProviderHealth {

    private static final long MIN_EWMA_SAMPLES = 5L;

    public enum Admission {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final boolean ewmaEnabled;
    private final boolean circuitEnabled;
    private final double alpha;
    private final double degradedThreshold;
    private final long failureThreshold;
    private final long openNanos;
    private final LongSupplier ticker;

    private final ConcurrentHashMap<String, AtomicReference<Double>> ewma = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> samples = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> consecutiveFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> openUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> halfOpen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> rateLimitUntil = new ConcurrentHashMap<>();

    public ProviderHealth(boolean ewmaEnabled, boolean circuitEnabled, double alpha, double degradedThreshold,
        int failureThreshold, long openMs) {
        this(ewmaEnabled, circuitEnabled, alpha, degradedThreshold, failureThreshold, openMs, System::nanoTime);
    }

    ProviderHealth(boolean ewmaEnabled, boolean circuitEnabled, double alpha, double degradedThreshold,
        int failureThreshold, long openMs, LongSupplier ticker) {
        this.ewmaEnabled = ewmaEnabled;
        this.circuitEnabled = circuitEnabled;
        this.alpha = alpha > 0 && alpha <= 1 ? alpha : 0.3;
        this.degradedThreshold = degradedThreshold >= 0 && degradedThreshold <= 1 ? degradedThreshold : 0.5;
        this.failureThreshold = failureThreshold > 0 ? failureThreshold : 5;
        this.openNanos = TimeUnit.MILLISECONDS.toNanos(openMs > 0 ? openMs : 30_000L);
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    public void recordSuccess(String target) {
        if (target == null) return;
        rateLimitUntil.remove(target);
        if (!ewmaEnabled && !circuitEnabled) { halfOpen.remove(target); return; }
        updateEwma(target, 0.0);
        consecutiveFailures.computeIfAbsent(target, ignored -> new AtomicLong()).set(0L);
        openUntil.remove(target);
        halfOpen.remove(target);
    }

    public void recordFailure(String target) {
        if (target == null) return;
        if (!ewmaEnabled && !circuitEnabled) { releaseProbe(target); return; }
        updateEwma(target, 1.0);
        long failures = consecutiveFailures.computeIfAbsent(target, ignored -> new AtomicLong()).incrementAndGet();
        if (circuitEnabled && failures >= failureThreshold) {
            openUntil.put(target, ticker.getAsLong() + openNanos);
        }
        releaseProbe(target);
    }

    private void updateEwma(String target, double sample) {
        if (!ewmaEnabled) return;
        ewma.computeIfAbsent(target, ignored -> new AtomicReference<>(sample))
            .updateAndGet(previous -> alpha * sample + (1.0 - alpha) * previous);
        samples.computeIfAbsent(target, ignored -> new AtomicLong()).incrementAndGet();
    }

    public double currentEwma(String target) {
        AtomicReference<Double> value = ewma.get(target);
        return value == null ? 0.0 : value.get();
    }

    public boolean isDegraded(String target) {
        if (!ewmaEnabled || target == null) return false;
        AtomicLong count = samples.get(target);
        return count != null && count.get() >= MIN_EWMA_SAMPLES && currentEwma(target) >= degradedThreshold;
    }

    public boolean isOpen(String target) {
        return target != null && (rateLimitUntil.containsKey(target) || circuitEnabled && openUntil.containsKey(target));
    }

    /** Retry-After is an upstream limit and remains effective even when the optional circuit breaker is disabled. */
    public void recordRateLimit(String target, long retryAfterMillis) {
        if (target == null || retryAfterMillis <= 0) return;
        long deadline = ticker.getAsLong() + TimeUnit.MILLISECONDS.toNanos(Math.min(86_400_000L, retryAfterMillis));
        rateLimitUntil.merge(target, deadline, Math::max);
        releaseProbe(target);
    }

    public long cooldownRemainingMillis(String target) {
        Long deadline = rateLimitUntil.get(target);
        return deadline == null ? 0 : Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - ticker.getAsLong()));
    }

    /** Admit one request, with a single-flight HALF_OPEN probe after cooldown. */
    public Admission acquire(String target) {
        if (target == null) return Admission.CLOSED;
        Long rateDeadline = rateLimitUntil.get(target);
        if (rateDeadline != null && ticker.getAsLong() < rateDeadline) return Admission.OPEN;
        if (!circuitEnabled && rateDeadline == null) return Admission.CLOSED;
        Long deadline = openUntil.get(target);
        if (deadline == null && rateDeadline == null) return Admission.CLOSED;
        if (deadline != null && ticker.getAsLong() < deadline) return Admission.OPEN;
        AtomicBoolean probe = halfOpen.computeIfAbsent(target, ignored -> new AtomicBoolean());
        return probe.compareAndSet(false, true) ? Admission.HALF_OPEN : Admission.OPEN;
    }

    /** Ensure cancellation or a non-transient probe failure cannot leave HALF_OPEN permanently occupied. */
    public void abandonProbe(String target) {
        if (target == null || !probeInFlight(target)) return;
        if (circuitEnabled) openUntil.put(target, ticker.getAsLong() + openNanos);
        if (rateLimitUntil.containsKey(target)) rateLimitUntil.put(target, ticker.getAsLong() + openNanos);
        releaseProbe(target);
    }

    private boolean probeInFlight(String target) {
        AtomicBoolean probe = halfOpen.get(target);
        return probe != null && probe.get();
    }

    private void releaseProbe(String target) {
        AtomicBoolean probe = halfOpen.get(target);
        if (probe != null) probe.set(false);
    }

    public Map<String, Double> snapshot() {
        Map<String, Double> out = new LinkedHashMap<>();
        ewma.forEach((target, value) -> out.put(target, value.get()));
        return Collections.unmodifiableMap(out);
    }
}
