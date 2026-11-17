package com.harnex.remote;

import com.harnex.HarnexApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.boot.system.ApplicationHome;

/** Immutable identity of the code loaded by this process, computed once rather than on each probe. */
public final class RuntimeBuild {
    private RuntimeBuild() {}
    // Packaged native hosts verify every bundle file before launching the JVM.
    // Reuse that result instead of hashing the same ~100 MB JAR again on the
    // first protocol request. Bare JAR and remote launches still hash their source.
    private static final String HASH = resolveHash(System.getProperty("harnex.runtime.verified-sha256"), RuntimeBuild::hashSource);
    public static String sha256() { return HASH; }
    public static String version() {
        String version = HarnexApplication.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "1.0.0-SNAPSHOT" : version;
    }
    static String resolveHash(String verified, Supplier<String> sourceHash) {
        return verified != null && verified.matches("[a-fA-F0-9]{64}")
            ? verified.toLowerCase(Locale.ROOT) : sourceHash.get();
    }
    private static String hashSource() {
        try {
            var source = new ApplicationHome(HarnexApplication.class).getSource();
            return source == null || !source.isFile() ? "" : hash(source.toPath());
        } catch (Exception unavailable) { return ""; }
    }
    static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
