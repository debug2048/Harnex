package com.harnex.run;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.run.RunLedger.RunPersistenceException;
import com.harnex.util.Ids;

/** Workspace-local atomic persistence for per-Run Steering/Follow-up mailboxes. */
@Service
public class RunInputStore {

    private static final ObjectMapper M = new ObjectMapper();

    private final Path root;
    private final Map<String, RunInputMailboxRecord> memory;

    @Autowired
    public RunInputStore(AgentConfig cfg) {
        this(Path.of(cfg.getDataDir()).toAbsolutePath().normalize().resolve("runs/inputs"));
    }

    public RunInputStore(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.memory = root == null ? new ConcurrentHashMap<>() : null;
    }

    public static RunInputStore inMemory() {
        return new RunInputStore((Path)null);
    }

    public synchronized Optional<RunInputMailboxRecord> findMailbox(String runId) {
        if (!Ids.isSafeCallId(runId)) return Optional.empty();
        if (memory != null) return Optional.ofNullable(memory.get(runId));
        Path file = path(runId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            RunInputMailboxRecord mailbox = M.readValue(file.toFile(), RunInputMailboxRecord.class).validated();
            if (!runId.equals(mailbox.runId())) {
                throw new IllegalArgumentException("run input mailbox path/identity mismatch");
            }
            return Optional.of(mailbox);
        } catch (IOException | IllegalArgumentException failure) {
            throw new RunPersistenceException("run input mailbox read failed for " + runId, failure);
        }
    }

    public synchronized Optional<RunInputRecord> findInput(String runId, String inputId) {
        if (!Ids.isSafeCallId(inputId)) return Optional.empty();
        return findMailbox(runId).stream().flatMap(mailbox -> mailbox.inputs().stream())
            .filter(input -> inputId.equals(input.inputId())).findFirst();
    }

    public synchronized Optional<RunInputRecord> findByEnqueueRequest(String runId, String clientRequestId) {
        if (clientRequestId == null) return Optional.empty();
        return findMailbox(runId).stream().flatMap(mailbox -> mailbox.inputs().stream())
            .filter(input -> clientRequestId.equals(input.clientRequestId())).findFirst();
    }

    public synchronized Optional<RunInputRecord> findByWithdrawRequest(String runId, String inputId,
        String clientRequestId) {
        if (clientRequestId == null) return Optional.empty();
        return findInput(runId, inputId).filter(input -> clientRequestId.equals(input.withdrawRequestId()));
    }

    public synchronized Mutation enqueue(String runId, String threadId, RunInputMode mode, String text,
        List<String> attachments, String channel, RunInputStatus initialStatus, String clientRequestId,
        String requestHash) {
        RunInputMailboxRecord mailbox = findMailbox(runId).orElseGet(() ->
            RunInputMailboxRecord.empty(runId, threadId));
        if (!threadId.equals(mailbox.threadId())) {
            throw new IllegalArgumentException("run input mailbox thread mismatch");
        }
        RunInputRecord existing = mailbox.inputs().stream()
            .filter(input -> clientRequestId.equals(input.clientRequestId())).findFirst().orElse(null);
        if (existing != null) {
            if (!requestHash.equals(existing.requestHash())) throw new InputIdempotencyConflictException();
            return new Mutation(mailbox.revision(), existing, true);
        }
        long now = System.currentTimeMillis();
        String inputId = "input_" + UUID.randomUUID().toString().replace("-", "");
        RunInputRecord input = new RunInputRecord(RunInputRecord.CURRENT_SCHEMA_VERSION, inputId, runId, threadId,
            mailbox.nextSequence(), 1L, mode, text, attachments, channel, initialStatus, initialStatus,
            clientRequestId, requestHash, null, null, now, null, null, null, null, null, null, null, null).validated();
        List<RunInputRecord> updated = new ArrayList<>(mailbox.inputs());
        updated.add(input);
        RunInputMailboxRecord next = new RunInputMailboxRecord(RunInputMailboxRecord.CURRENT_SCHEMA_VERSION,
            runId, threadId, mailbox.revision() + 1L, mailbox.nextSequence() + 1L, updated).validated();
        persist(next);
        return new Mutation(next.revision(), input, false);
    }

