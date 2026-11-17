package com.harnex.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;

import lombok.extern.slf4j.Slf4j;

/**
 * Bridges an MCP server tool into Harnex's native {@link Tool} interface.
 * <p>
 * Tool name convention: {@code mcp__<server>__<tool>} (double underscores), matching the Claude Code / Cursor MCP
 * naming pattern.
 */
@Slf4j
public class McpToolAdapter implements Tool {

    /**
     * Initial / fallback client. Used ONLY when {@link #registry} is null (legacy two-arg constructor for unit tests).
     * In production the adapter always resolves the live client through {@code registry} so a background reconnect
     * cannot leave the adapter pinned to a closed transport — the previous behaviour was to cache the {@code McpClient}
     * reference at construction time, then keep using the dead connection until the first call surfaced an error.
     */
    private final McpClient initialClient;
    private final String serverName;
    private final McpClient.McpToolInfo info;
    private final String qualifiedName;
    private final McpRegistry registry; // For on-demand reconnection AND live-client lookup

    public McpToolAdapter(McpClient client, String serverName, McpClient.McpToolInfo info) {
        this(client, serverName, info, null);
    }

    public McpToolAdapter(McpClient client, String serverName, McpClient.McpToolInfo info, McpRegistry registry) {
        this.initialClient = client;
        this.serverName = serverName;
        this.info = info;
        this.registry = registry;
        // Sanitize: OpenAI function names must match ^[a-zA-Z0-9_-]{1,64}$
        this.qualifiedName = sanitize("mcp__" + serverName + "__" + info.getName());
    }

    /** Resolve the currently-live client for this adapter's server, or null if registry/server gone. */
    private McpClient liveClient() {
        if (registry != null) {
            McpClient live = registry.getClient(serverName);
            if (live != null) {
                return live;
            }
        }
        return initialClient;
    }

    @Override
    public String getName() {
        return qualifiedName;
    }

    @Override
    public String getDescription() {
        return "[MCP:" + serverName + "] " + info.getDescription();
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(qualifiedName).description(getDescription())
            .parameters(normalizeSchema(info.getInputSchema())).build();
    }

    /**
     * Normalise the MCP-supplied JSON schema into something OpenAI / Anthropic will accept as a function/tool parameter
     * spec. The MCP spec only requires a JSON Schema object, but in practice we've seen servers send {@code {}} (no
     * fields), {@code null}, or even an array of schemas; uploading any of those verbatim makes the upstream provider
     * reject the ENTIRE tool batch with a 400, silently disabling every other tool too. The fix is defensive: always
     * emit a valid {@code object} schema, defaulting to the empty-object shape that {@code ToolDefinition} itself uses.
     */
    private static Map<String, Object> normalizeSchema(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        Object type = raw.get("type");
        if (!"object".equals(type)) {
            // Wrap non-object schemas (array / primitive) as a single-property object so the
            // provider's strict schema validator doesn't fail the whole tool catalogue.
            return Map.of("type", "object", "properties", Map.of("value", raw));
        }
        Object props = raw.get("properties");
        if (!(props instanceof Map)) {
            // properties is required by OpenAI's strict mode even when empty, AND must be an
            // object — but in the wild we've seen MCP servers return `properties: null` (when
            // their schema generator emits an absent field) and `properties: []` (Python /
            // serde quirk where an empty dict round-tripped through JSON-as-list). Both make
            // the provider 400 the entire tool batch. Replace any non-Map shape with `{}`.
            Map<String, Object> patched = new LinkedHashMap<>(raw);
            patched.put("properties", Map.of());
            return patched;
        }
        return raw;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        McpClient client = liveClient();
        if (client == null) {
            return ToolResult.builder().error(true).content("MCP server '" + serverName + "' not available").build();
        }
        try {
            McpClient.McpToolResult result = client.callTool(info.getName(), input);
            return result(result, ctx);
        } catch (Exception e) {
            log.warn("MCP tool {}::{} failed: {}", serverName, info.getName(), e.getMessage());
            // Attempt on-demand reconnection if registry is available
            if (registry != null) {
                log.info("Attempting on-demand reconnect for MCP server '{}' after tool failure", serverName);
                registry.attemptReconnect(serverName);
                // Retry once with the FRESH client — we resolve through the registry rather than reusing
                // the stale local reference so concurrent reconnects can't leave us pinned to a closed pipe.
                McpClient fresh = registry.getClient(serverName);
                if (fresh != null && fresh != client) {
                    try {
                        McpClient.McpToolResult result = fresh.callTool(info.getName(), input);
                        return result(result, ctx);
                    } catch (Exception e2) {
                        log.warn("MCP tool {}::{} failed again after reconnect: {}", serverName, info.getName(),
                            e2.getMessage());
                    }
                }
            }
            return ToolResult.builder().error(true).content("MCP tool call failed: " + e.getMessage()).build();
        }
    }

