package com.harnex.web.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.preview.PreviewService;
import com.harnex.auth.AuthService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Bearer-token gate that supports two token sources:
 * <ol>
 * <li>Static tokens from {@code harnex.auth.tokens} in config (legacy / automation)</li>
 * <li>Session tokens issued by {@link com.harnex.auth.AuthService#login} (interactive login)</li>
 * </ol>
 * Skipped when {@code harnex.auth.enabled=false}.
 */
@Slf4j
@Configuration
public class ApiAuthConfig {

    @Bean
    public FilterRegistrationBean<ApiAuthFilter> apiAuth(AgentConfig cfg,
        AuthService authService) {
        FilterRegistrationBean<ApiAuthFilter> reg =
            new FilterRegistrationBean<ApiAuthFilter>(new ApiAuthFilter(cfg, authService));
        reg.addUrlPatterns("/api/*", "/actuator/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    static class ApiAuthFilter extends OncePerRequestFilter {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final AgentConfig cfg;
        private final AuthService authService;
        private final Map<String, FailState> failByIp = new ConcurrentHashMap<>();
        /** Per-IP token-bucket for root dispatch and Run Input control-plane writes. */
        private final Map<String, RateBucket> chatRateByIp = new ConcurrentHashMap<>();
        private static final int MAX_FAILS_PER_MIN = 20;
        private static final int MAX_FAIL_MAP_ENTRIES = 10_000;
        private static final int MAX_RATE_MAP_ENTRIES = 10_000;
        /** Root/input write cap: 30 operations/min/IP — generous for humans, lethal for floods. */
        private static final int CHAT_PER_MIN = 30;
        /**
         * Periodic pruner so slow-trickle floods (1 req/min/IP across 50k spoofed IPs) cannot indefinitely grow the
         * maps without ever crossing the on-insert MAX_*_MAP_ENTRIES gate.
         */
        private final ScheduledExecutorService prune;

        ApiAuthFilter(AgentConfig cfg, AuthService authService) {
            this.cfg = cfg;
            this.authService = authService;
            this.prune = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "api-auth-prune");
                t.setDaemon(true);
                return t;
            });
            // Every 5 minutes drop fail buckets older than 1 min and rate buckets idle >2 min.
            prune.scheduleAtFixedRate(() -> {
                try {
                    long now = System.currentTimeMillis();
                    failByIp.entrySet().removeIf(e -> now - e.getValue().windowStart > 60_000);
                    chatRateByIp.entrySet().removeIf(e -> now - e.getValue().windowStart > 120_000);
                } catch (Throwable t) {
                    log.warn("api-auth prune tick failed: {}", t.getMessage());
                }
            }, 5, 5, TimeUnit.MINUTES);
        }

        @Override
        public void destroy() {
            super.destroy();
            prune.shutdownNow();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
            boolean previewPath = req.getRequestURI().startsWith(PreviewService.CONTENT_PREFIX);
            boolean previewRead = previewPath && ("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod()));
            if (previewPath && CorsUtils.isPreFlightRequest(req)) { chain.doFilter(req, res); return; }
            // Sandboxed documents have an opaque origin. Only their short-lived read grants are usable here.
            if ("null".equals(req.getHeader("Origin")) && !previewRead) {
                reject(res, 403, "Preview documents cannot call the Runtime API");
                return;
            }
            if (previewRead) { chain.doFilter(req, res); return; }
            // Desktop WebViews preflight cross-origin Bearer requests without sending credentials.
            // Let MVC's CORS policy validate origin/method/headers; the actual API request still
            // passes through authentication below. Preflights must not exhaust the failure budget.
            if (CorsUtils.isPreFlightRequest(req)) {
                chain.doFilter(req, res);
                return;
            }
            AgentConfig.Auth a = cfg.getAuth();
            if (!a.isEnabled()) {
                chain.doFilter(req, res);
                return;
            }

            String path = req.getRequestURI();
            for (String p : a.getPublicPaths()) {
                // Strict match: equals OR proper child path. Previously a bare prefix match would
                // also let "/api/auth-anything" through when "/api/auth" was on the list.
                if (path.equals(p) || path.startsWith(p.endsWith("/") ? p : p + "/")) {
                    chain.doFilter(req, res);
                    return;
                }
            }

            String ip = clientIp(req, cfg);
            // Bound the failure-tracking map BEFORE inserting so a sustained spoofed-IP
            // flood (each fresh IP within the 60s window) can't grow the map past the cap.
            // Soft expiry sweep first; if that didn't free enough room, force-evict the
            // oldest entries (by windowStart) until back under a 90% high-water mark.
            // Falling back to fail-closed on the rare case where even forced eviction
            // races a concurrent flood preserves the throttle's intent.
            // Use >= so the cap is a true ceiling: at MAX_FAIL_MAP_ENTRIES exactly, a new
            // distinct ip would otherwise push the map to MAX+1 before the check runs again.
            if (failByIp.size() >= MAX_FAIL_MAP_ENTRIES && !failByIp.containsKey(ip)) {
                long now = System.currentTimeMillis();
                failByIp.entrySet().removeIf(e -> now - e.getValue().windowStart > 60_000);
                if (failByIp.size() >= MAX_FAIL_MAP_ENTRIES && !failByIp.containsKey(ip)) {
                    int target = MAX_FAIL_MAP_ENTRIES * 9 / 10;
                    failByIp.entrySet().stream().sorted(Comparator.comparingLong(e -> e.getValue().windowStart))
                        .limit(Math.max(0, failByIp.size() - target)).map(Map.Entry::getKey).toList()
                        .forEach(failByIp::remove);
                }
                if (failByIp.size() >= MAX_FAIL_MAP_ENTRIES && !failByIp.containsKey(ip)) {
                    // Hard last-resort: refuse the request rather than admit it into an
                    // unbounded set. 503 with Retry-After lets a legitimate client back off
                    // while spoofed-IP attackers see the same response and burn nothing.
                    reject(res, 503, "service busy (auth-tracking saturated)");
                    return;
                }
            }
            FailState fs = failByIp.computeIfAbsent(ip, k -> new FailState());
            if (fs.isThrottled()) {
                reject(res, 429, "too many failed auths");
                return;
            }

            String header = req.getHeader("Authorization");
            String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
            // Cookie fallback: AuthController sets HARNEX_TOKEN as HttpOnly + SameSite=Strict on
            // login, so a SPA can authenticate without ever putting the bearer in localStorage
            // (mitigates XSS-stolen-token risk). The cookie is read ONLY when no Bearer header is
            // present \u2014 explicit Authorization always wins so machine-to-machine clients are
            // unaffected.
            if (token == null && req.getCookies() != null) {
                for (Cookie c : req.getCookies()) {
                    if ("HARNEX_TOKEN".equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                        token = c.getValue();
                        break;
                    }
                }
            }
            // NOTE: we deliberately do NOT honour `?token=` on /stream any more.
            // URL query parameters end up in nginx access logs, browser history, CDN logs,
            // referer headers and crash reports. The HttpOnly + SameSite=Strict HARNEX_TOKEN
            // cookie set by AuthController covers the EventSource/browser case without
            // leaking credentials to those sinks.

            Set<String> accepted = Set.copyOf(a.getTokens());
            // Rate-limit root dispatch plus the new enqueue/withdraw control-plane writes. GET mailbox discovery is
            // read-only and stays outside the bucket.
            boolean expensiveDispatch = isRateLimitedRunWrite(path, req.getMethod());
            // 1. Check static config tokens (legacy / automation)
            if (token != null && !token.isBlank() && constantTimeContainsCached(accepted, token)) {
                fs.reset();
                if (expensiveDispatch && !checkChatRate(ip)) {
                    reject(res, 429, "chat rate limit exceeded");
                    return;
                }
                chain.doFilter(req, res);
                return;
            }
            // 2. Check session tokens issued by AuthService (interactive login)
            if (token != null && !token.isBlank() && authService.validateToken(token) != null) {
                fs.reset();
                if (expensiveDispatch && !checkChatRate(ip)) {
                    reject(res, 429, "chat rate limit exceeded");
                    return;
                }
                chain.doFilter(req, res);
                return;
            }
            fs.registerFail();
            reject(res, 401, "unauthorized");
        }

        static boolean isRateLimitedRunWrite(String path, String method) {
            if ("POST".equalsIgnoreCase(method) && "/api/v1/runs".equals(path)) return true;
            if (!("POST".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method))) return false;
            return path != null && (path.matches("/api/v1/runs/[^/]+/inputs(?:/[^/]+)?")
                || path.matches("/api/v1/conversations/[^/]+/submissions(?:/[^/]+/cancel)?")
                || path.matches("/api/v1/conversations/[^/]+/messages/[^/]+/(?:retry|revisions)")
                || path.matches("/api/v1/runs/[^/]+/(?:pause|resume|cancel|review)")
                || path.matches("/api/v1/goals(?:/[^/]+(?:/runs)?)?"));
        }

        /** Per-IP token bucket: returns true if a request is allowed. */
        private boolean checkChatRate(String ip) {
            // Same hard-cap pattern as the failByIp map: soft expiry sweep, then forced
            // eviction by oldest windowStart, finally fail-closed if the map still cannot
            // accept a new key. A spoofed-IP flood otherwise grows chatRateByIp without
            // bound (every fresh IP gets its own RateBucket and entries don't expire under
            // 2 minutes of activity).
            // Same off-by-one fix as failByIp above: >= so the cap is a true ceiling.
            if (chatRateByIp.size() >= MAX_RATE_MAP_ENTRIES && !chatRateByIp.containsKey(ip)) {
                long now = System.currentTimeMillis();
                chatRateByIp.entrySet().removeIf(e -> now - e.getValue().windowStart > 120_000);
                if (chatRateByIp.size() >= MAX_RATE_MAP_ENTRIES && !chatRateByIp.containsKey(ip)) {
                    int target = MAX_RATE_MAP_ENTRIES * 9 / 10;
                    chatRateByIp.entrySet().stream().sorted(Comparator.comparingLong(e -> e.getValue().windowStart))
                        .limit(Math.max(0, chatRateByIp.size() - target)).map(Map.Entry::getKey).toList()
                        .forEach(chatRateByIp::remove);
                }
                if (chatRateByIp.size() >= MAX_RATE_MAP_ENTRIES && !chatRateByIp.containsKey(ip)) {
                    // Refuse rather than create an unbounded entry. The caller treats this
                    // as "rate limit exceeded" — close enough to true under load.
                    return false;
                }
            }
            RateBucket b = chatRateByIp.computeIfAbsent(ip, k -> new RateBucket());
            return b.tryAcquire();
        }

        /**
         * Returns the client IP. X-Forwarded-For is only trusted when the direct peer is in the configured
         * trusted-proxy list — otherwise any client could spoof it and defeat the per-IP throttle.
         */
        private static String clientIp(HttpServletRequest req, AgentConfig cfg) {
            String remote = req.getRemoteAddr();
            Set<String> trusted = cfg.getAuth().getTrustedProxies();
            if (trusted != null && !trusted.isEmpty() && trusted.contains(remote)) {
                String h = req.getHeader("X-Forwarded-For");
                if (h != null && !h.isBlank()) {
                    return h.split(",")[0].trim();
                }
            }
            return remote;
        }

        /**
         * Cache of pre-hashed accepted tokens. Avoids re-hashing every configured token on every single request — with
         * N tokens and R requests/second the previous implementation did N*R SHA-256 ops per second. We refresh the
         * cache when the configured set changes (identity check on the {@code Set.copyOf} reference, then equality
         * fallback).
         */
        private volatile Set<String> cachedAcceptedSrc = Set.of();
        private volatile List<byte[]> cachedAcceptedHashes = List.of();

        private boolean constantTimeContainsCached(Set<String> accepted, String candidate) {
            if (cachedAcceptedSrc != accepted && !accepted.equals(cachedAcceptedSrc)) {
                List<byte[]> hashes = new ArrayList<>(accepted.size());
                for (String s : accepted) {
                    if (s == null || s.isBlank())
                        continue;
                    try {
                        hashes.add(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
                    } catch (NoSuchAlgorithmException ignored) {
                        /* SHA-256 always present */ }
                }
                cachedAcceptedHashes = List.copyOf(hashes);
                cachedAcceptedSrc = accepted;
            }
            byte[] cand;
            try {
                cand = MessageDigest.getInstance("SHA-256").digest(candidate.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                return false;
            }
            boolean found = false;
            // Iterate ALL entries (not break-on-match) so timing leaks the size of the token set,
            // never which token matched. MessageDigest.isEqual is itself constant-time.
            for (byte[] ref : cachedAcceptedHashes) {
                if (MessageDigest.isEqual(ref, cand)) {
                    found = true;
                }
            }
            return found;
        }

        private static boolean constantTimeContains(Set<String> set, String candidate) {
            byte[] cb = candidate.getBytes(StandardCharsets.UTF_8);
            byte[] cand;
            try {
                cand = MessageDigest.getInstance("SHA-256").digest(cb);
            } catch (NoSuchAlgorithmException e) {
                return false;
            }
            boolean found = false;
            for (String s : set) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                byte[] ref;
                try {
                    ref = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
                } catch (NoSuchAlgorithmException e) {
                    continue;
                }
                // MessageDigest.isEqual does a timing-safe compare; both sides are fixed 32-byte hashes.
                if (MessageDigest.isEqual(ref, cand)) {
                    found = true;
                }
            }
            return found;
        }

        private void reject(HttpServletResponse res, int code, String msg) throws IOException {
            res.setStatus(code);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            JSON.writeValue(res.getWriter(), Map.of("error", msg));
        }

        static class FailState {
            volatile long windowStart = System.currentTimeMillis();
            volatile int fails = 0;

            synchronized void registerFail() {
                long now = System.currentTimeMillis();
                if (now - windowStart > 60_000) {
                    windowStart = now;
                    fails = 0;
                }
                fails++;
            }

            synchronized boolean isThrottled() {
                long now = System.currentTimeMillis();
                if (now - windowStart > 60_000) {
                    return false;
                }
                return fails >= MAX_FAILS_PER_MIN;
            }

            synchronized void reset() {
                fails = 0;
                windowStart = System.currentTimeMillis();
            }
        }

        /** Sliding-1-min token bucket. {@code CHAT_PER_MIN} requests per rolling minute per IP. */
        static class RateBucket {
            volatile long windowStart = System.currentTimeMillis();
            volatile int count = 0;

            synchronized boolean tryAcquire() {
                long now = System.currentTimeMillis();
                if (now - windowStart > 60_000) {
                    windowStart = now;
                    count = 0;
                }
                if (count >= CHAT_PER_MIN) {
                    return false;
                }
                count++;
                return true;
            }
        }
    }
}
