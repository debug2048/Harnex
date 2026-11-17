package com.harnex.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.HarnexApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.system.ApplicationHome;

/** Collect only generated SSH artifacts; project data, configuration and externally installed JARs are never visited. */
final class RuntimeCacheCleanup {
    static final Duration PREPARATION_GRACE = Duration.ofHours(2);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LinkOption[] NO_LINKS = {LinkOption.NOFOLLOW_LINKS};
    private RuntimeCacheCleanup() {}

    static void afterControl(Path user) {
        Path cache = user.resolve("runtime-cache").toAbsolutePath().normalize();
        if (!Files.isDirectory(cache, NO_LINKS) || Files.isSymbolicLink(cache)
            || Files.isSymbolicLink(cache.resolve(".maintenance.lock"))) return;
        // flock coordinates with native clients without adding another SSH or JVM startup on a warm connection.
        Process guard = null;
        try {
            guard = new ProcessBuilder("flock", "-n", "-x", cache.resolve(".maintenance.lock").toString(),
                "sh", "-c", "printf 'ready\\n'; cat >/dev/null").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (guard.isAlive() && guard.getInputStream().available() < 6 && System.nanoTime() < deadline) Thread.sleep(10);
            if (guard.getInputStream().available() < 6 || !"ready\n".equals(new String(guard.getInputStream().readNBytes(6), StandardCharsets.US_ASCII))) return;
            Set<String> protectedHashes = protectedBuilds(user, cache);
            var source = new ApplicationHome(HarnexApplication.class).getSource();
            if (source != null) protectPath(protectedHashes, cache, source.toPath());
            prune(cache, protectedHashes, Instant.now());
        } catch (Exception unavailable) {
            // Never turn successful connection/upgrade/shutdown into a failure, or guess when ownership is uncertain.
            System.err.println("Runtime cache cleanup deferred: " + unavailable.getClass().getSimpleName());
        } finally {
            if (guard != null) {
                try { guard.getOutputStream().close(); if (!guard.waitFor(1, TimeUnit.SECONDS)) guard.destroyForcibly(); }
                catch (Exception ignored) { guard.destroyForcibly(); }
            }
        }
    }

    static Set<String> protectedBuilds(Path user, Path cache) throws IOException {
        Set<String> result = protectedOwnerBuilds(user);
        String userName = ProcessHandle.current().info().user().orElseThrow(() -> new IOException("process owner is unavailable"));
        // Also protect independently launched Java processes using this cache, not just managed remote.json owners.
        try (var processes = ProcessHandle.allProcesses()) {
            for (ProcessHandle process : processes.toList()) {
                var info = process.info();
                if (!userName.equals(info.user().orElse(null))) continue;
                String executable = info.command().orElse("");
                if (!Path.of(executable).getFileName().toString().matches("java(?:\\.exe)?")) continue;
                String[] args = info.arguments().orElseThrow(() -> new IOException("Java process arguments are unavailable"));
                for (String arg : args) {
                    if (arg.endsWith(".jar")) {
                        Path path = Path.of(arg);
                        if (!path.isAbsolute()) path = Path.of("/proc", String.valueOf(process.pid()), "cwd").resolve(path).toRealPath();
                        protectPath(result, cache, path);
                    }
                }
            }
        }
        return result;
    }

