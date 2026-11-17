package com.harnex.auth;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.UserDataPaths;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles the optional remote/API authentication, session tokens, and captcha. Users are persisted to
 * {@code <userDataDir>/users.json}; the local user-level GUI keeps authentication disabled by default. Sessions
 * live in-memory (lost on restart — user re-logs in).
 */
@Slf4j
@Service
public class AuthService {

    private static final ObjectMapper JSON = new ObjectMapper();
    // BCrypt strength 12 = ~250ms/hash on modern hardware — strong enough to resist
    // offline GPU attacks without hurting login latency.
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    private static final SecureRandom RNG = new SecureRandom();
    private static final long SESSION_TTL_MS = 24 * 60 * 60 * 1000L; // 24h absolute cap
    private static final long SESSION_IDLE_MS = 2 * 60 * 60 * 1000L; // 2h sliding idle
    private static final long CAPTCHA_TTL_MS = 5 * 60 * 1000L; // 5min
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASS = "admin123";

    /**
     * Restrict a sensitive auth file to the current process user where the filesystem supports it. On Windows this
     * deliberately grants the account running Harnex, not the Administrators group.
     */
    private static void hardenSensitiveFile(Path file) {
        var fs = file.getFileSystem();
        if (fs.supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(file,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                return;
            } catch (Exception e) {
                log.warn("{}: POSIX chmod 600 failed, falling back: {}", file.getFileName(), e.getMessage());
            }
        }
        AclFileAttributeView aclView = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (aclView != null) {
            try {
                // Do not use Files.getOwner(file): on Windows that can be BUILTIN\Administrators
                // when the process is elevated. Bind access to the actual account running this JVM.
                var user = fs.getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
                AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                aclView.setAcl(List.of(entry));
                return;
            } catch (Exception e) {
                log.warn("{}: ACL hardening failed (will fall back to setReadable): {}", file.getFileName(),
                    e.getMessage());
            }
        }
        try {
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
        } catch (Exception ignored) {
            /* best effort */ }
    }

    private final AgentConfig cfg;
    private final Path usersFile;

    /** username -> UserRecord */
    private final Map<String, UserRecord> users = new ConcurrentHashMap<>();
    /** token -> Session */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** Maximum number of concurrent sessions before forced pruning. */
    private static final int MAX_SESSIONS = 1000;
    /** captchaId -> CaptchaEntry */
    private final Map<String, CaptchaEntry> captchas = new ConcurrentHashMap<>();

    /**
     * Per-IP and per-username login failure tracking. Pure server-side throttle: even though /api/auth/login is on the
     * public-paths list (so the ApiAuthFilter's per-IP fail bucket doesn't cover it), repeated bad-password attempts
     * here will short-circuit with HTTP 429 after {@link #LOGIN_MAX_FAILS_PER_WINDOW} failures inside
     * {@link #LOGIN_WINDOW_MS}. Keys are bounded — both maps are pruned on insert when over MAX_LOGIN_THROTTLE_ENTRIES.
     */
    private static final int LOGIN_MAX_FAILS_PER_WINDOW = 8;
    private static final long LOGIN_WINDOW_MS = 60_000L; // sliding 60s window
    private static final long LOGIN_LOCK_MS = 5L * 60_000L; // 5-minute backoff once tripped
    private static final int MAX_LOGIN_THROTTLE_ENTRIES = 10_000;
    private final Map<String, LoginFailState> loginFailsByIp = new ConcurrentHashMap<>();
    private final Map<String, LoginFailState> loginFailsByUser = new ConcurrentHashMap<>();

    private static final class LoginFailState {
        // volatile so {@link #evictThrottleMap} can read these without acquiring the per-state
        // monitor. Writers are still under {@code synchronized} (registerFail / reset) so the
        // increment-and-publish sequence stays atomic; readers just need a happens-before edge
        // for visibility, which {@code volatile} provides. Without this the eviction pass could
        // read a stale {@code lockedUntil == 0} for an entry that was just locked by a parallel
        // {@code registerFail()} on a different thread, and silently drop it \u2014 weakening the
        // very throttle the map exists to enforce.
        volatile long windowStart;
        int count; // only touched under the monitor
        volatile long lockedUntil;

