package com.harnex.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.conversation.ProjectStorage;
import com.harnex.safety.SecretRedactor;
import com.harnex.tool.builtin.support.TextEditCore;
import com.harnex.util.Ids;

/** Agent progress belongs to the Run, independently of the bounded live display and model context. */
@Service
public class RunProcessStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;
    private final AgentConfig config;
    private final Map<String, Map<Long, RunPresentationState.ProcessMessage>> memory;
    private ProjectStorage storage;

    @Autowired
    public RunProcessStore(AgentConfig config, ProjectStorage storage) {
        this(Path.of(config.getSharedDataDir()).toAbsolutePath().normalize().resolve("runs/process"), config);
        this.storage = storage;
    }
    public RunProcessStore(Path root, AgentConfig config) {
        this.root = root; this.config = config; memory = root == null ? new ConcurrentHashMap<>() : null;
    }
    static RunProcessStore inMemory() { return new RunProcessStore(null, new AgentConfig()); }

    public synchronized void save(String runId, RunPresentationState.ProcessMessage message) {
        if (storage == null) write(runId, message);
        else storage.transaction(() -> { write(runId, message); return null; });
    }
    private void write(String runId, RunPresentationState.ProcessMessage message) {
        if (!Ids.isSafeResourceId(runId) || message.seq() < 1) throw new IllegalArgumentException("Invalid process identity");
        var safe = new RunPresentationState.ProcessMessage(message.seq(), scrub(message.text()), scrub(message.thinking()), message.afterMessageId());
        if (memory != null) { memory.computeIfAbsent(runId, ignored -> new ConcurrentHashMap<>()).put(safe.seq(), safe); return; }
        try {
            Path directory = root.resolve(runId); Files.createDirectories(directory);
            TextEditCore.atomicWrite(directory.resolve(safe.seq() + ".json"), JSON.writeValueAsString(safe));
        } catch (IOException failure) { throw new RunLedger.RunPersistenceException("Cannot persist Run progress", failure); }
    }
    public List<RunPresentationState.ProcessMessage> list(String runId) {
        // Immutable per-sequence files are atomically published; reading them does not need the writer lock.
        return read(runId);
    }
    private List<RunPresentationState.ProcessMessage> read(String runId) {
        if (!Ids.isSafeResourceId(runId)) return List.of();
        if (memory != null) return memory.getOrDefault(runId, Map.of()).values().stream()
            .sorted(Comparator.comparingLong(RunPresentationState.ProcessMessage::seq)).toList();
        Path directory = root.resolve(runId);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().matches("[1-9][0-9]{0,18}\\.json"))
                .map(path -> {
                    try {
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root.toRealPath()))
                            throw new IOException("Process path escapes storage");
                        var message = JSON.readValue(path.toFile(), RunPresentationState.ProcessMessage.class);
                        if (!path.getFileName().toString().equals(message.seq() + ".json")) throw new IOException("Process identity mismatch");
                        return message;
                    } catch (IOException failure) { throw new RunLedger.RunPersistenceException("Cannot read Run progress", failure); }
                }).sorted(Comparator.comparingLong(RunPresentationState.ProcessMessage::seq)).toList();
        } catch (IOException failure) { throw new RunLedger.RunPersistenceException("Cannot list Run progress", failure); }
    }
    private String scrub(String value) {
        var safety = config.getSafety();
        if (value == null) return "";
        return safety == null || !safety.isRedactSecretsInToolResults() && !safety.isRedactPiiInToolResults()
            ? value : SecretRedactor.redact(value, safety.isRedactPiiInToolResults());
    }
}