    private ToolResult result(McpClient.McpToolResult result, ToolContext context) {
        return registry != null && registry.resultAssets() != null ? registry.resultAssets().convert(result, context)
            : ToolResult.builder().error(result.isError()).content(result.getText()).build();
    }

    @Override
    public Set<ToolCapability> capabilities() {
        // MCP-hint translation. The 2025-06-18 spec only gives us two coarse signals
        // (readOnlyHint / destructiveHint); we map them to the smallest capability set that
        // preserves the safety semantics:
        // - readOnlyHint=true → PLAN_MODE_SAFE + CONCURRENCY_SAFE (the server promised no
        // state mutation, so plan-mode admission and parallel batching are both safe).
        // We deliberately do NOT add READS_FILESYSTEM / READS_NETWORK because the hint
        // doesn't tell us which side the tool reads from — the absence is the honest answer.
        // - destructiveHint=true → DESTRUCTIVE_IRREVERSIBLE (drives approval + UI badge).
        // - both absent → empty Set (most conservative: not concurrency-safe, not
        // plan-mode safe; the agent must run the tool serially outside plan-mode).
        EnumSet<ToolCapability> caps =
            EnumSet.noneOf(ToolCapability.class);
        Boolean ro = info.getReadOnlyHint();
        if (ro != null && ro) {
            caps.add(ToolCapability.PLAN_MODE_SAFE);
            caps.add(ToolCapability.CONCURRENCY_SAFE);
        }
        Boolean dh = info.getDestructiveHint();
        if (dh != null && dh) {
            caps.add(ToolCapability.DESTRUCTIVE_IRREVERSIBLE);
        }
        return caps;
    }

    @Override
    public int timeoutMillis() {
        return 120_000;
    }

    /** Original (unqualified) tool name on the MCP server. */
    public String getOriginalName() {
        return info.getName();
    }

    public String getServerName() {
        return serverName;
    }

    /** Identify possible owners of an explicitly configured tool before discovery; resolve() still checks exact names. */
    static boolean mayBelongToServer(String toolName, String serverName) {
        if (toolName == null || !toolName.startsWith("mcp__")) return false;
        String prefix = ("mcp__" + serverName + "__").replaceAll("[^a-zA-Z0-9_-]", "_");
        // Sanitized long names retain at most 47 leading characters before their hash suffix.
        return toolName.startsWith(prefix.substring(0, Math.min(47, prefix.length())));
    }

    private static String sanitize(String name) {
        // OpenAI / Anthropic function-name constraint: ^[a-zA-Z0-9_-]{1,64}$. The previous
        // implementation only replaced illegal chars and would happily emit names that:
        // (a) exceeded 64 characters — every provider rejects the whole tool list with
        // a 400 in that case, silently disabling the entire MCP server's toolset.
        // (b) collided after sanitisation — e.g. "foo.bar" and "foo/bar" both map to
        // "foo_bar"; "foo bar" and "foo-bar" both map to "foo_bar". Different MCP
        // tools collapsing onto the same qualified name would shadow each other in
        // ToolRegistry.register() (the second one silently replaces the first; the
        // agent then picks one at random when the model invokes the colliding name).
        //
        // Strategy: replace illegal chars; if anything was changed OR the length exceeds 64,
        // append a 16-char SHA-256 prefix of the ORIGINAL name as a stable suffix. The hash
        // is content-addressed, so the same MCP tool always sanitises to the same qualified
        // name across restarts, but two distinct originals that would otherwise collide get
        // different hash suffixes. 16 hex = 64-bit collision resistance.
        String replaced = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        boolean changed = !replaced.equals(name);
        boolean tooLong = replaced.length() > 64;
        if (!changed && !tooLong) {
            return replaced;
        }
        String suffix = shortHash(name);
        // Reserve room for "_" + 16-char suffix when truncating.
        int budget = 64 - 1 - suffix.length();
        if (budget < 1)
            budget = 1;
        String head = replaced.length() > budget ? replaced.substring(0, budget) : replaced;
        return head + "_" + suffix;
    }

    /**
     * First 16 hex chars of SHA-256(name) — 64-bit collision resistance, sufficient to resist intentional construction
     * even when MCP server/tool names are attacker-controlled.
     */
    private static String shortHash(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is mandated by the JDK; this is unreachable. Fallback to a non-crypto
            // hash so we still return *something* deterministic rather than throwing.
            return Integer.toHexString(name.hashCode() & 0x7fffffff);
        }
    }
}
