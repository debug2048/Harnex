package com.harnex.process;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.HarnexApplication;
import com.harnex.config.AgentConfig;
import com.harnex.config.UserDataPaths;
import org.springframework.boot.system.ApplicationHome;

/** Optional adapter to a pinned sandbox runtime. Never installs dependencies or falls back to unconfined execution. */
final class NativeSandbox {
    static final String SRT_VERSION = "0.0.75";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ENVIRONMENT = Set.of("PATH", "HOME", "USER", "LOGNAME", "LANG", "TERM",
        "JAVA_HOME", "JDK_HOME", "CARGO_HOME", "RUSTUP_HOME", "GOPATH", "GOROOT", "VIRTUAL_ENV", "CONDA_PREFIX",
        "PYTHONUTF8", "PYTHONIOENCODING", "HARNEX_WORKSPACE", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT");
    private NativeSandbox() {}

    static String effectiveMode(String configured, String requested) {
        if (!Set.of("none", "read-only", "workspace-write").contains(configured))
            throw new IllegalArgumentException("Invalid configured sandbox mode");
        if (requested == null || requested.isBlank()) return configured;
        if (!Set.of("read-only", "workspace-write").contains(requested))
            throw new IllegalArgumentException("sandbox must be read-only or workspace-write; commands cannot disable configured isolation");
        return "read-only".equals(configured) ? configured : requested;
    }

    static Map<String, Object> capabilities(AgentConfig cfg) {
        boolean supported = supported();
        boolean installed = false;
        String note;
        try {
            if (!supported) throw new IllegalStateException("Native isolation is not enabled on this platform; use explicit host execution or a configured Docker environment.");
            packageRoot(cfg);
            if (executable("node") == null) throw new IllegalStateException("Node 20.11+ is required only for optional native isolation.");
            installed = true;
            note = "OS and IPC dependencies are verified at execution time; failures never fall back to host execution.";
        } catch (RuntimeException missing) { note = missing.getMessage(); }
        return Map.of("implementation", "srt", "requiredVersion", SRT_VERSION, "supported", supported,
            "installed", installed, "enforcementVerified", false, "note", note);
    }

    static Prepared wrap(ProcessBuilder original, Path workspace, AgentConfig cfg, String mode, Map<String, String> suppliedEnv) {
        if (!supported()) throw new IllegalStateException("SANDBOX_UNAVAILABLE: native isolation is not enabled on "
            + System.getProperty("os.name") + ". No command was started; ordinary host execution remains available.");
        // Unrestricted egress could reach privileged local control services. Native isolation is deliberately offline.
        if (!"deny".equals(cfg.getExecution().getSandboxNetwork())) throw new IllegalStateException(
            "SANDBOX_UNAVAILABLE: native isolation currently requires sandbox-network=deny. Use an explicitly configured Docker environment for isolated network access.");
        Path module = real(packageRoot(cfg).resolve("dist/index.js"));
        String nodeExecutable = executable("node");
        if (nodeExecutable == null) throw new IllegalStateException("SANDBOX_UNAVAILABLE: Node 20.11+ is required for the optional sandbox runner");
        Path node = real(Path.of(nodeExecutable));
        Path root = real(workspace);
        List<Path> writable = new ArrayList<>();
        if ("workspace-write".equals(mode)) {
            writable.add(root);
            for (String path : cfg.getExecution().getWritableRoots()) writable.add(real(Path.of(path)));
        }
        List<Path> forbiddenTools = new ArrayList<>(writable);
        forbiddenTools.add(root);
        Path application = new ApplicationHome(HarnexApplication.class).getSource().toPath().toAbsolutePath().normalize();
        for (Path path : forbiddenTools) if (module.startsWith(path) || node.startsWith(path) || application.startsWith(path))
            throw new IllegalStateException("SANDBOX_UNAVAILABLE: the Runtime, Node and sandbox runner must be outside the workspace and writable roots");
        Path control = null;
        Path temporary = null;
        try {
            Path controlRoot = Path.of(cfg.getDataDir()).toAbsolutePath().resolve("tmp/commands");
            Files.createDirectories(controlRoot);
            Files.setPosixFilePermissions(controlRoot, PosixFilePermissions.fromString("rwx------"));
            control = Files.createTempDirectory(controlRoot, "invocation-").toRealPath();
            temporary = Files.createTempDirectory("harnex-command-").toRealPath();
            List<String> denyRead = new ArrayList<>();
            for (Path hidden : hidden(cfg)) denyRead.add(hidden.toString());
            denyRead.add(control.toString());
            for (Path hidden : hidden(cfg)) {
                if (root.startsWith(hidden) || temporary.startsWith(hidden))
                    throw new IllegalStateException("SANDBOX_DENIED: workspace or command temp directory is inside private Runtime storage");
                for (Path write : writable) if (write.startsWith(hidden))
                    throw new IllegalStateException("SANDBOX_DENIED: private storage cannot be an additional writable root");
            }
            List<String> denyWrite = new ArrayList<>(denyRead);
            Path home = Path.of(System.getProperty("user.home"));
            // Do not grant the runner's broad compatibility scratch directories belonging to unrelated tools.
            denyWrite.addAll(List.of("/tmp/claude", "/private/tmp/claude", home.resolve(".npm/_logs").toString(), home.resolve(".claude/debug").toString()));
            denyWrite.add(root.resolve(".git/hooks").toString());
            denyWrite.add(root.resolve(".git/config").toString());
            if ("read-only".equals(mode)) denyWrite.add(root.toString());
            List<String> allowWrite = new ArrayList<>(writable.stream().map(Path::toString).toList());
            allowWrite.add(temporary.toString());
            Map<String, String> childEnv = new LinkedHashMap<>();
            if (original.environment().get("PATH") != null) childEnv.put("PATH", original.environment().get("PATH"));
            if (suppliedEnv != null) suppliedEnv.forEach((key, value) -> {
                if (key != null && value != null && CommandExecutor.isSafeEnvKey(key)) childEnv.put(key, value);
            });
            Map<String, Object> policy = Map.of(
                "network", Map.of("allowedDomains", List.of(), "deniedDomains", List.of("*"), "allowAllUnixSockets", false, "allowLocalBinding", false),
                "filesystem", Map.of("denyRead", denyRead, "allowRead", List.of(), "allowWrite", allowWrite, "denyWrite", denyWrite),
                "enableWeakerNestedSandbox", false, "enableWeakerNetworkIsolation", false, "allowAppleEvents", false);
            Path input = control.resolve("invocation.json");
            JSON.writeValue(input.toFile(), Map.of("policy", policy, "argv", original.command(), "env", childEnv));
            Files.setPosixFilePermissions(input, PosixFilePermissions.fromString("rw-------"));
            Path launcher = control.resolve("sandbox-launcher.mjs");
            try (var resource = NativeSandbox.class.getResourceAsStream("/execution/sandbox-launcher.mjs")) {
                if (resource == null) throw new IOException("sandbox adapter resource is missing");
                Files.copy(resource, launcher);
            }
            ProcessBuilder wrapped = new ProcessBuilder(node.toString(), launcher.toString(), module.toString(), input.toString())
                .directory(original.directory()).redirectErrorStream(true);
            wrapped.environment().clear();
            original.environment().forEach((key, value) -> {
                if (inheritedEnvironmentAllowed(key)) wrapped.environment().put(key, value);
            });
            // Security helper discovery must never execute a workspace's bwrap/rg/socat shim on the host.
            List<String> trustedPath = new ArrayList<>();
            for (String entry : original.environment().getOrDefault("PATH", "").split(File.pathSeparator)) {
                try {
                    Path directory = Path.of(entry).toRealPath();
                    if (forbiddenTools.stream().noneMatch(directory::startsWith)) trustedPath.add(directory.toString());
                } catch (IOException | RuntimeException ignored) { /* absent or unsafe search directories are excluded */ }
            }
            wrapped.environment().put("PATH", String.join(File.pathSeparator, trustedPath));
            wrapped.environment().put("TMPDIR", temporary.toString());
            wrapped.environment().put("CLAUDE_CODE_TMPDIR", temporary.toString());
            return new Prepared(wrapped, control, temporary);
        } catch (IOException | RuntimeException failure) {
            new Prepared(null, control, temporary).close();
            throw new IllegalStateException("SANDBOX_UNAVAILABLE: " + failure.getMessage(), failure);
        }
    }

    static boolean inheritedEnvironmentAllowed(String key) {
        String normalized = key.toUpperCase(Locale.ROOT);
        return ENVIRONMENT.contains(normalized) || normalized.startsWith("LC_");
    }

    private static boolean supported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") || os.contains("mac");
    }

