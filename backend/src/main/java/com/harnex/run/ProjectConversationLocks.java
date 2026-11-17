package com.harnex.run;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import com.harnex.conversation.ConversationException;
import com.harnex.conversation.ProjectStorage;
import com.harnex.execution.ExecutionSpec;
import com.harnex.util.Ids;
import jakarta.annotation.PreDestroy;

/** Different conversations can execute concurrently; one conversation has one owning host until its Run ends. */
@Component
public final class ProjectConversationLocks implements RunLifecycleParticipant {
    private static final Map<Path, ProjectConversationLocks> LOCAL = new ConcurrentHashMap<>();
    private final Map<String, Lease> leases = new HashMap<>();
    private final Path root;
    private final boolean shared;
    private static final class Lease {
        final Path path; final FileChannel channel; final FileLock lock;
        String runId; boolean active = true; boolean terminal; boolean settled; int mutations;
        Lease(Path path, FileChannel channel, FileLock lock) { this.path = path; this.channel = channel; this.lock = lock; }
    }
    public ProjectConversationLocks(ProjectStorage storage) { root = storage.root().resolve("conversations/leases"); shared = storage.shared(); }
    @Override public boolean supports(ExecutionSpec.Kind kind) { return shared; }
    @Override public synchronized boolean tryReserve(String id) {
        if (!shared) return true;
        if (!Ids.isSafeResourceId(id)) return false;
        Lease held = leases.get(id);
        if (held != null) { if (held.active) return false; held.active = true; return true; }
        Path path = root.resolve(id + ".lock");
        if (LOCAL.putIfAbsent(path, this) != null) return false;
        FileChannel channel = null;
        try {
            Files.createDirectories(root);
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) { channel.close(); LOCAL.remove(path, this); return false; }
            leases.put(id, new Lease(path, channel, lock));
            return true;
        } catch (IOException | RuntimeException failure) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) {}
            LOCAL.remove(path, this);
            throw new IllegalStateException("Cannot acquire conversation ownership", failure);
        }
    }
    synchronized void bind(String id, String runId) {
        Lease held = leases.get(id);
        if (held != null) { held.runId = runId; held.terminal = false; held.settled = false; }
    }
    synchronized boolean recover(String id, String runId) {
        if (!shared) return true;
        if (!tryReserve(id)) return false;
        bind(id, runId); leases.get(id).active = false;
        return true;
    }
    public synchronized boolean owns(String id) { return !shared || leases.containsKey(id); }
    public synchronized boolean ownsRun(String id, String runId) {
        return !shared || leases.containsKey(id) && runId.equals(leases.get(id).runId);
    }
    public synchronized boolean busyElsewhere(String id) {
        if (!shared || leases.containsKey(id)) return false;
        if (!tryReserve(id)) return true;
        release(id); return false;
    }
    public <T> T mutate(String id, Supplier<T> action) {
        if (!shared) return action.get();
        boolean acquired;
        synchronized (this) {
            acquired = !leases.containsKey(id);
            if (acquired && !tryReserve(id)) throw new ConversationException("thread_busy", "此会话正在另一端使用，请先在那里结束任务。");
            leases.get(id).mutations++;
        }
        try { return action.get(); }
        finally {
            synchronized (this) {
                Lease held = leases.get(id);
                if (held != null) {
                    held.mutations--;
                    if (acquired) held.active = false;
                    if (!held.active && held.mutations == 0 && (held.runId == null || held.settled)) unlock(id);
                }
            }
        }
    }
    synchronized void terminal(String id, String runId) {
        Lease held = leases.get(id);
        if (held == null || !runId.equals(held.runId)) return;
        held.terminal = true;
    }
    synchronized void settle(String id, String runId) {
        Lease held = leases.get(id);
        if (held == null || !runId.equals(held.runId) || !held.terminal) return;
        held.settled = true;
        if (!held.active && held.mutations == 0) unlock(id);
    }
    @Override public synchronized void release(String id) {
        Lease held = leases.get(id);
        if (held == null) return;
        held.active = false;
        if (held.mutations == 0 && (held.runId == null || held.settled)) unlock(id);
    }
    private void unlock(String id) {
        Lease held = leases.remove(id);
        if (held == null) return;
        try { held.lock.release(); } catch (IOException ignored) {}
        try { held.channel.close(); } catch (IOException ignored) {}
        LOCAL.remove(held.path, this);
    }
    @Override public synchronized Set<String> activeConversationIds() {
        return leases.entrySet().stream().filter(entry -> entry.getValue().active).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }
    @Override public boolean cancel(String id) { return false; }
    @Override public boolean interrupt(String id) { return false; }
    @PreDestroy public synchronized void close() { for (String id : Set.copyOf(leases.keySet())) unlock(id); }
}
