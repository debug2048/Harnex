package com.harnex.mcp;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harnex.process.ChildProcessEnvironment;

import lombok.extern.slf4j.Slf4j;

/**
 * MCP transport over stdio — launches a child process and communicates via newline-delimited JSON-RPC 2.0 on
 * stdin/stdout.
 */
@Slf4j
public class StdioMcpTransport implements McpTransport {

    private static final ObjectMapper M = new ObjectMapper();

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;

    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private Thread errThread;

    /**
     * Pending requests keyed by JSON-RPC id. Id is a {@code long} (was {@code int}) to push the rollover point from
     * ~2.1 billion to ~9.2 quintillion requests per process, eliminating a real — if astronomically rare — risk that a
     * long-lived MCP server's id sequence would wrap into negatives and collide with an in-flight request, returning
     * the wrong response to a caller.
     */
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    public StdioMcpTransport(String command, List<String> args, Map<String, String> env) {
        this.command = command;
        this.args = args != null ? args : List.of();
        this.env = env != null ? env : Map.of();
    }

    @Override
    public void start() throws Exception {
        List<String> cmd = new ArrayList<>();
        // On Windows, .cmd scripts (npx, node, etc.) need cmd.exe wrapper
        if (isWindows() && !command.endsWith(".exe")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(command);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Set<String> injectedSecrets = new HashSet<>();
        pb.environment().forEach((name, value) -> {
            if (name.startsWith(ChildProcessEnvironment.KEYCHAIN_PROVIDER_PREFIX) && value != null
                && !value.isEmpty()) {
                injectedSecrets.add(value);
            }
        });
        pb.environment().putAll(env);
        ChildProcessEnvironment.sanitize(pb);
        // Spring may have resolved ${HARNEX_KEYCHAIN_PROVIDER_*} before McpRegistry receives the config, so the
        // original reference name can be gone. Strip matching values too; an MCP-specific credential must use its own
        // secret rather than borrowing a desktop Provider credential.
        if (!injectedSecrets.isEmpty()) {
            pb.environment().entrySet().removeIf(entry -> injectedSecrets.contains(entry.getValue()));
        }
        pb.redirectErrorStream(false);
        // Defensive setup: if anything between pb.start() and the reader threads being healthy
        // throws, the OS-level child process will leak (file descriptors, possibly socket /
        // database connections inside a long-lived MCP server). On rapid-failure (e.g. wrong
        // binary path) this used to exhaust the parent's file descriptor table within minutes.
        try {
            process = ChildProcessEnvironment.start(pb);
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            startReaderThreads();
        } catch (Throwable t) {
            // Tear down anything we already constructed so the failed start() leaves no zombies.
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                    /* best-effort */ }
                writer = null;
            }
            if (process != null) {
                try {
                    process.destroyForcibly();
                } catch (Exception ignored) {
                    /* best-effort */ }
                process = null;
            }
            throw t;
        }
    }

    private void startReaderThreads() {

        // Reader thread: parse JSON-RPC responses from stdout
        readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonNode msg = M.readTree(line);
                        if (msg.has("id") && !msg.get("id").isNull()) {
                            long id = msg.get("id").asLong();
                            CompletableFuture<JsonNode> fut = pending.remove(id);
                            if (fut != null) {
                                fut.complete(msg);
                            }
                        }
                        // Notifications from server (no id) are logged
                        else if (msg.has("method")) {
                            log.debug("MCP server notification: {}", msg.get("method").asText());
                        }
                    } catch (Exception e) {
                        log.trace("Ignoring non-JSON line from MCP server: {}", line);
                    }
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.warn("MCP stdio reader ended: {}", e.getMessage());
                }
            }
            // Complete all pending futures on stream close
            pending.forEach((id, fut) -> fut.completeExceptionally(new IOException("MCP process ended")));
            pending.clear();
        }, "mcp-stdio-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // Stderr logger
        errThread = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    log.debug("[MCP stderr] {}", line);
                }
            } catch (IOException ignored) {
            }
        }, "mcp-stdio-stderr");
        errThread.setDaemon(true);
        errThread.start();
    }

    @Override
    public JsonNode sendRequest(String method, JsonNode params) throws Exception {
        long id = idSeq.getAndIncrement();
        ObjectNode req = M.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }

        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);

        // Write under the writer monitor. ANY failure here (broken pipe, EOF on a dead child,
        // IOException flushing) MUST remove the pending entry — otherwise the map grows
        // unboundedly across reconnect attempts and every prior caller's CompletableFuture
        // sits forever waiting for a response that will never arrive (their thread blocks on
        // fut.get(60s) and finally times out, while the leaked entry stays in `pending` until
        // close() finally drains it). The previous code only cleaned up on the timeout path.
        try {
            synchronized (writer) {
                writer.write(M.writeValueAsString(req));
                writer.newLine();
                writer.flush();
            }
        } catch (Throwable writeFail) {
            pending.remove(id);
            fut.completeExceptionally(writeFail);
            // Re-throw so the caller surfaces the underlying transport failure (consistent with
            // the original contract: sendRequest throws on transport errors).
            if (writeFail instanceof Exception ex) {
                throw ex;
            }
            throw new IOException("MCP stdio write failed", writeFail);
        }

        try {
            return fut.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IOException("MCP request timed out: " + method);
        } catch (Exception e) {
            // Reader thread may have completed the future exceptionally (e.g. on stream close)
            // — make sure we don't leak the pending entry on any other failure path either.
            pending.remove(id);
            throw e;
        }
    }

    @Override
    public void sendNotification(String method, JsonNode params) throws Exception {
        ObjectNode req = M.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }

        synchronized (writer) {
            writer.write(M.writeValueAsString(req));
            writer.newLine();
            writer.flush();
        }
    }

    @Override
    public void close() throws Exception {
        // Order matters: close STDIN first so the child gets EOF on its read loop and exits cleanly
        // when possible, THEN destroy the process if it didn't exit, THEN interrupt our reader threads.
        // The previous order (interrupt reader → close writer → destroyForcibly) left a window where
        // a wedged child holding the pipe would also wedge the reader threads in a blocking readLine()
        // — daemon threads can be force-killed by the JVM at shutdown but they could leave any pending
        // CompletableFutures uncompleted, which then hung user-facing tool calls until their own 60s
        // timeout fired.
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception e) {
                log.debug("MCP stdio writer.close() failed: {}", e.getMessage());
            }
        }
        if (process != null) {
            // Give the child a brief opportunity to drain on EOF before we kill it. 200ms is plenty
            // for a well-behaved MCP server and trivial for a misbehaving one.
            try {
                process.waitFor(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        // Now interrupt readers so a stuck readLine() unblocks even if the pipe is still backed up.
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (errThread != null) {
            errThread.interrupt();
            try {
                errThread.join(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        // Fail any callers still parked on a pending response so they unblock instead of timing out at 60s.
        pending.forEach((id, fut) -> fut.completeExceptionally(new IOException("MCP transport closed")));
        pending.clear();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
