package com.harnex.config;

import java.nio.file.Path;

/**
 * Resolves the stable user-level Harnex data root.
 *
 * <p>This location must never fall back to the active workspace data directory: a
 * Workspace JVM is disposable, while optional authentication, user configuration,
 * the user profile and user Skill state must survive every workspace replacement.</p>
 */
public final class UserDataPaths {

    private UserDataPaths() {}

    public static Path resolve(AgentConfig cfg) {
        String configured = cfg == null ? null : cfg.getUserDataDir();
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("HARNEX_USER_DATA_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home", "."), ".harnex").toString();
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
