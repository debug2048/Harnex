package com.harnex.mcp;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.observability.HarnexMetrics;
import com.harnex.config.ProjectRuntime;
import com.harnex.application.session.SessionHooks;
import com.harnex.core.AgentCtx;
import com.harnex.middleware.SensitiveDataMiddleware;
import com.harnex.model.ConversationThread;
import com.harnex.model.ToolCall;
import com.harnex.tool.*;
import com.harnex.tool.approval.ApprovalService;
import com.harnex.toolset.ToolsetRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * Expose Harnex's built-in tools as a Model Context Protocol server so external agents (Claude Desktop
 * / Cursor / VS Code Continue / another Harnex instance) can call them over JSON-RPC.
 *
 * <p>
 * Two endpoints:
 *
 * <ul>
 * <li>{@code GET  /api/mcp/server/info} — capability discovery (also useful for healthchecks)</li>
 * <li>{@code POST /api/mcp/server/rpc} — JSON-RPC 2.0 entrypoint, supports {@code tools/list} and
 * {@code tools/call}</li>
 * </ul>
 *
 * <p>
 * The RPC endpoint returns one JSON response per call.
 *
 * <p>
 * <b>Security:</b>
 * <ul>
 * <li>Disabled by default — set {@code harnex.mcp.server-enabled=true} (and {@code HARNEX_MCP_SERVER_ENABLED=true}) to
 * expose. Reasoning: the moment this is on, any holder of a valid {@code HARNEX_AUTH_TOKENS} bearer can drive
 * {@code bash}, {@code file_write}, etc. on this host. Operators must opt in deliberately.</li>
 * <li>Reuses the standard {@code ApiAuthFilter} bearer / cookie auth — same gate as the chat endpoints. Unauthenticated
 * calls 401 before reaching this controller.</li>
 * <li>Tool exposure is filtered through {@link com.harnex.config.AgentConfig.Toolsets} — by default we expose the
 * {@code read} preset (no bash / no writes). Operators wanting to expose write capability set
 * {@code harnex.mcp.server-toolset=act} (or any custom preset name).</li>
 * <li>Each call runs in a synthetic ephemeral {@link ConversationThread} — no persistence, no audit log of the caller's
 * turn. The tool's own audit hooks still fire.</li>
 * </ul>
 *
 * <p>
 * Resources, prompts and roots are not supported.
 */
@Slf4j
@RestController
@ProjectRuntime
@RequestMapping("/api/mcp/server")
public class McpServerController {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "harnex";
    private static final ObjectMapper M = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final ToolsetRegistry toolsetRegistry;
    private final McpRegistry mcpRegistry;
    private final ParallelToolExecutor toolExecutor;
    private final ApprovalService approvalService;
    private final HarnexMetrics metrics;
    private final SensitiveDataMiddleware sensitiveData;
    /**
     * Ephemeral-thread cleanup hook. Each {@code tools/call} mints a fresh synthetic {@code mcp-server-<uuid>} thread
     * that never goes through {@code SessionStore.save}, so the normal {@code onThreadDeleted} listeners never fire —
     * any tool that stores per-thread state (e.g. an upload) would leave a {@code data/uploads/mcp-server-<uuid>/}
     * directory orphaned on disk. We invoke this in a {@code finally} after every tools/call so external MCP traffic
     * doesn't slowly fill the disk on long-running deployments. Nullable so tests can construct this controller without
     * the full config-tier bean graph.
     */
    private final SessionHooks sessionHooks;
    private final boolean enabled;
    private final String exposedPreset;
    private final String permissionMode;

