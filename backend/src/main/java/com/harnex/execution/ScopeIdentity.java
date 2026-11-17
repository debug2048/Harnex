package com.harnex.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.harnex.config.AgentConfig;
import com.harnex.config.RuntimeProfile;
import com.harnex.config.UserDataPaths;

/** Immutable authenticated data owner. A personal scope does not require a workspace resolver. */
@Component
public final class ScopeIdentity {
    private final String scopeId;
    private final String instanceId;
    private final RuntimeProfile profile;
    private final Path projectRoot;

    public ScopeIdentity(AgentConfig config) {
        profile = config.getRuntimeProfile();
        String root = config.getWorkspace() == null ? null : config.getWorkspace().getRoot();
        if (root == null || root.isBlank()) root = System.getenv("HARNEX_WORKSPACE");
        if (root == null || root.isBlank()) root = System.getProperty("user.dir", ".");
        projectRoot = profile == RuntimeProfile.PROJECT ? canonicalProjectRoot(root) : null;
        String supplied = System.getenv("HARNEX_SCOPE_ID");
        scopeId = supplied != null && !supplied.isBlank() ? supplied.trim()
            : stableId(profile == RuntimeProfile.PERSONAL ? "personal:" + canonicalUserRoot(UserDataPaths.resolve(config)) : projectRoot.toString());
        String instance = System.getenv("HARNEX_INSTANCE_ID");
        instanceId = instance == null || instance.isBlank() ? UUID.randomUUID().toString() : instance.trim();
    }
    private static Path canonicalProjectRoot(String root) {
        try { return Path.of(root).toRealPath(); }
        catch (IOException failure) { throw new IllegalStateException("Project root is unavailable: " + root, failure); }
    }
    private static Path canonicalUserRoot(Path root) {
        Path ancestor = root;
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor == null) return root;
        try { return ancestor.toRealPath().resolve(ancestor.relativize(root)).normalize(); }
        catch (IOException failure) { throw new IllegalStateException("Personal data root is unavailable", failure); }
    }
    public String scopeId() { return scopeId; }
    public String instanceId() { return instanceId; }
    public RuntimeProfile profile() { return profile; }
    public Path projectRoot() { return projectRoot; }
    public static String stableId(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 6); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