        synchronized boolean isLocked(long now) {
            return lockedUntil > now;
        }

        synchronized void registerFail(long now) {
            if (now - windowStart > LOGIN_WINDOW_MS) {
                windowStart = now;
                count = 0;
            }
            count++;
            if (count >= LOGIN_MAX_FAILS_PER_WINDOW) {
                lockedUntil = now + LOGIN_LOCK_MS;
            }
        }

        synchronized void reset() {
            count = 0;
            lockedUntil = 0L;
        }
    }

    /**
     * Sweep + force-evict a throttle map down to a 90% high-water mark. Soft pass first (drop expired+unlocked
     * entries); if that wasn't enough, drop the entries with the oldest {@code windowStart}, but always preserve
     * currently-locked entries (they're the whole point of the map). The {@code keepKey} parameter is the key the
     * caller is about to write \u2014 we never evict it even if it's the oldest, so the caller's subsequent
     * computeIfAbsent doesn't immediately re-trip the cap.
     */
    private static void evictThrottleMap(Map<String, LoginFailState> map, long now, String keepKey) {
        // Soft pass: expired AND not locked.
        map.entrySet()
            .removeIf(e -> now - e.getValue().windowStart > LOGIN_WINDOW_MS && e.getValue().lockedUntil < now);
        if (map.size() <= MAX_LOGIN_THROTTLE_ENTRIES) {
            return;
        }
        // Forced pass: oldest windowStart first, skipping locked entries and the caller's key.
        int target = MAX_LOGIN_THROTTLE_ENTRIES * 9 / 10;
        map.entrySet().stream().filter(e -> e.getValue().lockedUntil <= now && !e.getKey().equals(keepKey))
            .sorted(Comparator.comparingLong(e -> e.getValue().windowStart)).limit(Math.max(0, map.size() - target))
            .map(Map.Entry::getKey).toList().forEach(map::remove);
    }

    public AuthService(AgentConfig cfg) {
        this.cfg = cfg;
        this.usersFile = UserDataPaths.resolve(cfg).resolve("users.json");
    }

    @PostConstruct
    void init() throws IOException {
        if (cfg.getAuth() == null || !cfg.getAuth().isEnabled()) {
            log.debug("AuthService account persistence disabled for the local user-level runtime");
            return;
        }
        Files.createDirectories(usersFile.getParent());
        if (Files.exists(usersFile)) {
            try {
                Map<String, UserRecord> loaded = JSON.readValue(usersFile.toFile(), new TypeReference<>() {});
                users.putAll(loaded);
                log.info("AuthService loaded {} user(s) from {}", users.size(), usersFile);
            } catch (IOException e) {
                // A users.json left ACL-locked by an older build's over-aggressive hardening (a single
                // ALLOW entry for the BUILTIN\\Administrators GROUP, which a non-elevated process token
                // treats as deny-only) is unreadable here. Do NOT rethrow — that fails @PostConstruct and
                // bricks startup, so the desktop shell hangs on a white screen. Fall through to bootstrap a
                // fresh admin so the app still opens; persist() will try to overwrite the stale file.
                log.error(
                    "AuthService: users.json present but unreadable ({}). Re-bootstrapping a fresh "
                        + "admin. If this is an old read-protected file, delete {} manually to keep prior users.",
                    e.toString(), usersFile);
            }
        }
        if (users.isEmpty()) {
            UserRecord admin = new UserRecord();
            // Honour HARNEX_ADMIN_PASSWORD if set; otherwise use the documented first-login credential.
            // The default is intentionally predictable for this single-user runtime: security scans see
            // an auth gate, while humans are not blocked by a hidden credential file. First login
            // is forced through the password-change flow.
            String envPass = System.getenv("HARNEX_ADMIN_PASSWORD");
            String password;
            if (envPass != null && !envPass.isBlank()) {
                password = envPass;
                log.info("AuthService bootstrap: using HARNEX_ADMIN_PASSWORD from env");
            } else {
                password = DEFAULT_PASS;
                log.warn(
                    "\u2554\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2557");
                log.warn("\u2551  AuthService bootstrap: created default user '{}'.             \u2551", DEFAULT_USER);
                log.warn("\u2551  Initial password is '{}'; change it on first login.          \u2551", DEFAULT_PASS);
                log.warn(
                    "\u255a\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u255d");
            }
            admin.setPasswordHash(ENCODER.encode(password));
            admin.setCreatedAt(System.currentTimeMillis());
            // When the operator explicitly provides HARNEX_ADMIN_PASSWORD they've chosen the
            // credential intentionally — don't nag them to change it on first login.
            // Only force-change for the documented default password.
            admin.setMustChangePassword(envPass == null || envPass.isBlank());
            users.put(DEFAULT_USER, admin);
            persist();
        }
    }