    public McpServerController(ToolRegistry toolRegistry, ToolsetRegistry toolsetRegistry, McpRegistry mcpRegistry,
        ParallelToolExecutor toolExecutor, ApprovalService approvalService, HarnexMetrics metrics,
        SensitiveDataMiddleware sensitiveData,
        @Autowired(required = false) SessionHooks sessionHooks,
        @Value("${harnex.mcp.server-enabled:false}") boolean enabled,
        @Value("${harnex.mcp.server-toolset:read}") String exposedPreset,
        @Value("${harnex.mcp.server-permission-mode:yolo}") String permissionMode) {
        this.toolRegistry = toolRegistry;
        this.toolsetRegistry = toolsetRegistry;
        this.mcpRegistry = mcpRegistry;
        this.toolExecutor = toolExecutor;
        this.approvalService = approvalService;
        this.metrics = metrics;
        this.sensitiveData = sensitiveData;
        this.sessionHooks = sessionHooks;
        this.enabled = enabled;
        this.exposedPreset = (exposedPreset == null || exposedPreset.isBlank()) ? "read" : exposedPreset;
        // Non-interactive entrypoint: there is no UI emitter behind /api/mcp/server/rpc, so an
        // ASK decision from ApprovalService would block on the 120s approval-wait deadline and
        // then deny — turning every gated call into a 2-minute hang. Default to `yolo` (the
        // dangerous-pattern denylist still applies, and exposed-toolset filtering above still
        // forms the hard cap on which tools can even be reached). Operators that want stricter
        // behaviour can set `harnex.mcp.server-permission-mode=accept-edits` (or any thread-state
        // mode); ASK calls under that mode will fail-fast via the explicit check below rather
        // than hang.
        String pm = (permissionMode == null || permissionMode.isBlank()) ? "yolo"
            : permissionMode.trim().toLowerCase(Locale.ROOT);
        this.permissionMode = pm;
        if (enabled) {
            log.info("McpServerController enabled — exposing toolset '{}' under /api/mcp/server/rpc "
                + "(permission-mode={})", this.exposedPreset, this.permissionMode);
        }
    }

