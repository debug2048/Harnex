package com.harnex.web.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * CSRF protection via the Double-Submit-Cookie pattern.
 *
 * <p>
 * Threat model: cookie auth (HARNEX_TOKEN) is auto-sent on cross-site form posts. SameSite=Strict mitigates this on
 * modern browsers, but defence-in-depth is required because:
 * <ul>
 * <li>Older Safari / WebView build still treat SameSite as Lax in some contexts.</li>
 * <li>Subdomain attacks can side-step SameSite when both sites share an eTLD+1.</li>
 * <li>Many corporate proxies strip the SameSite attribute.</li>
 * </ul>
 *
 * <p>
 * How it works:
 * <ol>
 * <li>On any safe ({@code GET}/{@code HEAD}/{@code OPTIONS}) request the filter mints a 256-bit random token, sets it
 * as a non-HttpOnly cookie {@code XSRF-TOKEN} (path=/) and lets the request through. The SPA reads the cookie via
 * {@code document.cookie} and echoes it back on every state-changing request as the {@code X-XSRF-TOKEN} header.</li>
 * <li>On state-changing methods ({@code POST}/{@code PUT}/{@code DELETE}/{@code PATCH}) the filter compares the cookie
 * value to the header value with a constant-time check; mismatch returns {@code 403 csrf_token_mismatch}.</li>
 * <li>Bearer token requests (header {@code Authorization: Bearer ...}) are exempt — they are machine-to-machine and the
 * attacker has no way to inject the header from another origin.</li>
 * <li>Public auth endpoints ({@code /api/auth/login}, {@code /api/auth/captcha}) and SSE bootstrap are exempt to allow
 * first contact before a cookie exists.</li>
 * </ol>
 */
@Slf4j
@Configuration
public class CsrfConfig {

    @Bean
    public FilterRegistrationBean<CsrfFilter> csrfFilter(AgentConfig cfg) {
        FilterRegistrationBean<CsrfFilter> reg = new FilterRegistrationBean<>(new CsrfFilter(cfg));
        reg.addUrlPatterns("/api/*");
        // Run AFTER ApiAuthFilter (HIGHEST_PRECEDENCE+10) but before MVC.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return reg;
    }

    static class CsrfFilter extends OncePerRequestFilter {
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final String COOKIE = "XSRF-TOKEN";
        private static final String HEADER = "X-XSRF-TOKEN";
        private static final SecureRandom RNG = new SecureRandom();
        /**
         * Endpoints that must work *before* a token cookie can possibly exist.
         *
         * <p>
         * NOTE: {@code /api/auth/logout} was historically here \u2014 that's a CSRF hole, because a cross-site form
         * POST could log out a victim user (annoyance vector, and a foothold for fixation-style attacks where the
         * attacker then offers their own session). The SPA always has an XSRF cookie by the time the logout button is
         * clickable (issued on every safe GET response, including the SPA bootstrap), so requiring the token costs
         * nothing. CLI / Bearer clients are still exempt via the {@code bearer} branch above.
         */
        private static final Set<String> PUBLIC =
            Set.of("/api/v1/auth/login", "/api/v1/auth/captcha", "/api/health",
                // Boot-token exchange (docs/03 §3.14): machine clients (CLI/TUI/VS Code) POST here with
                // neither a session cookie nor a Bearer header — possession of the boot-token file IS
                // the proof. Same first-contact reasoning as /api/v1/auth/login.
                "/api/v1/auth/boot-token");

        /**
         * Path prefixes whose authenticity is verified by per-endpoint signature/secret rather than by browser-cookie
         * session. CSRF is browser-only — these requests come from external IM platforms (DingTalk / Feishu / WeCom /
         * custom bridges) and could never carry our XSRF-TOKEN cookie. Skipping CSRF here is safe because: 1. Each
         * gateway controller verifies a per-platform HMAC/nonce signature and enforces its own anti-replay window
         * before dispatching. 2. The endpoints set no Cookie of their own and ignore session state.
         */
        private static final String[] PUBLIC_PREFIXES = {"/api/gateway/"};

        private final AgentConfig cfg;

        CsrfFilter(AgentConfig cfg) {
            this.cfg = cfg;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
            // When auth is globally disabled the deployment is single-trusted-user mode and
            // cookie-based session auth isn't even active — CSRF has no attack surface.
            if (!cfg.getAuth().isEnabled()) {
                chain.doFilter(req, res);
                return;
            }

            String method = req.getMethod();
            boolean safe =
                "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);

            // Bearer-token clients (CLI, automation) are immune to browser CSRF — an attacker on
            // another origin cannot make the victim's browser send a custom Authorization header.
            String authz = req.getHeader("Authorization");
            boolean bearer = authz != null && authz.startsWith("Bearer ");

            String path = req.getRequestURI();
            boolean publicPath = PUBLIC.contains(path);
            if (!publicPath && path != null) {
                for (String prefix : PUBLIC_PREFIXES) {
                    if (path.startsWith(prefix)) {
                        publicPath = true;
                        break;
                    }
                }
            }

            // Always issue a token cookie when missing so even brand-new clients (immediately
            // after /api/auth/login, before any GET roundtrip) have a usable token to echo back.
            String existing = readCookie(req);
            if (existing == null) {
                setTokenCookie(req, res, mint());
            }

            if (safe || bearer || publicPath) {
                chain.doFilter(req, res);
                return;
            }

            String headerVal = req.getHeader(HEADER);
            if (existing == null || headerVal == null || !constantTimeEquals(existing, headerVal)) {
                reject(res);
                return;
            }
            chain.doFilter(req, res);
        }

        private static String readCookie(HttpServletRequest req) {
            Cookie[] cs = req.getCookies();
            if (cs == null)
                return null;
            for (Cookie c : cs) {
                if (COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
            return null;
        }

        private static String mint() {
            byte[] buf = new byte[32];
            RNG.nextBytes(buf);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        }

        private static void setTokenCookie(HttpServletRequest req, HttpServletResponse res, String token) {
            // NOT HttpOnly — the SPA must be able to read it via document.cookie.
            // SameSite=Strict prevents the cookie from leaking on cross-site navigations.
            StringBuilder sb = new StringBuilder();
            sb.append(COOKIE).append('=').append(token).append("; Path=/; SameSite=Strict; Max-Age=").append(8 * 3600);
            if (req.isSecure()) {
                sb.append("; Secure");
            }
            res.addHeader("Set-Cookie", sb.toString());
        }

        private static boolean constantTimeEquals(String a, String b) {
            byte[] ab = a.getBytes(StandardCharsets.UTF_8);
            byte[] bb = b.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(ab, bb);
        }

        private static void reject(HttpServletResponse res) throws IOException {
            res.setStatus(403);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            JSON.writeValue(res.getWriter(), Map.of("error", "csrf_token_mismatch"));
        }
    }
}