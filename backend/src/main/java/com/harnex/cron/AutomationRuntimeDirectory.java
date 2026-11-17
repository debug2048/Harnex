package com.harnex.cron;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.config.*;
import com.harnex.execution.ScopeIdentity;
import org.springframework.stereotype.Component;

import io.swagger.v3.oas.annotations.media.Schema;

/** Discovers only already-running, host-published runtimes. Never opens projects or starts a process. */
@Component
public class AutomationRuntimeDirectory {
    @Schema(name = "AutomationTarget")
    public record Target(String id, RuntimeProfile profile, String scopeId, String root, String connectionId, String label) {}
    @Schema(name = "AutomationTargetView")
    public record TargetView(Target target, boolean online) {}
    public record Peer(Target target, Path manifest, String endpoint, String instanceId, boolean local) {}
    private final AgentConfig config;
    private final ScopeIdentity scope;
    private final Path user;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).proxy(new ProxySelector() {
        public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
    }).build();
    public AutomationRuntimeDirectory(AgentConfig config, ScopeIdentity scope) {
        this.config = config; this.scope = scope; this.user = RuntimeDataPaths.canonical(UserDataPaths.resolve(config));
    }
    public List<TargetView> targets() {
        List<TargetView> views = new ArrayList<>();
        for (Target target : catalog()) {
            boolean online;
            try { find(target); online = true; } catch (RuntimeException unavailable) { online = false; }
            views.add(new TargetView(target, online));
        }
        return views;
    }
    public Target target(String id) {
        String requested = id == null || id.isBlank() ? "personal" : id;
        return catalog().stream().filter(target -> target.id().equals(requested)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Execution environment was removed; choose it again"));
    }
    private List<Target> catalog() {
        List<Target> targets = new ArrayList<>();
        targets.add(new Target("personal", RuntimeProfile.PERSONAL, scope.profile() == RuntimeProfile.PERSONAL ? scope.scopeId() : ScopeIdentity.stableId("personal:" + user), null, null, "Personal"));
        Path catalog = user.resolve("catalog/workspaces.json");
        try {
            if (Files.isRegularFile(catalog)) for (JsonNode item : AutomationStore.JSON.readTree(catalog.toFile()).path("workspaces")) {
                String id = item.path("id").asText(), root = item.path("root").asText();
                if (id.isBlank() || root.isBlank()) continue;
                String connection = nullable(item.path("remoteProfile").asText());
                targets.add(new Target(id, RuntimeProfile.PROJECT, item.path("runtimeScopeId").asText(id), root, connection,
                    item.path("name").asText(root) + (connection == null ? "" : " · " + item.path("remoteHost").asText(connection))));
            }
        } catch (Exception failure) { throw new IllegalStateException("Unable to read saved execution environments", failure); }
        if (scope.profile() == RuntimeProfile.PROJECT && targets.stream().noneMatch(target -> target.connectionId() == null && target.scopeId().equals(scope.scopeId()))) {
            targets.add(new Target(scope.scopeId(), RuntimeProfile.PROJECT, scope.scopeId(), scope.projectRoot().toString(), null,
                scope.projectRoot().getFileName() == null ? scope.projectRoot().toString() : scope.projectRoot().getFileName().toString()));
        }
        return targets;
    }
    public Peer find(Target target) {
        if (target.connectionId() == null && target.scopeId().equals(scope.scopeId()) && target.profile() == scope.profile()
            && (target.profile() == RuntimeProfile.PERSONAL || sameRoot(target.root(), scope.projectRoot().toString()))) {
            return new Peer(target, null, null, scope.instanceId(), true);
        }
        Path domains = user.resolve("runtime-domains");
        if (Files.isDirectory(domains)) try (var folders = Files.list(domains)) {
            for (Path folder : folders.filter(Files::isDirectory).toList()) {
                Path manifest = folder.resolve("domain.json");
                try {
                    JsonNode value = validated(manifest);
                    if (!value.path("scopeId").asText().equals(target.scopeId()) || !value.path("runtimeProfile").asText().equals(target.profile().name())) continue;
                    if (!Objects.equals(nullable(value.path("remote").path("connectionId").asText()), target.connectionId())) continue;
                    if (target.profile() == RuntimeProfile.PROJECT && !(target.connectionId() == null
                        ? sameRoot(target.root(), value.path("canonicalRoot").asText()) : Objects.equals(target.root(), value.path("canonicalRoot").asText()))) continue;
                    return new Peer(target, manifest, value.path("endpoint").asText(), value.path("instanceId").asText(), false);
                } catch (Exception invalid) { /* closed, replaced or unverifiable runtime: never fall back to another project */ }
            }
        } catch (IOException failure) { throw new OfflineException("Execution environment is unavailable"); }
        throw new OfflineException(target.profile() == RuntimeProfile.PERSONAL ? "Waiting for personal space to start" : "Waiting for the selected project to open");
    }
    private JsonNode validated(Path path) throws Exception {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || Files.size(path) > 65536) throw new IllegalStateException("Invalid Runtime manifest");
        JsonNode value = AutomationStore.JSON.readTree(path.toFile());
        JsonNode owner = AutomationStore.JSON.readTree(path.resolveSibling("owner.json").toFile());
        if (value.path("schemaVersion").asInt() != 3 || !"READY".equals(value.path("lifecycle").asText())
            || !"1.0".equals(value.path("protocolVersion").asText())
            || value.path("ownerInstanceId").asText().isBlank()
            || !value.path("runtimeDomainId").asText().equals(owner.path("runtimeDomainId").asText())
            || !value.path("ownerKind").asText().equals(owner.path("ownerKind").asText())
            || !owner.path("ownerInstanceId").asText().equals(value.path("ownerInstanceId").asText())
            || owner.path("ownerPid").asLong() != value.path("ownerPid").asLong()
            || !ProcessHandle.of(value.path("ownerPid").asLong()).map(ProcessHandle::isAlive).orElse(false)
            || !ProcessHandle.of(value.path("runtimePid").asLong()).map(ProcessHandle::isAlive).orElse(false)) throw new IllegalStateException("Runtime owner unavailable");
        URI endpoint = URI.create(value.path("endpoint").asText());
        if (!"http".equals(endpoint.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(endpoint.getHost())
            || endpoint.getPort() < 1 || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
            || endpoint.getRawFragment() != null || endpoint.getPath() != null && !endpoint.getPath().isEmpty() && !endpoint.getPath().equals("/")) {
            throw new IllegalStateException("Runtime endpoint must be loopback");
        }
        return value;
    }
    public <T> T request(Peer peer, String path, Object body, Class<T> type) {
        if (peer.local()) throw new IllegalArgumentException("Local runtime calls must stay in-process");
        boolean submitted = false;
        try {
            JsonNode manifest = validated(peer.manifest());
            if (!peer.instanceId().equals(manifest.path("instanceId").asText())
                || !peer.endpoint().equals(manifest.path("endpoint").asText())) throw new OfflineException("Execution environment changed");
            Path credential = Path.of(manifest.path("bootTokenFile").asText()).toAbsolutePath().normalize();
            Path directory = peer.manifest().getParent().toRealPath();
            if (!credential.getParent().toRealPath().equals(directory) || Files.isSymbolicLink(credential) || Files.size(credential) > 65536) {
                throw new IllegalStateException("Invalid Runtime credential location");
            }
            HttpResponse<String> identity = send(peer, "/api/v1/scope", null, null);
            JsonNode actual = AutomationStore.JSON.readTree(identity.body());
            if (identity.statusCode() != 200 || !peer.instanceId().equals(actual.path("instanceId").asText())
                || !peer.target().scopeId().equals(actual.path("scopeId").asText())) throw new OfflineException("Execution environment changed");
            String token;
            if ("bearer-json".equals(manifest.path("authType").asText())) token = AutomationStore.JSON.readTree(credential.toFile()).path("token").asText();
            else {
                HttpResponse<String> handshake = send(peer, "/api/v1/auth/boot-token", Map.of("token", Files.readString(credential).trim()), null);
                if (handshake.statusCode() != 200) throw new OfflineException("Runtime authentication is unavailable");
                token = AutomationStore.JSON.readTree(handshake.body()).path("token").asText();
            }
            if (token.isBlank()) throw new OfflineException("Runtime authentication is unavailable");
            submitted = body != null;
            HttpResponse<String> response = send(peer, path, body, token);
            if (response.statusCode() == 404) return null;
            if (response.statusCode() == 400) {
                JsonNode error = AutomationStore.JSON.readTree(response.body());
                throw new IllegalArgumentException(error.path("error").path("message").asText("The execution environment rejected this task"));
            }
            if (response.statusCode() == 401 || response.statusCode() == 409) throw new OfflineException("Execution environment changed or rejected authentication");
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new OfflineException("Execution environment returned HTTP " + response.statusCode(), submitted);
            if (!peer.target().scopeId().equals(response.headers().firstValue("X-Harnex-Scope-Id").orElse(null))
                || !peer.instanceId().equals(response.headers().firstValue("X-Harnex-Instance-Id").orElse(null))) throw new OfflineException("Runtime response identity changed");
            return AutomationStore.JSON.readValue(response.body(), type);
        } catch (OfflineException failure) { throw failure; }
        catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception failure) { throw new OfflineException("Execution environment is unavailable: " + failure.getClass().getSimpleName(), submitted); }
    }
    private HttpResponse<String> send(Peer peer, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(peer.endpoint() + path)).timeout(Duration.ofSeconds(5))
            .header("X-Harnex-Scope-Id", peer.target().scopeId()).header("X-Harnex-Instance-Id", peer.instanceId());
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(AutomationStore.JSON.writeValueAsString(body)));
        else request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value; }
    private static boolean sameRoot(String a, String b) {
        if (a == null || b == null) return Objects.equals(a, b);
        String left = a.replace('\\', '/'), right = b.replace('\\', '/');
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? left.equalsIgnoreCase(right) : left.equals(right);
    }
    public static class OfflineException extends RuntimeException {
        public final boolean possiblySubmitted;
        public OfflineException(String message) { this(message, false); }
        public OfflineException(String message, boolean possiblySubmitted) { super(message); this.possiblySubmitted = possiblySubmitted; }
    }
}