    public synchronized Mutation withdraw(String runId, String inputId, String clientRequestId, String requestHash) {
        RunInputMailboxRecord mailbox = findMailbox(runId).orElseThrow(InputNotFoundException::new);
        List<RunInputRecord> updated = new ArrayList<>(mailbox.inputs());
        for (int index = 0; index < updated.size(); index++) {
            RunInputRecord input = updated.get(index);
            if (!inputId.equals(input.inputId())) continue;
            if (clientRequestId.equals(input.withdrawRequestId())) {
                if (!requestHash.equals(input.withdrawRequestHash())) throw new InputIdempotencyConflictException();
                return new Mutation(mailbox.revision(), input, true);
            }
            if (!input.status().isWithdrawable()) throw new InputNotWithdrawableException(input.status());
            RunInputRecord withdrawn = input.withdraw(clientRequestId, requestHash, System.currentTimeMillis());
            updated.set(index, withdrawn);
            RunInputMailboxRecord next = new RunInputMailboxRecord(RunInputMailboxRecord.CURRENT_SCHEMA_VERSION,
                mailbox.runId(), mailbox.threadId(), mailbox.revision() + 1L, mailbox.nextSequence(), updated)
                .validated();
            persist(next);
            return new Mutation(next.revision(), withdrawn, false);
        }
        throw new InputNotFoundException();
    }

