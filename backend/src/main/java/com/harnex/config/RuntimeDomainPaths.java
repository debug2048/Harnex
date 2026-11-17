package com.harnex.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.regex.Pattern;

/** Resolves the host-owned Runtime Domain namespace without ever using the user-data root itself as runtime state. */
public final class RuntimeDomainPaths {

    private static final Pattern DOMAIN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private RuntimeDomainPaths() {}

    public static String id(AgentConfig cfg) {
        String value = cfg == null ? null : cfg.getRuntimeDomainId();
        if (value == null || value.isBlank()) value = System.getenv("HARNEX_RUNTIME_DOMAIN_ID");
        if (value == null || value.isBlank()) value = "standalone-" + ProcessHandle.current().pid();
        value = value.trim();
        if (!DOMAIN_ID.matcher(value).matches() || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("invalid runtimeDomainId: " + value);
        }
        return value;
    }

    public static Path resolve(AgentConfig cfg) {
        String configured = cfg == null ? null : cfg.getRuntimeDomainDir();
        if (configured == null || configured.isBlank()) configured = System.getenv("HARNEX_RUNTIME_DOMAIN_DIR");
        Path result;
        if (configured == null || configured.isBlank()) {
            Path domainsRoot = UserDataPaths.resolve(cfg).resolve("runtime-domains").toAbsolutePath().normalize();
            result = domainsRoot.resolve(id(cfg)).normalize();
            if (!result.getParent().equals(domainsRoot)) {
                throw new IllegalArgumentException("runtimeDomainId escapes runtime-domains root");
            }
        } else {
            result = Path.of(configured).toAbsolutePath().normalize();
        }
        Path userData = UserDataPaths.resolve(cfg);
        boolean sameDirectory = result.equals(userData);
        if (!sameDirectory && Files.exists(result) && Files.exists(userData)) {
            try {
                sameDirectory = result.toRealPath().equals(userData.toRealPath());
            } catch (IOException ignored) {
                // Fall back to the normalized comparison when canonicalisation is unavailable.
            }
        }
        if (sameDirectory) {
            throw new IllegalArgumentException("runtimeDomainDir must not equal userDataDir");
        }
        return result;
    }

    public static Path ensurePrivateDirectory(AgentConfig cfg) throws IOException {
        Path dir = resolve(cfg);
        Files.createDirectories(dir);
        hardenDirectory(dir);
        return dir;
    }

    /** Resolve a manifest-owned relative file and reject absolute/traversing values. */
    public static Path resolveRelative(Path domainDir, String value) {
        if (domainDir == null || value == null || value.isBlank()) {
            throw new IllegalArgumentException("domain relative path is required");
        }
        Path relative = Path.of(value);
        if (relative.isAbsolute()) throw new IllegalArgumentException("domain path must be relative: " + value);
        Path base = domainDir.toAbsolutePath().normalize();
        Path result = base.resolve(relative).normalize();
        if (!result.startsWith(base) || result.equals(base)) {
            throw new IllegalArgumentException("domain path escapes runtimeDomainDir: " + value);
        }
        return result;
    }

    private static void hardenDirectory(Path dir) {
        try {
            if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(dir, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                return;
            }
        } catch (Exception ignored) {
            // Best effort on filesystems without POSIX permissions.
        }
        try {
            dir.toFile().setReadable(false, false);
            dir.toFile().setReadable(true, true);
            dir.toFile().setWritable(false, false);
            dir.toFile().setWritable(true, true);
            dir.toFile().setExecutable(false, false);
            dir.toFile().setExecutable(true, true);
        } catch (Exception ignored) {
            // Best effort; the host performs the platform-specific ACL hardening as well.
        }
    }
}
