package com.harnex.gateway.im;

import com.harnex.config.ProjectRuntime;
import com.harnex.gateway.im.ImTypes.*;
import com.harnex.gateway.im.dingtalk.DingTalkTransport;
import com.harnex.gateway.im.wecom.WeComTransport;
import com.harnex.gateway.im.feishu.FeishuTransport;
import com.harnex.gateway.im.telegram.TelegramTransport;
import com.harnex.execution.ScopeIdentity;
import java.time.Duration;
import java.util.function.Consumer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import com.harnex.config.AgentConfig;
import com.harnex.config.UserDataPaths;
import com.harnex.net.HttpClients;
import com.harnex.net.OutboundProxyResolver;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
@ProjectRuntime
public class ImTransportFactory {
    private final Path leases;
    private final OutboundProxyResolver proxy;
    private final CardMessages cardMessages;
    private final String scopeId;
    private final Set<Transport> owned = ConcurrentHashMap.newKeySet();
    public ImTransportFactory(AgentConfig cfg, OutboundProxyResolver proxy, ImJournal journal, ScopeIdentity scope) {
        leases = UserDataPaths.resolve(cfg).resolve("im-leases"); this.proxy = proxy; cardMessages = journal;
        scopeId = scope.scopeId();
    }
    public Transport create(String channel, Connection config, Consumer<Inbound> inbound) {
        String host = switch (channel) {
            case "dingtalk" -> "api.dingtalk.com";
            case "wecom" -> "openws.work.weixin.qq.com";
            case "feishu" -> "open.feishu.cn";
            case "telegram" -> "api.telegram.org";
            default -> throw new IllegalArgumentException("unsupported IM channel");
        };
        OkHttpClient http = HttpClients.outboundBuilder(proxy.resolveGlobal(host, 443))
            .connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(15)).writeTimeout(Duration.ofSeconds(10))
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .pingInterval(Duration.ofSeconds(30)).build();
        Transport connection = switch (channel) {
            case "dingtalk" -> new DingTalkTransport(config, http, inbound);
            case "wecom" -> new WeComTransport(config, http, inbound);
            case "feishu" -> new FeishuTransport(config, http, inbound, cardMessages);
            case "telegram" -> new TelegramTransport(config, http, inbound, cardMessages,
                leases.resolve(ImTypes.key(channel, config.clientId()) + ".cursor.json"), scopeId);
            default -> throw new IllegalArgumentException("unsupported IM channel");
        };
        Transport transport = new Transport() {
            private FileChannel file;
            private FileLock lock;
            private boolean started;
            private String failure = "";
            @Override public synchronized void start() {
                if (started) return;
                try {
                    Files.createDirectories(leases);
                    file = FileChannel.open(leases.resolve(ImTypes.key(channel, config.clientId()) + ".lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    lock = file.tryLock();
                    if (lock == null) throw new IllegalStateException("already owned");
                    started = true; connection.start();
                } catch (Exception e) {
                    failure = "机器人已由另一工作区连接，或无法取得连接锁。请断开另一工作区后重新连接。";
                    closeResources();
                }
            }
            @Override public void send(Inbound target, String id, String text) throws DeliveryException {
                if (!started) throw new DeliveryException(DeliveryException.Kind.RETRYABLE, "连接尚未建立");
                connection.send(target, id, text);
            }
            @Override public void sendNotification(Inbound target, String id, String text) throws DeliveryException {
                if (!started) throw new DeliveryException(DeliveryException.Kind.RETRYABLE, "连接尚未建立");
                connection.sendNotification(target, id, text);
            }
            @Override public boolean sendCard(Inbound target, ImInteractionCard card) throws DeliveryException { return connection.sendCard(target, card); }
            @Override public boolean updateCard(Inbound target, ImInteractionCard card) throws DeliveryException { return connection.updateCard(target, card); }
            @Override public void onCardAction(Function<CardAction, CardResult> handler) { connection.onCardAction(handler); }
            @Override public Map<String, Object> status() {
                return failure.isEmpty() ? connection.status() : Map.of("state", "ERROR", "detail", failure, "lastMessageAt", 0);
            }
            private void closeResources() {
                try { if (lock != null) lock.release(); } catch (Exception ignored) {}
                try { if (file != null) file.close(); } catch (Exception ignored) {}
                lock = null; file = null;
            }
            @Override public synchronized void close() {
                connection.close(); started = false;
                http.dispatcher().cancelAll(); http.dispatcher().executorService().shutdownNow(); http.connectionPool().evictAll();
                closeResources(); owned.remove(this);
            }
        };
        owned.add(transport); return transport;
    }
    @PreDestroy public void close() {
        owned.forEach(Transport::close); owned.clear();
    }
}
