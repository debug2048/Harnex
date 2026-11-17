package com.harnex.web.api.auth;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.config.AgentConfig;
import com.harnex.auth.AuthService;
import com.harnex.auth.AuthService.CaptchaResult;
import com.harnex.auth.AuthService.LoginResult;
import com.harnex.auth.AuthService.PasswordResult;
import com.harnex.auth.BootTokenService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * The auth resource of the Local API (docs/03 §3.14) — all endpoints public (no Bearer required); each proves
 * identity its own way. Two credential-exchange methods mint the SAME session:
 * <ul>
 * <li>POST /api/v1/auth/boot-token — optional trusted-host handshake: possession of
 * {@code <runtimeDomainDir>/boot-token} (readable only by the OS user running the runtime) IS the proof. Hosts use it
 * silently when explicit API authentication is enabled.</li>
 * <li>POST /api/v1/auth/login — interactive username/password, for remote-access / shared deployments.</li>
 * </ul>
 * Plus session/account lifecycle:
 * <ul>
 * <li>GET /api/v1/auth/config — reports whether bearer auth is enabled (lets the SPA skip the login page)</li>
 * <li>GET /api/v1/auth/captcha — generate a new arithmetic captcha</li>
 * <li>POST /api/v1/auth/logout — invalidate current session</li>
 * <li>POST /api/v1/auth/password — change password (requires valid session)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AgentConfig agentConfig;
    private final BootTokenService bootToken;

    /**
     * Local trusted-boot exchange (docs/03 §3.14): a client that can read {@code <runtimeDomainDir>/boot-token} proves it runs
     * as the same OS user as the runtime and receives a normal session — no interactive login. Wrong/absent tokens get
     * 401 with the unified error object.
     */
    @PostMapping("/boot-token")
    public ResponseEntity<Map<String, Object>> exchangeBootToken(
        @RequestBody(required = false) Map<String, String> body, HttpServletRequest httpReq) {
        String candidate = body == null ? null : body.get("token");
        if (!bootToken.matches(candidate)) {
            return ResponseEntity.status(401).body(Map.of("error",
                Map.of("code", "invalid_boot_token", "message", "boot token missing, stale, or disabled this run")));
        }
        String session = authService.issueLocalSession();
        // Same dual transport as login(): JSON body for machine clients (CLI/TUI/VS Code hold the
        // bearer), HttpOnly cookie for the browser-first-open flow (?bootToken= redirect).
        ResponseCookie cookie = ResponseCookie.from("HARNEX_TOKEN", session).httpOnly(true).secure(httpReq.isSecure())
            .sameSite("Strict").path("/").maxAge(Duration.ofHours(8)).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(Map.of("token", session));
    }

    /**
     * Public auth-mode probe. Lets the SPA decide whether to show a login screen at all. Returns the bare minimum (no
     * user list, no policy) so it is safe to leave unauthenticated. The accountless local GUI does not branch on this
     * endpoint; it remains useful to API clients and diagnostics for explicit remote deployments.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        boolean enabled = agentConfig.getAuth() != null && agentConfig.getAuth().isEnabled();
        return Map.of("enabled", enabled);
    }

    @GetMapping("/captcha")
    public CaptchaResult captcha() {
        return authService.generateCaptcha();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginReq req, HttpServletRequest httpReq) {
        // Defensive validation BEFORE touching AuthService. The previous path called
        // req.username() / req.password() directly, which:
        // * NPE'd (\u2192 500) when the body was missing or wasn't valid JSON.
        // * Indexed throttle maps under <null> / "" when fields were absent, polluting them
        // and conflating malformed clients with real failed logins.
        // Surface a clean 400 instead so SPAs and CLIs get an actionable error.
        if (req == null || req.username() == null || req.username().isBlank() || req.password() == null
            || req.password().isEmpty()) {
            return ResponseEntity.badRequest().body(err("bad_request", "username and password are required"));
        }
        LoginResult r = authService.login(req.username(), req.password(), req.captchaId(), req.captchaAnswer(),
            httpReq.getRemoteAddr());
        if (r.ok()) {
            // Issue token via TWO transports so callers can pick:
            // 1. JSON body field {"token": "..."} \u2014 backwards-compatible (existing SPA + CLI).
            // 2. HttpOnly + SameSite=Strict cookie HARNEX_TOKEN \u2014 lets the SPA stop persisting
            // the token in localStorage (mitigates XSS). Path=/ so SSE endpoints under
            // run-event requests also receive it. `Secure` is set when the request was over
            // HTTPS so dev (http://localhost:18081) still works.
            ResponseCookie cookie = ResponseCookie.from("HARNEX_TOKEN", r.token()).httpOnly(true)
                .secure(httpReq.isSecure()).sameSite("Strict").path("/")
                // 8h matches a typical workday; AuthService session TTL is the upper bound.
                .maxAge(Duration.ofHours(8)).build();
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("token", r.token(), "mustChangePassword", authService.mustChangePassword(req.username())));
        }
        // Map the structured errorCode to HTTP status. Previously this was inferred by
        // substring-matching the localized error message, which silently broke whenever the
        // throttle copy was reworded (the "稍后再试" check would miss "稍候再试" etc., and the
        // saturation message has the same prefix as a throttle lock so SPAs got 401 + no
        // backoff hint when the server was overloaded).
        AuthService.LoginErrorCode code =
            r.errorCode() == null ? AuthService.LoginErrorCode.BAD_CREDENTIALS : r.errorCode();
        int status = switch (code) {
            case THROTTLED -> 429;
            case BUSY -> 503;
            case BAD_REQUEST -> 400;
            case BAD_CREDENTIALS -> 401;
        };
        return ResponseEntity.status(status).body(err(code.name().toLowerCase(), r.error() == null ? "" : r.error()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String header,
        HttpServletRequest httpReq) {
        String token = extractToken(header);
        // Cookie path: pick up the token from HARNEX_TOKEN if no header was sent.
        if (token == null && httpReq.getCookies() != null) {
            for (Cookie c : httpReq.getCookies()) {
                if ("HARNEX_TOKEN".equals(c.getName())) {
                    token = c.getValue();
                    break;
                }
            }
        }
        if (token != null) {
            authService.logout(token);
        }
        // Always emit a Max-Age=0 cookie so the browser drops HARNEX_TOKEN even when no header was sent.
        ResponseCookie expire = ResponseCookie.from("HARNEX_TOKEN", "").httpOnly(true).secure(httpReq.isSecure())
            .sameSite("Strict").path("/").maxAge(0).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expire.toString()).body(Map.of("ok", true));
    }

    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@RequestHeader(value = "Authorization", required = false) String header,
        @RequestBody(required = false) PasswordReq req, HttpServletRequest httpReq) {
        if (req == null || req.oldPassword() == null || req.oldPassword().isEmpty() || req.newPassword() == null
            || req.newPassword().isEmpty()) {
            return ResponseEntity.badRequest().body(err("bad_request", "oldPassword and newPassword are required"));
        }
        String token = extractToken(header);
        // Cookie fallback: the SPA stopped persisting the token in localStorage to mitigate XSS,
        // so it relies entirely on the HttpOnly HARNEX_TOKEN cookie. Mirror the logout()
        // contract — without this fallback the password-change form returned 401 even for a
        // perfectly valid logged-in browser session.
        if (token == null && httpReq.getCookies() != null) {
            for (Cookie c : httpReq.getCookies()) {
                if ("HARNEX_TOKEN".equals(c.getName())) {
                    token = c.getValue();
                    break;
                }
            }
        }
        if (token == null) {
            return ResponseEntity.status(401).body(err("unauthorized", "未登录"));
        }
        String username = authService.validateToken(token);
        if (username == null) {
            return ResponseEntity.status(401).body(err("session_expired", "会话已过期"));
        }
        PasswordResult r = authService.changePassword(username, req.oldPassword(), req.newPassword());
        if (r.ok()) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        return ResponseEntity.badRequest().body(err("bad_request", r.error()));
    }

    /** Unified error envelope (docs/03 §3.14): {@code {"error":{"code","message"}}}. */
    private static Map<String, Object> err(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message == null ? "" : message));
    }

    private static String extractToken(String header) {
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    // ---- Request DTOs (records) -------------------------------------------

    public record LoginReq(String username, String password, String captchaId, int captchaAnswer) {
    }

    public record PasswordReq(String oldPassword, String newPassword) {
    }
}
