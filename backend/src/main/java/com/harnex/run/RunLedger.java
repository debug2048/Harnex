package com.harnex.run;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.conversation.ProjectStorage;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

/**
 * Durable, workspace-local run facts.
 *
 * <p>Only the versioned {@link RunRecord} contract crosses the persistence boundary. Unknown, pre-C0 map ledgers are
 * intentionally rejected rather than guessed or migrated because the product has not shipped those formats.</p>
 */
@Slf4j
@Service
public class RunLedger {

    private static final ObjectMapper M = new ObjectMapper();

    private final Path root;
    private Path publishedRoot;
    private ProjectStorage storage;
    private final ConcurrentMap<String, RunRecord> pendingPublication = new ConcurrentHashMap<>();
    private ScheduledExecutorService publicationRetry;

    @Autowired
    public RunLedger(AgentConfig cfg, ProjectStorage storage) {
        this(cfg);
        this.storage = storage;
        for (RunRecord record : list()) pendingPublication.put(record.runId(), record);
        publicationRetry = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "run-history-publication"); thread.setDaemon(true); return thread;
        });
        publicationRetry.scheduleWithFixedDelay(this::retryPublication, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    public RunLedger(AgentConfig cfg) {
        this(Path.of(cfg.getDataDir()).toAbsolutePath().normalize().resolve("runs/records"));
        publishedRoot = Path.of(cfg.getSharedDataDir()).resolve("history-runs/records");
    }

    public RunLedger(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public synchronized void persist(RunRecord record) {
        if (record == null) throw new IllegalArgumentException("run record is required");
        record.validated();
        String runId = record.runId();
        try {
            Files.createDirectories(root);
            Path target = root.resolve(runId + ".json");
            Path tmp = root.resolve(runId + ".json.tmp");
            M.writeValue(tmp.toFile(), record);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (publishedRoot != null) {
                pendingPublication.put(runId, record);
                publish(record);
            }
        } catch (IOException e) {
            throw new RunPersistenceException("run ledger persist failed for " + runId, e);
        }
    }

    /** Published facts are read-only here: they must never be hydrated as this JVM's workers. */
    public Optional<RunRecord> findPublished(String runId) {
        return publishedRoot == null ? Optional.empty() : sharedTransaction(() -> new RunLedger(publishedRoot).find(runId));
    }
    public List<RunRecord> published() {
        return publishedRoot == null ? List.of() : sharedTransaction(() -> new RunLedger(publishedRoot).list());
    }
    private <T> T sharedTransaction(Supplier<T> action) { return storage == null ? action.get() : storage.transaction(action); }

    /** The local ledger is the commit point. A delayed read-only publication must never roll back executed work. */
    private void publish(RunRecord record) {
        try {
            sharedTransaction(() -> {
                try { AtomicJsonFiles.write(M, publishedRoot.resolve(record.runId() + ".json"), record); return null; }
                catch (IOException failure) { throw new RunPersistenceException("Cannot publish run history", failure); }
            });
            pendingPublication.remove(record.runId(), record);
        } catch (RuntimeException failure) {
            log.debug("Run history publication deferred for {}: {}", record.runId(), failure.toString());
        }
    }
    private synchronized void retryPublication() {
        pendingPublication.values().stream().limit(16).toList().forEach(this::publish);
    }
    @PreDestroy public void close() {
        if (publicationRetry != null) publicationRetry.shutdownNow();
    }

    public synchronized Optional<RunRecord> find(String runId) {
        if (!Ids.isSafeCallId(runId)) return Optional.empty();
        Path file = root.resolve(runId + ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(read(file));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("run ledger read failed for {}: {}", runId, e.toString());
            return Optional.empty();
        }
    }

    public synchronized List<RunRecord> list() {
        if (!Files.isDirectory(root)) return List.of();
        List<RunRecord> out = new ArrayList<>();
        try (var files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    out.add(read(path));
                } catch (IOException | IllegalArgumentException failure) {
                    log.warn("skipping invalid run ledger entry {}: {}", path.getFileName(), failure.toString());
                }
            });
        } catch (IOException e) {
            throw new RunPersistenceException("run ledger list failed", e);
        }
        out.sort(Comparator.comparingLong(RunRecord::createdAt).reversed());
        return List.copyOf(out);
    }

    private RunRecord read(Path file) throws IOException {
        return M.readValue(file.toFile(), RunRecord.class).validated();
    }

    public static final class RunPersistenceException extends IllegalStateException {
        public RunPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
