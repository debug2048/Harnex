package com.harnex.conversation;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import com.harnex.config.AgentConfig;
import com.harnex.config.DataDirLock;
import com.harnex.config.RuntimeProfile;

/** Short cross-process storage transactions; provider/tool execution never holds this lock. */
@Component
public final class ProjectStorage {
    private static final Map<Path, ReentrantLock> TRANSACTIONS = new ConcurrentHashMap<>();
    private final Path root;
    private final boolean shared;

    public ProjectStorage(AgentConfig config, DataDirLock localOwner) {
        root = Path.of(config.getSharedDataDir()).toAbsolutePath().normalize();
        shared = config.getRuntimeProfile() == RuntimeProfile.PROJECT;
    }

    public Path root() { return root; }
    public boolean shared() { return shared; }

    public <T> T transaction(Supplier<T> action) {
        if (!shared) return action.get();
        Path file = root.resolve(".history-transaction.lock");
        ReentrantLock local = TRANSACTIONS.computeIfAbsent(file, ignored -> new ReentrantLock());
        local.lock();
        try {
            if (local.getHoldCount() > 1) return action.get();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) { return action.get(); }
            catch (IOException failure) { throw new IllegalStateException("Cannot lock shared history", failure); }
        } finally { local.unlock(); }
    }

}