    private static Path packageRoot(AgentConfig cfg) {
        try {
            String configured = cfg.getExecution().getSandboxRuntime();
            Path root;
            if (configured != null && !configured.isBlank()) root = Path.of(configured).toRealPath();
            else {
                String cli = executable("srt");
                if (cli == null) throw new IOException("install optional @anthropic-ai/sandbox-runtime@" + SRT_VERSION + " or configure execution.sandbox-runtime");
                root = Path.of(cli).toRealPath().getParent().getParent();
            }
            var manifest = JSON.readTree(root.resolve("package.json").toFile());
            if (!"@anthropic-ai/sandbox-runtime".equals(manifest.path("name").asText())
                || !SRT_VERSION.equals(manifest.path("version").asText()) || !Files.isRegularFile(root.resolve("dist/index.js")))
                throw new IOException("native isolation requires @anthropic-ai/sandbox-runtime@" + SRT_VERSION);
            return root;
        } catch (IOException | RuntimeException failure) { throw new IllegalStateException("SANDBOX_UNAVAILABLE: " + failure.getMessage(), failure); }
    }

    record Prepared(ProcessBuilder builder, Path control, Path temp) implements AutoCloseable {
        public void close() { removeOwned(control); removeOwned(temp); }
        private static void removeOwned(Path root) {
            if (root == null) return;
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { /* never follow symbolic links */ }
                });
            } catch (IOException ignored) { /* cleanup must not replace the command outcome */ }
        }
    }

    static List<Path> hidden(AgentConfig cfg) {
        Set<Path> paths = new LinkedHashSet<>();
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> candidates = new ArrayList<>(List.of(UserDataPaths.resolve(cfg), Path.of(cfg.getDataDir())));
        for (String name : List.of(".ssh", ".aws", ".gnupg", ".kube", ".docker", ".npmrc", ".netrc", ".pypirc",
            ".git-credentials", ".cargo/credentials", ".cargo/credentials.toml", ".config/gcloud", "Library/Keychains"))
            candidates.add(home.resolve(name));
        for (Path candidate : candidates) if (Files.exists(candidate)) paths.add(real(candidate));
        return List.copyOf(paths);
    }

    private static Path real(Path path) {
        try { return path.toRealPath(); }
        catch (IOException e) { throw new IllegalStateException("sandbox path is unavailable: " + path, e); }
    }

    static String executable(String name) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path path = Path.of(dir, name);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toAbsolutePath().toString();
        }
        return null;
    }
}
