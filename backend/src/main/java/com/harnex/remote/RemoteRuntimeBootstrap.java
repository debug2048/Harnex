package com.harnex.remote;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.HarnexApplication;
import org.springframework.boot.system.ApplicationHome;

/** SSH invokes this small, non-Spring control process. Only its owned detached Runtime may be resumed/stopped. */
public final class RemoteRuntimeBootstrap {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .proxy(new ProxySelector() {
            public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
        }).build();
    private RemoteRuntimeBootstrap() {}

    public static void main(String[] args) {
        try {
            Map<String,Object> result = dispatch(args);
            if (List.of("open", "upgrade", "close").contains(args[0])) RuntimeCacheCleanup.afterControl(Path.of(System.getProperty("user.home"), ".harnex"));
            System.out.println(JSON.writeValueAsString(result));
        }
        catch (Exception failure) {
            System.err.println("remote runtime: " + failure.getMessage());
            System.exit(1);
        }
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (i + 1 >= args.length || !List.of("--workspace", "--domain", "--instance").contains(args[i])
                || values.put(args[i], args[i + 1]) != null)
                throw new IllegalArgumentException("invalid or duplicate remote option");
        }
        return values;
    }

    private static Map<String, Object> dispatch(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("remote operation is required");
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"))
            throw new IllegalStateException("Remote Runtime hosts currently require Linux and Java 17+; clients may use Windows, macOS or Linux");
        Map<String, String> opts = options(args);
        Path user = Path.of(System.getProperty("user.home"), ".harnex");
        privateDirectory(user);
        if ("probe".equals(args[0])) return Map.of("platform", System.getProperty("os.name"),
            "arch", System.getProperty("os.arch"), "java", Runtime.version().feature(),
            "rg", commandAvailable("rg"), "setsid", commandAvailable("setsid"));
        // Closing uses the canonical path bound at connection time. A renamed/deleted directory must not strand its Runtime.
        Path root = "close".equals(args[0]) ? controlRoot(opts.get("--workspace")) : resolveRoot(opts.get("--workspace"));
        String workspaceId = workspaceId(user.toRealPath(), root);
        if ("describe".equals(args[0]) || "directories".equals(args[0])) {
            Map<String, Object> result = new LinkedHashMap<>(Map.of("root", root.toString(), "workspaceId", workspaceId));
            if ("directories".equals(args[0])) {
                try (var stream = Files.list(root)) {
                    result.put("directories", stream.filter(Files::isDirectory).sorted().limit(500)
                        .map(path -> Map.of("name", path.getFileName().toString(), "path", path.toString())).toList());
                }
                result.put("parentPath", root.getParent() == null ? null : root.getParent().toString());
            }
            return result;
        }
        String domainId = opts.get("--domain");
        if (domainId == null || !domainId.matches("remote-[a-zA-Z0-9_-]{8,96}"))
            throw new IllegalArgumentException("invalid remote owner domain");
        Path dir = user.resolve("runtime-domains").resolve(domainId);
        privateDirectory(dir);
        Path record = dir.resolve("remote.json");
        try (FileChannel channel = FileChannel.open(dir.resolve("bootstrap.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("another connection is opening this remote domain; retry");
            try (lock) {
                Map<String, Object> previous = Files.isRegularFile(record) ? JSON.readValue(record.toFile(), MAP) : null;
                if (previous != null && live(previous)) {
                    if (!root.toString().equals(previous.get("root")))
                        throw new IllegalStateException("remote domain still owns another workspace; close it first");
                    if ("close".equals(args[0])) {
                        if (!String.valueOf(previous.get("instanceId")).equals(opts.get("--instance")))
                            throw new IllegalStateException("remote instance changed; refusing to stop it");
                        close(previous);
                        previous.put("closed", true);
                        writePrivate(record, previous);
                        return Map.of("closed", true);
                    }
                    if ("upgrade".equals(args[0])) {
                        if (!String.valueOf(previous.get("instanceId")).equals(opts.get("--instance")))
                            throw new IllegalStateException("remote instance changed; reconnect before updating");
                        assertIdentity(previous);
                        Map<String, Object> running = withBuild(previous);
                        if (!Boolean.TRUE.equals(running.get("updateAvailable"))) return running;
                        if (!Boolean.TRUE.equals(running.get("canUpgrade")))
                            throw new IllegalStateException("UPGRADE_UNSUPPORTED: this Runtime cannot stop atomically while idle; explicitly stop the old service and reconnect to update");
                        HttpResponse<String> stopped = HTTP.send(request(previous, "/api/v1/admin/upgrade-stop-idle")
                            .header("X-Harnex-Supervisor-Token", String.valueOf(previous.get("supervisorToken")))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                        if (stopped.statusCode() == 409) throw new IllegalStateException("UPGRADE_BUSY: remote Runtime has active work; wait for it to finish before updating. No task was cancelled");
                        if (stopped.statusCode() != 200) throw new IllegalStateException("remote idle upgrade was not acknowledged; service was not replaced");
                        terminate(previous);
                        if (live(previous)) throw new IllegalStateException("old Runtime stop could not be confirmed; new version was not started");
                        return start(user, dir, root, workspaceId, domainId, record);
                    }
                    if (!"open".equals(args[0])) throw new IllegalArgumentException("unknown remote operation");
                    assertIdentity(previous);
                    Map<String,Object> result = withBuild(previous);
                    // The live JVM and the control client can use different builds until an explicit upgrade.
                    previous.put("controlSha256", RuntimeBuild.sha256());
                    previous.put("runtimeSha256", result.get("runtimeSha256"));
                    writePrivate(record, previous);
                    return result;
                }
                if ("close".equals(args[0])) {
                    if (previous == null || !root.toString().equals(previous.get("root"))
                        || !opts.getOrDefault("--instance", "").equals(previous.get("instanceId"))) {
                        throw new IllegalStateException("no matching remote instance record; shutdown cannot be confirmed");
                    }
                    return Map.of("closed", true);
                }
                if ("upgrade".equals(args[0])) throw new IllegalStateException("remote instance is no longer running; reconnect to start the current version");
                if (!"open".equals(args[0])) throw new IllegalArgumentException("unknown remote operation");
                return start(user, dir, root, workspaceId, domainId, record);
            }
        }
    }

    static Path controlRoot(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("bound remote workspace path is required");
        Path path = Path.of(input);
        if (!path.isAbsolute()) throw new IllegalArgumentException("bound remote workspace path must be absolute");
        return path.normalize();
    }

    static Path resolveRoot(String input) throws IOException {
        if (input == null || input.isBlank()) input = System.getProperty("user.home");
        Path path = input.equals("~") ? Path.of(System.getProperty("user.home"))
            : input.startsWith("~/") ? Path.of(System.getProperty("user.home")).resolve(input.substring(2)) : Path.of(input);
        if (!path.isAbsolute()) throw new IllegalArgumentException("remote workspace must be an absolute path or ~/path");
        path = path.toRealPath();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("remote workspace is not a directory");
        return path;
    }

    static String workspaceId(Path user, Path root) throws Exception {
        Path hostId = user.resolve("host-id");
        if (!Files.exists(hostId)) {
            try { Files.writeString(hostId, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException race) { /* concurrent first connection */ }
        }
        String identity = Files.readString(hostId).trim();
        if (identity.isEmpty()) throw new IllegalStateException("remote host identity is being initialized; retry");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((identity + "\n" + root).getBytes(StandardCharsets.UTF_8))).substring(0, 24);
    }

    private static Map<String, Object> start(Path user, Path dir, Path root, String workspaceId,
        String domainId, Path record) throws Exception {
        if (!commandAvailable("setsid")) throw new IllegalStateException("install util-linux (setsid) on the remote host");
        Path jar = new ApplicationHome(HarnexApplication.class).getSource().toPath().toRealPath();
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("remote bootstrap requires the packaged executable JAR");
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        String supervisor = UUID.randomUUID().toString();
        String instance = UUID.randomUUID().toString();
        Path data = root.resolve(".harnex/data");
        privateDirectory(data);
        List<String> command = new ArrayList<>(List.of("setsid", Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms128m", "-Xmx512m", "-XX:TieredStopAtLevel=1", "-jar", jar.toString(), "--server.port=" + port,
            "--server.address=127.0.0.1", "--harnex.auth.enabled=true", "--harnex.data-dir=" + data,
            "--harnex.user-data-dir=" + user, "--harnex.runtime-domain-id=" + domainId,
            "--harnex.runtime-domain-dir=" + dir, "--harnex.runtime-owner-kind=standalone",
            "--harnex.cors.allowed-origins[0]=http://tauri.localhost", "--harnex.cors.allowed-origins[1]=tauri://localhost",
            "--harnex.cors.allowed-origins[2]=https://tauri.localhost", "--harnex.cors.allow-credentials=false"));
        Path config = user.resolve("agent-config.yml");
        if (Files.isRegularFile(config)) command.add("--spring.config.additional-location=" + config.toUri());
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("runtime.log").toFile()))
            .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
        Map<String, String> env = builder.environment();
        // Never inherit the connection client's local configuration, ownership or routing into a new Runtime.
        env.keySet().removeIf(key -> key.startsWith("HARNEX_") || key.startsWith("SPRING_"));
        String jarName = jar.getFileName().toString();
        Path searchTool = jar.resolveSibling(jarName.replaceFirst("\\.jar$", "")).resolve("bin/rg");
        if (Files.isRegularFile(searchTool) && Files.isExecutable(searchTool)) {
            env.put("HARNEX_RG_PATH", searchTool.toString());
        }
        env.put("HARNEX_AUTH_TOKENS", token);
        env.put("HARNEX_INTERNAL_SUPERVISOR_TOKEN", supervisor);
        env.put("HARNEX_WORKSPACE", root.toString());
        env.put("HARNEX_SCOPE_ID", workspaceId);
        env.put("HARNEX_INSTANCE_ID", instance);
        Process process = builder.start();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("root", root.toString());
        result.put("workspaceId", workspaceId);
        result.put("instanceId", instance);
        result.put("domainId", domainId);
        result.put("port", port);
        result.put("pid", process.pid());
        result.put("processStart", process.info().startInstant().orElseThrow().toString());
        result.put("token", token);
        result.put("supervisorToken", supervisor);
        result.put("protocolVersion", "1.0");
        result.put("runtimeSha256", RuntimeBuild.sha256());
        result.put("controlSha256", RuntimeBuild.sha256());
        result.put("runtimeVersion", RuntimeBuild.version());
        result.put("ownership", "managed");
        long deadline = System.nanoTime() + Duration.ofSeconds(110).toNanos();
        try {
            // Publish ownership before waiting so a failed SSH client can recover the same process, never start a duplicate.
            writePrivate(record, result);
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IllegalStateException("remote Runtime exited; inspect " + dir.resolve("runtime.log"));
                try { assertIdentity(result); return withBuild(result); }
                catch (IOException unavailable) { Thread.sleep(250); }
            }
            throw new IllegalStateException("remote Runtime startup timed out; inspect " + dir.resolve("runtime.log"));
        } catch (Exception failure) {
            if (live(result)) terminate(result);
            if (live(result)) {
                // Keep the record when shutdown is unconfirmed; a later connection must not start a replacement.
                writePrivate(record, result);
                throw new IllegalStateException("remote startup failed and process stop is unconfirmed; ownership retained in " + record, failure);
            }
            Files.deleteIfExists(record);
            throw failure;
        }
    }

    static boolean live(Map<String, Object> record) {
        try {
            return ProcessHandle.of(((Number)record.get("pid")).longValue()).filter(ProcessHandle::isAlive)
                .flatMap(handle -> handle.info().startInstant()).map(time -> time.equals(Instant.parse((String)record.get("processStart"))))
                .orElse(false);
        } catch (Exception invalid) { return false; }
    }

    private static HttpRequest.Builder request(Map<String, Object> record, String path) {
        int port = ((Number)record.get("port")).intValue();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid remote Runtime port");
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(4))
            .header("Authorization", "Bearer " + record.get("token"))
            .header("X-Harnex-Scope-Id", String.valueOf(record.get("workspaceId")))
            .header("X-Harnex-Instance-Id", String.valueOf(record.get("instanceId")));
    }

    private static void assertIdentity(Map<String, Object> record) throws Exception {
        HttpResponse<String> response = HTTP.send(request(record, "/api/v1/workspace").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("remote Runtime is not ready: HTTP " + response.statusCode());
        Map<String, Object> body = JSON.readValue(response.body(), MAP);
        if (!record.get("workspaceId").equals(body.get("workspaceId")) || !record.get("instanceId").equals(body.get("instanceId"))
            || !record.get("root").equals(body.get("root"))) throw new IllegalStateException("remote Runtime identity mismatch");
    }

    private static Map<String, Object> withBuild(Map<String, Object> record) throws Exception {
        HttpResponse<String> response = HTTP.send(request(record, "/api/v1/protocol").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("remote Runtime build information is unavailable");
        Map<String, Object> protocol = JSON.readValue(response.body(), MAP);
        if (!"harnex-local-api".equals(protocol.get("name")) || !"1.0".equals(protocol.get("version"))
            || !"PROJECT".equals(protocol.get("runtimeProfile"))) throw new IOException("remote Runtime protocol is incompatible");
        String running = String.valueOf(protocol.getOrDefault("runtimeSha256", ""));
        Map<String, Object> result = new LinkedHashMap<>(record);
        result.put("ownership", "managed"); result.put("runtimeSha256", running);
        result.put("runtimeVersion", String.valueOf(protocol.getOrDefault("runtimeVersion", "unknown")));
        result.put("targetSha256", RuntimeBuild.sha256()); result.put("targetVersion", RuntimeBuild.version());
        result.put("updateAvailable", running.isBlank() || !running.equals(RuntimeBuild.sha256()));
        result.put("canUpgrade", protocol.get("capabilities") instanceof List<?> capabilities && capabilities.contains("runtime.idle-upgrade"));
        return result;
    }

    private static void close(Map<String, Object> record) throws Exception {
        assertIdentity(record);
        HttpResponse<String> response = HTTP.send(request(record, "/api/v1/admin/drain?timeoutMs=10000")
            .timeout(Duration.ofSeconds(15)).header("X-Harnex-Supervisor-Token", String.valueOf(record.get("supervisorToken")))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("remote drain was not acknowledged");
        terminate(record);
        if (live(record)) throw new IllegalStateException("remote process stop is not confirmed");
    }

    private static void terminate(Map<String, Object> record) throws Exception {
        if (!live(record)) return;
        String group = "-" + ((Number)record.get("pid")).longValue();
        new ProcessBuilder("kill", "-TERM", "--", group).start().waitFor(3, TimeUnit.SECONDS);
        for (int i = 0; i < 60 && live(record); i++) Thread.sleep(100);
        if (live(record)) new ProcessBuilder("kill", "-KILL", "--", group).start().waitFor(3, TimeUnit.SECONDS);
        for (int i = 0; i < 20 && live(record); i++) Thread.sleep(100);
    }

    private static boolean commandAvailable(String binary) {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!directory.isBlank() && Files.isExecutable(Path.of(directory, binary))) return true;
        }
        return false;
    }

    private static void privateDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path)) throw new IOException("private Runtime directory cannot be a symlink: " + path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }

    private static void writePrivate(Path file, Object value) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "remote-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            JSON.writeValue(temporary.toFile(), value);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
