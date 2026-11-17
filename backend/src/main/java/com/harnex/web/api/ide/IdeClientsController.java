package com.harnex.web.api.ide;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.auth.ClientIdentity;
import com.harnex.mcp.McpRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Runtime-local editor connections, separate from the host process and persisted MCP settings. */
@RestController @ProjectRuntime @RequestMapping("/api/v1/ide/clients")
public final class IdeClientsController {
    private static final long LEASE_MS = 120_000;
    private record Connection(String endpoint, String token, long touchedAt) {}
    private final Map<String, Connection> connections = new HashMap<>();
    private final McpRegistry mcp;
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ide-client-expiry"); thread.setDaemon(true); return thread;
    });
    public IdeClientsController(McpRegistry mcp) {
        this.mcp = mcp;
        expiry.scheduleWithFixedDelay(this::expire, 30, 30, TimeUnit.SECONDS);
    }
    public record Bridge(String endpoint, String token) {}

    @PutMapping("/{id}") public synchronized Map<String, String> connect(@PathVariable String id, @RequestBody Bridge bridge) {
        requireClient(id);
        URI endpoint;
        try { endpoint = URI.create(bridge.endpoint()); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid editor endpoint"); }
        if (!"http".equals(endpoint.getScheme()) || endpoint.getHost() == null || !List.of("127.0.0.1", "[::1]", "::1").contains(endpoint.getHost())
            || endpoint.getPort() < 1 || !"/mcp".equals(endpoint.getPath()) || endpoint.getUserInfo() != null
            || endpoint.getQuery() != null || endpoint.getFragment() != null || bridge.token() == null
            || !bridge.token().matches("[A-Za-z0-9_-]{32,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Editor bridge must use an authenticated loopback endpoint");
        }
        if (!connections.containsKey(id) && connections.size() >= 64) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many IDE connections");
        String name = "ide-" + id;
        Connection previous = connections.get(id);
        if (previous == null || !previous.endpoint().equals(bridge.endpoint()) || !previous.token().equals(bridge.token())) {
            AgentConfig.McpServer server = new AgentConfig.McpServer();
            server.setName(name); server.setTransport("sse"); server.setUrl(bridge.endpoint());
            server.setHeaders(Map.of("x-harnex-bridge-token", bridge.token())); server.setExposeToPresets(List.of(name));
            mcp.registerTransient(server);
        }
        connections.put(id, new Connection(bridge.endpoint(), bridge.token(), System.currentTimeMillis()));
        return Map.of("toolset", name);
    }

    @PostMapping("/{id}") public synchronized Map<String, Boolean> renew(@PathVariable String id) {
        requireClient(id);
        Connection previous = connections.get(id);
        if (previous == null) throw new ResponseStatusException(HttpStatus.GONE, "IDE connection expired; reconnect it");
        connections.put(id, new Connection(previous.endpoint(), previous.token(), System.currentTimeMillis()));
        return Map.of("connected", true);
    }
    @DeleteMapping("/{id}") public synchronized Map<String, Boolean> disconnect(@PathVariable String id) {
        requireClient(id); remove(id); return Map.of("disconnected", true);
    }
    private static void requireClient(String id) {
        if (!id.matches("vscode-[A-Za-z0-9-]{1,80}") || !id.equals(ClientIdentity.current())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "IDE client identity mismatch");
        }
    }
    private synchronized void expire() {
        long cutoff = System.currentTimeMillis() - LEASE_MS;
        connections.entrySet().stream().filter(entry -> entry.getValue().touchedAt() < cutoff).map(Map.Entry::getKey).toList().forEach(this::remove);
    }
    private void remove(String id) {
        if (connections.remove(id) != null) mcp.unregisterTransient("ide-" + id);
        // Accepted tasks retain the virtual vscode preset; only this connection's editor tools disappear.
    }
    @PreDestroy public synchronized void close() { expiry.shutdownNow(); List.copyOf(connections.keySet()).forEach(this::remove); }
}
