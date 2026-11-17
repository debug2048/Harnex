package com.harnex.util;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.PriorityQueue;

import org.slf4j.LoggerFactory;

/** Retention for disposable per-run JSON diagnostics only, never conversation or billing records. */
public final class DiagnosticRetention {
    private final Path root;
    private final long maxBytes;
    private final int maxFiles;
    private final long maxAgeMillis;
    private long lastSweep;
    private int writes;

    public DiagnosticRetention(Path root) { this(root, 8L * 1024 * 1024, 2048, Duration.ofDays(7)); }
    public DiagnosticRetention(Path root, long maxBytes, int maxFiles, Duration maxAge) {
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes; this.maxFiles = maxFiles; this.maxAgeMillis = maxAge.toMillis();
        if (maxBytes < 1 || maxFiles < 1 || maxAgeMillis < 1) throw new IllegalArgumentException("invalid diagnostic retention");
    }

    public synchronized void afterWrite() {
        long now = System.currentTimeMillis();
        if (++writes < 64 && lastSweep != 0 && now - lastSweep < 60_000) return;
        writes = 0; lastSweep = now;
        try { prune(now); }
        catch (IOException failure) { LoggerFactory.getLogger(getClass()).debug("Diagnostic retention deferred: {}", failure.toString()); }
    }

    public synchronized void prune(long now) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return;
        Path realRoot = root.toRealPath();
        var retained = new PriorityQueue<Entry>(Comparator.comparingLong(Entry::modified));
        long bytes = 0;
        try (var paths = Files.walk(root, 2)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.getFileName().toString().endsWith(".json")
                    || path.getFileName().toString().startsWith(".pending-")) continue;
                if (!path.toRealPath().startsWith(realRoot)) continue;
                long modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
                if (modified < now - maxAgeMillis) { Files.deleteIfExists(path); continue; }
                Entry entry = new Entry(path, Files.size(path), modified);
                retained.add(entry); bytes += entry.bytes();
                // Keep memory bounded even when an old installation has accumulated millions of small records.
                while (retained.size() > maxFiles || bytes > maxBytes) {
                    Entry oldest = retained.remove(); bytes -= oldest.bytes();
                    if (Files.isRegularFile(oldest.path(), LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(oldest.path()).toMillis() == oldest.modified()) Files.deleteIfExists(oldest.path());
                }
            }
        }
        try (var dirs = Files.newDirectoryStream(root)) {
            for (Path directory : dirs) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!directory.toRealPath().startsWith(realRoot)) continue;
                try { Files.delete(directory); } catch (DirectoryNotEmptyException ignored) { }
            }
        }
    }

    private record Entry(Path path, long bytes, long modified) {}
}