    /** True if the user was created with the default password and has not yet changed it. */
    public boolean mustChangePassword(String username) {
        UserRecord u = users.get(username);
        return u != null && u.isMustChangePassword();
    }

    // ---- Captcha ----------------------------------------------------------

    public CaptchaResult generateCaptcha() {
        // Prune old captchas (limit memory)
        long now = System.currentTimeMillis();
        captchas.entrySet().removeIf(e -> now - e.getValue().createdAt > CAPTCHA_TTL_MS);

        int a = RNG.nextInt(20) + 1;
        int b = RNG.nextInt(20) + 1;
        int answer = a + b;
        String text = a + " + " + b + " = ?";

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        captchas.put(id, new CaptchaEntry(answer, now));

        String base64 = renderCaptchaImage(text);
        return new CaptchaResult(id, base64);
    }

    public boolean verifyCaptcha(String captchaId, int userAnswer) {
        if (captchaId == null) {
            return false;
        }
        CaptchaEntry entry = captchas.remove(captchaId);
        if (entry == null) {
            return false;
        }
        if (System.currentTimeMillis() - entry.createdAt > CAPTCHA_TTL_MS) {
            return false;
        }
        return entry.answer == userAnswer;
    }

    // ---- Login / Logout ---------------------------------------------------

    /**
     * Typed login outcomes so callers (controller, CLI, tests) can map to HTTP status / UX without parsing localized
     * error strings. The previous controller path inferred 429 by substring-matching the Chinese phrase “稍后再试” — any
     * reword (i18n, log polish, even a trailing punctuation tweak) silently demoted throttled responses back to 401,
     * defeating client-side backoff. Add a structured field instead.
     */
    public enum LoginErrorCode {
        /** Username/password mismatch, unknown user, or missing field → HTTP 401. */
        BAD_CREDENTIALS,
        /** Per-IP / per-user lockout window active → HTTP 429. */
        THROTTLED,
        /** Throttle map saturated, server refused to accept the attempt → HTTP 503. */
        BUSY,
        /** Malformed request body (null/blank required field) → HTTP 400. */
        BAD_REQUEST
    }

    public record LoginResult(boolean ok, String token, String error, LoginErrorCode errorCode) {
        /** Backwards-compatible 3-arg shim used by older tests / call sites. */
        public LoginResult(boolean ok, String token, String error) {
            this(ok, token, error, ok ? null : LoginErrorCode.BAD_CREDENTIALS);
        }
    }

    public LoginResult login(String username, String password, String captchaId, int captchaAnswer) {
        return login(username, password, captchaId, captchaAnswer, null);
    }

