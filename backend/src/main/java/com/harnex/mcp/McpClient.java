package com.harnex.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * High-level MCP client. Wraps a {@link McpTransport} and speaks JSON-RPC 2.0 using its supported MCP revision.
 */
@Slf4j
public class McpClient implements AutoCloseable {

    private static final ObjectMapper M = new ObjectMapper();

    private final String serverName;
    private final McpTransport transport;
    private String serverVersion;
    private JsonNode serverCapabilities;
    private boolean closed;

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
    }

    /** Connect transport + MCP initialize handshake. */
    public void connect() throws Exception {
        synchronized (this) {
            if (closed) throw new IOException("MCP client is closed");
            transport.start();
        }

        // initialize request
        ObjectNode params = M.createObjectNode();
        params.put("protocolVersion", transport.protocolVersion());
        params.set("capabilities", M.createObjectNode());
        ObjectNode clientInfo = M.createObjectNode();
        clientInfo.put("name", "Harnex");
        clientInfo.put("version", "1.0.0");
        params.set("clientInfo", clientInfo);

        JsonNode resp = transport.sendRequest("initialize", params);
        checkError(resp, "initialize");

        JsonNode result = resp.get("result");
        if (result != null) {
            serverVersion = result.path("serverInfo").path("version").asText("");
            serverCapabilities = result.get("capabilities");
            log.info("MCP server '{}' initialized (version={}, protocol={})", serverName, serverVersion,
                result.path("protocolVersion").asText(""));
        }

        // Send initialized notification
        transport.sendNotification("notifications/initialized", null);
    }

    /** Discover tools from the MCP server. */
    public List<McpToolInfo> listTools() throws Exception {
        JsonNode resp = transport.sendRequest("tools/list", null);
        checkError(resp, "tools/list");

        List<McpToolInfo> tools = new ArrayList<>();
        JsonNode result = resp.get("result");
        if (result != null && result.has("tools")) {
            for (JsonNode t : result.get("tools")) {
                McpToolInfo info = new McpToolInfo();
                info.setName(t.path("name").asText());
                info.setDescription(t.path("description").asText(""));
                info.setInputSchema(t.has("inputSchema") ? M.convertValue(t.get("inputSchema"), Map.class) : Map.of());
                // C8 (P2): surface MCP tool annotation hints so the safety stack treats them
                // the same as built-in tools. The MCP spec (2024-11-05) places these on
                // tools[].annotations.{destructiveHint,readOnlyHint}. Absent = leave null so
                // McpToolAdapter can apply its conservative default.
                JsonNode ann = t.get("annotations");
                if (ann != null && ann.isObject()) {
                    if (ann.has("destructiveHint")) {
                        info.setDestructiveHint(ann.get("destructiveHint").asBoolean(false));
                    }
                    if (ann.has("readOnlyHint")) {
                        info.setReadOnlyHint(ann.get("readOnlyHint").asBoolean(false));
                    }
                }
                tools.add(info);
            }
        }
        log.debug("MCP server '{}' provides {} tools: {}", serverName, tools.size(),
            tools.stream().map(McpToolInfo::getName).toList());
        return tools;
    }

    /** Call a tool on the MCP server. */
    public McpToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        ObjectNode params = M.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", M.valueToTree(arguments));

        JsonNode resp = transport.sendRequest("tools/call", params);

        // Check for JSON-RPC error
        if (resp.has("error")) {
            JsonNode err = resp.get("error");
            return new McpToolResult(true,
                "MCP error " + err.path("code").asInt() + ": " + err.path("message").asText(""));
        }

        JsonNode result = resp.get("result");
        if (result == null) {
            return new McpToolResult(true, "Empty response from MCP server");
        }

        boolean isError = result.path("isError").asBoolean(false);
        StringBuilder text = new StringBuilder();
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode item : content) {
                String type = item.path("type").asText("text");
                if ("text".equals(type)) {
                    if (!text.isEmpty()) {
                        text.append("\n");
                    }
                    text.append(item.path("text").asText(""));
                } else if ("image".equals(type)) {
                    text.append("[image: ").append(item.path("mimeType").asText("")).append("]");
                } else if ("resource".equals(type)) {
                    JsonNode resource = item.path("resource");
                    if (resource.path("text").isTextual()) text.append("\n").append(resource.path("text").asText());
                    else text.append("[resource: ").append(resource.path("uri").asText("")).append("]");
                } else if ("resource_link".equals(type)) {
                    text.append("\n[resource: ").append(item.path("uri").asText("")).append("]");
                }
            }
        }
        JsonNode structured = result.get("structuredContent");
        if (structured != null && !structured.isNull()) text.append("\n").append(structured.toPrettyString());
        return new McpToolResult(isError, text.toString(), content, structured);
    }

    public String getServerName() {
        return serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public JsonNode getServerCapabilities() {
        return serverCapabilities;
    }

    @Override
    public void close() throws Exception {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        transport.close();
    }

    private void checkError(JsonNode resp, String method) throws IOException {
        if (resp.has("error")) {
            JsonNode err = resp.get("error");
            throw new IOException(
                "MCP " + method + " failed: " + err.path("code").asInt() + " " + err.path("message").asText(""));
        }
    }

    // ── Inner types ──────────────────────────────────────────────

    @Data
    public static class McpToolInfo {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;
        /**
         * MCP {@code annotations.destructiveHint} from {@code tools/list}. {@code null} means the server did not
         * advertise a hint (treat as safe default = false). {@code true} means the server explicitly flagged the tool
         * as mutating external state — {@link com.harnex.mcp.McpToolAdapter#isDestructive()} forwards this so the
         * ApprovalService gate can prompt the user.
         */
        private Boolean destructiveHint;
        /** MCP {@code annotations.readOnlyHint}; {@code null} means "not advertised". */
        private Boolean readOnlyHint;
    }

    @Data
    public static class McpToolResult {
        private final boolean error;
        private final String text;
        private final JsonNode content;
        private final JsonNode structuredContent;
        public McpToolResult(boolean error, String text) { this(error, text, null, null); }
        public McpToolResult(boolean error, String text, JsonNode content, JsonNode structuredContent) {
            this.error = error; this.text = text; this.content = content; this.structuredContent = structuredContent;
        }
    }
}
