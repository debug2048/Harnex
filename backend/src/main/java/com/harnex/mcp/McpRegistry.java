package com.harnex.mcp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.tool.ToolRegistry;
import com.harnex.toolset.ToolsetRegistry;
import com.harnex.process.ChildProcessEnvironment;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Registers configured MCP servers at startup and connects them when an execution needs their tools.
 * Connections and discovered tools are shared until the Runtime shuts down.
 *
 * <p>
 * Includes a periodic health-check that detects dead connections and attempts automatic reconnection.
 * </p>
 */
@Slf4j
@Component
@ProjectRuntime
public class McpRegistry {

    private static final long HEALTH_CHECK_INTERVAL_S = 60;

    private final AgentConfig cfg;
    private final ToolRegistry toolRegistry;
    private final ToolsetRegistry toolsetRegistry;
    private final McpResultAssets resultAssets;

    /** server-name → live client */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    /** server-name → list of adapter tools */
    private final Map<String, List<McpToolAdapter>> serverTools = new ConcurrentHashMap<>();
    /** server-name → original config (needed for reconnection) */
    private final Map<String, AgentConfig.McpServer> serverConfigs = new ConcurrentHashMap<>();
    /** server-name → connection alive flag */
    private final Map<String, Boolean> connectionState = new ConcurrentHashMap<>();
    /** server-name → next reconnect attempt epoch ms (exponential backoff). */
    private final Map<String, Long> nextReconnectAt = new ConcurrentHashMap<>();
    /** server-name → consecutive failures (drives backoff). */
    private final Map<String, Integer> consecutiveFails = new ConcurrentHashMap<>();
    /** One connection attempt per server; task cancellation can interrupt a waiter without touching its owner. */
    private final ConcurrentHashMap<String, ReentrantLock> reconnectLocks = new ConcurrentHashMap<>();
    private final Map<String, McpClient> connectingClients = new ConcurrentHashMap<>();
    /** Serializes publication with shutdown, never held during transport I/O. */
    private final Object lifecycle = new Object();
    private volatile boolean closing;
    /**
     * Max backoff between reconnect attempts — 5 minutes. The previous 15-minute cap combined with the 8-bit shift
     * below produced a worst-case window of ~4.3 h, during which a brief upstream blip locked the server out of
     * recovery without any operator-visible recourse. 5 minutes is the longest a human is willing to wait for an
     * automatic retry; for anything worse, the explicit {@link #attemptReconnect(String)} REST endpoint is the right
     * escape hatch.
     */
    private static final long MAX_BACKOFF_MS = 5L * 60_000L;
    /** Base backoff — 60 seconds, doubled each failure (capped at MAX_BACKOFF_MS). */
    private static final long BASE_BACKOFF_MS = 60_000L;

    private ScheduledExecutorService healthCheck;
    private final Set<String> transientServers = ConcurrentHashMap.newKeySet();

    public McpRegistry(AgentConfig cfg, ToolRegistry toolRegistry, ToolsetRegistry toolsetRegistry) {
        this(cfg, toolRegistry, toolsetRegistry, null);
    }
    @Autowired
    public McpRegistry(AgentConfig cfg, ToolRegistry toolRegistry, ToolsetRegistry toolsetRegistry, McpResultAssets resultAssets) {
        this.cfg = cfg;
        this.toolRegistry = toolRegistry;
        this.toolsetRegistry = toolsetRegistry;
        this.resultAssets = resultAssets;
    }
    McpResultAssets resultAssets() { return resultAssets; }