    static Set<String> protectedOwnerBuilds(Path user) throws IOException {
        Set<String> result = new HashSet<>();
        Path domains = user.resolve("runtime-domains");
        if (Files.isSymbolicLink(domains)) throw new IOException("runtime domain directory is not verifiable");
        if (Files.isDirectory(domains, NO_LINKS)) try (var entries = Files.list(domains)) {
            for (Path domain : entries.toList()) {
                if (Files.isSymbolicLink(domain)) throw new IOException("runtime domain is not verifiable");
                Path record = domain.resolve("remote.json");
                if (!Files.exists(record, NO_LINKS)) continue;
                if (!Files.isRegularFile(record, NO_LINKS) || Files.size(record) > 65536) throw new IOException("remote owner record is not verifiable");
                var value = JSON.readTree(record.toFile());
                long pid = value.path("pid").asLong(0);
                if (pid <= 0) throw new IOException("remote owner process is not verifiable");
                var process = ProcessHandle.of(pid);
                if (process.isEmpty() || !process.get().isAlive()) continue;
                var start = process.get().info().startInstant();
                if (start.isEmpty()) throw new IOException("remote owner start time is not verifiable");
                if (!start.get().toString().equals(value.path("processStart").asText())) continue;
                // A live older owner may have been opened through another cached control JAR. Keep all until it is reconnected.
                for (String field : new String[]{"runtimeSha256", "controlSha256"}) {
                    String hash = value.path(field).asText();
                    if (!validHash(hash)) throw new IOException("live legacy Runtime build is not verifiable");
                    result.add(hash);
                }
            }
        }
        return result;
    }

    private static void protectPath(Set<String> hashes, Path cache, Path file) throws IOException {
        Path actual = file.toRealPath();
        if (actual.getParent().equals(cache.toRealPath())) {
            String name = actual.getFileName().toString();
            if (name.endsWith(".jar") && validHash(name.substring(0, name.length()-4))) hashes.add(name.substring(0, name.length()-4));
        }
    }

    private record Artifact(String hash, Path jar, Instant used) {}
    static int prune(Path directory, Set<String> protectedHashes, Instant now) throws IOException {
        if (Files.isSymbolicLink(directory)) return 0;
        Path cache = directory.toRealPath();
        Path usage = cache.resolve(".usage");
        if (Files.isSymbolicLink(usage)) return 0;
        var artifacts = new ArrayList<Artifact>();
        try (var entries = Files.list(cache)) {
            for (Path jar : entries.toList()) {
                String name = jar.getFileName().toString();
                if (!name.endsWith(".jar") || !validHash(name.substring(0,name.length()-4)) || !Files.isRegularFile(jar, NO_LINKS)) continue;
                String hash = name.substring(0,name.length()-4);
                Instant used = Files.getLastModifiedTime(jar, NO_LINKS).toInstant();
                Path pin = usage.resolve(hash);
                if (Files.isSymbolicLink(pin)) continue;
                if (Files.isRegularFile(pin, NO_LINKS)) {
                    Instant pinned = Files.getLastModifiedTime(pin, NO_LINKS).toInstant();
                    if (pinned.isAfter(used)) used = pinned;
                }
                artifacts.add(new Artifact(hash,jar,used));
            }
        }
        artifacts.sort(Comparator.comparing(Artifact::used).reversed().thenComparing(Artifact::hash));
        int deleted = 0;
        for (int index=2; index<artifacts.size() && deleted<32; index++) {
            Artifact item = artifacts.get(index);
            if (protectedHashes.contains(item.hash()) || !item.used().isBefore(now.minus(PREPARATION_GRACE))) continue;
            Path tools = cache.resolve(item.hash()), bin = tools.resolve("bin"), rg = bin.resolve("rg");
            if (Files.isSymbolicLink(tools) || Files.isSymbolicLink(bin) || Files.isSymbolicLink(rg)) continue;
            // Delete only our known files and empty directories. Unexpected contents are preserved.
            if (!item.jar().toRealPath().getParent().equals(cache)) continue;
            Files.deleteIfExists(rg);
            deleteEmpty(bin); deleteEmpty(tools);
            Files.delete(item.jar()); Files.deleteIfExists(usage.resolve(item.hash())); deleted++;
        }
        return deleted;
    }
    private static void deleteEmpty(Path path) throws IOException {
        if (!Files.isDirectory(path, NO_LINKS)) return;
        try (var contents = Files.list(path)) { if (contents.findAny().isPresent()) return; }
        Files.delete(path);
    }
    private static boolean validHash(String value) { return value.matches("[0-9a-f]{64}"); }
}
