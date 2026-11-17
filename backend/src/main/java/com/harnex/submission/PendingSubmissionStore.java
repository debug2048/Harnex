package com.harnex.submission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.conversation.ConversationException;
import com.harnex.execution.ScopeIdentity;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;

@Component
public final class PendingSubmissionStore {
    private final Path base;
    private final String scopeId;
    private final ObjectMapper json = new ObjectMapper();
    @Autowired public PendingSubmissionStore(AgentConfig config, ScopeIdentity scope) { this(Path.of(config.getDataDir()), scope.scopeId()); }
    public PendingSubmissionStore(Path directory, String scopeId) {
        this.base = directory.toAbsolutePath().normalize().resolve("runs/submissions"); this.scopeId = scopeId;
        try { Files.createDirectories(base); } catch (IOException failure) { throw unavailable(); }
    }
    public synchronized Optional<PendingSubmission> find(String conversationId, String submissionId) {
        Path path = file(conversationId, submissionId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            checked(path);
            var value = json.readValue(Files.readAllBytes(path), PendingSubmission.class);
            if (value.schemaVersion() != 1 || !scopeId.equals(value.scopeId()) || !conversationId.equals(value.conversationId())
                || !submissionId.equals(value.submissionId())) throw unavailable();
            return Optional.of(value);
        } catch (IOException failure) { throw unavailable(); }
    }
    public synchronized List<PendingSubmission> list(String conversationId) {
        Path dir = directory(conversationId);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        checked(dir);
        try (var paths = Files.list(dir)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("submission_") && path.getFileName().toString().endsWith(".json")).map(path -> path.getFileName().toString())
                .map(name -> find(conversationId, name.substring(0, name.length() - 5)).orElseThrow())
                .sorted(Comparator.comparingLong(PendingSubmission::ordinal)).toList();
        } catch (IOException failure) { throw unavailable(); }
    }
    public List<String> conversations() {
        try (var paths = Files.list(base)) {
            return paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).map(path -> path.getFileName().toString()).toList();
        } catch (IOException failure) { throw unavailable(); }
    }
    public synchronized PendingSubmission save(PendingSubmission value) {
        if (!scopeId.equals(value.scopeId())) throw new ConversationException("scope_mismatch", "Submission belongs to another scope");
        var old = find(value.conversationId(), value.submissionId());
        if (value.revision() != old.map(record -> record.revision() + 1).orElse(1L)) throw new ConversationException("submission_conflict", "Submission revision changed");
        Path path = file(value.conversationId(), value.submissionId());
        try { Files.createDirectories(path.getParent()); checked(path.getParent()); AtomicJsonFiles.write(json, path, value); return value; }
        catch (IOException failure) { throw unavailable(); }
    }
    private Path directory(String id) {
        if (!Ids.isSafeResourceId(id)) throw new ConversationException("bad_request", "Invalid conversation identity");
        return base.resolve(id);
    }
    private Path file(String conversation, String id) {
        if (!Ids.isSafeResourceId(id)) throw new ConversationException("bad_request", "Invalid submission identity");
        return directory(conversation).resolve(id + ".json");
    }
    private void checked(Path path) {
        try { if (!path.toRealPath().startsWith(base.toRealPath())) throw unavailable(); }
        catch (IOException failure) { throw unavailable(); }
    }
    private static ConversationException unavailable() { return new ConversationException("submission_storage_unavailable", "Submission storage is unavailable"); }
}
