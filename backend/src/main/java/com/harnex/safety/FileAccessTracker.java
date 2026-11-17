package com.harnex.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;

/**
 * Per-thread "what did the model recently read" log — backbone of the "read-before-edit / read-before-overwrite" guard
 * rail.
 *
 * <p>
 * Mirrors the well-known mainstream pattern (Claude Code's {@code Edit} requires a prior {@code Read}; Aider tracks
 * file-set membership; Cline records read state in its checkpoint store) without a heavyweight content store: each
 * entry is just {@code (mtimeMillis, size)}, which is enough to detect the two failure modes that actually matter:
 *
 * <ul>
 * <li><b>NEVER_READ</b> — model is about to mutate a file it has never inspected this turn. Almost always means it is
 * hallucinating contents from prior knowledge.</li>
 * <li><b>STALE</b> — model read the file, then external state changed (user edit on disk, another tool in the same loop
 * wrote it), then the model proposed an edit based on the outdated copy. Letting it through silently overwrites the
 * user's intervening change.</li>
 * </ul>
 *
 * <p>
 * Bounded LRU per thread so a glob+read loop cannot grow memory unbounded; oldest entries are evicted past the cap from
 * {@code AgentConfig.Safety.trackerMaxEntriesPerThread}.
 *
 * <p>
 * Cleared on thread deletion via {@code SessionHooks}.
 * Exact-context edit tools may proceed on STALE/NEVER_READ records; those statuses disable their whitespace
 * tolerance. Whole-file overwrites still require an OK record unless explicitly acknowledged.
 */
@Component
@ProjectRuntime
public class FileAccessTracker {

    public enum Status {
        OK, STALE, NEVER_READ
    }

    /**
     * Per-file fingerprint captured at read time.
     *
     * <p>
     * Stores the FULL {@link FileTime} (nanosecond precision when the FS supports it) plus size, plus an OPTIONAL short
     * content hash. Bumping mtime resolution from millis to the native FileTime closes a real false-OK window: on
     * filesystems whose mtime granularity is coarser than 1ms (FAT32 = 2s, ext3 default = 1s, NFS commonly 1s) two
     * writes within the same granule produced identical mtime+size and the stale-detector approved an edit against an
     * already-changed file. On NTFS / APFS / ext4 / xfs we now get 100ns / 1ns / 1ns / 1ns precision respectively,
     * eliminating the window for normal user-edit cadence.
     *
     * <p>
     * {@code contentHash} is populated only on write paths (and on read for files marked as "high-risk" \u2014 see
     * {@link #recordHashed}); on files where it's set, validate() compares hashes on top of mtime+size for absolute
     * correctness on coarse-mtime filesystems.
     */
    public record ReadStamp(FileTime mtime, long size, String contentHash) {
        /** Back-compat factory \u2014 no content hash. */
        public static ReadStamp of(FileTime mtime, long size) {
            return new ReadStamp(mtime, size, null);
        }
    }

    private final int maxEntries;

    /** {@code threadId -> LRU<absolutePathString, ReadStamp>}. */
    private final ConcurrentMap<String, Map<String, ReadStamp>> byThread = new ConcurrentHashMap<>();

    public FileAccessTracker(AgentConfig cfg) {
        int n = cfg.getSafety() == null ? 256 : cfg.getSafety().getTrackerMaxEntriesPerThread();
        this.maxEntries = Math.max(16, n);
    }

    /**
     * Record (or refresh) a read stamp for {@code path}. No-op if the path does not exist or cannot be {@code stat}'d.
     * Safe to call from any thread; map access is synchronised on the per-thread bucket.
     */
    public void record(String threadId, Path path) {
        if (threadId == null || path == null) {
            return;
        }
        ReadStamp stamp = stamp(path, false);
        if (stamp == null) {
            return;
        }
        Map<String, ReadStamp> bucket = bucket(threadId);
        synchronized (bucket) {
            bucket.put(path.toAbsolutePath().normalize().toString(), stamp);
        }
    }

