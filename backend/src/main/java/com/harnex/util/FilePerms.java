package com.harnex.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import java.util.EnumSet;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * Cross-platform helpers for hardening on-disk credential / configuration files to owner-only.
 *
 * <p>
 * Centralised so {@code users.json}, {@code admin-bootstrap.txt}, {@code cron.json} (which embeds prompts + toolsets
 * that effectively grant the agent execution rights) all use the same vetted code path. POSIX hosts get
 * {@code chmod 600}; NTFS / Windows hosts get an ACL stripped of inherited entries and granting only the file owner. A
 * best-effort {@link java.io.File#setReadable(boolean, boolean)} is the final fallback for exotic filesystems.
 */
@Slf4j
public final class FilePerms {

    private FilePerms() {}

    /**
     * Restrict {@code file} to the current process owner. Best-effort and idempotent — logs at WARN and continues if
     * neither POSIX nor ACL views are available (callers must not rely on file-system isolation as the sole defence).
     */
    public static void hardenOwnerOnly(Path file) {
        var fs = file.getFileSystem();
        // 1) POSIX path — the simple, authoritative case.
        if (fs.supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(file,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                return;
            } catch (Exception e) {
                log.warn("hardenOwnerOnly: POSIX chmod 600 failed for {}, falling back: {}", file, e.getMessage());
            }
        }
        // 2) NTFS / Windows path — strip inherited entries, grant only owner.
        AclFileAttributeView aclView = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (aclView != null) {
            try {
                UserPrincipal owner = aclView.getOwner();
                AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                aclView.setAcl(List.of(entry));
                return;
            } catch (Exception e) {
                log.warn("hardenOwnerOnly: ACL hardening failed for {} (falling back to setReadable): {}", file,
                    e.getMessage());
            }
        }
        // 3) Last-resort fallback (pre-NT FS, exotic volumes).
        try {
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
        } catch (Exception ignored) {
            /* best effort */ }
    }
}
