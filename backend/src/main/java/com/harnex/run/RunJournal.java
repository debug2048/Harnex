package com.harnex.run;

import java.io.IOException;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import com.harnex.util.Ids;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.run.RunJournalEntry.Kind;
import com.harnex.run.RunJournalEntry.Phase;
import com.harnex.run.RunLedger.RunPersistenceException;

import lombok.extern.slf4j.Slf4j;

/** Minimal append-and-force write-ahead journal for RunRecord replacement. */
@Slf4j
@Service
public class RunJournal {

    private static final ObjectMapper M = new ObjectMapper();
    private static final long COMPACT_BYTES = 64 * 1024;
    private static final int RECENT_MARKERS = 16;

    private final Path root;
    private final List<RunJournalEntry> memory;

    @Autowired
    public RunJournal(AgentConfig cfg) {
        this(Path.of(cfg.getDataDir()).toAbsolutePath().normalize().resolve("runs/events"));
    }

    public RunJournal(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.memory = root == null ? new ArrayList<>() : null;
    }

    static RunJournal inMemory() {
        return new RunJournal((Path)null);
    }

    public synchronized void prepare(String operationId, String runId, Kind kind, long expectedRevision) {
        append(new RunJournalEntry(RunJournalEntry.CURRENT_SCHEMA_VERSION, operationId, runId, kind,
            expectedRevision, Phase.PREPARED, System.currentTimeMillis()).validated());
    }

    public synchronized void commit(String operationId, String runId, Kind kind, long expectedRevision) {
        append(new RunJournalEntry(RunJournalEntry.CURRENT_SCHEMA_VERSION, operationId, runId, kind,
            expectedRevision, Phase.COMMITTED, System.currentTimeMillis()).validated());
    }

    public synchronized void abort(String operationId, String runId, Kind kind, long expectedRevision) {
        append(new RunJournalEntry(RunJournalEntry.CURRENT_SCHEMA_VERSION, operationId, runId, kind,
            expectedRevision, Phase.ABORTED, System.currentTimeMillis()).validated());
    }

    /** Reconcile only PREPARED operations lacking a terminal marker; committed facts are never replayed twice. */
    public synchronized void reconcile(RunLedger ledger) {
        for (RunJournalEntry prepared : uncommitted()) {
            RunRecord record = ledger.find(prepared.runId()).orElse(null);
            boolean durable = record != null
                && prepared.operationId().equals(record.lastCommittedOperationId())
                && record.revision() == prepared.expectedRevision() + 1L;
            if (durable) {
                commit(prepared.operationId(), prepared.runId(), prepared.kind(), prepared.expectedRevision());
            } else {
                abort(prepared.operationId(), prepared.runId(), prepared.kind(), prepared.expectedRevision());
            }
        }
        if (root != null && Files.isDirectory(root)) {
            try (var files = Files.list(root)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > COMPACT_BYTES) compactFile(file);
                }
            } catch (IOException failure) { log.debug("run journal compaction deferred", failure); }
        }
    }

    public synchronized List<RunJournalEntry> uncommitted() {
        Map<String, RunJournalEntry> prepared = new LinkedHashMap<>();
        for (RunJournalEntry entry : readAll()) {
            if (entry.phase() == Phase.PREPARED) {
                prepared.put(entry.operationId(), entry);
            } else {
                prepared.remove(entry.operationId());
            }
        }
        return List.copyOf(prepared.values());
    }

    private void append(RunJournalEntry entry) {
        if (memory != null) {
            memory.add(entry);
            return;
        }
        Path file = root.resolve(entry.runId() + ".jsonl");
        try {
            Files.createDirectories(root);
            byte[] bytes = (M.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
            // A cancellation may interrupt the producer at any point. FileChannel would close mid-record,
            // corrupting the very cancellation facts we must persist. This synchronized append uses a
            // non-interruptible stream and still forces the bytes to disk; the caller's interrupt remains set.
            try (FileOutputStream stream = new FileOutputStream(file.toFile(), true)) {
                stream.write(bytes);
                stream.getFD().sync();
            }
            if (entry.phase() != Phase.PREPARED && Files.size(file) > COMPACT_BYTES) compactFile(file);
        } catch (IOException failure) {
            throw new RunPersistenceException("run journal append failed for " + entry.runId(), failure);
        }
    }

    private List<RunJournalEntry> readAll() {
        if (memory != null) return List.copyOf(memory);
        if (!Files.isDirectory(root)) return List.of();
        List<RunJournalEntry> entries = new ArrayList<>();
        try (var files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".jsonl")
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().forEach(path -> entries.addAll(readFile(path)));
        } catch (IOException failure) {
            throw new RunPersistenceException("run journal scan failed", failure);
        }
        return entries;
    }

    private List<RunJournalEntry> readFile(Path path) {
        List<RunJournalEntry> entries = new ArrayList<>();
        try {
            // Replace a torn final UTF-8 sequence so it can be discarded without hiding earlier corruption.
            byte[] raw = Files.readAllBytes(path);
            List<String> lines = List.of(new String(raw, StandardCharsets.UTF_8).split("\\R", -1));
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) continue;
                try { entries.add(M.readValue(line, RunJournalEntry.class).validated()); }
                catch (IOException | IllegalArgumentException invalid) {
                    if (index != lines.size() - 1) throw new RunPersistenceException("invalid run journal entry in " + path, invalid);
                    log.warn("ignoring incomplete final run journal entry in {}", path.getFileName());
                    truncateIncompleteTail(path, raw);
                }
            }
        } catch (IOException failure) { throw new RunPersistenceException("run journal read failed for " + path, failure); }
        return entries;
    }

    /** Run facts and user history are authoritative. Keep unresolved recovery markers and a short diagnostic tail. */
    synchronized void compact(String runId) {
        if (root == null || !Ids.isSafeCallId(runId)) return;
        compactFile(root.resolve(runId + ".jsonl"));
    }
    private void compactFile(Path file) {
        Path temporary = null;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
            List<RunJournalEntry> entries = readFile(file);
            if (entries.size() <= RECENT_MARKERS) return;
            Map<String, RunJournalEntry> pending = new LinkedHashMap<>();
            for (var entry : entries) {
                if (entry.phase() == Phase.PREPARED) pending.put(entry.operationId(), entry);
                else pending.remove(entry.operationId());
            }
            var keep = new HashSet<>(pending.values());
            keep.addAll(entries.subList(entries.size() - RECENT_MARKERS, entries.size()));
            if (keep.size() == entries.size()) return;
            temporary = Files.createTempFile(root, ".journal-", ".tmp");
            try (FileOutputStream stream = new FileOutputStream(temporary.toFile())) {
                for (var entry : entries) if (keep.contains(entry)) {
                    stream.write((M.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8));
                }
                stream.getFD().sync();
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException | RunPersistenceException failure) { log.debug("run journal compaction deferred for {}", file, failure); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }

    private static void truncateIncompleteTail(Path path, byte[] raw) {
        int lastCompleteLineEnd = -1;
        for (int index = raw.length - 1; index >= 0; index--) {
            if (raw[index] == (byte)'\n') {
                lastCompleteLineEnd = index;
                break;
            }
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(lastCompleteLineEnd + 1L);
            channel.force(true);
        } catch (IOException failure) {
            throw new RunPersistenceException("failed to truncate incomplete run journal tail in " + path, failure);
        }
    }
}
