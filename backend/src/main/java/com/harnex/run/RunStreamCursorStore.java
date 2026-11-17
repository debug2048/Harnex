package com.harnex.run;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.run.RunLedger.RunPersistenceException;
import com.harnex.util.Ids;

/** Atomic store for stream epochs and pre-published sequence leases. */
@Service
public class RunStreamCursorStore {

    private static final ObjectMapper M = new ObjectMapper();

    private final Path root;
    private final Map<String, RunStreamCursorRecord> memory;

    @Autowired
    public RunStreamCursorStore(AgentConfig cfg) {
        this(Path.of(cfg.getDataDir()).toAbsolutePath().normalize().resolve("runs/streams"));
    }

    public RunStreamCursorStore(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.memory = root == null ? new ConcurrentHashMap<>() : null;
    }

    static RunStreamCursorStore inMemory() {
        return new RunStreamCursorStore((Path)null);
    }

    /** Open a newly created run without changing an existing epoch. */
    public synchronized RunStreamCursorRecord open(String runId, long minimumReservedThrough) {
        RunStreamCursorRecord existing = find(runId).orElse(null);
        if (existing != null) {
            if (existing.reservedThrough() >= minimumReservedThrough) return existing;
            RunStreamCursorRecord advanced = new RunStreamCursorRecord(
                RunStreamCursorRecord.CURRENT_SCHEMA_VERSION, runId, existing.cursorRevision() + 1L,
                existing.streamEpoch(), minimumReservedThrough).validated();
            persist(advanced);
            return advanced;
        }
        RunStreamCursorRecord created = new RunStreamCursorRecord(
            RunStreamCursorRecord.CURRENT_SCHEMA_VERSION, runId, 1L, 1L,
            Math.max(0L, minimumReservedThrough)).validated();
        persist(created);
        return created;
    }

    /** Start a new in-memory stream incarnation after hydrating a persisted open RunHandle. */
    public synchronized RunStreamCursorRecord rehydrate(String runId, long minimumReservedThrough) {
        RunStreamCursorRecord existing = find(runId).orElseGet(() -> new RunStreamCursorRecord(
            RunStreamCursorRecord.CURRENT_SCHEMA_VERSION, runId, 0L, 0L,
            Math.max(0L, minimumReservedThrough)));
        RunStreamCursorRecord next = new RunStreamCursorRecord(
            RunStreamCursorRecord.CURRENT_SCHEMA_VERSION,
            runId,
            existing.cursorRevision() + 1L,
            existing.streamEpoch() + 1L,
            Math.max(existing.reservedThrough(), minimumReservedThrough)).validated();
        persist(next);
        return next;
    }

    /** Reserve a durable sequence block before any event in that block can be published. */
    public synchronized RunStreamCursorRecord reserve(String runId, long streamEpoch, int blockSize) {
        if (blockSize < 1) throw new IllegalArgumentException("stream sequence blockSize must be positive");
        RunStreamCursorRecord existing = find(runId)
            .orElseThrow(() -> new IllegalStateException("missing stream cursor for " + runId));
        if (existing.streamEpoch() != streamEpoch) {
            throw new IllegalStateException("stale stream epoch for " + runId);
        }
        long reservedThrough;
        try {
            reservedThrough = Math.addExact(existing.reservedThrough(), blockSize);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("stream sequence exhausted for " + runId, overflow);
        }
        RunStreamCursorRecord next = new RunStreamCursorRecord(
            RunStreamCursorRecord.CURRENT_SCHEMA_VERSION,
            runId,
            existing.cursorRevision() + 1L,
            existing.streamEpoch(),
            reservedThrough).validated();
        persist(next);
        return next;
    }

    public synchronized Optional<RunStreamCursorRecord> find(String runId) {
        if (!Ids.isSafeCallId(runId)) return Optional.empty();
        if (memory != null) return Optional.ofNullable(memory.get(runId));
        Path file = root.resolve(runId + ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(M.readValue(file.toFile(), RunStreamCursorRecord.class).validated());
        } catch (IOException | IllegalArgumentException failure) {
            throw new RunPersistenceException("run stream cursor read failed for " + runId, failure);
        }
    }

    private void persist(RunStreamCursorRecord record) {
        record.validated();
        if (memory != null) {
            memory.put(record.runId(), record);
            return;
        }
        try {
            Files.createDirectories(root);
            Path target = root.resolve(record.runId() + ".json");
            Path tmp = root.resolve(record.runId() + ".json.tmp");
            M.writeValue(tmp.toFile(), record);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new RunPersistenceException("run stream cursor persist failed for " + record.runId(), failure);
        }
    }
}
