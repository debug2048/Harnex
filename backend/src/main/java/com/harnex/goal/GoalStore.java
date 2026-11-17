package com.harnex.goal;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.run.RunLedger.RunPersistenceException;
import com.harnex.util.Ids;

/** Atomic, workspace-local persistence and CAS owner for {@link GoalRecord}. */
@Service
@ProjectRuntime
public class GoalStore {
    private static final ObjectMapper M = new ObjectMapper();
    private final Path root;

    @Autowired
    public GoalStore(AgentConfig cfg) {
        this(Path.of(cfg.getDataDir()).toAbsolutePath().normalize().resolve("agent/goals"));
    }

    public GoalStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public synchronized GoalRecord create(GoalRecord record) {
        record.validated();
        if (Files.exists(path(record.goalId()))) throw new GoalConflictException("goal already exists");
        persist(record);
        return record;
    }

    public synchronized Optional<GoalRecord> find(String goalId) {
        if (!Ids.isSafeCallId(goalId)) return Optional.empty();
        Path file = path(goalId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(read(file));
        } catch (IOException | IllegalArgumentException failure) {
            throw new RunPersistenceException("Goal read failed for " + goalId, failure);
        }
    }

    public synchronized List<GoalRecord> list(String threadId) {
        if (!Files.isDirectory(root)) return List.of();
        List<GoalRecord> out = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                GoalRecord record = read(file);
                if (threadId == null || threadId.equals(record.threadId())) out.add(record);
            }
        } catch (IOException failure) {
            throw new RunPersistenceException("Goal list failed", failure);
        }
        out.sort(Comparator.comparingLong(GoalRecord::createdAt).reversed());
        return List.copyOf(out);
    }

    public synchronized Optional<GoalRecord> findByCreationRequest(String scope, String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return list(null).stream().filter(record -> record.creationRequest() != null
            && scope.equals(record.creationRequest().scope()) && key.equals(record.creationRequest().key())).findFirst();
    }

    public synchronized GoalRecord replace(GoalRecord replacement, long expectedRevision) {
        GoalRecord current = find(replacement.goalId()).orElseThrow(() -> new GoalNotFoundException(replacement.goalId()));
        if (current.revision() != expectedRevision) throw new GoalRevisionConflictException(current);
        if (!current.threadId().equals(replacement.threadId()) || replacement.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("Goal identity/revision cannot be rewritten");
        }
        persist(replacement.validated());
        return replacement;
    }

    public synchronized GoalRecord appendRun(String goalId, String runId, long expectedRevision, String operationId) {
        GoalRecord current = find(goalId).orElseThrow(() -> new GoalNotFoundException(goalId));
        if (current.runIds().contains(runId)) return current;
        List<String> runIds = new ArrayList<>(current.runIds());
        runIds.add(runId);
        return replace(new GoalRecord(current.schemaVersion(), current.goalId(), current.threadId(),
            current.revision() + 1, current.objective(), current.criteria(), current.budget(), current.runDefaults(),
            current.instructionRevisions(), runIds, current.createdAt(), System.currentTimeMillis(), operationId,
            current.creationRequest()), expectedRevision);
    }

    public synchronized GoalRecord appendInstruction(String goalId, GoalRecord.InstructionRevision instruction) {
        GoalRecord current = find(goalId).orElseThrow(() -> new GoalNotFoundException(goalId));
        if (current.instructionRevisions().stream().anyMatch(item -> item.inputId().equals(instruction.inputId()))) {
            return current;
        }
        List<GoalRecord.InstructionRevision> revisions = new ArrayList<>(current.instructionRevisions());
        revisions.add(instruction);
        return replace(new GoalRecord(current.schemaVersion(), current.goalId(), current.threadId(),
            current.revision() + 1, current.objective(), current.criteria(), current.budget(), current.runDefaults(),
            revisions, current.runIds(), current.createdAt(), System.currentTimeMillis(), "steer:" + instruction.inputId(),
            current.creationRequest()), current.revision());
    }

    public synchronized void purgeThread(String threadId) {
        if (!Ids.isSafe(threadId)) return;
        for (GoalRecord goal : list(threadId)) {
            try {
                Files.deleteIfExists(path(goal.goalId()));
            } catch (IOException failure) {
                throw new RunPersistenceException("Goal purge failed for " + goal.goalId(), failure);
            }
        }
    }

    private GoalRecord read(Path file) throws IOException {
        return M.readValue(file.toFile(), GoalRecord.class).validated();
    }

    private void persist(GoalRecord record) {
        Path target = path(record.goalId());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(root);
            M.writeValue(tmp.toFile(), record);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new RunPersistenceException("Goal persist failed for " + record.goalId(), failure);
        }
    }

    private Path path(String goalId) {
        return root.resolve(goalId + ".json");
    }

    public static final class GoalConflictException extends IllegalStateException {
        GoalConflictException(String message) { super(message); }
    }

    public static final class GoalNotFoundException extends IllegalArgumentException {
        GoalNotFoundException(String goalId) { super("Goal not found: " + goalId); }
    }

    public static final class GoalRevisionConflictException extends IllegalStateException {
        private final GoalRecord current;
        GoalRevisionConflictException(GoalRecord current) {
            super("Goal revision conflict; current=" + current.revision());
            this.current = current;
        }
        public GoalRecord current() { return current; }
    }
}
