package com.harnex.cron;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.RuntimeDataPaths;
import com.harnex.config.UserDataPaths;
import com.harnex.util.FilePerms;
import com.harnex.util.Ids;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Global task definitions and execution claims; no project process owns this data. */
@Component
public final class AutomationStore {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path root;
    @FunctionalInterface interface StateWriter { void write(Path file, Object value) throws IOException; }
    private final StateWriter writer;
    @Autowired
    public AutomationStore(AgentConfig config) { this(UserDataPaths.resolve(config).resolve("automation")); }
    public AutomationStore(Path root) { this(root, AutomationStore::writePrivate); }
    AutomationStore(Path root, StateWriter writer) { this.root = RuntimeDataPaths.canonical(root); this.writer = writer; }
    public Path root() { return root; }
    @Data public static class Document {
        private int schemaVersion = 1;
        private Map<String, Job> jobs = new LinkedHashMap<>();
        private Map<String, Attempt> attempts = new LinkedHashMap<>();
    }
    @Data public static class Job {
        private AgentCronScheduler.CronJob task;
        private AutomationRuntimeDirectory.Target target;
        private long createdAt;
        private long nextRunAt;
        private long pendingAt;
        private String activeAttempt;
        private String waitingReason;
    }
    @Data @Schema(name = "AutomationAttempt") public static class Attempt {
        private String id;
        private String jobId;
        private String name;
        private AutomationRuntimeDirectory.Target target;
        private AgentCronScheduler.CronJob task;
        private String trigger;
        private String status = "DISPATCHING";
        private String runId;
        private String conversationId;
        private long startedAt;
        private long finishedAt;
        private String error;
        private long ownerPid;
        private long ownerStartedAt;
        private boolean cancelRequested;
        public boolean active() { return "DISPATCHING".equals(status) || "RUNNING".equals(status); }
        public boolean unfinished() { return active() || "WAITING_RESULT".equals(status); }
    }
    public <T> T read(Function<Document, T> action) { return access(false, action); }
    public <T> T update(Function<Document, T> action) { return access(true, action); }
    private <T> T access(boolean write, Function<Document, T> action) {
        synchronized (LOCKS.computeIfAbsent(root, key -> new Object())) {
            if (!write && !Files.exists(root.resolve("state.json"))) return action.apply(new Document());
            try {
                Files.createDirectories(root);
                Path lockPath = root.resolve("state.lock");
                try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                    FilePerms.hardenOwnerOnly(lockPath);
                    Path file = root.resolve("state.json");
                    Document value = Files.exists(file) ? JSON.readValue(file.toFile(), Document.class) : new Document();
                    if (value.getSchemaVersion() != 1) throw new IllegalStateException("Unsupported automation data version");
                    T result = action.apply(value);
                    if (write) writer.write(file, value);
                    return result;
                }
            } catch (IOException failure) { throw new StorageException("Unable to save or read automations. Check the user data directory and available disk space.", failure); }
        }
    }
    public void saveResult(String id, AutomationExecutionService.Result result) {
        if (!Ids.isSafeResourceId(id)) throw new IllegalArgumentException("Invalid automation attempt");
        try { writePrivate(root.resolve("results").resolve(id + ".json"), result); }
        catch (IOException failure) { throw new StorageException("The task finished but its result could not be saved; check its effects before running again.", failure); }
    }
    public AutomationExecutionService.Result result(String id) {
        if (!Ids.isSafeResourceId(id)) throw new IllegalArgumentException("Invalid automation attempt");
        Path path = root.resolve("results").resolve(id + ".json");
        try { return Files.exists(path) ? JSON.readValue(path.toFile(), AutomationExecutionService.Result.class) : null; }
        catch (IOException failure) { throw new StorageException("Unable to read this automation result", failure); }
    }
    static void writePrivate(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".pending-", ".json");
        try {
            FilePerms.hardenOwnerOnly(temporary);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(value));
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    static <T> T copy(T value, Class<T> type) { return JSON.convertValue(value, type); }
    public static final class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) { super(message, cause); }
    }
}