    /** Idempotently converge pending inputs when their source Run reaches a technical terminal state. */
    public synchronized List<RunInputRecord> onRunTerminal(String runId, RunStatus terminalStatus) {
        if (terminalStatus == null || !terminalStatus.isTerminal()) return List.of();
        RunInputMailboxRecord mailbox = findMailbox(runId).orElse(null);
        if (mailbox == null) return List.of();
        List<RunInputRecord> updated = new ArrayList<>(mailbox.inputs());
        List<RunInputRecord> changed = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int index = 0; index < updated.size(); index++) {
            RunInputRecord input = updated.get(index);
            if (!input.status().isPending()) continue;
            RunInputRecord next = input.transition(RunInputStatus.CANCELLED, "source_run_terminal", now);
            if (next != null && (next.status() != input.status()
                || !Objects.equals(next.terminalReason(), input.terminalReason()))) {
                updated.set(index, next);
                changed.add(next);
            }
        }
        if (changed.isEmpty()) return List.of();
        persist(new RunInputMailboxRecord(RunInputMailboxRecord.CURRENT_SCHEMA_VERSION, mailbox.runId(),
            mailbox.threadId(), mailbox.revision() + 1L, mailbox.nextSequence(), updated).validated());
        return List.copyOf(changed);
    }

    public synchronized List<RunInputRecord> list(String runId, RunInputStatus status) {
        return findMailbox(runId).map(mailbox -> mailbox.inputs().stream()
            .filter(input -> status == null || input.status() == status)
            .sorted(Comparator.comparingLong(RunInputRecord::sequence)).toList()).orElse(List.of());
    }

    public synchronized int pendingCount(String runId) {
        return (int)list(runId, null).stream().filter(input -> input.status().isPending()).count();
    }

    /**
     * Claim every queued Steering input for one root run as a single deterministic apply operation. Recovery always
     * resumes an existing APPLYING operation before claiming newer QUEUED rows, so two batches can never overtake.
     */
    public synchronized ApplyBatch claimSteering(String runId) {
        RunInputMailboxRecord mailbox = findMailbox(runId).orElse(null);
        if (mailbox == null) return ApplyBatch.empty();
        List<RunInputRecord> applying = mailbox.inputs().stream()
            .filter(input -> input.mode() == RunInputMode.STEER && input.status() == RunInputStatus.APPLYING)
            .sorted(Comparator.comparingLong(RunInputRecord::sequence)).toList();
        if (!applying.isEmpty()) {
            String operationId = applying.get(0).applyOperationId();
            List<RunInputRecord> sameOperation = applying.stream()
                .filter(input -> Objects.equals(operationId, input.applyOperationId())).toList();
            return new ApplyBatch(operationId, sameOperation, true);
        }
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < mailbox.inputs().size(); index++) {
            RunInputRecord input = mailbox.inputs().get(index);
            if (input.mode() == RunInputMode.STEER && input.status() == RunInputStatus.QUEUED) indexes.add(index);
        }
        if (indexes.isEmpty()) return ApplyBatch.empty();
        String operationId = "apply_" + UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        List<RunInputRecord> updated = new ArrayList<>(mailbox.inputs());
        List<RunInputRecord> claimed = new ArrayList<>();
        for (int index : indexes) {
            RunInputRecord next = updated.get(index).claim(operationId, now);
            updated.set(index, next);
            claimed.add(next);
        }
        RunInputMailboxRecord nextMailbox = new RunInputMailboxRecord(RunInputMailboxRecord.CURRENT_SCHEMA_VERSION,
            mailbox.runId(), mailbox.threadId(), mailbox.revision() + 1L, mailbox.nextSequence(), updated).validated();
        persist(nextMailbox);
        return new ApplyBatch(operationId, List.copyOf(claimed), false);
    }

    public synchronized boolean hasPendingSteering(String runId) {
        return findMailbox(runId).stream().flatMap(mailbox -> mailbox.inputs().stream()).anyMatch(input ->
            input.mode() == RunInputMode.STEER
                && (input.status() == RunInputStatus.QUEUED || input.status() == RunInputStatus.APPLYING));
    }

    /** Converge one claimed batch after its durable conversation message has been saved. */
    public synchronized List<RunInputRecord> markApplied(String runId, String operationId, String stepId,
        Long afterSeq, String spawnedRunId) {
        RunInputMailboxRecord mailbox = findMailbox(runId).orElse(null);
        if (mailbox == null || operationId == null) return List.of();
        List<RunInputRecord> updated = new ArrayList<>(mailbox.inputs());
        List<RunInputRecord> changed = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int index = 0; index < updated.size(); index++) {
            RunInputRecord input = updated.get(index);
            if (input.status() != RunInputStatus.APPLYING
                || !operationId.equals(input.applyOperationId())) continue;
            RunInputRecord next = input.applied(stepId, afterSeq, spawnedRunId, now);
            updated.set(index, next);
            changed.add(next);
        }
        if (!changed.isEmpty()) persist(new RunInputMailboxRecord(RunInputMailboxRecord.CURRENT_SCHEMA_VERSION,
            mailbox.runId(), mailbox.threadId(), mailbox.revision() + 1L, mailbox.nextSequence(), updated).validated());
        return List.copyOf(changed);
    }

    /**
     * Linearize the durable Thread mutation and Mailbox APPLIED transition against terminal cascade/withdrawal.
     * The callback runs while this store monitor is held; returning abort leaves the APPLYING claim untouched.
     */
    public synchronized List<RunInputRecord> commitApplying(String runId, String operationId,
        Set<String> inputIds, String stepId, String spawnedRunId, DurableApply durableApply) {
        RunInputMailboxRecord mailbox = findMailbox(runId).orElse(null);
        if (mailbox == null || operationId == null || durableApply == null) return List.of();
        List<RunInputRecord> current = mailbox.inputs().stream().filter(input ->
            input.status() == RunInputStatus.APPLYING && operationId.equals(input.applyOperationId())
                && (inputIds == null || inputIds.contains(input.inputId())))
            .sorted(Comparator.comparingLong(RunInputRecord::sequence)).toList();
        if (current.isEmpty()) return List.of();
        CommitDecision decision = durableApply.commit(current);
        if (decision == null || !decision.commit()) return List.of();
        List<RunInputRecord> updated = new ArrayList<>(mailbox.inputs());
        List<RunInputRecord> changed = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int index = 0; index < updated.size(); index++) {
            RunInputRecord input = updated.get(index);
            if (input.status() != RunInputStatus.APPLYING || !operationId.equals(input.applyOperationId())
                || inputIds != null && !inputIds.contains(input.inputId())) continue;
            RunInputRecord next = input.applied(stepId, decision.afterSeq(), spawnedRunId, now);
            updated.set(index, next);
            changed.add(next);
        }
        if (!changed.isEmpty()) persist(new RunInputMailboxRecord(RunInputMailboxRecord.CURRENT_SCHEMA_VERSION,
            mailbox.runId(), mailbox.threadId(), mailbox.revision() + 1L, mailbox.nextSequence(), updated).validated());
        return List.copyOf(changed);
    }

    public synchronized List<RunInputRecord> rejectApplying(String runId, String operationId, String reason) {
        return rejectApplying(runId, operationId, null, reason);
    }

    public synchronized List<RunInputRecord> rejectApplying(String runId, String operationId, Set<String> ids,
        String reason) {
        RunInputMailboxRecord mailbox = findMailbox(runId).orElse(null);
        if (mailbox == null || operationId == null) return List.of();
        List<RunInputRecord> updated = new ArrayList<>(mailbox.inputs());
        List<RunInputRecord> changed = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int index = 0; index < updated.size(); index++) {
            RunInputRecord input = updated.get(index);
            if (input.status() != RunInputStatus.APPLYING
                || !operationId.equals(input.applyOperationId())) continue;
            if (ids != null && !ids.contains(input.inputId())) continue;
            RunInputRecord next = input.transition(RunInputStatus.REJECTED, reason, now);
            updated.set(index, next);
            changed.add(next);
        }
        if (!changed.isEmpty()) persist(new RunInputMailboxRecord(RunInputMailboxRecord.CURRENT_SCHEMA_VERSION,
            mailbox.runId(), mailbox.threadId(), mailbox.revision() + 1L, mailbox.nextSequence(), updated).validated());
        return List.copyOf(changed);
    }

    public synchronized long mailboxRevision(String runId) {
        return findMailbox(runId).map(RunInputMailboxRecord::revision).orElse(0L);
    }

    /** Delete every mailbox owned by a Thread and return its former records for receipt cleanup. */
    public synchronized List<RunInputMailboxRecord> purgeThread(String threadId) {
        if (!Ids.isSafe(threadId)) return List.of();
        List<RunInputMailboxRecord> victims = allMailboxes().stream()
            .filter(mailbox -> threadId.equals(mailbox.threadId())).toList();
        for (RunInputMailboxRecord mailbox : victims) {
            if (memory != null) {
                memory.remove(mailbox.runId());
            } else {
                try {
                    Files.deleteIfExists(path(mailbox.runId()));
                } catch (IOException failure) {
                    throw new RunPersistenceException("run input mailbox purge failed for " + mailbox.runId(), failure);
                }
            }
        }
        return victims;
    }

    private List<RunInputMailboxRecord> allMailboxes() {
        if (memory != null) return List.copyOf(memory.values());
        if (!Files.isDirectory(root)) return List.of();
        List<RunInputMailboxRecord> out = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    out.add(M.readValue(file.toFile(), RunInputMailboxRecord.class).validated());
                } catch (IOException | IllegalArgumentException failure) {
                    throw new RunPersistenceException("run input mailbox scan failed for " + file, failure);
                }
            }
        } catch (IOException failure) {
            throw new RunPersistenceException("run input mailbox scan failed", failure);
        }
        return List.copyOf(out);
    }

    private void persist(RunInputMailboxRecord mailbox) {
        mailbox.validated();
        if (memory != null) {
            memory.put(mailbox.runId(), mailbox);
            return;
        }
        Path target = path(mailbox.runId());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(root);
            M.writeValue(tmp.toFile(), mailbox);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new RunPersistenceException("run input mailbox persist failed for " + mailbox.runId(), failure);
        }
    }

    private Path path(String runId) {
        return root.resolve(runId + ".json");
    }

    public record Mutation(long mailboxRevision, RunInputRecord input, boolean replayed) {}
    public record ApplyBatch(String operationId, List<RunInputRecord> inputs, boolean recovered) {
        static ApplyBatch empty() { return new ApplyBatch(null, List.of(), false); }
        public boolean isEmpty() { return inputs == null || inputs.isEmpty(); }
    }
    @FunctionalInterface
    public interface DurableApply { CommitDecision commit(List<RunInputRecord> currentInputs); }
    public record CommitDecision(boolean commit, Long afterSeq) {
        public static CommitDecision applied(Long afterSeq) { return new CommitDecision(true, afterSeq); }
        public static CommitDecision abort() { return new CommitDecision(false, null); }
    }

    public static final class InputIdempotencyConflictException extends IllegalStateException {}
    public static final class InputNotFoundException extends IllegalStateException {}
    public static final class InputNotWithdrawableException extends IllegalStateException {
        private final RunInputStatus status;

        InputNotWithdrawableException(RunInputStatus status) {
            super("run input is not withdrawable in status " + status);
            this.status = status;
        }

        public RunInputStatus status() {
            return status;
        }
    }
}
