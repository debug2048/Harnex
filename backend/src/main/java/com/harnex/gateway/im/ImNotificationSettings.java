package com.harnex.gateway.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.storage.SharedFileTransaction;
import com.harnex.run.RunNotification.Recipient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A project default, distinct from authorization and from already admitted Run snapshots. */
@Component
@ProjectRuntime
public final class ImNotificationSettings {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    public record Value(String revision, Recipient recipient) {}
    public ImNotificationSettings(AgentConfig config) {
        file = Path.of(config.getDataDir()).toAbsolutePath().resolve("automation/im/notifications.json");
    }
    public Value read() {
        try { return Files.exists(file) ? JSON.readValue(Files.readAllBytes(file), Value.class) : new Value("0", null); }
        catch (Exception failure) { throw new IllegalStateException("通知设置无法读取，请检查项目数据目录", failure); }
    }
    public Value save(String revision, Recipient recipient) {
        return SharedFileTransaction.withExclusiveLock(file, () -> {
            if (!read().revision().equals(revision)) throw new ImConfigStore.ConflictException();
            Value value = new Value(UUID.randomUUID().toString(), recipient);
            SharedFileTransaction.writeAtomically(file, JSON.writeValueAsBytes(value), false, true);
            return value;
        });
    }
}
