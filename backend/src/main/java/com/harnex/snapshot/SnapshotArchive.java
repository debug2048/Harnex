package com.harnex.snapshot;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.*;
import com.harnex.conversation.*;
import com.harnex.execution.ScopeIdentity;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;

/** Snapshot history references point at immutable public messages; private Agent blobs never become chat history. */
@Component @ProjectRuntime
public final class SnapshotArchive {
    private final ConversationRepository archive;
    private final ScopeIdentity scope;
    private final Path receipts;
    private final ObjectMapper json = new ObjectMapper();
    public SnapshotArchive(ConversationRepository archive, ScopeIdentity scope, AgentConfig config) {
        this.archive = archive; this.scope = scope; receipts = Path.of(config.getDataDir()).resolve("agent/restores");
    }
    public record Reference(String scopeId, String conversationId, String leafId, long historyRevision, String fingerprint) {}
    public record RestoreReceipt(String requestHash, SnapshotService.RestoreResponse result) {}
    public Reference capture(String conversationId) {
        var meta = archive.loadConversation(scope.scopeId(), conversationId).orElse(null);
        if (meta == null || meta.deleted()) return null;
        String leaf = meta.activeLeafId();
        MessageRecord message = null;
        while (leaf != null) {
            message = archive.findMessage(scope.scopeId(),conversationId,leaf).orElseThrow(() -> failure("history_unavailable","Snapshot history is missing"));
            if (!message.isMutable()) break;
            leaf = message.parentMessageId(); message = null;
        }
        return new Reference(scope.scopeId(),conversationId,leaf,meta.historyRevision(),IdempotencyReceiptStore.requestHash(message));
    }
    public void validate(Reference reference) {
        if (reference == null || !scope.scopeId().equals(reference.scopeId())) throw failure("snapshot_archive_unavailable","This snapshot has no history reference in the current space");
        var meta = archive.loadConversation(scope.scopeId(),reference.conversationId()).orElseThrow(() -> failure("snapshot_archive_unavailable","Snapshot history is unavailable"));
        if (meta.deleted()) throw failure("conversation_deleted","Snapshot history was deleted");
        MessageRecord leaf = reference.leafId() == null ? null : archive.findMessage(scope.scopeId(),reference.conversationId(),reference.leafId())
            .orElseThrow(() -> failure("snapshot_archive_unavailable","Snapshot history is unavailable"));
        if (leaf != null && leaf.isMutable() || !reference.fingerprint().equals(IdempotencyReceiptStore.requestHash(leaf))) {
            throw failure("snapshot_archive_changed","The snapshot history reference is not intact");
        }
    }
    public void validateRestore(String conversationId, Reference reference, SnapshotService.RestoreRequest request) {
        validate(reference);
        if (!conversationId.equals(reference.conversationId())) throw failure("snapshot_archive_mismatch","Snapshot belongs to another conversation");
        var meta = archive.loadConversation(scope.scopeId(),conversationId).orElseThrow();
        if (request.expectedHistoryRevision == null || request.expectedHistoryRevision != meta.historyRevision()) throw failure("history_conflict","History changed; review the restore point again");
    }
    public CommitReceipt restore(String conversationId, Reference reference, SnapshotService.RestoreRequest request) {
        validateRestore(conversationId,reference,request);
        return archive.selectBranch(new ConversationRepository.SelectBranch(scope.scopeId(),conversationId,"snapshot_history_" + request.commandId,
            reference.leafId(),request.expectedHistoryRevision));
    }
    public void fork(Reference reference, String destination, String title, String commandId) {
        if (archive.findOperation(scope.scopeId(),"snapshot_fork_" + commandId).isEmpty()) validate(reference);
        archive.forkConversation(new ConversationRepository.ForkConversation(scope.scopeId(),destination,"snapshot_fork_" + commandId,
            reference.conversationId(),reference.leafId(),title));
        if (archive.loadConversation(scope.scopeId(),destination).orElseThrow().deleted()) throw failure("conversation_deleted","The fork was deleted");
    }
    public long revision(String conversationId) { return archive.loadConversation(scope.scopeId(),conversationId).map(ConversationMeta::historyRevision).orElse(0L); }
    public String forkId(String commandId) { requireCommand(commandId); return "fork_" + IdempotencyReceiptStore.requestHash(commandId).substring(0,32); }
    public synchronized SnapshotService.RestoreResponse replay(String conversationId, long seq, SnapshotService.RestoreRequest request) {
        requireCommand(request == null ? null : request.commandId);
        Path file = receipt(request.commandId);
        if (!Files.isRegularFile(file)) return null;
        RestoreReceipt saved = read(file);
        if (!saved.requestHash().equals(hash(conversationId,seq,request))) throw failure("idempotency_conflict","Restore ID was used for another request");
        if (saved.result() != null) return saved.result();
        var unknown = new SnapshotService.RestoreResponse(); unknown.reason = "restore_outcome_unknown";
        unknown.warnings = List.of("A previous restore lost its completion acknowledgement. Inspect the current history and files before starting a new restore.");
        unknown.conflicts = List.of(); unknown.failedFiles = List.of(); return unknown;
    }
    /** A crash after this point must never cause an automatic repetition of filesystem writes. */
    public synchronized void begin(String conversationId, long seq, SnapshotService.RestoreRequest request) {
        write(receipt(request.commandId), new RestoreReceipt(hash(conversationId,seq,request),null));
    }
    public synchronized void complete(String conversationId, long seq, SnapshotService.RestoreRequest request, SnapshotService.RestoreResponse result) {
        Path file = receipt(request.commandId);
        if (Files.isRegularFile(file)) write(file,new RestoreReceipt(hash(conversationId,seq,request),result));
    }
    private static String hash(String conversationId, long seq, SnapshotService.RestoreRequest request) {
        return IdempotencyReceiptStore.requestHash(Map.of("conversationId",conversationId,"seq",seq,"request",request));
    }
    private Path receipt(String commandId) { requireCommand(commandId); return receipts.resolve(IdempotencyReceiptStore.requestHash(commandId) + ".json"); }
    private static void requireCommand(String commandId) { if (!Ids.isSafeCallId(commandId)) throw failure("bad_request","commandId is required for Snapshot mutations"); }
    private RestoreReceipt read(Path file) {
        try { return json.readValue(file.toFile(),RestoreReceipt.class); }
        catch (IOException failure) { throw failure("snapshot_receipt_unavailable","Restore acknowledgement is unavailable"); }
    }
    private void write(Path path,Object value) {
        try { AtomicJsonFiles.write(json,path,value); }
        catch (IOException failure) { throw failure("snapshot_receipt_unavailable","Could not persist the restore acknowledgement"); }
    }
    private static ConversationException failure(String code,String message) { return new ConversationException(code,message); }
}
