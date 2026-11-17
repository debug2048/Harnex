package com.harnex.conversation;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.DiagnosticRetention;
import com.harnex.util.Ids;

/** Context diagnostics contain source references and estimates, never credentials or raw request bodies. */
@Component
public final class ContextPlanStore {
    private final Path base;
    private final DiagnosticRetention retention;
    private final ObjectMapper json = new ObjectMapper();
    public ContextPlanStore(AgentConfig config) {
        base = Path.of(config.getDataDir()).resolve("diagnostics/context-plans");
        retention = new DiagnosticRetention(base);
    }
    public synchronized void record(ConversationContextPlanner.Trace trace) {
        try { AtomicJsonFiles.write(json, path(trace.runId(), trace.contextPlanId()), trace); retention.afterWrite(); }
        catch (IOException failure) { LoggerFactory.getLogger(getClass()).warn("Context trace could not be saved: {}", failure.toString()); }
    }
    public synchronized List<ConversationContextPlanner.Trace> list(String runId) {
        validate(runId); Path directory = base.resolve(runId);
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().matches("ctx_[a-zA-Z0-9_-]+\\.json"))
                .map(this::read).sorted(Comparator.comparingLong((ConversationContextPlanner.Trace plan) -> plan.capturedAt() == null ? 0 : plan.capturedAt()).reversed()
                    .thenComparing(ConversationContextPlanner.Trace::contextPlanId)).toList();
        } catch (IOException failure) { throw new IllegalStateException("Context diagnostics unavailable", failure); }
    }
    public synchronized Optional<ConversationContextPlanner.Trace> find(String runId, String planId) {
        Path path = path(runId, planId); return Files.exists(path) ? Optional.of(read(path)) : Optional.empty();
    }
    private ConversationContextPlanner.Trace read(Path path) {
        try { return json.readValue(Files.readAllBytes(path), ConversationContextPlanner.Trace.class); }
        catch (IOException failure) { throw new IllegalStateException("Context diagnostics unavailable", failure); }
    }
    private Path path(String runId, String planId) { validate(runId); validate(planId); return base.resolve(runId).resolve(planId + ".json"); }
    private static void validate(String id) { if (!Ids.isSafeResourceId(id)) throw new IllegalArgumentException("invalid context identity"); }
}
