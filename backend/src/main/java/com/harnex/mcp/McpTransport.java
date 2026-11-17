package com.harnex.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP (Model Context Protocol) transport abstraction. Handles JSON-RPC 2.0 message exchange over different transport
 * layers.
 */
public interface McpTransport extends AutoCloseable {

    /** Protocol revision supported by this transport's initialize handshake. */
    default String protocolVersion() {
        return "2024-11-05";
    }

    /** Start the transport (connect / launch process). */
    void start() throws Exception;

    /** Send a JSON-RPC request and wait for the response. */
    JsonNode sendRequest(String method, JsonNode params) throws Exception;

    /** Send a JSON-RPC notification (no response expected). */
    void sendNotification(String method, JsonNode params) throws Exception;
}