    public LoginResult login(String username, String password, String captchaId, int captchaAnswer, String clientIp) {
        // Captcha is now a pure-frontend UX hint — the SPA validates it locally before POSTing.
        // The previous server-side path treated captchaId as OPTIONAL ("only verify if non-empty"),
        // which let any scripted client trivially bypass it by sending {username,password} with no
        // captcha fields at all. Don't pretend to enforce something we don't, and don't reward
        // captcha-bypass scripts; rely on per-IP/per-username throttling below for abuse control.
        // (captchaId / captchaAnswer kept in the signature for backwards-compatibility only.)
        long now = System.currentTimeMillis();
        String ipKey = (clientIp == null || clientIp.isBlank()) ? "<unknown>" : clientIp;
        String userKey = username == null ? "<null>" : username.toLowerCase(Locale.ROOT);

        // Hard-cap both throttle maps BEFORE inserting. The previous soft sweep (only remove
        // expired+unlocked) could be defeated by a flood of fresh IPs / usernames inside a
        // single LOGIN_WINDOW_MS \u2014 every entry was still in-window and the map kept growing
        // past the cap. Now: soft sweep first, then forced eviction by oldest windowStart down
        // to a 90% high-water mark (preserving locked entries), and finally fail closed if the
        // map STILL cannot accept this attempt's key. Mirrors the pattern in ApiAuthConfig.
        // Validate at the service boundary BEFORE we touch throttle maps so a flood of
        // malformed requests can’t evict legitimate throttle entries. The previous path
        // happily indexed loginFailsByUser under "<null>" / "" and even reached the password
        // hash check with username == null — functional, but noisy in logs and a small
        // pollution vector.
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return new LoginResult(false, null, "用户名或密码不能为空", LoginErrorCode.BAD_REQUEST);
        }

        if (loginFailsByIp.size() >= MAX_LOGIN_THROTTLE_ENTRIES && !loginFailsByIp.containsKey(ipKey)) {
            evictThrottleMap(loginFailsByIp, now, ipKey);
            if (loginFailsByIp.size() >= MAX_LOGIN_THROTTLE_ENTRIES && !loginFailsByIp.containsKey(ipKey)) {
                log.warn("login throttle map saturated \u2014 refusing ip={}", ipKey);
                return new LoginResult(false, null, "\u670d\u52a1\u7e41\u5fd9\uff0c\u8bf7\u7a0d\u5019\u518d\u8bd5",
                    LoginErrorCode.BUSY);
            }
        }
        if (loginFailsByUser.size() >= MAX_LOGIN_THROTTLE_ENTRIES && !loginFailsByUser.containsKey(userKey)) {
            evictThrottleMap(loginFailsByUser, now, userKey);
            if (loginFailsByUser.size() >= MAX_LOGIN_THROTTLE_ENTRIES && !loginFailsByUser.containsKey(userKey)) {
                log.warn("login throttle map saturated \u2014 refusing user={}", userKey);
                return new LoginResult(false, null, "\u670d\u52a1\u7e41\u5fd9\uff0c\u8bf7\u7a0d\u5019\u518d\u8bd5",
                    LoginErrorCode.BUSY);
            }
        }
        LoginFailState ipState = loginFailsByIp.computeIfAbsent(ipKey, k -> new LoginFailState());
        LoginFailState userState = loginFailsByUser.computeIfAbsent(userKey, k -> new LoginFailState());
        if (ipState.isLocked(now) || userState.isLocked(now)) {
            log.warn("login throttled: ip={} user={}", ipKey, userKey);
            return new LoginResult(false, null, "登录失败次数过多，请稍后再试", LoginErrorCode.THROTTLED);
        }

