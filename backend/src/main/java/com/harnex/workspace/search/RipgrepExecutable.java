package com.harnex.workspace.search;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.system.ApplicationHome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Locate rg from Runtime code, independent of the client and the agent's workspace/cwd. */
public final class RipgrepExecutable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RipgrepExecutable() {}

    public static String resolve() {
        ApplicationHome home = new ApplicationHome(RipgrepExecutable.class);
        // ApplicationHome falls back to cwd when it cannot identify the code source (e.g. JUnit).
        // Do not search that fallback: an opened workspace is not an application installation.
        Path applicationDir = home.getSource() == null ? null : home.getDir().toPath();
        return resolve(System.getenv("HARNEX_RG_PATH"), Path.of(System.getProperty("java.home")), applicationDir,
            System.getProperty("os.name", ""), System.getProperty("os.arch", ""), System.getenv("PATH"));
    }

    static String resolve(String configured, Path javaHome, Path applicationDir, String osName, String osArch,
        String searchPath) {
        // An explicit override is authoritative; report a stale override instead of silently substituting another rg.
        if (configured != null && !configured.isBlank()) {
            try {
                return Path.of(configured).toAbsolutePath().normalize().toString();
            } catch (InvalidPathException ignored) {
                return configured; // GrepTool reports invalid overrides when invoked, without breaking Runtime startup.
            }
        }
        String target = target(osName, osArch);
        boolean windows = osName.toLowerCase(Locale.ROOT).startsWith("windows");
        String name = windows ? "rg.exe" : "rg";
        List<Path> candidates = new ArrayList<>();
        if (applicationDir != null) candidates.add(applicationDir.resolve("bin").resolve(name));
        candidates.add(javaHome.resolve("../bin").resolve(name));

        Path root = sourceCheckout(applicationDir);
        if (root != null && target != null) {
            candidates.add(root.resolve("dist/build-tools/ripgrep").resolve(target).resolve("bin").resolve(name));
            JsonNode metadata = readJson(root.resolve("package.json"));
            String version = metadata == null ? "" : metadata.path("version").asText();
            if (version.matches("[0-9A-Za-z.+_-]+")) {
                addBundle(candidates, root.resolve("dist/runtime-bundles").resolve(version + "-" + target), target, name);
            }
            addBundle(candidates, root.resolve("src-tauri/resources"), target, name);
            addBundle(candidates, root.resolve("extension-runtime/runtime"), target, name);
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize().toString();
        }
        // Resolve PATH to an absolute executable, so changing the workspace cannot change what gets launched.
        for (String entry : (searchPath == null ? "" : searchPath).split(Pattern.quote(File.pathSeparator))) {
            String directory = entry.trim();
            if (directory.startsWith("\"") && directory.endsWith("\"") && directory.length() > 1) {
                directory = directory.substring(1, directory.length() - 1);
            }
            if (directory.isBlank()) continue;
            try {
                Path pathDirectory = Path.of(directory);
                if (!pathDirectory.isAbsolute()) continue;
                Path candidate = pathDirectory.resolve(name);
                if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize().toString();
            } catch (InvalidPathException ignored) {
                // A malformed PATH entry must not hide a later valid entry.
            }
        }
        // Never launch a bare name: Windows could execute an opened workspace's rg.exe.
        // Keep tool registration available; GrepTool reports the missing dependency when called.
        return null;
    }

    private static Path sourceCheckout(Path applicationDir) {
        if (applicationDir == null) return null;
        Path directory = applicationDir.toAbsolutePath().normalize();
        if (directory.endsWith("target/classes")) directory = directory.getParent();
        if (!directory.endsWith("backend/target")) return null;
        Path root = directory.getParent().getParent();
        return Files.isRegularFile(root.resolve("package.json")) && Files.isRegularFile(root.resolve("backend/pom.xml"))
            ? root : null;
    }

    private static void addBundle(List<Path> candidates, Path directory, String target, String name) {
        JsonNode manifest = readJson(directory.resolve("runtime-bundle.json"));
        if (manifest != null && target.equals(manifest.path("target").asText())) {
            candidates.add(directory.resolve("bin").resolve(name));
        }
    }

    private static JsonNode readJson(Path file) {
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException ignored) {
            return null;
        }
    }

    static String target(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String platform = os.startsWith("windows") ? "win32"
            : os.contains("mac") || os.equals("darwin") ? "darwin" : os.contains("linux") ? "linux" : null;
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        return platform == null || arch == null ? null : platform + "-" + arch;
    }
}