    @PostConstruct
    public void init() {
        AgentConfig.Mcp mcp = cfg.getMcp();
        List<AgentConfig.McpServer> configured = new ArrayList<>();
        // The host editor bridge keeps its eager lifecycle; ordinary user servers remain dormant.
        if (cfg.getRuntimeMcpBridge() != null) configured.add(cfg.getRuntimeMcpBridge());
        if (mcp != null && mcp.getServers() != null) configured.addAll(mcp.getServers());
        if (configured.isEmpty()) {
            log.info("No MCP servers configured");
            return;
        }

        Set<String> seen = new HashSet<>();
        for (AgentConfig.McpServer serverCfg : configured) {
            if (serverCfg.getName() == null || serverCfg.getName().isBlank()) {
                log.warn("Skipping MCP server with no name");
                continue;
            }
            if (!seen.add(serverCfg.getName())) {
                log.warn("Skipping duplicate MCP server name '{}' — only the first entry is used. "
                    + "Fix harnex.mcp.servers in your config to use unique names.", serverCfg.getName());
                continue;
            }
            serverConfigs.put(serverCfg.getName(), serverCfg);
        }
        log.info("Registered {} MCP server configurations (ordinary servers connect on demand)",
            serverConfigs.size());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        AgentConfig.McpServer bridge = cfg.getRuntimeMcpBridge();
        if (bridge == null) return;
        Thread thread = new Thread(() -> connect(bridge.getName(), false, null), "mcp-init");
        thread.setDaemon(true);
        thread.start();
    }

    /** Prepare discovery before the caller resolves its final tool definitions. Metadata reads never call this. */
    public List<String> prepareToolsets(List<String> names) {
        return prepareToolsets(names, null);
    }

    public List<String> prepareToolsets(List<String> names, Function<Runnable, AutoCloseable> onCancel) {
        Map<String, Set<String>> selected = toolsetRegistry.selectedPresets(names);
        List<String> unavailable = new ArrayList<>();
        for (var entry : serverConfigs.entrySet()) {
            if (closing || Thread.currentThread().isInterrupted()) break;
            if (!neededBy(entry.getValue(), selected)) continue;
            if (!connect(entry.getKey(), false, onCancel)) unavailable.add(entry.getKey());
        }
        return unavailable;
    }

    private boolean neededBy(AgentConfig.McpServer server, Map<String, Set<String>> selected) {
        List<String> targets = effectiveExposeTargets(server);
        boolean editorBridge = transientServers.contains(server.getName())
            || cfg.getRuntimeMcpBridge() != null && Objects.equals(server.getName(), cfg.getRuntimeMcpBridge().getName());
        return selected.entrySet().stream().anyMatch(preset -> targets.contains(preset.getKey())
            // Private IDE presets inherit ordinary vscode additions, but never another editor bridge.
            || !editorBridge && toolsetRegistry.isClientPreset(preset.getKey()) && targets.contains("vscode")
            // A custom preset may explicitly name an MCP tool instead of exposing the whole server.
            || preset.getValue().stream().anyMatch(tool -> McpToolAdapter.mayBelongToServer(tool, server.getName())));
    }

