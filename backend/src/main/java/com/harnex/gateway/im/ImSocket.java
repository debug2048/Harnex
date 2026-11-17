package com.harnex.gateway.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.gateway.im.ImTypes.*;
import com.harnex.gateway.im.ImTypes.Connection;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import okhttp3.*;

import okio.ByteString;

/** One owned connection, with fenced callbacks, bounded backoff and explicit authentication state. */
public abstract class ImSocket implements Transport {
    protected static final ObjectMapper JSON = new ObjectMapper();
    protected final Connection config;
    protected final OkHttpClient http;
    protected final Consumer<Inbound> inbound;
    protected final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "im-connection"); t.setDaemon(true); return t;
    });
    protected volatile WebSocket socket;
    protected volatile boolean closed;
    protected volatile String state = "DISCONNECTED";
    protected volatile String detail = "";
    protected volatile long lastMessageAt;
    private volatile long epoch;
    private int failures;
    private ScheduledFuture<?> reconnect;

    protected ImSocket(Connection config, OkHttpClient http, Consumer<Inbound> inbound) {
        this.config = config; this.http = http; this.inbound = inbound;
    }
    @Override public void start() {
        if (closed) return;
        state = "CONNECTING"; detail = "";
        timer.execute(this::connect);
    }
    protected abstract Request request() throws Exception;
    protected abstract void opened(WebSocket ws) throws Exception;
    protected abstract void received(WebSocket ws, JsonNode frame) throws Exception;
    protected void receivedBinary(WebSocket ws, ByteString frame) throws Exception {
        throw new IllegalArgumentException("unexpected binary frame");
    }
    protected void disconnected() {}

    private void connect() {
        if (closed) return;
        state = "CONNECTING";
        long attempt = ++epoch;
        try {
            Request request = request();
            if (closed || epoch != attempt) return;
            http.newWebSocket(request, new WebSocketListener() {
                private boolean current() { return !closed && epoch == attempt; }
                @Override public void onOpen(WebSocket ws, Response response) {
                    if (!current()) { ws.cancel(); return; }
                    socket = ws;
                    try { opened(ws); } catch (Exception e) { reconnect("连接初始化失败"); }
                }
                @Override public void onMessage(WebSocket ws, String text) {
                    if (!current()) return;
                    try {
                        if (text.length() > 2_000_000) throw new IllegalArgumentException("oversized frame");
                        lastMessageAt = System.currentTimeMillis();
                        received(ws, JSON.readTree(text));
                    } catch (Exception e) {
                        // Do not expose platform frames, credential URLs or arbitrary exception messages.
                        reconnect("消息处理失败，正在重新连接");
                    }
                }
                @Override public void onMessage(WebSocket ws, ByteString bytes) {
                    if (!current()) return;
                    try {
                        if (bytes.size() > 2_000_000) throw new IllegalArgumentException("oversized frame");
                        lastMessageAt = System.currentTimeMillis();
                        receivedBinary(ws, bytes);
                    } catch (Exception e) { reconnect("消息处理失败，正在重新连接"); }
                }
                @Override public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, null);
                    if (current()) reconnect("连接已断开，正在重连");
                }
                @Override public void onClosed(WebSocket ws, int code, String reason) {
                    if (current()) reconnect("连接已断开，正在重连");
                }
                @Override public void onFailure(WebSocket ws, Throwable failure, Response response) {
                    if (current()) reconnect("网络连接失败，请检查网络或代理设置");
                }
            });
            timer.schedule(() -> {
                if (!closed && epoch == attempt && !"CONNECTED".equals(state)) reconnect("连接或认证超时");
            }, 20, TimeUnit.SECONDS);
        } catch (AuthenticationException e) { authenticationFailed(); }
        catch (Exception e) { reconnect("连接失败，请检查凭据、机器人配置及网络"); }
    }
    protected synchronized void connected() {
        if (closed) return;
        failures = 0; state = "CONNECTED"; detail = "";
    }
    protected synchronized void authenticationFailed() {
        epoch++; state = "ERROR"; detail = "认证失败，请检查机器人 ID、密钥和平台接入模式，然后重新连接";
        if (socket != null) socket.cancel();
        socket = null; disconnected();
    }
    protected synchronized void reconnect(String message) {
        if (closed || "ERROR".equals(state)) return;
        epoch++; state = "RECONNECTING"; detail = message;
        if (socket != null) socket.cancel();
        socket = null; disconnected();
        if (reconnect != null) reconnect.cancel(false);
        long delay = Math.min(30, 1L << Math.min(failures++, 5));
        reconnect = timer.schedule(this::connect, delay, TimeUnit.SECONDS);
    }
    protected void sendJson(WebSocket ws, Object frame) throws Exception {
        if (!ws.send(JSON.writeValueAsString(frame))) throw new IllegalStateException("connection queue closed");
    }
    @Override public Map<String, Object> status() {
        return Map.of("state", state, "detail", detail, "lastMessageAt", lastMessageAt);
    }
    @Override public synchronized void close() {
        closed = true; epoch++; state = "DISCONNECTED";
        if (socket != null) socket.cancel();
        socket = null; disconnected(); timer.shutdownNow();
    }
    protected static class AuthenticationException extends Exception { public AuthenticationException() {} }
}