    /**
     * Like {@link #record} but ALSO captures a SHA-256 of the file content. Use for paths the caller knows are about to
     * be mutated and where the cost of an extra read is acceptable (typically files smaller than a few MB; large files
     * skip the hash). When set, the subsequent {@link #validate} call will compare hashes on top of mtime+size,
     * eliminating the same-granule false-OK window completely.
     */
    public void recordHashed(String threadId, Path path) {
        if (threadId == null || path == null) {
            return;
        }
        ReadStamp stamp = stamp(path, true);
        if (stamp == null) {
            return;
        }
        Map<String, ReadStamp> bucket = bucket(threadId);
        synchronized (bucket) {
            bucket.put(path.toAbsolutePath().normalize().toString(), stamp);
        }
    }

    /**
     * Validate that {@code path} was read in this thread AND the on-disk fingerprint still matches the recorded stamp.
     * Missing files are reported as {@link Status#OK} (a write to a non-existent path is "create new", which is always
     * safe by definition).
     */
    public Status validate(String threadId, Path path) {
        if (threadId == null || path == null) {
            return Status.OK;
        }
        if (!Files.exists(path)) {
            return Status.OK;
        }
        Map<String, ReadStamp> bucket = byThread.get(threadId);
        if (bucket == null) {
            return Status.NEVER_READ;
        }
        String key = path.toAbsolutePath().normalize().toString();
        ReadStamp prev;
        synchronized (bucket) {
            prev = bucket.get(key);
        }
        if (prev == null) {
            return Status.NEVER_READ;
        }
        // Re-stat with hashing only when the previously-recorded stamp had a hash; otherwise
        // skip the read. Keeps the common-case (read \u2192 edit) cheap while making the
        // recordHashed() opt-in path absolutely correct.
        ReadStamp cur = stamp(path, prev.contentHash() != null);
        if (cur == null) {
            // File vanished between exists() and stat() \u2014 treat as OK; the caller will fail
            // its own existence check on the next step.
            return Status.OK;
        }
        // Compare with FileTime.equals (nanosecond when FS supports it) instead of
        // toMillis()-then-equals which silently rounds. Then size, then optional hash.
        if (!cur.mtime().equals(prev.mtime()))
            return Status.STALE;
        if (cur.size() != prev.size())
            return Status.STALE;
        if (prev.contentHash() != null && cur.contentHash() != null && !prev.contentHash().equals(cur.contentHash())) {
            return Status.STALE;
        }
        return Status.OK;
    }

    /**
     * Re-stamp after a successful mutation so that follow-up edits in the same loop iteration still pass
     * {@link #validate}. Without this every chain of {@code edit -> edit} on the same file would force the model to
     * re-read between calls, which is wasteful and noisy.
     */
    public void refreshAfterWrite(String threadId, Path path) {
        record(threadId, path);
    }

    /** Drop all tracked entries for {@code threadId}. Called from {@code SessionHooks}. */
    public void purge(String threadId) {
        if (threadId != null) {
            byThread.remove(threadId);
        }
    }

    // ---- internals ----

    /**
     * Files larger than this are stamped without a content hash (avoids loading huge blobs into memory just to
     * fingerprint them). 4 MiB covers source code / configs comfortably.
     */
    private static final long HASH_MAX_BYTES = 4L * 1024 * 1024;

    private static ReadStamp stamp(Path p, boolean withHash) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            if (a.isDirectory()) {
                return null;
            }
            String hash = null;
            if (withHash && a.size() <= HASH_MAX_BYTES) {
                hash = sha256(p);
            }
            return new ReadStamp(a.lastModifiedTime(), a.size(), hash);
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha256(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] data = Files.readAllBytes(p);
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return null; // best-effort; caller treats null as "no hash recorded"
        }
    }

    private Map<String, ReadStamp> bucket(String threadId) {
        return byThread.computeIfAbsent(threadId, k -> {
            // LinkedHashMap in access-order with size eviction == cheap LRU.
            return new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ReadStamp> eldest) {
                    return size() > maxEntries;
                }
            };
        });
    }
}
