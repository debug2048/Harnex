package com.harnex.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Single-instance guard on {@code harnex.dataDir}. Acquires an exclusive OS file lock on {@code <dataDir>/.harnex.lock}
 * at startup; releases it on shutdown.
 *
 * <p>
 * <b>Why this exists.</b> Every stateful component in Harnex — {@link com.harnex.session.FtsSessionStore}'s in-memory
 * caches + Lucene writer, {@link com.harnex.run.ActiveAgentRegistry}'s reservations,
 * {@link com.harnex.gateway.GatewayDispatcher}'s thread bindings, {@link com.harnex.cron.AgentCronScheduler}'s job map,
 * snapshot tombstones, the audit log writer — assumes it is the SOLE owner of {@code dataDir}. Two JVMs pointing at the
 * same dataDir would each have their own copy of every singleton bean and their state would silently diverge: the
 * second JVM would not see the first's reservations, would happily save a thread the first just deleted, and would
 * corrupt the Lucene index when both writers tried to commit.
 * </p>
 *
 * <p>
 * Desktop and IDE hosts use separate execution data directories. Their public history and immutable artifacts
 * live under sharedDataDir, where ProjectStorage serializes archive transactions and ProjectConversationLocks
 * holds per-conversation ownership. This lock still protects every host's mutable caches, indexes and queues.
 * Clients inside one domain (including the integrated CLI) reuse that domain's Runtime API.
 * </p>
 *
 * <p>
 * Failure to acquire the lock aborts startup with a clear error pointing at the existing lock file — far better than
 * booting into a state that silently corrupts user data.
 * </p>
 */
@Slf4j
@Component
public class DataDirLock {

    private static final ConcurrentMap<Path, DataDirLock> LOCAL_OWNERS = new ConcurrentHashMap<>();

    private final AgentConfig cfg;
    private FileChannel channel;
    private FileLock lock;
    private Path lockFile;
    private String ownerHint;

    public DataDirLock(AgentConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    void acquire() {
        Path prepared = RuntimeDataPaths.prepare(cfg);
        // The lock is best-effort opt-out via a system property so unit tests / dev workflows
        // that intentionally share a dataDir (e.g. live-reload) aren't blocked. Production
        // operators should leave this enabled.
        if (Boolean.getBoolean("harnex.disableDataDirLock")) {
            log.warn("DataDirLock disabled via -Dharnex.disableDataDirLock=true — skipping single-instance guard");
            return;
        }
        try {
            Path dir = prepared;
            // Resolve aliases before consulting the JVM-local registry. On POSIX, closing any
            // descriptor for a file can release every classic fcntl lock that this process holds
            // for that file. Therefore a second DataDirLock in this JVM must be rejected before it
            // opens (and later closes) another channel to the lock file.
            dir = dir.toRealPath();
            lockFile = dir.resolve(".harnex.lock");
            String root = cfg.getWorkspace() == null ? "" : String.valueOf(cfg.getWorkspace().getRoot());
            ownerHint = "pid=" + ProcessHandle.current().pid()
                + " runtimeDomainId=" + RuntimeDomainPaths.id(cfg)
                + " ownerKind=" + normalizedOwnerKind(cfg.getRuntimeOwnerKind())
                + " root=" + root
                + " startedAt=" + ProcessHandle.current().info().startInstant()
                    .map(Instant::toString).orElseGet(() -> Long.toString(System.currentTimeMillis()));
            DataDirLock localOwner = LOCAL_OWNERS.putIfAbsent(lockFile, this);
            if (localOwner != null) {
                throw lockConflict(dir, localOwner.ownerHint);
            }
            // Open with READ + WRITE so tryLock() can take an EXCLUSIVE lock (a write-only
            // FileChannel can't hold an exclusive lock on every JVM).
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException ofle) {
                // Defence in depth for an overlapping channel outside DataDirLock's local registry.
                lock = null;
            }
            if (lock == null) {
                throw lockConflict(dir, readPidHint());
            }
            // Write the current PID to the lock file so a future failed-acquire can tell the
            // operator who's holding it. Truncate first so old content (from a previous owner)
            // doesn't bleed through.
            channel.truncate(0);
            channel.write(ByteBuffer.wrap((ownerHint + "\n").getBytes(StandardCharsets.UTF_8)));
            channel.force(false);
            log.info("DataDirLock acquired on {} (pid={})", lockFile, ProcessHandle.current().pid());
        } catch (IllegalStateException ise) {
            // Re-throw the user-facing message above unchanged.
            cleanupOnFailure();
            throw ise;
        } catch (IOException ioe) {
            cleanupOnFailure();
            throw new IllegalStateException("DataDirLock failed to open " + lockFile + ": " + ioe.getMessage(), ioe);
        }
    }

    /**
     * Listen for ApplicationReadyEvent purely to log a confirmation; the lock is already held from {@link #acquire()}.
     */
    @EventListener(ApplicationReadyEvent.class)
    void confirmReady() {
        if (lock != null) {
            log.info("Harnex startup complete; dataDir lock active.");
        }
    }

    @PreDestroy
    void release() {
        String releasingOwner = ownerHint;
        try {
            if (lock != null) {
                lock.release();
                lock = null;
            }
        } catch (IOException e) {
            log.warn("DataDirLock release failed: {}", e.getMessage());
        }
        try {
            if (channel != null) {
                channel.close();
                channel = null;
            }
        } catch (IOException e) {
            log.warn("DataDirLock channel close failed: {}", e.getMessage());
        }
        if (lockFile != null && releasingOwner != null) LOCAL_OWNERS.remove(lockFile, this);
        ownerHint = null;
        // Intentionally do NOT delete the lock file — leaving it (now unlocked) on disk is
        // harmless and lets a future failed-acquire still surface the previous owner's PID
        // hint. Files.deleteIfExists would race with a subsequent quick restart that re-opens
        // the same path under a brand-new FileChannel.
    }

    private void cleanupOnFailure() {
        try {
            if (channel != null)
                channel.close();
        } catch (IOException ignored) {
            /* best effort */
        }
        channel = null;
        lock = null;
        if (lockFile != null && ownerHint != null) LOCAL_OWNERS.remove(lockFile, this);
        ownerHint = null;
    }

    private static IllegalStateException lockConflict(Path dir, String existing) {
        return new IllegalStateException("dataDir " + dir + " is already locked by another Harnex JVM "
            + (existing == null || existing.isEmpty() ? "" : "(" + existing + ") ")
            + "— refusing to start. Stop the other process first, or point this one at a "
            + "different harnex.dataDir. Set -Dharnex.disableDataDirLock=true to bypass "
            + "(NOT recommended; risks data corruption).");
    }

    private static String normalizedOwnerKind(String value) {
        if (value == null || value.isBlank()) return "standalone";
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Best-effort read of the existing lock-file payload to surface to the user. */
    private String readPidHint() {
        // Prefer the channel we already opened for tryLock(). On Windows an exclusive lock can reject a third
        // Files.readString handle even though this losing channel may still read the owner-written bytes.
        if (channel != null && channel.isOpen()) {
            try {
                long size = Math.min(channel.size(), 4096L);
                ByteBuffer buffer = ByteBuffer.allocate((int)size);
                channel.position(0L);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
                buffer.flip();
                String hint = StandardCharsets.UTF_8.decode(buffer).toString().strip();
                if (!hint.isEmpty()) return hint;
            } catch (IOException ignored) {
                // Fall through to an independent best-effort read for filesystems that permit it.
            }
        }
        try {
            return Files.readString(lockFile, StandardCharsets.UTF_8).strip();
        } catch (IOException ignored) {
            return "";
        }
    }
}
