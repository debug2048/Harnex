package com.harnex.gateway.im;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.storage.SharedFileTransaction;
import com.harnex.config.UserDataPaths;
import com.harnex.gateway.im.ImTypes.Connection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reusable credential snapshots on this Runtime host; never stores workspace permissions or peers. */
final class ImCredentialStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;

    ImCredentialStore(AgentConfig cfg) {
        file = UserDataPaths.resolve(cfg).resolve("im/credentials.json");
    }

    record Credential(String channel, String clientId, String clientSecret, String cardTemplateId) {
        String revision() { return ImTypes.key(channel, clientId, clientSecret, cardTemplateId); }
        @Override public String toString() { return "ImCredential[redacted]"; }
    }

    private Map<String, Credential> read() throws IOException {
        return Files.exists(file) ? JSON.readValue(Files.readAllBytes(file), new TypeReference<LinkedHashMap<String, Credential>>() {})
            : new LinkedHashMap<>();
    }

    List<Map<String, String>> views() {
        try {
            return read().entrySet().stream().map(entry -> {
                Credential value = entry.getValue();
                return Map.of("id", entry.getKey(), "channel", value.channel(), "clientId", value.clientId(),
                    "cardTemplateId", value.cardTemplateId(), "revision", value.revision());
            }).toList();
        } catch (IOException e) { throw new IllegalStateException("已保存的机器人凭据无法读取", e); }
    }

    Credential require(String id, String revision) {
        try {
            Credential value = read().get(id);
            if (value == null || !value.revision().equals(revision))
                throw new IllegalArgumentException("已保存的机器人凭据已变化，请刷新并重新选择");
            return value;
        } catch (IOException e) { throw new IllegalStateException("已保存的机器人凭据无法读取", e); }
    }

    void remember(String channel, Connection connection, boolean replace) {
        if (connection.clientId().isBlank() || connection.clientSecret().isBlank() || ImTypes.MASK.equals(connection.clientSecret())) return;
        SharedFileTransaction.withExclusiveLock(file, () -> {
            Map<String, Credential> entries = read();
            String id = ImTypes.key(channel, connection.clientId());
            Credential value = new Credential(channel, connection.clientId(), connection.clientSecret(), connection.cardTemplateId());
            if ((!replace && entries.containsKey(id)) || value.equals(entries.get(id))) return null;
            entries.put(id, value);
            SharedFileTransaction.writeAtomically(file, JSON.writeValueAsBytes(entries), false, true);
            return null;
        });
    }
}
