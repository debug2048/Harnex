package com.harnex.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.conversation.ProjectStorage;
import com.harnex.model.StreamEvent;
import com.harnex.safety.SecretRedactor;
import com.harnex.tool.builtin.support.TextEditCore;
import com.harnex.util.Ids;

import io.swagger.v3.oas.annotations.media.Schema;

/** Durable tool bodies for detail views and bounded Agent history reconstruction. */
@Service
public class RunToolStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;
    private final AgentConfig config;
    private final Map<String, ToolDetail> memory;
    private ProjectStorage storage;

    @Schema(name = "RunToolDetail", requiredProperties = {"runId", "seq", "id", "name", "resultPending"})
    public record ToolDetail(String runId, long seq, String id, String name, String subagentConversationId,
        String arguments, String content, Map<String, Object> metadata, Long durationMs, boolean resultPending,
        String state, String afterMessageId, String purpose) {
        public ToolDetail(String runId, long seq, String id, String name, String subagentConversationId,
            String arguments, String content, Map<String, Object> metadata, Long durationMs, boolean resultPending,
            String state, String afterMessageId) {
            this(runId, seq, id, name, subagentConversationId, arguments, content, metadata, durationMs, resultPending,
                state, afterMessageId, com.harnex.tool.ToolPurpose.fromArguments(arguments));
        }
        public ToolDetail(String runId, long seq, String id, String name, String subagentConversationId,
            String arguments, String content, Map<String, Object> metadata, Long durationMs, boolean resultPending) {
            this(runId, seq, id, name, subagentConversationId, arguments, content, metadata, durationMs, resultPending, null, null);
        }
    }

    @Autowired
    public RunToolStore(AgentConfig config, ProjectStorage storage) {
        this(config);
        this.storage = storage;
    }

    public RunToolStore(AgentConfig config) {
        this(Path.of(config.getSharedDataDir()).toAbsolutePath().normalize().resolve("runs/tools"), config);
    }
    public RunToolStore(Path root, AgentConfig config) {
        this.root = root; this.config = config; this.memory = root == null ? new ConcurrentHashMap<>() : null;
    }
    static RunToolStore inMemory() { return new RunToolStore(null, new AgentConfig()); }

    public synchronized Optional<ToolDetail> find(String runId, long seq) {
        return storage == null ? read(runId, seq) : storage.transaction(() -> read(runId, seq));
    }
    private Optional<ToolDetail> read(String runId, long seq) {
        if (!Ids.isSafeResourceId(runId) || seq < 1) return Optional.empty();
        if (memory != null) return Optional.ofNullable(memory.get(runId + ":" + seq));
        Path file = root.resolve(runId).resolve(seq + ".json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            if (!file.toRealPath().startsWith(root.toRealPath())) throw new IOException("Tool detail path escapes storage");
            ToolDetail result = JSON.readValue(file.toFile(), ToolDetail.class);
            if (!runId.equals(result.runId()) || seq != result.seq()) throw new IOException("Tool identity mismatch");
            return Optional.of(result);
        } catch (IOException failure) { throw new RunLedger.RunPersistenceException("Cannot read tool detail for " + runId, failure); }
    }

    /** Most recent completed root calls, newest first. Child calls never become the root Agent's own observations. */
    public synchronized List<ToolDetail> recentRootResults(String runId, int limit) {
        if (!Ids.isSafeResourceId(runId) || limit <= 0) return List.of();
        return sequences(runId).stream().sorted(Comparator.reverseOrder()).limit(64)
            .map(seq -> find(runId, seq)).flatMap(Optional::stream)
            .filter(detail -> detail.subagentConversationId() == null && !detail.resultPending() && detail.content() != null)
            .limit(Math.min(32, limit)).toList();
    }

    /** Resolve legacy clipped child outputs by the unique thread in the delegate result header. */
    public synchronized Optional<ToolDetail> subagentResult(String runId, String threadId) {
        if (!Ids.isSafeResourceId(runId) || !Ids.isSafeResourceId(threadId)) return Optional.empty();
        String suffix = " (thread: " + threadId + ")\n\n";
        return sequences(runId).stream().sorted(Comparator.reverseOrder())
            .map(seq -> find(runId, seq)).flatMap(Optional::stream)
            .filter(detail -> "delegate".equals(detail.name()) && !detail.resultPending() && detail.content() != null)
            .filter(detail -> detail.content().startsWith("Sub-agent completed" + suffix)
                || detail.content().startsWith("Sub-agent did not complete cleanly" + suffix))
            .findFirst();
    }

    private List<Long> sequences(String runId) {
        List<Long> sequences;
        if (memory != null) {
            sequences = memory.values().stream().filter(detail -> runId.equals(detail.runId())).map(ToolDetail::seq).toList();
        } else {
            Path directory = root.resolve(runId);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
            try (var files = Files.list(directory)) {
                sequences = files.map(file -> file.getFileName().toString()).filter(name -> name.matches("[1-9][0-9]{0,18}\\.json"))
                    .map(name -> {
                        try { return Long.parseLong(name.substring(0, name.length() - 5)); }
                        catch (NumberFormatException invalid) { return 0L; }
                    }).filter(seq -> seq > 0).toList();
            } catch (IOException failure) { throw new RunLedger.RunPersistenceException("Cannot list tools for " + runId, failure); }
        }
        return sequences;
    }

    public List<ToolDetail> list(String runId) {
        if (!Ids.isSafeResourceId(runId)) return List.of();
        // Each file is atomically replaced. Bulk history reads must not hold the writer/shared-storage lock.
        return sequences(runId).stream().sorted().map(seq -> read(runId, seq)).flatMap(Optional::stream).toList();
    }

    synchronized void capture(String runId, RunPresentationState.ToolActivity tool, StreamEvent event) {
        ToolDetail previous = find(runId, tool.seq()).orElse(null);
        Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = data.get("metadata") instanceof Map<?, ?> value
            ? (Map<String, Object>)value : previous == null ? null : previous.metadata();
        save(new ToolDetail(runId, tool.seq(), tool.id(), tool.name(), tool.subagentConversationId(),
            data.containsKey("arguments") ? text(data.get("arguments")) : previous == null ? tool.arguments() : previous.arguments(),
            data.containsKey("content") ? text(data.get("content")) : previous == null ? tool.content() : previous.content(),
            metadata, tool.durationMs(), tool.resultPending(), tool.state(), tool.afterMessageId(), tool.purpose()));
    }

    public synchronized ToolDetail save(ToolDetail detail) {
        return storage == null ? write(detail) : storage.transaction(() -> write(detail));
    }
    private ToolDetail write(ToolDetail detail) {
        if (!Ids.isSafeResourceId(detail.runId()) || detail.seq() < 1) throw new IllegalArgumentException("Invalid tool identity");
        @SuppressWarnings("unchecked") Map<String, Object> metadata = (Map<String, Object>)scrubValue(detail.metadata());
        ToolDetail safe = new ToolDetail(detail.runId(), detail.seq(), detail.id(), detail.name(), detail.subagentConversationId(),
            scrub(detail.arguments()), scrub(detail.content()), metadata, detail.durationMs(), detail.resultPending(),
            detail.state(), detail.afterMessageId(), scrub(detail.purpose()));
        if (memory != null) { memory.put(safe.runId() + ":" + safe.seq(), safe); return safe; }
        try {
            Path dir = root.resolve(safe.runId()); Files.createDirectories(dir);
            TextEditCore.atomicWrite(dir.resolve(safe.seq() + ".json"), JSON.writeValueAsString(safe));
            return safe;
        } catch (IOException failure) { throw new RunLedger.RunPersistenceException("Cannot persist tool detail for " + safe.runId(), failure); }
    }

    private String scrub(String value) {
        var safety = config.getSafety();
        return value == null || safety == null || !safety.isRedactSecretsInToolResults() && !safety.isRedactPiiInToolResults()
            ? value : SecretRedactor.redact(value, safety.isRedactPiiInToolResults());
    }
    private Object scrubValue(Object value) {
        if (value instanceof String text) return scrub(text);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), scrubValue(item))); return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::scrubValue).toList();
        return value;
    }
    private static String text(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        try { return JSON.writeValueAsString(value); } catch (IOException failure) { throw new IllegalArgumentException(failure); }
    }
}
