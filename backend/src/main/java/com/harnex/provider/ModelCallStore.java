package com.harnex.provider;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.DiagnosticRetention;
import com.harnex.util.Ids;

import jakarta.annotation.PreDestroy;

/** Bounded diagnostic writer; a failed diagnostic sink never blocks or retries a business operation. */
@Component
public class ModelCallStore implements AutoCloseable {
    private final Path base;
    private final DiagnosticRetention retention;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong dropped = new AtomicLong();
    private final long processStartedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1024), runnable -> { Thread thread = new Thread(runnable, "model-call-records"); thread.setDaemon(true); return thread; },
        new ThreadPoolExecutor.AbortPolicy());

    @Autowired public ModelCallStore(AgentConfig config) { this(Path.of(config.getDataDir())); }
    public ModelCallStore(Path scopeDirectory) {
        base = scopeDirectory.toAbsolutePath().normalize().resolve("diagnostics/model-calls");
        retention = new DiagnosticRetention(base);
    }

    public void record(ModelCallRecord record) {
        try {
            writer.execute(() -> persist(record));
        } catch (RejectedExecutionException saturated) { dropped.incrementAndGet(); }
    }
    private synchronized void persist(ModelCallRecord record) {
        try {
            Path path = path(record.runId(), record.callId());
            if (Files.exists(path)) {
                ModelCallRecord previous = json.readValue(Files.readAllBytes(path), ModelCallRecord.class);
                if (previous.revision() >= record.revision()) return;
            }
            AtomicJsonFiles.write(json, path, record);
            retention.afterWrite();
        } catch (Exception failure) { dropped.incrementAndGet(); }
    }
    public synchronized Optional<ModelCallRecord> find(String runId, String callId) {
        Path path = path(runId, callId);
        if (!Files.exists(path)) return Optional.empty();
        try { return Optional.of(recovered(json.readValue(Files.readAllBytes(path), ModelCallRecord.class))); }
        catch (IOException failure) { throw new IllegalStateException("call diagnostics unavailable", failure); }
    }
    public synchronized List<ModelCallRecord> list(String runId) {
        if (!Ids.isSafeResourceId(runId)) throw new IllegalArgumentException("invalid runId");
        Path directory = base.resolve(runId);
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json")).map(path -> {
                try { return recovered(json.readValue(Files.readAllBytes(path), ModelCallRecord.class)); }
                catch (IOException failure) { throw new IllegalStateException("call diagnostics unavailable", failure); }
            }).sorted(Comparator.comparingLong(ModelCallRecord::createdAt)).toList();
        } catch (IOException failure) { throw new IllegalStateException("call diagnostics unavailable", failure); }
    }
    public long droppedWrites() { return dropped.get(); }
    private ModelCallRecord recovered(ModelCallRecord record) {
        if (record.createdAt() >= processStartedAt || record.state() != ModelCallRecord.State.RUNNING) return record;
        var attempts = record.attempts().stream().map(attempt -> attempt.state() != ModelCallRecord.State.RUNNING ? attempt
            : new ModelCallRecord.Attempt(attempt.attemptId(), attempt.index(), attempt.provider(), attempt.model(), attempt.protocol(),
                attempt.startedAt(), null, attempt.firstOutputAfterMillis(), ModelCallRecord.State.UNKNOWN, attempt.acceptance(),
                attempt.upstreamRequestId(), "runtime_interrupted", attempt.retryAfterMillis(), attempt.usage(), attempt.contextPlanId(), attempt.timings())).toList();
        return new ModelCallRecord(record.schemaVersion(), record.callId(), record.runId(), record.threadId(), record.stepId(), record.purpose(),
            record.requestedProvider(), record.requestedModel(), ModelCallRecord.State.UNKNOWN, record.revision(), record.createdAt(), null, attempts, false);
    }
    private Path path(String runId, String callId) {
        if (runId != null && !Ids.isSafeResourceId(runId) || !Ids.isSafeResourceId(callId)) throw new IllegalArgumentException("invalid call identity");
        return base.resolve(runId == null ? "utility" : runId).resolve(callId + ".json");
    }
    @Override @PreDestroy public void close() {
        writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); writer.shutdownNow(); }
    }
}
