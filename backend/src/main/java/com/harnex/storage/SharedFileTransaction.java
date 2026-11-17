package com.harnex.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.harnex.util.FilePerms;

/**
 * Cross-process transaction boundary for shared user files. The in-process guard avoids
 * {@code OverlappingFileLockException}; the sibling lock file serialises independent Runtime JVMs.
 */
public final class SharedFileTransaction {

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    private SharedFileTransaction() {}

    @FunctionalInterface
    public interface Operation<T> {
        T run() throws IOException;
    }

    public static <T> T withExclusiveLock(Path target, Operation<T> operation) {
        Path absolute = target.toAbsolutePath().normalize();
        Path lockPath = absolute.resolveSibling(absolute.getFileName() + ".lock");
        ReentrantLock local = LOCAL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock(true));
        local.lock();
        try {
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                channel.truncate(0);
                channel.write(StandardCharsets.UTF_8.encode("pid=" + ProcessHandle.current().pid()
                    + " acquiredAt=" + Instant.now()));
                channel.force(true);
                return operation.run();
            }
        } catch (IOException e) {
            throw new IllegalStateException("shared file transaction failed for " + absolute + ": " + e.getMessage(), e);
        } finally {
            local.unlock();
        }
    }

    public static void writeAtomically(Path target, byte[] bytes, boolean backup) throws IOException {
        writeAtomically(target, bytes, backup, false);
    }

    /**
     * Atomic replacement with optional owner-only hardening. The temporary file is hardened
     * before it becomes visible at the stable path, so API keys never have a world-readable
     * window on a permissive POSIX umask. Backups receive the same policy.
     */
    public static void writeAtomically(Path target, byte[] bytes, boolean backup, boolean ownerOnly) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        String nonce = ProcessHandle.current().pid() + "." + UUID.randomUUID();
        Path tmp = absolute.resolveSibling(absolute.getFileName() + "." + nonce + ".tmp");
        Path backupTmp = null;
        try {
            if (backup && Files.isRegularFile(absolute)) {
                Path backupFile = absolute.resolveSibling(absolute.getFileName() + ".bak");
                backupTmp = absolute.resolveSibling(backupFile.getFileName() + "." + nonce + ".tmp");
                Files.copy(absolute, backupTmp, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
                if (ownerOnly) FilePerms.hardenOwnerOnly(backupTmp);
                moveReplace(backupTmp, backupFile);
                if (ownerOnly) FilePerms.hardenOwnerOnly(backupFile);
                backupTmp = null;
            }
            Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (ownerOnly) FilePerms.hardenOwnerOnly(tmp);
            moveReplace(tmp, absolute);
            if (ownerOnly) FilePerms.hardenOwnerOnly(absolute);
        } finally {
            Files.deleteIfExists(tmp);
            if (backupTmp != null) Files.deleteIfExists(backupTmp);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
