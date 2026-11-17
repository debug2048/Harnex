package com.harnex.gateway.im;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.storage.SharedFileTransaction;
import com.harnex.gateway.im.ImTypes.Connection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Project connection snapshots and permissions; reusable credentials require explicit selection. */
@Component
@ProjectRuntime
public class ImConfigStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final ImCredentialStore credentials;
    private Map<String, Connection> connections;
    private String revision;

    public ImConfigStore(AgentConfig cfg) {
        this.file = Path.of(cfg.getDataDir()).toAbsolutePath().resolve("automation/im/connections.json");
        this.credentials = new ImCredentialStore(cfg);
        load();
    }

    private void load() {
        try {
            byte[] data = Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
            connections = data.length == 0 ? new LinkedHashMap<>()
                : JSON.readValue(data, new TypeReference<LinkedHashMap<String, Connection>>() {});
            revision = ImTypes.key(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("IM connection settings cannot be read", e); }
    }

    public synchronized Connection get(String channel) {
        checkChannel(channel);
        return connections.getOrDefault(channel, Connection.empty());
    }

    public synchronized Map<String, Object> view() {
        Map<String, Connection> masked = new LinkedHashMap<>();
        ImTypes.CHANNELS.forEach(channel -> masked.put(channel, get(channel).masked()));
        List<Map<String, String>> saved;
        String credentialError = "";
        try { saved = credentials.views(); }
        catch (RuntimeException e) {
            saved = List.of(); credentialError = "已保存的机器人列表无法读取，请检查用户数据目录";
        }
        return Map.of("revision", revision, "connections", masked, "savedCredentials", saved, "credentialError", credentialError);
    }

    public Connection useCredential(String channel, Connection next, String credentialId, String credentialRevision) {
        checkChannel(channel);
        if (credentialId == null || credentialId.isBlank()) return next;
        var saved = credentials.require(credentialId, credentialRevision);
        if (!saved.channel().equals(channel) || !saved.clientId().equals(next.clientId()) || !ImTypes.MASK.equals(next.clientSecret()))
            throw new IllegalArgumentException("所选机器人与当前凭据不一致，请重新选择");
        return new Connection(next.enabled(), next.clientId(), saved.clientSecret(), next.allowMutations(),
            next.peers(), next.cardTemplateId(), next.allowYolo());
    }

    public String revealSavedSecret(String id, String revision) { return credentials.require(id, revision).clientSecret(); }

    public synchronized String revealSecret(String channel, String expectedRevision, String clientId) {
        Connection current = get(channel);
        if (!revision.equals(expectedRevision) || !current.clientId().equals(clientId)) throw new ConflictException();
        return current.clientSecret();
    }

    public synchronized Connection save(String channel, String expectedRevision, Connection next) {
        return save(channel, expectedRevision, next, false);
    }
    synchronized Connection save(String channel, String expectedRevision, Connection next, boolean reusedCredential) {
        checkChannel(channel);
        Connection normalized = normalize(channel, next);
        return SharedFileTransaction.withExclusiveLock(file, () -> {
            load();
            if (!revision.equals(expectedRevision)) throw new ConflictException();
            Connection old = get(channel);
            if (ImTypes.MASK.equals(normalized.clientSecret()) && !old.clientId().equals(normalized.clientId()))
                throw new IllegalArgumentException("更换机器人后请重新填写密钥或选择已保存的机器人");
            String secret = ImTypes.MASK.equals(normalized.clientSecret()) ? old.clientSecret() : normalized.clientSecret();
            Connection value = new Connection(normalized.enabled(), normalized.clientId(), secret, normalized.allowMutations(), normalized.peers(), normalized.cardTemplateId(), normalized.allowYolo());
            if (!value.cardTemplateId().isEmpty() && !value.cardTemplateId().matches("[A-Za-z0-9_.-]{1,200}"))
                throw new IllegalArgumentException("卡片模板 ID 格式不正确");
            if (value.enabled() && (value.clientId().isBlank() || value.clientSecret().isBlank()))
                throw new IllegalArgumentException("请填写机器人 ID 和密钥后再连接");
            if (value.clientId().length() > 256 || value.clientSecret().length() > 2048 || value.peers().size() > 100)
                throw new IllegalArgumentException("IM configuration is too large");
            if (!old.clientId().equals(value.clientId())) {
                // A new bot must be paired again, even when a form accidentally carries old peers.
                value = new Connection(value.enabled(), value.clientId(), secret, value.allowMutations(), List.of(), value.cardTemplateId(), value.allowYolo());
            }
            for (var peer : value.peers()) {
                if (peer.userId() == null || peer.userId().isBlank() || peer.chatId() == null || peer.chatId().isBlank())
                    throw new IllegalArgumentException("会话授权必须包含发送者和会话 ID");
            }
            Map<String, Connection> updated = new LinkedHashMap<>(connections);
            updated.put(channel, value);
            boolean credentialChanged = !old.clientId().equals(value.clientId()) || !old.clientSecret().equals(value.clientSecret())
                || !old.cardTemplateId().equals(value.cardTemplateId());
            credentials.remember(channel, value, credentialChanged && !reusedCredential);
            SharedFileTransaction.writeAtomically(file, JSON.writeValueAsBytes(updated), false, true);
            load();
            return get(channel);
        });
    }

    public static void checkChannel(String channel) {
        if (!ImTypes.CHANNELS.contains(channel)) throw new IllegalArgumentException("unsupported IM channel");
    }
    static Connection normalize(String channel, Connection value) {
        if (!"telegram".equals(channel) || value.clientSecret().isEmpty() || ImTypes.MASK.equals(value.clientSecret())) return value;
        if (!value.clientSecret().matches("[1-9][0-9]{0,19}:[A-Za-z0-9_-]{20,200}"))
            throw new IllegalArgumentException("Telegram Bot Token 格式不正确，请填写 BotFather 提供的完整 Token");
        String id = value.clientSecret().substring(0, value.clientSecret().indexOf(':'));
        return new Connection(value.enabled(), id, value.clientSecret(), value.allowMutations(), value.peers(), "", value.allowYolo());
    }
    public static class ConflictException extends RuntimeException {
        public ConflictException() { super("设置已变化，请刷新后重试"); }
    }
}