        UserRecord user = users.get(username);
        if (user == null || !ENCODER.matches(password, user.getPasswordHash())) {
            ipState.registerFail(now);
            userState.registerFail(now);
            return new LoginResult(false, null, "用户名或密码错误", LoginErrorCode.BAD_CREDENTIALS);
        }
        // Issue session — prune expired sessions first to bound memory growth.
        // Prune AFTER insert so the new session is included in the cap check, avoiding
        // an off-by-one where size transiently exceeds MAX_SESSIONS.
        // Pass the new token as keepToken so it's never evicted by the hard-cap logic.
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(username, System.currentTimeMillis()));
        pruneExpiredSessions(token);
        ipState.reset();
        userState.reset();
        log.info("User '{}' logged in, session issued (active sessions: {})", username, sessions.size());
        return new LoginResult(true, token, null, null);
    }

    public void logout(String token) {
        Session s = sessions.remove(token);
        if (s != null) {
            log.info("User '{}' logged out", s.username);
        }
    }

    /**
     * Issue a session for the local single user WITHOUT password proof — the boot-token trusted handshake
     * (docs/03 §3.14). Callers must have already verified possession of the boot-token file, which only the local OS
     * user can read; that file read IS the authentication.
     */
    public String issueLocalSession() {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(DEFAULT_USER, System.currentTimeMillis()));
        pruneExpiredSessions(token);
        log.info("Boot-token session issued for '{}' (active sessions: {})", DEFAULT_USER, sessions.size());
        return token;
    }

    /**
     * Validate a bearer token: returns username or null. Slides the idle window on success. The TTL/idle check +
     * lastSeen update are performed inside a per-Session monitor so two concurrent requests cannot race on a token that
     * is on the very edge of expiry.
     */
    public String validateToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Session s = sessions.get(token);
        if (s == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        synchronized (s) {
            // Re-fetch under the monitor — a parallel request may have just removed the entry.
            if (sessions.get(token) != s) {
                return null;
            }
            // Absolute cap: 24h since login
            if (now - s.createdAt > SESSION_TTL_MS) {
                sessions.remove(token);
                return null;
            }
            // Sliding idle: 2h since last activity
            if (now - s.lastSeen > SESSION_IDLE_MS) {
                sessions.remove(token);
                return null;
            }
            s.lastSeen = now;
            return s.username;
        }
    }

    // ---- Session janitor ----------------------------------------------------

    /**
     * Remove sessions that have exceeded their absolute TTL or idle timeout. Called on every login so orphaned tokens
     * (never validated again after expiry) don't accumulate forever. Also enforces a hard cap ({@link #MAX_SESSIONS}) —
     * if still over after pruning expired entries, evict the oldest by creation time, but never evict {@code keepToken}
     * (the just-issued session that the caller is about to return).
     */
    private void pruneExpiredSessions(String keepToken) {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> {
            if (e.getKey().equals(keepToken))
                return false;
            Session s = e.getValue();
            return (now - s.createdAt > SESSION_TTL_MS) || (now - s.lastSeen > SESSION_IDLE_MS);
        });
        // Hard cap: if somehow we still have too many (e.g. many active users), drop oldest.
        if (sessions.size() > MAX_SESSIONS) {
            int excess = sessions.size() - MAX_SESSIONS;
            sessions.entrySet().stream().filter(e -> !e.getKey().equals(keepToken))
                .sorted(Comparator.comparingLong(e -> e.getValue().createdAt)).limit(excess).map(Map.Entry::getKey)
                .toList().forEach(sessions::remove);
            log.warn("Session cap reached — evicted {} oldest sessions", excess);
        }
    }

    // ---- Password change --------------------------------------------------

    public record PasswordResult(boolean ok, String error) {
    }

    public PasswordResult changePassword(String username, String oldPass, String newPass) {
        UserRecord user = users.get(username);
        if (user == null) {
            return new PasswordResult(false, "用户不存在");
        }
        if (!ENCODER.matches(oldPass, user.getPasswordHash())) {
            return new PasswordResult(false, "旧密码错误");
        }
        if (newPass == null || newPass.length() < 8) {
            return new PasswordResult(false, "新密码至少 8 个字符");
        }
        if (newPass.equals(oldPass)) {
            return new PasswordResult(false, "新密码不能与旧密码相同");
        }
        // Mutate in memory, then attempt to persist. If persist fails (disk full, perms, FS
        // gone) we MUST roll back the in-memory hash + mustChangePassword flag — otherwise
        // we'd respond 200 OK, kill all the user's sessions, and on the next restart the
        // password silently reverts to the old value (auth state must be strongly consistent).
        String prevHash = user.getPasswordHash();
        boolean prevMust = user.isMustChangePassword();
        user.setPasswordHash(ENCODER.encode(newPass));
        user.setMustChangePassword(false);
        if (!persist()) {
            user.setPasswordHash(prevHash);
            user.setMustChangePassword(prevMust);
            log.warn("changePassword for '{}' rolled back: persist() failed", username);
            return new PasswordResult(false, "密码修改保存失败，请检查磁盘空间或文件权限后重试");
        }
        // Invalidate all sessions for this user
        sessions.entrySet().removeIf(e -> username.equals(e.getValue().username));
        log.info("User '{}' changed password, all sessions invalidated", username);
        return new PasswordResult(true, null);
    }

    // ---- Internals --------------------------------------------------------

    /**
     * @return true iff users.json was written successfully. Callers that mutate auth state in memory before calling
     *         this MUST roll back on false return — see changePassword.
     */
    private synchronized boolean persist() {
        try {
            // Atomic write: tmp + ATOMIC_MOVE so a SIGKILL mid-write cannot truncate users.json
            // (which would brick the admin login). Mirrors FtsSessionStore's persistence pattern.
            Path tmp = usersFile.resolveSibling(usersFile.getFileName() + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), users);
            try {
                Files.move(tmp, usersFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, usersFile, StandardCopyOption.REPLACE_EXISTING);
            }
            // users.json contains BCrypt password hashes plus active session tokens. World-readable
            // perms here would let any local user offline-attack passwords.
            hardenSensitiveFile(usersFile);
            return true;
        } catch (IOException e) {
            log.error("Failed to persist users.json", e);
            return false;
        }
    }

    private String renderCaptchaImage(String text) {
        int w = 160, h = 50;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Background
        g.setColor(new Color(30, 32, 40));
        g.fillRect(0, 0, w, h);
        // Noise lines
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(60 + RNG.nextInt(60), 60 + RNG.nextInt(60), 80 + RNG.nextInt(60)));
            g.drawLine(RNG.nextInt(w), RNG.nextInt(h), RNG.nextInt(w), RNG.nextInt(h));
        }
        // Text
        g.setFont(new Font("Monospaced", Font.BOLD, 24));
        g.setColor(new Color(160, 170, 255));
        FontMetrics fm = g.getFontMetrics();
        int tx = (w - fm.stringWidth(text)) / 2;
        int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text, tx, ty);
        // Noise dots
        for (int i = 0; i < 40; i++) {
            g.setColor(new Color(RNG.nextInt(200), RNG.nextInt(200), RNG.nextInt(200)));
            g.fillRect(RNG.nextInt(w), RNG.nextInt(h), 2, 2);
        }
        g.dispose();

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            log.error("Captcha render failed", e);
            return "";
        }
    }

    // ---- DTOs / inner records ---------------------------------------------

    @Data
    public static class UserRecord {
        private String passwordHash;
        private long createdAt;
        /** True when the account still has its initial default password. */
        private boolean mustChangePassword;
    }

    /** Mutable session to support sliding idle expiration. */
    static class Session {
        final String username;
        final long createdAt;
        volatile long lastSeen;

        Session(String username, long createdAt) {
            this.username = username;
            this.createdAt = createdAt;
            this.lastSeen = createdAt;
        }
    }

    record CaptchaEntry(int answer, long createdAt) {
    }

    public record CaptchaResult(String captchaId, String image) {
    }
}