    @GetMapping(path = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> info() {
        if (!enabled)
            return disabledResponse();
        return ResponseEntity.ok(Map.of("name", SERVER_NAME, "protocolVersion", PROTOCOL_VERSION, "capabilities",
            Map.of("tools", Map.of()), "exposedToolset", exposedPreset, "exposedToolCount", exposedTools().size()));
    }

    @PostMapping(path = "/rpc", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rpc(@RequestBody Map<String, Object> body) {
        if (!enabled)
            return disabledResponse();
        Object id = body.get("id");
        String method = String.valueOf(body.getOrDefault("method", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map<?, ?> p ? (Map<String, Object>)p : Map.of();
        try {
            switch (method) {
                case "initialize" -> {
                    return ResponseEntity
                        .ok(jsonRpcResult(id, Map.of("protocolVersion", PROTOCOL_VERSION, "capabilities",
                            Map.of("tools", Map.of()), "serverInfo", Map.of("name", SERVER_NAME, "version", "1.0"))));
                }
                case "tools/list" -> {
                    mcpRegistry.prepareToolsets(List.of(exposedPreset));
                    List<Map<String, Object>> tools = new ArrayList<>();
                    for (Tool t : exposedTools()) {
                        ToolDefinition d = t.getDefinition();
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("name", d.getName() == null ? t.getName() : d.getName());
                        entry.put("description", d.getDescription() == null ? t.getDescription() : d.getDescription());
                        entry.put("inputSchema", d.getParameters() == null
                            ? Map.of("type", "object", "properties", Map.of()) : d.getParameters());
                        tools.add(entry);
                    }
                    return ResponseEntity.ok(jsonRpcResult(id, Map.of("tools", tools)));
                }
                case "tools/call" -> {
                    mcpRegistry.prepareToolsets(List.of(exposedPreset));
                    String name = String.valueOf(params.getOrDefault("name", ""));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args =
                        params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>)a : Map.of();
                    Tool tool = toolRegistry.get(name);
                    if (tool == null) {
                        return ResponseEntity.ok(jsonRpcError(id, -32601, "Unknown tool: " + name));
                    }
                    if (!exposedTools().contains(tool)) {
                        // Defence: a caller might guess a tool name not in the exposed preset
                        // (e.g. "bash" when we only expose 'read'). Refuse explicitly so the
                        // failure mode is "not exposed" rather than "Unknown" — avoids leaking
                        // the existence of internal tools.
                        return ResponseEntity.ok(jsonRpcError(id, -32601,
                            "Tool not exposed by this MCP server (configured toolset='" + exposedPreset + "')"));
                    }
                    ConversationThread synth = new ConversationThread();
                    final String synthId = "mcp-server-" + UUID.randomUUID();
                    synth.setId(synthId);
                    // Wrap the entire tool-call body in try/finally so the synthetic thread's
                    // on-disk footprint (data/uploads/<synthId>/) is
                    // always purged, regardless of which code path returns. Synthetic threads
                    // never go through SessionStore.save, so the normal onThreadDeleted listeners
                    // never fire and these directories would otherwise leak forever on every MCP
                    // tools/call that touches the filesystem (bash, file_write, file_edit,
                    // file_read, glob, grep). SessionHooks is @Autowired(required=false) so
                    // tests that wire this controller without the config tier still work.
                    try {
                        // Apply non-interactive permission mode so ApprovalService does not branch
                        // into ASK on a channel that has no approval UI behind it. Default `yolo`
                        // turns ASK into ALLOW (dangerous-pattern denylist + exposed-toolset cap
                        // remain enforced); other modes (e.g. `accept-edits`) leave ASK calls to
                        // the explicit fail-fast path below.
                        if (synth.getState() != null) {
                            // Always use the normalised field — the constructor trims/lowercases the
                            // raw @Value and falls back to "yolo" on null/blank. The bare
                            // `permissionMode` identifier in this method DOES resolve to the field
                            // (no parameter shadowing here), but spelling it `this.permissionMode`
                            // removes any ambiguity for a future maintainer who refactors this method
                            // to take a permission-mode override as a parameter — at which point the
                            // shadow would silently use the un-normalised raw value and
                            // ApprovalService would fall through to ASK on a non-interactive channel.
                            synth.getState().setPermissionMode(this.permissionMode);
                        }
                        ToolContext ctx = new ToolContext(synth, null);
                        // Build a synthetic AgentCtx with no SSE emitter so middleware hooks
                        // (preToolCall / postToolCall — audit, redact, veto) actually fire. The
                        // earlier two-arg executeBatch overload passes agentCtx=null, which makes
                        // ParallelToolExecutor.run() skip the entire MiddlewareChain branch (see
                        // the `chain != null && agentCtx != null` guard). Without this, MCP RPC
                        // callers bypass the same audit / redact / veto gates that protect the
                        // in-process AgentLoop — a regression once `server-toolset` is widened
                        // beyond `read`.
                        AgentCtx agentCtx = new AgentCtx(synth, null);
                        String argsJson;
                        try {
                            argsJson = M.writeValueAsString(args);
                        } catch (Exception je) {
                            return ResponseEntity.ok(jsonRpcError(id, -32602, "Invalid arguments: " + je.getMessage()));
                        }
                        ToolCall tc =
                            ToolCall.builder().id("mcp-" + UUID.randomUUID()).name(name).arguments(argsJson).build();
                        // Pre-screen the call against ApprovalService BEFORE submitting to the
                        // executor. Required because /api/mcp/server/rpc is non-interactive: the
                        // executor's ASK branch (ParallelToolExecutor.APPROVAL_TIMEOUT_MS = 120_000)
                        // would emit an `approval_required` event into a null emitter and then block
                        // on pollAndWait for the full 2 minutes before denying. By short-circuiting
                        // here we turn that 120s hang into an instant tool-call error.
                        //
                        // Result-shape note: per MCP spec, application-level tool failures are
                        // reported as a normal `result` object with `isError: true`, NOT as a
                        // JSON-RPC `error`. Reserving `error` for protocol-level failures (bad
                        // method, bad params) lets external clients (Claude Desktop / Cursor /
                        // Continue) surface the refusal as "tool returned an error" — actionable
                        // signal — instead of "MCP server is broken" — a session-killer. We also
                        // bump HarnexMetrics so an operator can graph approval refusals via
                        // harnex.approval.blocks just like any other approval-gated path.
                        ApprovalService.Decision pre = approvalService.decide(tc, ctx);
                        if (pre == ApprovalService.Decision.DENY || pre == ApprovalService.Decision.ASK) {
                            metrics.approvalBlocks.increment();
                            metrics.toolErrors.increment();
                            String reason = pre == ApprovalService.Decision.DENY
                                ? Optional.ofNullable(approvalService.denialReason(tc, ctx)).filter(value -> !value.isBlank()).orElse("Denied by approval policy")
                                : "requires interactive approval which is unavailable on the MCP "
                                    + "server endpoint (no UI emitter); permission-mode='" + this.permissionMode
                                    + "' does not auto-allow tool '" + name + "'. "
                                    + "Set harnex.mcp.server-permission-mode=yolo (or widen "
                                    + "accept-edits) to permit this tool over MCP, or call it "
                                    + "through the WebUI/TUI.";
                            log.warn("MCP tools/call refused: tool={} decision={} thread={} reason={}", name, pre,
                                synth.getId(), reason);
                            Map<String, Object> errResult = new LinkedHashMap<>();
                            errResult.put("content", List.of(Map.of("type", "text", "text", reason)));
                            errResult.put("isError", true);
                            errResult.put("durationMs", 0L);
                            return ResponseEntity.ok(jsonRpcResult(id, errResult));
                        }
                        long t0 = System.currentTimeMillis();
                        List<ToolResult> rs = toolExecutor.executeBatch(List.of(tc), ctx, agentCtx);
                        long dur = System.currentTimeMillis() - t0;
                        ToolResult r = rs.isEmpty() ? null : rs.get(0);
                        // Apply the same secret/PII redaction the in-process AgentLoop applies via
                        // SensitiveDataMiddleware.afterTools — the executor only fires preToolCall /
                        // postToolCall, NOT afterTools (afterTools is invoked by AgentLoop after it
                        // appends the tool messages to the thread, which we don't do here). Without
                        // this, an external MCP client calling `bash` / `read_file` over
                        // /api/mcp/server/rpc gets raw credentials & PII back even when
                        // harnex.safety.redact-secrets-in-tool-results is on. The redactor is a
                        // no-op when both flags are off, so this is free for the default config
                        // and only does work when the operator opted into compliance mode.
                        String redacted =
                            sensitiveData.redactToolContent(r == null || r.getContent() == null ? "" : r.getContent());
                        Map<String, Object> result = new LinkedHashMap<>();
                        // MCP `tools/call` response shape — content is an array of typed parts.
                        result.put("content", List.of(Map.of("type", "text", "text", redacted)));
                        result.put("isError", r != null && r.isError());
                        result.put("durationMs", dur);
                        return ResponseEntity.ok(jsonRpcResult(id, result));
                    } finally {
                        if (sessionHooks != null) {
                            try {
                                sessionHooks.purgeEphemeralThread(synthId);
                            } catch (Exception ce) {
                                // Cleanup must never propagate \u2014 the user already has their
                                // tool response above; failing the response over a stale temp
                                // directory would be worse than leaking one.
                                log.debug("MCP synthetic-thread cleanup failed for {}: {}", synthId, ce.toString());
                            }
                        }
                    }
                }
                case "ping" -> {
                    return ResponseEntity.ok(jsonRpcResult(id, Map.of()));
                }
                default -> {
                    return ResponseEntity.ok(jsonRpcError(id, -32601, "Method not implemented: " + method));
                }
            }
        } catch (RuntimeException e) {
            log.warn("MCP server RPC '{}' failed: {}", method, e.getMessage());
            return ResponseEntity.ok(jsonRpcError(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private List<Tool> exposedTools() {
        Set<String> allow = toolsetRegistry.resolve(List.of(exposedPreset));
        List<Tool> out = new ArrayList<>();
        for (String name : allow) {
            Tool t = toolRegistry.get(name);
            if (t != null)
                out.add(t);
        }
        return out;
    }

    private static Map<String, Object> jsonRpcResult(Object id, Object result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("result", result);
        return out;
    }

    private static Map<String, Object> jsonRpcError(Object id, int code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", Map.of("code", code, "message", message));
        return out;
    }

    private static ResponseEntity<?> disabledResponse() {
        return ResponseEntity.status(404)
            .body(Map.of("error", "MCP server mode disabled. Set harnex.mcp.server-enabled=true to enable."));
    }
}