    private synchronized void startHealthCheck() {
        if (closing || healthCheck != null || connectionState.isEmpty()) return;
        healthCheck = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mcp-health-check");
            thread.setDaemon(true);
            return thread;
        });
        healthCheck.scheduleAtFixedRate(() -> {
            try { performHealthCheck(); }
            catch (Throwable failure) { log.warn("MCP health-check tick failed: {}", failure.getMessage(), failure); }
        }, HEALTH_CHECK_INTERVAL_S, HEALTH_CHECK_INTERVAL_S, TimeUnit.SECONDS);
    }

    private void performHealthCheck() {
        // Only attempted connections have a state. Never activate a dormant configured server here.
        for (String name : connectionState.keySet()) {
            if (closing || Thread.currentThread().isInterrupted()) return;
            ReentrantLock lock = reconnectLocks.computeIfAbsent(name, ignored -> new ReentrantLock());
            if (!lock.tryLock()) continue;
            try {
                AgentConfig.McpServer server = serverConfigs.get(name);
                if (closing || server == null || !connectionState.containsKey(name)) continue;
                McpClient client = clients.get(name);
                if (client != null && Boolean.TRUE.equals(connectionState.get(name))) {
                    try { client.listTools(); continue; }
                    catch (Exception failure) {
                        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                        log.warn("MCP server '{}' health check failed: {}", name, failure.getMessage());
                    }
                } else if (System.currentTimeMillis() < nextReconnectAt.getOrDefault(name, 0L)) {
                    continue;
                }
                if (!Thread.currentThread().isInterrupted()) doConnect(name, server, null);
            } finally { lock.unlock(); }
        }
    }

    /** Resolve a shared live client; existing tool adapters follow reconnections through this lookup. */
    public McpClient getClient(String serverName) { return clients.get(serverName); }

    /** Explicit operator/tool recovery may bypass backoff and refresh an existing connection. */
    public void attemptReconnect(String serverName) { connect(serverName, true, null); }

    private boolean connect(String name, boolean force, Function<Runnable, AutoCloseable> onCancel) {
        if (closing || Thread.currentThread().isInterrupted()) return false;
        ReentrantLock lock = reconnectLocks.computeIfAbsent(name, ignored -> new ReentrantLock());
        try { lock.lockInterruptibly(); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return false; }
        try {
            AgentConfig.McpServer server = serverConfigs.get(name);
            if (closing || server == null || Thread.currentThread().isInterrupted()) return false;
            if (!force && Boolean.TRUE.equals(connectionState.get(name))) return true;
            if (!force && System.currentTimeMillis() < nextReconnectAt.getOrDefault(name, 0L)) return false;
            return doConnect(name, server, onCancel);
        } finally {
            lock.unlock();
            startHealthCheck();
        }
    }

    /** Connection-scoped editor bridges remain eager and never enter user configuration. */
    public void registerTransient(AgentConfig.McpServer server) {
        String name = server.getName();
        ReentrantLock lock = reconnectLocks.computeIfAbsent(name, ignored -> new ReentrantLock());
        lock.lock();
        try {
            synchronized (lifecycle) {
                if (closing) throw new IllegalStateException("MCP registry is shutting down");
                if (serverConfigs.containsKey(name) && !transientServers.contains(name)) {
                    throw new IllegalArgumentException("MCP server name is already configured");
                }
                transientServers.add(name);
                serverConfigs.put(name, server);
            }
            if (!doConnect(name, server, null)) {
                unregisterTransient(name);
                throw new IllegalStateException("Cannot connect to the IDE editor bridge");
            }
            startHealthCheck();
        } finally { lock.unlock(); }
    }

    public void unregisterTransient(String name) {
        ReentrantLock lock = reconnectLocks.computeIfAbsent(name, ignored -> new ReentrantLock());
        lock.lock();
        try {
            McpClient removed;
            synchronized (lifecycle) {
                if (!transientServers.remove(name)) return;
                serverConfigs.remove(name);
                removed = clients.remove(name);
                removeTools(name);
                connectionState.remove(name);
                consecutiveFails.remove(name);
                nextReconnectAt.remove(name);
            }
            closeClient(removed);
        } finally { lock.unlock(); }
    }

    private boolean doConnect(String name, AgentConfig.McpServer server, Function<Runnable, AutoCloseable> onCancel) {
        McpClient candidate = null;
        AutoCloseable cancellation = null;
        boolean published = false;
        boolean abandoned = false;
        try {
            synchronized (lifecycle) {
                if (closing || serverConfigs.get(name) != server || Thread.currentThread().isInterrupted()) return false;
                connectionState.putIfAbsent(name, false);
            }
            candidate = createClient(server);
            synchronized (lifecycle) {
                if (closing || serverConfigs.get(name) != server) return false;
                connectingClients.put(name, candidate);
            }
            McpClient pending = candidate;
            if (onCancel != null) cancellation = onCancel.apply(() -> cancelConnection(name, pending));
            if (Thread.currentThread().isInterrupted()) { abandoned = true; return false; }
            log.info("Connecting to MCP server '{}'", name);
            candidate.connect();
            List<McpToolAdapter> adapters = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (McpClient.McpToolInfo info : candidate.listTools()) {
                McpToolAdapter adapter = new McpToolAdapter(candidate, name, info, this);
                if (names.add(adapter.getName())) adapters.add(adapter);
                else log.warn("MCP server '{}' exposes duplicate tool '{}' — keeping first", name, adapter.getName());
            }
            if (Thread.currentThread().isInterrupted()) { abandoned = true; return false; }
            McpClient oldClient;
            synchronized (lifecycle) {
                if (Thread.currentThread().isInterrupted()) { abandoned = true; return false; }
                if (closing || serverConfigs.get(name) != server || connectingClients.get(name) != candidate) return false;
                List<String> dropped = serverTools.getOrDefault(name, List.of()).stream()
                    .map(McpToolAdapter::getName).filter(tool -> !names.contains(tool)).toList();
                dropped.forEach(toolRegistry::unregister);
                toolsetRegistry.removeFromPresets(dropped);
                adapters.forEach(toolRegistry::register);
                toolsetRegistry.addToPresets(names, effectiveExposeTargets(server));
                serverTools.put(name, adapters);
                oldClient = clients.put(name, candidate);
                connectionState.put(name, true);
                consecutiveFails.remove(name);
                nextReconnectAt.remove(name);
                connectingClients.remove(name, candidate);
                published = true;
            }
            closeClient(oldClient);
            log.info("MCP server '{}' connected with {} tools", name, adapters.size());
            return true;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            abandoned = Thread.currentThread().isInterrupted()
                || candidate != null && connectingClients.get(name) != candidate;
            synchronized (lifecycle) {
                if (!closing && !abandoned && serverConfigs.get(name) == server) {
                    connectionState.put(name, false);
                    int failures = consecutiveFails.merge(name, 1, Integer::sum);
                    nextReconnectAt.put(name, System.currentTimeMillis()
                        + Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << Math.min(failures - 1, 8))));
                    log.warn("MCP server '{}' connection failed: {} (will retry)", name, failure.getMessage());
                }
            }
            return false;
        } finally {
            if (cancellation != null) try { cancellation.close(); } catch (Exception ignored) {}
            synchronized (lifecycle) {
                if (candidate != null) connectingClients.remove(name, candidate);
                if (abandoned && !clients.containsKey(name)) connectionState.remove(name);
            }
            if (!published) closeClient(candidate);
        }
    }

    private void cancelConnection(String name, McpClient candidate) {
        synchronized (lifecycle) {
            if (!connectingClients.remove(name, candidate)) return; // Already shared: cancellation must not close it.
            if (!clients.containsKey(name)) connectionState.remove(name);
        }
        closeClient(candidate);
    }

    private void removeTools(String name) {
        List<McpToolAdapter> removed = serverTools.remove(name);
        if (removed == null) return;
        List<String> names = removed.stream().map(McpToolAdapter::getName).toList();
        names.forEach(toolRegistry::unregister);
        toolsetRegistry.removeFromPresets(names);
    }

    private static void closeClient(McpClient client) {
        if (client == null) return;
        try { client.close(); }
        catch (Exception failure) { log.debug("MCP connection close failed: {}", failure.getMessage()); }
    }

    @PreDestroy
    public void shutdown() {
        Set<McpClient> owned;
        synchronized (lifecycle) {
            closing = true;
            owned = new HashSet<>(clients.values());
            owned.addAll(connectingClients.values());
            connectingClients.clear();
            clients.clear();
            new ArrayList<>(serverTools.keySet()).forEach(this::removeTools);
            connectionState.clear();
            serverConfigs.clear();
            transientServers.clear();
            consecutiveFails.clear();
            nextReconnectAt.clear();
        }
        synchronized (this) {
            if (healthCheck != null) healthCheck.shutdownNow();
        }
        owned.forEach(McpRegistry::closeClient);
    }

    // ── Query API (used by McpController) ──────────────────────

    public Collection<String> listServerNames() {
        // Use serverConfigs so callers (e.g. the /mcp UI list, manual reconnect endpoint) can
        // see servers whose initial connect failed — clients.keySet() would hide them.
        return Collections.unmodifiableSet(serverConfigs.keySet());
    }

    public List<McpServerStatus> listServers() {
        List<McpServerStatus> out = new ArrayList<>();
        for (var entry : serverConfigs.entrySet()) {
            String name = entry.getKey();
            McpClient client = clients.get(name);
            McpServerStatus s = new McpServerStatus();
            s.setName(name);
            s.setConnected(connectionState.getOrDefault(name, false));
            s.setStatus(connectingClients.containsKey(name) ? "connecting" : s.isConnected() ? "connected"
                : connectionState.containsKey(name) ? "disconnected" : "not_started");
            if (s.isConnected() && client != null) {
                s.setVersion(client.getServerVersion());
            } else {
                s.setVersion(s.getStatus());
            }
            List<McpToolAdapter> tools = serverTools.getOrDefault(name, List.of());
            s.setTools(tools.stream().map(t -> {
                McpToolStatus ts = new McpToolStatus();
                ts.setName(t.getName());
                ts.setOriginalName(t.getOriginalName());
                ts.setDescription(t.getDescription());
                return ts;
            }).toList());
            out.add(s);
        }
        return out;
    }

    // ── Factory ────────────────────────────────────────────────

    /**
     * Resolve which toolset presets a given MCP server's tools should be auto-injected into. Default = {@code [act]}
     * when the operator has not opted in via {@code harnex.mcp.servers[].expose-to-presets}; this keeps the read-only
     * safety presets (`read` / `inspect`) free of arbitrary MCP side-effects unless the operator explicitly authorises
     * it. A null serverCfg (the very rare case where reconnect runs after the cfg was removed mid-flight) also falls
     * back to {@code [act]} for the same fail-closed reason.
     */
    private List<String> effectiveExposeTargets(AgentConfig.McpServer cfg) {
        if (cfg == null || cfg.getExposeToPresets() == null || cfg.getExposeToPresets().isEmpty()) {
            return List.of("act");
        }
        return cfg.getExposeToPresets();
    }

    private McpClient createClient(AgentConfig.McpServer cfg) {
        McpTransport transport = switch (cfg.getTransport()) {
            case "stdio" ->
                new StdioMcpTransport(resolveEnv(cfg.getCommand()),
                    cfg.getArgs() == null ? List.of() : cfg.getArgs().stream().map(McpRegistry::resolveEnv).toList(),
                    resolveEnvMap(cfg.getEnv()));
            case "sse" -> new SseMcpTransport(resolveEnv(cfg.getUrl()), resolveEnvMap(cfg.getHeaders()));
            case "streamable-http", "http" ->
                new StreamableHttpMcpTransport(resolveEnv(cfg.getUrl()), resolveEnvMap(cfg.getHeaders()));
            default -> throw new IllegalArgumentException("Unknown MCP transport: " + cfg.getTransport());
        };
        return new McpClient(cfg.getName(), transport);
    }

    static String resolveEnv(String v) {
        if (v == null) {
            return null;
        }
        if (v.startsWith("${") && v.endsWith("}")) {
            String inside = v.substring(2, v.length() - 1);
            String def = "";
            int idx = inside.indexOf(':');
            if (idx >= 0) {
                def = inside.substring(idx + 1);
                inside = inside.substring(0, idx);
            }
            if (inside.startsWith(ChildProcessEnvironment.KEYCHAIN_PROVIDER_PREFIX)) {
                log.warn("Refusing to expand desktop Keychain Provider material into MCP configuration");
                return def;
            }
            String env = System.getenv(inside);
            String resolved = env != null ? env : def;
            if (isInjectedKeychainSecret(resolved)) {
                log.warn("Refusing to copy desktop Keychain Provider material into MCP configuration");
                return "";
            }
            return resolved;
        }
        if (isInjectedKeychainSecret(v)) {
            log.warn("Refusing to copy resolved desktop Keychain Provider material into MCP configuration");
            return "";
        }
        return v;
    }

    private static boolean isInjectedKeychainSecret(String value) {
        if (value == null || value.isEmpty()) return false;
        return System.getenv().entrySet().stream()
            .anyMatch(entry -> entry.getKey().startsWith(ChildProcessEnvironment.KEYCHAIN_PROVIDER_PREFIX)
                && !entry.getValue().isEmpty() && value.contains(entry.getValue()));
    }

    private static Map<String, String> resolveEnvMap(Map<String, String> m) {
        if (m == null || m.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, resolveEnv(v)));
        return out;
    }

    // ── DTOs ───────────────────────────────────────────────────

    @Data
    public static class McpServerStatus {
        private String name;
        private boolean connected;
        private String status;
        private String version;
        private List<McpToolStatus> tools;
    }

    @Data
    public static class McpToolStatus {
        private String name;
        private String originalName;
        private String description;
    }
}
