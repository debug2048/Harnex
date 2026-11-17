package com.harnex.web.api.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.config.ProjectRuntime;
import com.harnex.mcp.McpRegistry;
import com.harnex.util.Ids;

@RestController
@ProjectRuntime
@RequestMapping("/api/mcp")
public class McpController {

    private final McpRegistry registry;

    public McpController(McpRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/servers")
    public Map<String, Object> servers() {
        List<McpRegistry.McpServerStatus> servers = registry.listServers();
        return Map.of("servers", servers, "totalTools", servers.stream().mapToInt(s -> s.getTools().size()).sum());
    }

    /**
     * Operator-triggered reconnect: bypass the exponential-backoff window so a server stuck in the cool-off period can
     * be brought back immediately after the operator has fixed the underlying issue. Without this endpoint, after ~9
     * consecutive health-check failures the next automatic retry was scheduled up to 5 minutes out (was 4 hours before
     * the cap was lowered) with no manual override.
     */
    @PostMapping("/{name}/reconnect")
    public ResponseEntity<?> reconnect(@PathVariable String name) {
        if (!Ids.isSafe(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid server name"));
        }
        if (registry.getClient(name) == null && !registry.listServerNames().contains(name)) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown MCP server: " + name));
        }
        registry.attemptReconnect(name);
        boolean connected = registry.listServers().stream().filter(s -> name.equals(s.getName())).findFirst()
            .map(McpRegistry.McpServerStatus::isConnected).orElse(false);
        return ResponseEntity.ok(Map.of("name", name, "connected", connected));
    }
}
