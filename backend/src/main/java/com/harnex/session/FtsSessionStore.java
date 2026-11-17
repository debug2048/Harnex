package com.harnex.session;

import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.util.Ids;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON-file metadata + Lucene inverted index full-text search. Hermes-inspired (SQLite + FTS5 equivalent).
 */
@Slf4j
@Component
@ProjectRuntime
public class FtsSessionStore implements SessionStore {

    private static final ObjectMapper M =
        new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final AgentConfig cfg;
    private final Path metaDir;
    private final Path indexDir;
    /** Lightweight index: id → metadata only (no messages). Always complete. */
    private final ConcurrentMap<String, ThreadMeta> metaCache = new ConcurrentHashMap<>();
    /** Full-thread LRU cache: at most MAX_CACHED_THREADS entries. */
    private static final int MAX_CACHED_THREADS = 200;
    private final LinkedHashMap<String, ConversationThread> fullCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Serial
        private static final long serialVersionUID = -5487370258528129830L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ConversationThread> e) {
            return size() > MAX_CACHED_THREADS;
        }
    };
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    /**
     * Per-thread fingerprint of the most recently indexed (msg-count, content-hash) tuple. Lets {@link #reindex}
     * short-circuit when a {@link #save} call only mutated metadata (title, model, toolsets) without changing any
     * message text — the previous Lucene doc is already correct so we skip the expensive content rebuild + writer
     * round-trip entirely. Cleared on {@link #delete} and rebuilt on startup.
     */
    private final ConcurrentMap<String, Long> indexedFingerprint = new ConcurrentHashMap<>();
    private final List<Consumer<String>> deleteListeners = new CopyOnWriteArrayList<>();

    /**
     * Recently-deleted thread ids → deletion timestamp (epoch ms). Consulted by {@link #save} UNDER THE SAME PER-THREAD
     * LOCK as {@link #delete} so the previous "AgentLoop check tombstone → user delete → AgentLoop save" race window is
     * closed atomically: any save() arriving after delete() has acquired+released the lock will see the entry and bail
     * before (re)writing the JSON / re-indexing / re-publishing the cache.
     *
     * <p>
     * Bounded by {@link #DELETE_TOMBSTONE_TTL_MS}: a thread that hasn't been re-saved within the TTL is no longer at
     * risk of resurrection by a stale in-flight loop, so the entry can age out and the id becomes reusable (e.g. cron
     * jobs that recycle stable ids, tests that re-create the same thread). Pruned lazily on each delete()/save() check
     * — the harness never has more than a handful of threads in flight, so a dedicated janitor is not worth the
     * lifecycle complexity.
     * </p>
     */
    private final ConcurrentMap<String, Long> deletedRecently = new ConcurrentHashMap<>();
    /**
     * Thread ids whose Lucene deindex failed after the JSON was successfully removed. {@code writer.deleteDocuments()}
     * throws do NOT re-queue the operation inside the writer, so {@code dirty.set(true)} / subsequent flush will NOT
     * retry it — the stale segment entry persists until a full reindex. Tracking failed ids here lets {@link #search}
     * filter them out immediately (so deleted threads don’t appear in search results) and retry the deindex on the next
     * search call (opportunistic lazy repair without a dedicated scheduler).
     */
    private final Set<String> pendingDeindex = ConcurrentHashMap.newKeySet();
    /**
     * Long enough to outlast any realistic single agent run (multiple approvals + many tool rounds). Short enough that
     * an operator who deletes a thread and immediately re-creates one with the same id (rare, but allowed for cron)
     * sees the new thread persist.
     */
    private static final long DELETE_TOMBSTONE_TTL_MS = 60L * 60 * 1000;

    /**
     * Persisted alongside the index segments — bumping this constant (or the analyzer choice itself) triggers a
     * one-shot startup reindex so old token streams are rebuilt with the new analyzer. Without this, switching from
     * StandardAnalyzer → CJKAnalyzer would silently leave existing CJK threads unsearchable since their tokens were
     * emitted by the old analyzer.
     *
     * v2 (B4 follow-up): system messages are now excluded from the indexed content + snippet so the sidebar search
     * results visually match what the chat UI renders (system prompts are hidden in ChatView). Bumping the tag forces
     * existing indexes to be rebuilt with the new role filter — otherwise old documents would keep returning
     * system-prompt text in snippets.
     *
     * v3: every document carries a root/subagent discriminator. User-facing search only queries roots; persisted child
     * traces remain directly addressable by id for the subagent inspector without becoming ordinary history records.
     */
    private static final String ANALYZER_TAG = "cjk-v3";

    private Analyzer analyzer;
    private FSDirectory dir;
    // volatile so a reopenWriter() executed after a commit-failure burst publishes the new
    // IndexWriter / SearcherManager visibly to concurrent save() / search() callers without
    // requiring them to take the reopen monitor on the hot path.
    private volatile IndexWriter writer;
    private volatile SearcherManager searcherManager;
    private ScheduledExecutorService commitScheduler;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    /**
     * Consecutive flushIfDirty failures. Reset on every successful commit. After {@link #COMMIT_REOPEN_THRESHOLD}
     * failures we attempt one writer reopen; after {@link #COMMIT_DEGRADED_THRESHOLD} we flip {@link #ftsDegraded} so
     * /search falls back to a JSON scan instead of silently returning stale Lucene results.
     */
    private final AtomicInteger commitFailures = new AtomicInteger(0);
    private static final int COMMIT_REOPEN_THRESHOLD = 5;
    // Degrade aggressively (3 failures = ~3 seconds of stale results at the default flush
    // cadence) so users do not silently see an out-of-date search index. The previous value of
    // 10 left a multi-second window during which freshly-saved messages were not findable yet
    // /search showed "no results" without any hint that the index was stuck. degradeIfThreshold
    // -Reached is idempotent so this only flips ftsDegraded once.
    private static final int COMMIT_DEGRADED_THRESHOLD = 3;
    /**
     * When true, the Lucene index is unhealthy and {@link #search} degrades to a simple in-memory contains-scan over
     * metaCache+fullCache so the user still gets results.
     */
    private final AtomicBoolean ftsDegraded = new AtomicBoolean(false);
    /**
     * Flips to true once the background metadata load completes. {@link #listThreads} can use this to know whether the
     * result is authoritative (true) or partial (false).
     */
    private final AtomicBoolean metaCacheReady = new AtomicBoolean(false);
    /**
     * Set the moment {@link #close} begins so {@link #flushIfDirty} and {@link #reopenWriter} can short-circuit instead
     * of resurrecting a writer that close() is in the middle of tearing down. Without this guard, a scheduled commit
     * tick racing close() would observe {@code AlreadyClosedException} after close() shut the writer, fall into the
     * reopen branch, and create a brand-new {@link IndexWriter} that nothing ever closes \u2014 holding the Lucene
     * segment files open and breaking @TempDir cleanup on Windows tests (which cannot delete a memory-mapped file while
     * the JVM still owns the handle) and leaving stale .lock files in production after JVM shutdown that prevent the
     * next startup.
     */
    private final AtomicBoolean closing = new AtomicBoolean(false);

    public FtsSessionStore(AgentConfig cfg) {
        this.cfg = cfg;
        this.metaDir = Paths.get(cfg.getDataDir(), "agent/threads");
        this.indexDir = Paths.get(cfg.getDataDir(), "indexes/agent-threads");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(metaDir);
            Files.createDirectories(indexDir);
            // CJKAnalyzer bigrams CJK runs (so 中文搜索 splits into 中文/文搜/搜索) AND treats Latin
            // input the same as StandardAnalyzer — covers the multilingual corpus we actually see.
            analyzer = new CJKAnalyzer();
            dir = FSDirectory.open(indexDir);
            // Detect analyzer change: if the on-disk tag mismatches, wipe the index segments before
            // opening the writer so we can rebuild from JSON metadata on the next pass.
            boolean reindexNeeded = needsReindex(indexDir);
            if (reindexNeeded) {
                log.warn("FTS analyzer marker missing/mismatch \u2014 wiping index for full reindex (target tag={})",
                    ANALYZER_TAG);
                wipeIndexSegments(indexDir);
            }
            writer = new IndexWriter(dir, new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND).setRAMBufferSizeMB(16));
            // SearcherManager gives near-real-time search with reader reuse — avoids
            // per-query open/close of DirectoryReader which is heavy under load.
            searcherManager = new SearcherManager(writer, true, true, null);
            // Commit every 1s if dirty, plus on shutdown. Trades a 1s RPO window for
            // higher save() throughput (no per-save fsync). RAM buffer capped at 16MB above
            // so even sustained writes will auto-flush to disk before OOM.
            commitScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fts-commit");
                t.setDaemon(true);
                return t;
            });
            commitScheduler.scheduleWithFixedDelay(this::flushIfDirty, 1, 1, TimeUnit.SECONDS);
            // Load thread metadata only (not full messages) into index cache. With thousands of
            // threads on disk this can take seconds and previously blocked Spring boot. We now
            // dispatch the walk to a daemon thread so the HTTP listener becomes ready immediately;
            // searches that race the warm-up will see partial results and fall back to scanning
            // the on-disk JSON files (handled below in {@link #findThread}).
            Thread bootLoader = new Thread(() -> {
                try (var stream = Files.list(metaDir)) {
                    int loaded = 0;
                    for (Path p : (Iterable<Path>)stream::iterator) {
                        if (!p.toString().endsWith(".json"))
                            continue;
                        try {
                            ConversationThread t =
                                M.readValue(Files.readString(p, StandardCharsets.UTF_8), ConversationThread.class);
                            metaCache.put(t.getId(), ThreadMeta.from(t));
                            loaded++;
                        } catch (Exception e) {
                            log.warn("Load thread {} failed: {}", p, e.getMessage());
                        }
                    }
                    log.info("FtsSessionStore lazy-loaded {} thread index entries", loaded);
                    metaCacheReady.set(true);
                } catch (IOException ioe) {
                    log.warn("FtsSessionStore lazy-load walk failed: {}", ioe.getMessage());
                    metaCacheReady.set(true); // unblock waiters; cache may be partial
                }
            }, "fts-meta-load");
            bootLoader.setDaemon(true);
            bootLoader.start();
            if (reindexNeeded) {
                int rebuilt = reindexAllFromDisk();
                writeAnalyzerTag(indexDir);
                log.info("FTS reindex complete: {} threads rebuilt with analyzer={}", rebuilt, ANALYZER_TAG);
            } else if (!Files.isRegularFile(indexDir.resolve("analyzer.tag"))) {
                // Fresh index: stamp the marker so subsequent boots are no-ops.
                writeAnalyzerTag(indexDir);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns true when the on-disk analyzer marker is missing or doesn't match {@link #ANALYZER_TAG}. */
    private static boolean needsReindex(Path indexDir) {
        Path marker = indexDir.resolve("analyzer.tag");
        if (!Files.isRegularFile(marker)) {
            // First-run heuristic: if there are pre-existing segments but no marker, the index was
            // created by an older build (StandardAnalyzer) and must be rebuilt.
            try (var s = Files.list(indexDir)) {
                return s.anyMatch(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("segments") || n.endsWith(".cfs") || n.endsWith(".cfe");
                });
            } catch (IOException e) {
                return false;
            }
        }
        try {
            return !ANALYZER_TAG.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return true;
        }
    }

    private static void wipeIndexSegments(Path indexDir) {
        try (var s = Files.list(indexDir)) {
            s.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    /* best effort */ }
            });
        } catch (IOException ignored) {
            /* best effort */ }
    }

    /**
     * Serialises the synchronous fallback scan triggered from {@link #list} when the background boot loader hasn't
     * completed yet. Without this, every concurrent request after restart would each walk the metadata dir in parallel.
     */
    private final Object syncLoadLock = new Object();

    /**
     * Synchronously load any thread metadata that the background boot loader hasn't reached yet. Idempotent — re-entry
     * is a no-op once {@link #metaCacheReady} is set, and individual entries use {@code putIfAbsent} so a racing async
     * load never overwrites the same key.
     */
    private void ensureMetaLoadedSync() {
        synchronized (syncLoadLock) {
            if (metaCacheReady.get()) {
                return;
            }
            try (var stream = Files.list(metaDir)) {
                int loaded = 0;
                for (Path p : (Iterable<Path>)stream::iterator) {
                    if (!p.toString().endsWith(".json"))
                        continue;
                    try {
                        ConversationThread t =
                            M.readValue(Files.readString(p, StandardCharsets.UTF_8), ConversationThread.class);
                        if (metaCache.putIfAbsent(t.getId(), ThreadMeta.from(t)) == null) {
                            loaded++;
                        }
                    } catch (Exception e) {
                        log.warn("ensureMetaLoadedSync: thread {} failed: {}", p, e.getMessage());
                    }
                }
                log.info("FtsSessionStore sync-loaded {} additional thread index entries", loaded);
            } catch (IOException ioe) {
                log.warn("FtsSessionStore sync-load walk failed: {}", ioe.getMessage());
            } finally {
                // Mark ready so subsequent callers don't pay the scan cost again, even if the
                // async loader is still mid-flight (it will continue to putIfAbsent harmlessly).
                metaCacheReady.set(true);
            }
        }
    }

    private static void writeAnalyzerTag(Path indexDir) {
        try {
            Path tmp = indexDir.resolve("analyzer.tag.tmp");
            Files.writeString(tmp, ANALYZER_TAG, StandardCharsets.UTF_8);
            Files.move(tmp, indexDir.resolve("analyzer.tag"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("FTS analyzer.tag write failed: {}", e.getMessage());
        }
    }

    /** Re-emit every thread on disk into the writer so tokens are produced by the current analyzer. */
    private int reindexAllFromDisk() {
        int n = 0;
        try (var stream = Files.list(metaDir)) {
            for (Path p : (Iterable<Path>)stream::iterator) {
                if (!p.toString().endsWith(".json"))
                    continue;
                try {
                    ConversationThread t =
                        M.readValue(Files.readString(p, StandardCharsets.UTF_8), ConversationThread.class);
                    reindex(t);
                    n++;
                } catch (Exception e) {
                    log.warn("FTS reindex {} skipped: {}", p.getFileName(), e.getMessage());
                }
            }
            // Force a commit so the new tokens are durable BEFORE we stamp the marker.
            writer.commit();
            searcherManager.maybeRefresh();
            dirty.set(false);
        } catch (IOException e) {
            log.warn("FTS reindex walk failed: {}", e.getMessage());
        }
        return n;
    }

    @PreDestroy
    public void close() {
        // Idempotent: Spring fires @PreDestroy at most once but tests can call close() explicitly
        // too. The flag is the LINCHPIN of this method — it must be set BEFORE the scheduler
        // shutdown so any tick that wakes up between shutdown() and awaitTermination() sees it
        // and bails out of reopenWriter().
        if (!closing.compareAndSet(false, true))
            return;
        if (commitScheduler != null) {
            // shutdown() then shutdownNow() if a tick is wedged: a scheduled flush that just
            // entered writer.commit() will be interrupted, releasing any IO-blocked thread that
            // would otherwise outlive close() and resurrect the writer.
            commitScheduler.shutdown();
            try {
                if (!commitScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    commitScheduler.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                commitScheduler.shutdownNow();
            }
        }
        try {
            flushIfDirty();
        } catch (Exception e) {
            log.warn("final flush: {}", e.getMessage());
        }
        try {
            if (searcherManager != null) {
                searcherManager.close();
            }
        } catch (IOException e) {
            log.warn("Close searcherManager: {}", e.getMessage());
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            log.warn("Close writer: {}", e.getMessage());
        }
        try {
            if (dir != null) {
                dir.close();
            }
        } catch (IOException e) {
            log.warn("Close dir: {}", e.getMessage());
        }
    }

    private void flushIfDirty() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        try {
            writer.commit();
            searcherManager.maybeRefresh();
            // Healthy commit — clear the failure counter and lift any degraded flag we set
            // during a transient outage so /search switches back to Lucene results.
            if (commitFailures.getAndSet(0) > 0 && ftsDegraded.compareAndSet(true, false)) {
                log.info("FTS commit recovered — search restored to Lucene index");
            }
        } catch (AlreadyClosedException ace) {
            // Writer entered a tragedy state (typically after a previous IOException). Try a
            // single reopen so the next save() can write again. If reopen fails we fall through
            // to the generic IOException branch which marks the index dirty and degrades.
            // Skip the reopen when we are tearing down: close() asks for a final flush and an
            // AlreadyClosed there means close()'s own writer.close() already landed (rare race
            // on a scheduled tick still in flight). Resurrecting the writer here would leak file
            // handles that nothing closes, breaking @TempDir cleanup on Windows and leaving
            // stale segment locks in production.
            if (closing.get()) {
                log.debug("FTS flushIfDirty: writer already closed during shutdown — not reopening");
                return;
            }
            log.error("FTS writer closed unexpectedly — attempting reopen", ace);
            if (!reopenWriter()) {
                degradeIfThresholdReached();
            }
        } catch (IOException e) {
            dirty.set(true); // retry next tick
            int n = commitFailures.incrementAndGet();
            log.warn("FTS commit failed (#{}): {}", n, e.getMessage());
            if (n == COMMIT_REOPEN_THRESHOLD && !closing.get()) {
                log.error("FTS commit failed {} times in a row — attempting writer reopen", n);
                reopenWriter();
            }
            degradeIfThresholdReached();
        }
    }

    /** True iff the Lucene index is currently unhealthy (failed commit / corrupt / closed). */
    public boolean isFtsDegraded() {
        return ftsDegraded.get();
    }

    private void degradeIfThresholdReached() {
        if (commitFailures.get() >= COMMIT_DEGRADED_THRESHOLD && ftsDegraded.compareAndSet(false, true)) {
            log.error("FTS marked DEGRADED after {} consecutive commit failures — /search will "
                + "fall back to a JSON contains-scan until commits recover. Check disk space "
                + "and permissions on {}", commitFailures.get(), indexDir);
        }
    }

    /**
     * Close and recreate the IndexWriter + SearcherManager in place. Best-effort: returns true on success. Callers must
     * be prepared for false (we stay in degraded mode and the next commit attempt will retry the reopen).
     */
    private synchronized boolean reopenWriter() {
        // Reject reopen attempts during shutdown so a flushIfDirty tick racing close() can
        // never resurrect the writer that close() just tore down. See the {@code closing}
        // field's javadoc for the @TempDir/Windows symptom this prevents.
        if (closing.get()) {
            log.info("FTS reopenWriter skipped — store is closing");
            return false;
        }
        try {
            try {
                if (searcherManager != null)
                    searcherManager.close();
            } catch (IOException ignored) {
                /* best */ }
            try {
                if (writer != null)
                    writer.close();
            } catch (IOException ignored) {
                /* best */ }
            writer = new IndexWriter(dir, new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND).setRAMBufferSizeMB(16));
            searcherManager = new SearcherManager(writer, true, true, null);
            log.info("FTS writer reopened");
            return true;
        } catch (IOException e) {
            log.error("FTS writer reopen failed: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void save(ConversationThread t) {
        String id = Ids.requireSafe(t.getId(), "threadId");
        Object lock = locks.computeIfAbsent(id, k -> new Object());
        synchronized (lock) {
            // Tombstone gate: if this id was DELETEd recently (under this same per-thread lock),
            // refuse the save. Closes the previous "loop checks tombstone → user deletes → loop
            // saves anyway" check-then-save race because the deletion timestamp lookup happens
            // INSIDE the lock that delete() also holds. Skipping silently (no exception) keeps
            // AgentLoop's terminal `done` SSE flowing — the loop has already cleaned up uploads/
            // sandbox/snapshots elsewhere; resurrecting the JSON would just re-orphan them.
            Long deletedAt = deletedRecently.get(id);
            if (deletedAt != null) {
                if (System.currentTimeMillis() - deletedAt <= DELETE_TOMBSTONE_TTL_MS) {
                    log.info("Refusing save for thread {} — deleted {}ms ago (tombstoned)", id,
                        System.currentTimeMillis() - deletedAt);
                    return;
                }
                // Stale tombstone — let the save proceed and clean up.
                deletedRecently.remove(id);
            }
            // Stamp updatedAt up-front so the metadata we eventually publish to the caches
            // matches what we serialise to disk. We do NOT touch metaCache / fullCache yet —
            // a disk-full / EACCES below must leave the in-memory view unchanged so a
            // subsequent get()/list() reflects exactly what is durable on disk.
            t.setUpdatedAt(Instant.now());
            Path file = metaDir.resolve(id + ".json");
            // Defense-in-depth: ensure the resolved path is still inside metaDir.
            if (!file.normalize().startsWith(metaDir.toAbsolutePath().normalize())
                && !file.normalize().startsWith(metaDir.normalize())) {
                throw new SecurityException("threadId escapes metaDir: " + id);
            }
            Path tmp = metaDir.resolve(id + ".json.tmp");
            try {
                Files.writeString(tmp, M.writerWithDefaultPrettyPrinter().writeValueAsString(t),
                    StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException amnse) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                // Best-effort cleanup of the tmp file so a partially-written sibling doesn't
                // accumulate on disk after repeated failures.
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    /* best effort */ }
                log.error("Persist thread {} failed: {}", id, e.getMessage());
                // Surface to the caller (AgentLoop wraps this in try/catch and emits
                // `error{persist_failed}`). Silently swallowing produced a cache/disk split
                // where the UI showed messages that were lost on the next restart.
                throw new UncheckedIOException("save thread " + id, e);
            }
            // Disk write succeeded — now publish to caches so readers see the new state.
            metaCache.put(id, ThreadMeta.from(t));
            synchronized (fullCache) {
                fullCache.put(id, t);
            }
            try {
                reindex(t);
            } catch (IOException reindexErr) {
                // The JSON is durably saved but the Lucene doc update failed. Drop the
                // fingerprint so the *next* save() rebuilds this thread's doc rather than
                // short-circuiting on an unchanged-content check, and force a commit retry
                // by marking the index dirty. /search would otherwise silently return stale
                // results for this thread until the process restarts.
                log.warn("FTS reindex failed for {} (JSON saved, index stale, will retry on next save): {}", id,
                    reindexErr.getMessage());
                indexedFingerprint.remove(id);
                dirty.set(true);
            }
        }
    }

    private void reindex(ConversationThread t) throws IOException {
        // Build the content blob first so we can fingerprint it and skip the writer round-trip
        // when no message text changed since the last save (a save() that only touched title /
        // model / toolsets re-enters here even though the indexed document would be identical).
        //
        // B4 follow-up: system messages are intentionally skipped. The chat UI never renders
        // system prompts (see PromptBuilder / ChatView), so surfacing them in sidebar search
        // snippets was both visually confusing ("why does this result show text I've never
        // seen?") and noisy (matching on boilerplate persona text that's identical across
        // every thread). Tool-result messages stay in the index since the UI does render them.
        StringBuilder content = new StringBuilder();
        for (ChatMessage m : t.getMessages()) {
            if (m.getContent() == null)
                continue;
            if ("system".equals(m.getRole()))
                continue;
            content.append(m.getRole()).append(": ").append(m.getContent()).append('\n');
        }
        // Mix message count into the fingerprint so an identical empty/blank delta still flips it
        // when the structure changes (e.g. role-only messages). Title is also indexed (see the
        // TextField below) so it MUST participate in the fingerprint — without it a rename-only
        // save() short-circuits and /search keeps returning the old title (or fails to find the
        // new one).
        String title = t.getTitle() == null ? "" : t.getTitle();
        long fp = 31L * t.getMessages().size() + content.toString().hashCode();
        fp = 31L * fp + title.hashCode();
        fp = 31L * fp + (t.getSubagentDepth() > 0 ? 1 : 0);
        Long prev = indexedFingerprint.get(t.getId());
        if (prev != null && prev == fp) {
            return;
        }
        Document d = new Document();
        d.add(new StringField("threadId", t.getId(), Field.Store.YES));
        d.add(new StringField("threadKind", t.getSubagentDepth() > 0 ? "subagent" : "root", Field.Store.NO));
        d.add(new TextField("title", title, Field.Store.YES));
        d.add(new TextField("content", content.toString(), Field.Store.NO));
        d.add(new StoredField("snippet", content.substring(0, Math.min(300, content.length()))));
        d.add(new NumericDocValuesField("updatedAt", t.getUpdatedAt().toEpochMilli()));
        writer.updateDocument(new Term("threadId", t.getId()), d);
        indexedFingerprint.put(t.getId(), fp);
        dirty.set(true);
    }

    @Override
    public Optional<ConversationThread> get(String id) {
        // Validate first: prevents path-traversal via crafted thread ids when we bypass the
        // metaCache existence gate below.
        if (!Ids.isSafe(id)) {
            return Optional.empty();
        }
        // P1 #3: refuse to "resurrect" a thread that delete() observed but couldn't fully clean
        // up off disk (e.g. IOException during Files.deleteIfExists from a stale file lock or
        // permission flap). Without this, the on-disk JSON would lazy-load below and the
        // sidebar / chat would silently re-show a conversation the user just deleted. The same
        // tombstone is consulted by save() so it already guards against re-persistence; reading
        // honours the same contract for symmetry. TTL is bounded (DELETE_TOMBSTONE_TTL_MS) so a
        // genuine re-create of the same id after the window will resolve normally.
        if (isDeletedRecently(id)) {
            return Optional.empty();
        }
        synchronized (fullCache) {
            ConversationThread cached = fullCache.get(id);
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        // Lazy-load from disk. Previously gated on metaCache.containsKey(id), which created a
        // boot-time race: if a request arrived before the background metadata loader had reached
        // this thread's JSON file, get() returned empty and the caller (Chat / Gateway) would
        // create a NEW thread with the same id and overwrite the historical conversation on
        // next save(). Going straight to disk eliminates that window — metaCache is then
        // populated on the subsequent save() so list()/search() catch up too.
        Path file = metaDir.resolve(id + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            ConversationThread t =
                M.readValue(Files.readString(file, StandardCharsets.UTF_8), ConversationThread.class);
            synchronized (fullCache) {
                fullCache.put(id, t);
            }
            // Backfill the metadata cache so list() reflects this thread even if the boot
            // walker hasn't reached it yet.
            metaCache.putIfAbsent(id, ThreadMeta.from(t));
            return Optional.of(t);
        } catch (Exception e) {
            log.warn("Lazy-load thread {} failed: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ConversationThread> list(int offset, int limit) {
        // If the background boot loader hasn't finished yet, the user-facing sidebar would render
        // an empty (or partial) list right after restart even though the JSON files exist on disk.
        // Do a one-shot synchronous scan inline so the very first list() call after boot is
        // authoritative. The scan is idempotent (putIfAbsent) and guarded so concurrent callers
        // don't all walk the dir at once.
        if (!metaCacheReady.get()) {
            ensureMetaLoadedSync();
        }
        // Defensive clamp: negative offset/limit from a misbehaving client must not throw
        // IndexOutOfBoundsException out of subList() below.
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(0, limit);
        List<ThreadMeta> all = new ArrayList<>(metaCache.values());
        // Child threads are persisted diagnostic traces, not user conversations. Filter before
        // paging so hidden children do not consume an offset/limit slot in the history sidebar.
        all.removeIf(m -> m.subagentDepth() > 0);
        all.sort(Comparator.comparing(ThreadMeta::updatedAt).reversed());
        if (safeLimit == 0 || safeOffset >= all.size()) {
            return List.of();
        }
        int end = Math.min(all.size(), safeOffset + safeLimit);
        List<ConversationThread> result = new ArrayList<>();
        for (ThreadMeta m : all.subList(safeOffset, end)) {
            ConversationThread stub = new ConversationThread();
            stub.setId(m.id());
            stub.setTitle(m.title());
            stub.setFullTitle(m.fullTitle());
            stub.setUpdatedAt(m.updatedAt());
            stub.setModel(m.model());
            stub.setToolsets(m.toolsets() == null ? List.of() : new ArrayList<>(m.toolsets()));
            result.add(stub);
        }
        return result;
    }

    @Override
    public boolean delete(String id) {
        if (!Ids.isSafe(id)) {
            return false;
        }
        Object lock = locks.computeIfAbsent(id, k -> new Object());
        boolean existed;
        synchronized (lock) {
            // Important: metaCache is lazy-loaded — a delete() racing a process restart (or
            // simply landing before the first list() that populates the cache) can find no
            // entry to remove yet still have an on-disk JSON to clean up. Combine BOTH signals
            // so we (a) actually remove the file and (b) still fire deleteListeners (memory
            // cleanup, audit, FTS deindex hooks) for those id's. Previously the early
            // existed=false path skipped the listeners entirely.
            // Snapshot cache entries BEFORE removing so we can restore them if the file-delete
            // leg fails — otherwise an IOException leaves the UI thinking the thread is gone
            // (sidebar drops it) while the JSON sits on disk waiting to resurrect on restart.
            ThreadMeta savedMeta = metaCache.get(id);
            ConversationThread savedFull;
            synchronized (fullCache) {
                savedFull = fullCache.get(id);
            }
            boolean cacheHit = metaCache.remove(id) != null;
            synchronized (fullCache) {
                fullCache.remove(id);
            }
            indexedFingerprint.remove(id);
            boolean fileDeleted = false;
            boolean jsonIoFailed = false;
            try {
                fileDeleted = Files.deleteIfExists(metaDir.resolve(id + ".json"));
            } catch (IOException e) {
                jsonIoFailed = true;
                log.warn("Delete {}: could not remove JSON file — thread is still on disk: {}", id, e.getMessage());
            }
            if (jsonIoFailed) {
                // JSON delete failed: the thread data is still on disk, still usable.
                // Restore the cache entries we just evicted so list() / get() / save() continue
                // to work normally. Do NOT tombstone — isDeletedRecently is consulted by get()
                // and save(); stamping it here would corrupt a thread that physically still exists.
                // Return false so the API layer can surface the failure to the caller.
                if (savedMeta != null) {
                    metaCache.putIfAbsent(id, savedMeta);
                }
                if (savedFull != null) {
                    synchronized (fullCache) {
                        fullCache.putIfAbsent(id, savedFull);
                    }
                }
                // existed stays false → deleteListeners do NOT fire → uploads / sandbox kept.
                existed = false;
            } else {
                // JSON is gone (or never existed). The Lucene deindex is best-effort: an
                // IOException here is a stale index entry, NOT a reason to declare delete
                // failed. The writer's dirty flag causes a re-flush on the next commit, and
                // search will simply return 0 hits for this id once the segment is refreshed.
                // The thread data on disk is gone so we MUST proceed with the success path:
                // tombstone, run listeners (cleans uploads/snapshots/tool_results), return true.
                if (writer != null) {
                    try {
                        writer.deleteDocuments(new Term("threadId", id));
                        dirty.set(true);
                    } catch (IOException e) {
                        // deleteDocuments() did NOT queue the delete inside the writer, so a
                        // later flush/commit will NOT retry it automatically. Track this id in
                        // pendingDeindex so search() filters it out and retries opportunistically.
                        pendingDeindex.add(id);
                        dirty.set(true);
                        log.warn("Delete {}: JSON removed but Lucene deindex failed — "
                            + "queued for retry on next search: {}", id, e.getMessage());
                    }
                }
                existed = cacheHit || fileDeleted;
                // Stamp the tombstone INSIDE the lock so any concurrent save() from an in-flight
                // AgentLoop / GatewayDispatcher / SubAgent that's about to enter its own
                // synchronized(lock) block will observe the entry and bail. Lazy-prune entries
                // older than the TTL while we're here so the map stays bounded over time.
                long now = System.currentTimeMillis();
                deletedRecently.put(id, now);
                if (deletedRecently.size() > 256) {
                    deletedRecently.entrySet().removeIf(e2 -> now - e2.getValue() > DELETE_TOMBSTONE_TTL_MS);
                }
            }
        }
        // Intentionally do NOT remove the per-thread lock object. The previous two-arg
        // remove(id, lock) had an ABA window: between the synchronized block exit and the
        // remove() call, another save() could have computeIfAbsent'd and acquired the SAME
        // lock object. After remove() succeeds, a third save/delete computeIfAbsent's a NEW
        // lock object, and the two operations now run against the same thread file/cache/Lucene
        // doc under different monitors. The locks map's growth is bounded by the lifetime
        // population of unique thread ids — a very modest leak in exchange for correctness.
        // Notify listeners outside the lock to avoid lock-order cycles.
        if (existed) {
            for (var cb : deleteListeners) {
                try {
                    cb.accept(id);
                } catch (Exception e) {
                    log.warn("delete listener failed: {}", e.getMessage());
                }
            }
        }
        return existed;
    }

    /** Register a callback invoked (outside any lock) when a thread is deleted. */
    public void onThreadDeleted(Consumer<String> cb) {
        deleteListeners.add(cb);
    }

    @Override
    public boolean isDeletedRecently(String id) {
        if (id == null)
            return false;
        Long ts = deletedRecently.get(id);
        if (ts == null)
            return false;
        if (System.currentTimeMillis() - ts > DELETE_TOMBSTONE_TTL_MS) {
            // Lazy cleanup of an aged-out entry so a subsequent re-creation of the same id
            // (allowed once TTL elapses) is not pessimistically refused by stale state.
            deletedRecently.remove(id, ts);
            return false;
        }
        return true;
    }

    /** Lightweight metadata for the index cache — avoids holding all messages in memory. */
    public record ThreadMeta(String id, String title, String fullTitle, Instant updatedAt, String model, List<String> toolsets,
        int subagentDepth) {
        static ThreadMeta from(ConversationThread t) {
            return new ThreadMeta(t.getId(), t.getTitle(), resolvedFullTitle(t), t.getUpdatedAt(), t.getModel(),
                t.getToolsets() == null ? List.of() : List.copyOf(t.getToolsets()), t.getSubagentDepth());
        }

        private static String resolvedFullTitle(ConversationThread t) {
            if (t.getFullTitle() != null && !t.getFullTitle().isBlank()) {
                return t.getFullTitle();
            }
            return t.getTitle() == null ? "" : t.getTitle();
        }
    }

    @Override
    public List<SearchHit> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // Degraded mode: Lucene index has been failing to commit. Fall back to a
        // case-insensitive contains-scan over titles in metaCache so the user keeps
        // getting *some* results until commits recover. Body content isn't held in
        // metaCache by design (memory budget) so this is intentionally lossy — the
        // alternative is silently returning stale Lucene hits, which is worse.
        if (ftsDegraded.get()) {
            return degradedTitleScan(query, Math.max(1, limit));
        }
        // Opportunistic retry of deindex operations that failed during delete(). The writer
        // may be healthy now; remove from pending on success so the set stays small.
        if (!pendingDeindex.isEmpty() && writer != null) {
            for (var pid : pendingDeindex) {
                try {
                    writer.deleteDocuments(new Term("threadId", pid));
                    dirty.set(true);
                    pendingDeindex.remove(pid);
                    log.info("Deindex retry succeeded for deleted thread {}", pid);
                } catch (IOException e) {
                    log.debug("Deindex retry still failing for {}: {}", pid, e.getMessage());
                }
            }
        }
        List<SearchHit> out = new ArrayList<>();
        IndexSearcher searcher = null;
        try {
            // SearcherManager caches readers and refreshes on commit — acquire/release per query is O(1).
            searcher = searcherManager.acquire();
            MultiFieldQueryParser p = new MultiFieldQueryParser(new String[] {"title", "content"}, analyzer);
            p.setDefaultOperator(QueryParser.Operator.AND);
            Query textQuery = p.parse(QueryParser.escape(query));
            Query rootOnlyQuery = new BooleanQuery.Builder()
                .add(textQuery, BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term("threadKind", "root")), BooleanClause.Occur.FILTER)
                .build();
            TopDocs top = searcher.search(rootOnlyQuery, Math.max(1, limit));
            StoredFields sf = searcher.storedFields();
            for (ScoreDoc sd : top.scoreDocs) {
                Document d = sf.document(sd.doc);
                String tid = d.get("threadId");
                // Filter stale Lucene hits: (a) thread was deleted and listeners fired but the
                // deindex failed (pendingDeindex), (b) thread is in the tombstone window
                // (deletedRecently), (c) thread is gone from metaCache for any other reason
                // (concurrent delete racing this search). All three mean "this thread no longer
                // exists" from the user’s perspective; returning it would let them click a
                // dead entry in the sidebar.
                ThreadMeta meta = metaCache.get(tid);
                if (pendingDeindex.contains(tid) || isDeletedRecently(tid) || meta == null
                    || meta.subagentDepth() > 0) {
                    continue;
                }
                ThreadMeta hitMeta = metaCache.get(tid);
                String title = d.get("title");
                String fullTitle = hitMeta == null ? title : hitMeta.fullTitle();
                out.add(new SearchHit(tid, title, fullTitle,
                    d.get("snippet") == null ? "" : d.get("snippet"), sd.score));
            }
            // Re-order by recency (updatedAt desc) so search results match the sidebar's
            // reverse-chronological main list. Lucene returns by TF-IDF score, which surfaces
            // the strongest textual matches first — but users perceive the sidebar as a
            // timeline, so a hit on an older thread ranked first under the most-recent
            // matching one made them look in the wrong place ("clicked but nothing selected").
            // Sort key comes from metaCache so we don't have to store a sortable numeric
            // field on every Lucene Document just for this view. Threads missing from the
            // cache (race with concurrent delete) sort last via Instant.EPOCH.
            out.sort((a, b) -> {
                ThreadMeta ma = metaCache.get(a.threadId());
                ThreadMeta mb = metaCache.get(b.threadId());
                Instant ta = ma != null ? ma.updatedAt() : Instant.EPOCH;
                Instant tb = mb != null ? mb.updatedAt() : Instant.EPOCH;
                return tb.compareTo(ta);
            });
        } catch (Exception e) {
            log.warn("FTS search failed: {}", e.getMessage());
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException e) {
                    log.warn("release: {}", e.getMessage());
                }
            }
        }
        return out;
    }

    private List<SearchHit> degradedTitleScan(String query, int limit) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<SearchHit> out = new ArrayList<>();
        for (ThreadMeta m : metaCache.values()) {
            if (m.subagentDepth() > 0) {
                continue;
            }
            String title = m.title() == null ? "" : m.title();
            if (title.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(new SearchHit(m.id(), title, m.fullTitle(), "(fts degraded — title match only)", 0f));
                if (out.size() >= limit)
                    break;
            }
        }
        return out;
    }
}
