package com.harnex.web.api.conversation;

import com.harnex.auth.ClientIdentity;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.artifact.ArtifactAttachments;
import com.harnex.artifact.ArtifactException;
import com.harnex.conversation.*;
import com.harnex.execution.ExecutionSpec;
import com.harnex.execution.ScopeIdentity;
import com.harnex.contract.StrictRequest;
import com.harnex.contract.run.RunView;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.run.ProjectConversationLocks;
import com.harnex.run.RunCoordinator;
import com.harnex.application.run.RunRequestService;
import com.harnex.util.Ids;

import io.swagger.v3.oas.annotations.media.Schema;

/** Public history resource. Querying it never loads mutable Agent working context. */
@RestController
@RequestMapping("/api/v1/conversations")
public final class ConversationsController {
    private final ConversationRepository archive;
    private final ConversationSearch search;
    private final ScopeIdentity scope;
    private final RunCoordinator runs;
    private final RunRequestService requests;
    private final ArtifactAttachments attachments;
    private ProjectConversationLocks ownership;
    private ConversationFactStore facts;
    @Autowired
    public ConversationsController(ConversationRepository archive, ConversationSearch search, ScopeIdentity scope,
        RunCoordinator runs, RunRequestService requests, ArtifactAttachments attachments, ProjectConversationLocks ownership,
        ConversationFactStore facts) {
        this(archive, search, scope, runs, requests, attachments, ownership);
        this.facts = facts;
    }
    public ConversationsController(ConversationRepository archive, ConversationSearch search, ScopeIdentity scope,
        RunCoordinator runs, RunRequestService requests, ArtifactAttachments attachments, ProjectConversationLocks ownership) {
        this(archive, search, scope, runs, requests, attachments);
        this.ownership = ownership;
    }
    public ConversationsController(ConversationRepository archive, ConversationSearch search, ScopeIdentity scope,
        RunCoordinator runs, RunRequestService requests, ArtifactAttachments attachments) {
        this.archive = archive; this.search = search; this.scope = scope; this.runs = runs; this.requests = requests; this.attachments = attachments;
    }
    @GetMapping public ConversationRepository.SummaryPage list(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "40") int limit) {
        return archive.listSummaries(scope.scopeId(), cursor, limit);
    }
    @GetMapping("/search") public ConversationSearch.SearchPage search(@RequestParam String q, @RequestParam(required = false) String conversationId,
        @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return search.search(scope.scopeId(), q, conversationId, cursor, limit);
    }
    @GetMapping("/{id}") public ResponseEntity<ConversationMeta> get(@PathVariable String id) {
        return archive.loadConversation(scope.scopeId(), id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
    @GetMapping("/{id}/messages") public ConversationRepository.MessagePage messages(@PathVariable String id,
        @RequestParam(required = false) String leafId, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "40") int limit) {
        return archive.pageMessages(scope.scopeId(), id, leafId, cursor, limit);
    }
    @GetMapping("/{id}/messages/{messageId}") public ResponseEntity<MessageRecord> message(@PathVariable String id, @PathVariable String messageId) {
        return archive.findMessage(scope.scopeId(), id, messageId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
    @GetMapping("/{id}/turns/{turnId}") public ResponseEntity<TurnRecord> turn(@PathVariable String id, @PathVariable String turnId) {
        return archive.findTurn(scope.scopeId(), id, turnId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
    @GetMapping("/{id}/execution-records/{recordId}") public ResponseEntity<ExecutionSearchRecord> executionRecord(@PathVariable String id, @PathVariable String recordId) {
        return archive.findExecutionRecord(scope.scopeId(),id,recordId).filter(record -> !record.deleted()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
    @GetMapping("/{id}/facts") public FactPage facts(@PathVariable String id,
        @RequestParam(required = false) String leafId, @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "40") int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("offset must be non-negative and limit must be 1..100");
        var active = factStore().active(scope.scopeId(), id, factLeaf(id, leafId));
        if (offset > active.size()) throw new IllegalArgumentException("offset exceeds active facts; restart listing after changes");
        int end = (int)Math.min(active.size(), (long)offset + limit);
        return new FactPage(List.copyOf(active.subList(offset, end)), end < active.size() ? end : null, active.size());
    }
    @PostMapping("/{id}/facts") public ConversationFactStore.Fact registerFact(@PathVariable String id, @RequestBody RegisterFact request) {
        return ownedMutation(id, () -> factStore().register(scope.scopeId(), id, factLeaf(id, request.leafId()),
            request.sourceMessageId(), request.sourceRevision(), request.key(), request.qualifier(), request.quote(), request.supersedes()));
    }
    @PostMapping("/{id}/title") public CommitReceipt title(@PathVariable String id, @RequestBody Title request) {
        return ownedMutation(id, () -> archive.updateTitle(new ConversationRepository.UpdateTitle(scope.scopeId(), id, operation(request.commandId()), request.title(), request.expectedMetadataRevision())));
    }
    @PostMapping("/{id}/forks") public CommitReceipt fork(@PathVariable String id, @RequestBody Fork request) {
        String operationId = operation(request.commandId());
        String destination = "fork_" + IdempotencyReceiptStore.requestHash(request.commandId()).substring(0,32);
        return archive.forkConversation(new ConversationRepository.ForkConversation(scope.scopeId(),destination,operationId,id,request.leafId(),request.title()));
    }
    @PostMapping("/{id}/branches/selection") public CommitReceipt branch(@PathVariable String id, @RequestBody Branch request) {
        return mutate(id,operation(request.commandId()),() -> archive.selectBranch(new ConversationRepository.SelectBranch(scope.scopeId(), id,
            operation(request.commandId()), request.leafId(), request.expectedHistoryRevision())));
    }
    @PostMapping("/{id}/messages/{messageId}/revisions") public CommitReceipt revise(@PathVariable String id, @PathVariable String messageId, @RequestBody Revision request) {
        return mutate(id,operation(request.commandId()),() -> {
        var original = archive.findMessage(scope.scopeId(), id, messageId).orElseThrow(() -> new ConversationException("message_not_found", "message not found"));
        if (original.role() != MessageRecord.Role.USER) throw new ConversationException("message_not_editable", "Only user messages can be revised");
        if (request.contentParts().stream().anyMatch(part -> part.type() == ContentPart.Type.IMAGE_OPERATION_REF)) throw new ConversationException("bad_request", "Image operation references are server-owned");
        attachments.validate(request.contentParts().stream().filter(part -> part.type() == ContentPart.Type.ARTIFACT_REF).map(ContentPart::artifactId).toList());
        return archive.reviseMessage(new ConversationRepository.ReviseMessage(scope.scopeId(), id, operation(request.commandId()), messageId, request.expectedHistoryRevision(), request.contentParts()));
        });
    }
    @PostMapping("/{id}/messages/{messageId}/retry") public ResponseEntity<?> retry(@PathVariable String id, @PathVariable String messageId, @RequestBody Retry request) {
        var result = requests.retry(id, messageId, request.clientRequestId(), request.expectedHistoryRevision(), request.execution(), request.rerunAgent(), ClientIdentity.actor());
        return result.rejectCode() == null ? ResponseEntity.accepted().body(RunView.from(runs.view(result.run().getRunId()), ClientIdentity.actor()))
            : ResponseEntity.status(status(result.rejectCode())).body(error(result.rejectCode(), result.rejectMessage()));
    }
    @DeleteMapping("/{id}") public CommitReceipt delete(@PathVariable String id, @RequestBody Delete request) {
        // Persist the tombstone first: cancellation and late provider results cannot resurrect the conversation.
        return ownedMutation(id, () -> {
            CommitReceipt receipt = archive.deleteConversation(new ConversationRepository.DeleteConversation(scope.scopeId(), id, operation(request.commandId()), request.expectedHistoryRevision()));
            runs.cancelThread(id); return receipt;
        });
    }

    private <T> T ownedMutation(String id, Supplier<T> action) {
        return ownership == null ? action.get() : ownership.mutate(id, action);
    }
    private ConversationFactStore factStore() {
        if (facts == null) throw new ConversationException("memory_unavailable", "Conversation fact storage is unavailable");
        return facts;
    }
    private String factLeaf(String id, String requested) {
        var meta = archive.loadConversation(scope.scopeId(), id)
            .orElseThrow(() -> new ConversationException("conversation_not_found", "Conversation is unavailable"));
        String leaf = requested == null ? meta.activeLeafId() : requested;
        if (leaf != null && archive.findMessageHeader(scope.scopeId(), id, leaf).isEmpty()) {
            throw new ConversationException("message_not_found", "Selected conversation branch is unavailable");
        }
        return leaf;
    }
    private <T> T mutate(String id, String operationId, Supplier<T> mutation) {
        if (archive.findOperation(scope.scopeId(),operationId).isPresent()) return mutation.get();
        var reservation = runs.tryReserveConversationMutation(id);
        if (reservation == null) throw new ConversationException("conversation_busy", "Stop the current task before changing its conversation branch");
        try (reservation) { return mutation.get(); }
    }
    @ExceptionHandler({ConversationException.class, ArtifactException.class, IllegalArgumentException.class}) public ResponseEntity<?> failure(RuntimeException failure) {
        String code = failure instanceof ConversationException domain ? domain.code() : failure instanceof ArtifactException asset ? asset.code() : "bad_request";
        return ResponseEntity.status(status(code)).body(error(code, failure.getMessage()));
    }
    private static int status(String code) {
        return switch (code) { case "conversation_not_found", "message_not_found", "turn_not_found" -> 404;
            case "conversation_deleted", "idempotency_resource_gone" -> 410;
            case "conversation_busy", "thread_busy", "history_conflict", "metadata_conflict", "idempotency_conflict", "predecessor_incomplete" -> 409;
            default -> 400; };
    }
    private static Map<String, Object> error(String code, String message) { return Map.of("error", Map.of("code", code, "message", message == null ? code : message)); }
    private static String operation(String commandId) {
        if (!Ids.isSafeCallId(commandId)) throw new IllegalArgumentException("commandId is required");
        return "conversation_" + IdempotencyReceiptStore.requestHash(Map.of("commandId", commandId)).substring(0, 32);
    }
    private static void revision(Long value) { if (value == null || value < 0) throw new IllegalArgumentException("expected revision is required"); }
    @Schema(requiredProperties = {"items", "total"})
    public record FactPage(List<ConversationFactStore.Fact> items,
        @Schema(nullable = true) Integer nextOffset, int total) {}
    @Schema(requiredProperties = {"sourceMessageId", "sourceRevision", "key", "quote"})
    public record RegisterFact(@Schema(nullable = true) String leafId,
        String sourceMessageId, @Schema(minimum = "1") Long sourceRevision,
        String key, @Schema(nullable = true) String qualifier,
        String quote, @Schema(nullable = true) String supersedes) implements StrictRequest {
        public RegisterFact {
            if (sourceRevision == null || sourceRevision < 1 || sourceMessageId == null || sourceMessageId.isBlank()
                || key == null || key.isBlank() || quote == null || quote.isBlank()) {
                throw new IllegalArgumentException("sourceMessageId, positive sourceRevision, key and exact quote are required");
            }
        }
    }
    public record Title(String commandId, String title, Long expectedMetadataRevision) implements StrictRequest { public Title { revision(expectedMetadataRevision); } }
    public record Branch(String commandId, String leafId, Long expectedHistoryRevision) implements StrictRequest { public Branch { revision(expectedHistoryRevision); } }
    public record Fork(String commandId, String leafId, String title) implements StrictRequest {}
    public record Revision(String commandId, Long expectedHistoryRevision, List<ContentPart> contentParts) implements StrictRequest {
        public Revision { revision(expectedHistoryRevision); contentParts = List.copyOf(contentParts); if (contentParts.isEmpty() || contentParts.size() > 32
            || contentParts.stream().mapToLong(part -> part.text() == null ? 0 : part.text().length()).sum() > 1_000_000) throw new IllegalArgumentException("invalid revised content"); }
    }
    public record Retry(String clientRequestId, Long expectedHistoryRevision, ExecutionSpec execution, boolean rerunAgent) implements StrictRequest { public Retry { revision(expectedHistoryRevision); } }
    public record Delete(String commandId, Long expectedHistoryRevision) implements StrictRequest { public Delete { revision(expectedHistoryRevision); } }
}
