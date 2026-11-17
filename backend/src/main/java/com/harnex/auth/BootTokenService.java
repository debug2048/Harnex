package com.harnex.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.harnex.config.AgentConfig;
import com.harnex.config.RuntimeDomainPaths;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Local trusted-boot handshake (docs/03 §3.14): on startup the runtime writes a fresh random token to
 * {@code <runtimeDomainDir>/boot-token}, readable only by the OS user running the process. Local clients (CLI / TUI / VS Code /
 * Tauri shell / first browser open) read that file and exchange it for a normal session via
 * {@code POST /api/v1/auth/boot-token} — no interactive login. Same trust model as Chrome DevTools / JetBrains local
 * ports: the ability to read the file IS the authentication.
 *
 * <p>
 * The token is regenerated on every boot, so a leaked value dies with the process. The interactive login/password
 * system remains available for remote/shared deployments.
 * </p>
 */
@Slf4j
@Service
public class BootTokenService {

    public static final String FILE_NAME = "boot-token";

    private final AgentConfig config;
    private final Path file;
    private volatile byte[] tokenSha256 = new byte[0];

    public BootTokenService(AgentConfig cfg) {
        this.config = cfg;
        this.file = RuntimeDomainPaths.resolveRelative(RuntimeDomainPaths.resolve(cfg), FILE_NAME);
    }

    @PostConstruct
    void init() {
        Path tmp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid()
            + "." + UUID.randomUUID() + ".tmp");
        try {
            RuntimeDomainPaths.ensurePrivateDirectory(config);
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            Files.writeString(tmp, token, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
            harden(tmp);
            moveAtomically(tmp, file);
            harden(file);
            this.tokenSha256 = sha256(token);
            log.info("Boot token written to {} (regenerated each start)", file);
        } catch (Exception e) {
            // Fail-open for the FEATURE, fail-closed for the TOKEN: without a file there is nothing
            // to exchange, matches() can never succeed, and interactive login remains the only path.
            this.tokenSha256 = new byte[0];
            log.warn("Boot token could not be written ({}); local trusted boot disabled this run", e.toString());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // Best-effort cleanup; a host-owned stale-domain sweep handles crash leftovers.
            }
        }
    }

    @PreDestroy
    void close() {
        tokenSha256 = new byte[0];
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.warn("Boot token cleanup failed for {}: {}", file, e.getMessage());
        }
    }

    Path file() {
        return file;
    }

    /** Constant-time check of a candidate against the current boot token; false when disabled. */
    public boolean matches(String candidate) {
        if (candidate == null || candidate.isBlank() || tokenSha256.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(tokenSha256, sha256(candidate));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            return new byte[0];
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Best-effort: restrict to the current OS user (POSIX 600; on Windows fall back to owner-only readability). */
    private static void harden(Path file) {
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                return;
            }
        } catch (Exception ignored) {
            /* fall through */ }
        try {
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
            file.toFile().setWritable(false, false);
            file.toFile().setWritable(true, true);
        } catch (Exception ignored) {
            /* best effort */ }
    }
}
