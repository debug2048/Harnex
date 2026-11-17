package com.harnex.conversation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.harnex.execution.ExecutionSpec;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;

/**
 * A durable journal transaction is the commit point. Message files, receipts and small summaries are projections
 * applied before the head advances. Fully projected transactions retain references instead of duplicate bodies.
 * Recovery only replays the unprojected tail; normal pagination never scans bodies.
 * ProjectStorage serializes shared archive transactions across hosts. No lock spans provider or tool execution.
 */
public class FileConversationRepository implements ConversationRepository {
    @Override public synchronized RunHistory captureRunHistory(String scope, String conversationId, String turnId, String runId) {
        var meta = loadConversation(scope, conversationId).orElse(null);
        if (meta == null || meta.deleted()) return new RunHistory(meta, null, null);
        var turn = turnId == null ? null : findTurn(scope, conversationId, turnId).orElse(null);
        MessageRecord answer = null;
        if (turn != null) for (String attemptId : turn.assistantAttemptIds()) {
            var attempt = findMessage(scope, conversationId, attemptId).orElse(null);
            if (attempt != null && runId.equals(attempt.runId())) { answer = attempt; break; }
        }
        return new RunHistory(meta, turn, answer);
    }
    private static final int FORMAT = 1;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Path base;
    private final String scopeId;
    private final NavigableMap<String, ConversationMeta> summaries = new TreeMap<>();
    private final Map<String, String> summaryKeys = new HashMap<>();
    private final AtomicLong messageReads = new AtomicLong();
    private final ConversationAncestryIndex ancestry;
    private Head head;

    public FileConversationRepository(Path scopeDataDirectory, String scopeId) {
        this.base = scopeDataDirectory.toAbsolutePath().normalize();
        this.scopeId = Objects.requireNonNull(scopeId);
        this.ancestry = new ConversationAncestryIndex(base.resolve("conversations/records"), json, this::message);
        try {
            Files.createDirectories(base.resolve("conversations/records"));
            head = readOptional(base.resolve("conversations/head.json"), Head.class)
                .orElse(new Head(FORMAT, 0, UUID.randomUUID().toString()));
            if (head.schemaVersion() != FORMAT) throw error("unsupported_history_format", "unsupported history schema");
            if (!Files.exists(base.resolve("conversations/head.json"))) persistHead(head);
            recover();
            compactOldJournal();
            try (var directories = Files.list(base.resolve("conversations/records"))) {
                for (Path directory : directories.filter(Files::isDirectory).toList()) {
                    Optional<ConversationMeta> meta = readOptional(directory.resolve("meta.json"), ConversationMeta.class);
                    meta.ifPresent(this::indexSummary);
                }
            }
        } catch (IOException failure) { throw unavailable(failure); }
    }

    @Override public synchronized SummaryPage listSummaries(String scope, String cursor, int limit) {
        prepare(scope);
        limit = boundedLimit(limit);
        NavigableMap<String, ConversationMeta> view = summaries;
        if (cursor != null && !cursor.isBlank()) view = summaries.tailMap(decode(cursor), false);
        List<ConversationMeta> page = new ArrayList<>();
        String last = null;
        var iterator = view.entrySet().iterator();
        while (iterator.hasNext() && page.size() < limit) {
            var entry = iterator.next();
            last = entry.getKey();
            page.add(entry.getValue());
        }
        return new SummaryPage(List.copyOf(page), iterator.hasNext() ? encode(last) : null);
    }

    @Override public synchronized Optional<ConversationMeta> loadConversation(String scope, String conversationId) {
        prepare(scope);
        return meta(conversationId).filter(value -> !value.deleted());
    }

    @Override public synchronized Optional<MessageRecord> findMessage(String scope, String conversationId, String messageId) {
        prepare(scope);
        requireConversation(conversationId);
        return message(conversationId, messageId);
    }

    @Override public synchronized Optional<TurnRecord> findTurn(String scope, String conversationId, String turnId) {
        prepare(scope);
        requireConversation(conversationId);
        return turn(conversationId, turnId);
    }

    @Override public synchronized Optional<MessageHeader> findMessageHeader(String scope, String conversationId, String messageId) {
        prepare(scope); requireConversation(conversationId);
        return ancestry.header(conversationId, messageId);
    }

    @Override public synchronized boolean isMessageAncestor(String scope, String conversationId, String ancestorId, String leafId) {
        prepare(scope); requireConversation(conversationId);
        return ancestry.contains(conversationId, ancestorId, leafId);
    }

    @Override public synchronized Optional<MessageHeader> findBranchChild(String scope, String conversationId, String ancestorId, String leafId) {
        prepare(scope); requireConversation(conversationId);
        return ancestry.child(conversationId, ancestorId, leafId);
    }

    @Override public synchronized boolean hasCompletedRun(String scope, String conversationId, String runId, String leafId) {
        prepare(scope); requireConversation(conversationId);
        return ancestry.completedRun(conversationId, runId, leafId);
    }

    @Override public synchronized MessagePage pageMessages(String scope, String conversationId, String leafId,
        String cursor, int limit) {
        prepare(scope);
        limit = boundedLimit(limit);
        ConversationMeta meta = requireConversation(conversationId);
        String leaf = leafId == null ? meta.activeLeafId() : leafId;
        String next = leaf;
        if (cursor != null && !cursor.isBlank()) {
            String[] fields = decode(cursor).split("\\|", -1);
            if (fields.length != 3 || !constantEquals(fields[2], cursorSignature(conversationId, fields[0], fields[1]))
                || leafId != null && !leafId.equals(fields[0])) {
                throw error("invalid_cursor", "history cursor does not belong to this selection");
            }
            leaf = fields[0];
            next = fields[1];
        }
        List<MessageRecord> result = new ArrayList<>();
        HashSet<String> visited = new HashSet<>();
        long textBytes = 0;
        while (next != null && result.size() < limit && (result.isEmpty() || textBytes < 2_000_000)) {
            if (!visited.add(next)) throw error("history_corrupt", "message parent cycle");
            MessageRecord item = requireMessage(conversationId, next);
            long size = item.contentParts().stream().mapToLong(part -> part.text() == null ? 128 : part.text().getBytes(StandardCharsets.UTF_8).length).sum();
            if (!result.isEmpty() && textBytes + size > 2_000_000) break;
            result.add(item);
            textBytes += size;
            next = item.parentMessageId();
        }
        String nextCursor = next == null ? null : encode(leaf + "|" + next + "|" + cursorSignature(conversationId, leaf, next));
        return new MessagePage(List.copyOf(result), nextCursor, meta.historyRevision(), meta.activeLeafId());
    }

    @Override public synchronized CommitReceipt acceptTurn(AcceptTurn command) {
        return acceptTurn(command, command.expectedHistoryRevision());
    }

    /** Recovery may advance the checked revision, but the original command remains the idempotency identity. */
    synchronized CommitReceipt acceptTurn(AcceptTurn command, Long expectedHistoryRevision) {
        prepare(command.scopeId());
        validateId(command.conversationId());
        validateId(command.turnId());
        validateId(command.runId());
        if (command.execution() == null || command.input().isEmpty() || blank(command.requestId()) || blank(command.requestHash())) {
            throw error("bad_request", "execution, input, requestId and requestHash are required");
        }
        Operation replay = operation(command.operationId());
        if (replay != null) return sameOperation(command, replay);
        Operation request = readOptional(requestPath(command.requestId()), Operation.class).orElse(null);
        if (request != null) {
            if (!command.requestHash().equals(request.requestHash())) {
                throw error("idempotency_conflict", "requestId already belongs to a different submission");
            }
            return request.receipt();
        }
        long now = System.currentTimeMillis();
        ConversationMeta current = meta(command.conversationId()).orElse(null);
        if (current == null) {
            if (expectedHistoryRevision != null && expectedHistoryRevision != 0
                || command.parentMessageId() != null) throw error("history_conflict", "new conversation requires revision 0 and no parent");
            current = new ConversationMeta(FORMAT, command.conversationId(), scopeId, 0, 0, null,
                title(command.input()), now, now, command.execution(), null, null, "", 0, false);
        } else {
            checkAlive(current);
            if (expectedHistoryRevision == null) throw error("history_revision_required", "expectedHistoryRevision is required");
            checkRevision(current, expectedHistoryRevision);
            if (current.messageCount() > 0 && command.parentMessageId() == null) {
                throw error("parent_required", "parentMessageId is required for an existing conversation");
            }
        }
        if (command.parentMessageId() != null) {
            MessageRecord parent = requireMessage(command.conversationId(), command.parentMessageId());
            if (parent.isMutable()) throw error("predecessor_incomplete", "parent answer is still running");
        }
        if (turn(command.conversationId(), command.turnId()).isPresent()) throw error("turn_conflict", "turnId already exists");
        String userId = newId("message");
        String assistantId = newId("message");
        MessageRecord user = new MessageRecord(FORMAT, userId, command.parentMessageId(), null, command.turnId(),
            command.runId(), MessageRecord.Role.USER, command.input(), MessageRecord.Status.COMPLETED, 1, now, now);
        MessageRecord answer = new MessageRecord(FORMAT, assistantId, userId, null, command.turnId(), command.runId(),
            MessageRecord.Role.ASSISTANT, List.of(), MessageRecord.Status.PENDING, 1, now, now);
        TurnRecord turn = new TurnRecord(command.turnId(), command.conversationId(), userId, List.of(assistantId),
            command.requestId(), command.requestHash(), command.predecessorTurnId(), command.execution(), command.runId(),
            command.operationId());
        ConversationMeta next = evolve(current, 1, 0, assistantId, current.title(), command.execution(), command.runId(),
            "QUEUED", plain(command.input()), 2, false);
        return commit(command, command.operationId(), CommitReceipt.Kind.TURN_ACCEPTED, next, command.turnId(),
            command.runId(), List.of(user, answer), List.of(turn), List.of(), command.requestId(), command.requestHash());
    }

    @Override public synchronized CommitReceipt checkpointAnswer(WriteResult command) {
        if (command.status() != MessageRecord.Status.STREAMING) throw error("bad_request", "checkpoint status must be STREAMING");
        return writeResult(command, CommitReceipt.Kind.ANSWER_CHECKPOINT);
    }

    @Override public synchronized CommitReceipt commitTurnResult(WriteResult command) {
        if (command.status() == null || command.status() == MessageRecord.Status.PENDING
            || command.status() == MessageRecord.Status.STREAMING || command.status() == MessageRecord.Status.PROGRESS) {
            throw error("bad_request", "a final result requires a final message status");
        }
        return writeResult(command, CommitReceipt.Kind.RESULT_COMMITTED);
    }

    private CommitReceipt writeResult(WriteResult command, CommitReceipt.Kind kind) {
        prepare(command.scopeId());
        Operation replay = operation(command.operationId());
        if (replay != null) return sameOperation(command, replay);
        ConversationMeta current = requireConversation(command.conversationId());
        TurnRecord turn = requireTurn(command.conversationId(), command.turnId(), command.runId());
        MessageRecord previous = requireMessage(command.conversationId(), turn.activeAttemptId());
        if (!previous.isMutable()) throw error("message_final", "a completed answer cannot be overwritten");
        MessageRecord answer = new MessageRecord(FORMAT, previous.messageId(), previous.parentMessageId(),
            previous.revisesMessageId(), previous.turnId(), previous.runId(), previous.role(), command.contentParts(),
            command.status(), previous.revision() + 1, previous.createdAt(), System.currentTimeMillis());
        ConversationMeta next = evolve(current, 1, 0, current.activeLeafId(), current.title(), current.defaults(),
            current.latestRunId(), command.runId().equals(current.latestRunId()) ? runStatus(command.status()) : current.latestRunStatus(),
            previous.messageId().equals(current.activeLeafId()) ? plain(command.contentParts()) : current.preview(), 0, false);
        return commit(command, command.operationId(), kind, next, command.turnId(), command.runId(), List.of(answer),
            List.of(), List.of(), null, null);
    }

    @Override public synchronized CommitReceipt commitAgentStepResult(AgentStepResult command) {
        prepare(command.scopeId());
        Operation replay = operation(command.operationId());
        if (replay != null) return sameOperation(command, replay);
        validateId(command.stepId());
        ConversationMeta current = requireConversation(command.conversationId());
        TurnRecord turn = requireTurn(command.conversationId(), command.turnId(), command.runId());
        if (turn.executionSnapshot().kind() != ExecutionSpec.Kind.AGENT) throw error("unsupported_control", "only Agent turns have steps");
        MessageRecord pending = requireMessage(command.conversationId(), turn.activeAttemptId());
        if (!pending.isMutable()) throw error("message_final", "turn already finished");
        long now = System.currentTimeMillis();
        String messageId = ConversationRepository.agentStepMessageId(command.runId(), command.stepId());
        if (message(command.conversationId(), messageId).isPresent()) throw error("step_conflict", "step already committed with another operationId");
        MessageRecord progress = new MessageRecord(FORMAT, messageId, pending.parentMessageId(), null, command.turnId(),
            command.runId(), MessageRecord.Role.ASSISTANT, command.contentParts(), MessageRecord.Status.PROGRESS, 1, now, now);
        MessageRecord answer = new MessageRecord(FORMAT, pending.messageId(), messageId, pending.revisesMessageId(),
            pending.turnId(), pending.runId(), pending.role(), pending.contentParts(), pending.status(),
            pending.revision() + 1, pending.createdAt(), now);
        ConversationMeta next = evolve(current, 1, 0, current.activeLeafId(), current.title(), current.defaults(),
            current.latestRunId(), command.runId().equals(current.latestRunId()) ? "RUNNING" : current.latestRunStatus(),
            pending.messageId().equals(current.activeLeafId()) ? plain(command.contentParts()) : current.preview(), 1, false);
        return commit(command, command.operationId(), CommitReceipt.Kind.AGENT_STEP_COMMITTED, next, command.turnId(),
            command.runId(), List.of(progress, answer), List.of(), List.of(), null, null);
    }

    @Override public synchronized CommitReceipt reviseMessage(ReviseMessage command) {
        prepare(command.scopeId());
        Operation replay = operation(command.operationId());
        if (replay != null) return sameOperation(command, replay);
        ConversationMeta current = requireConversation(command.conversationId());
        checkRevision(current, command.expectedHistoryRevision());
        MessageRecord original = requireMessage(command.conversationId(), command.messageId());
        if (original.isMutable()) throw error("message_running", "stop the current answer before editing");
        long now = System.currentTimeMillis();
        MessageRecord revised = new MessageRecord(FORMAT, newId("message"), original.parentMessageId(), original.messageId(),
            original.turnId(), null, original.role(), command.contentParts(), MessageRecord.Status.COMPLETED, 1, now, now);
        ConversationMeta next = evolve(current, 1, 0, revised.messageId(), current.title(), current.defaults(),
            null, null, plain(command.contentParts()), 1, false);
        return commit(command, command.operationId(), CommitReceipt.Kind.MESSAGE_REVISED, next, original.turnId(), null,
            List.of(revised), List.of(), List.of(), null, null);
    }

    @Override public synchronized CommitReceipt selectBranch(SelectBranch command) {
        prepare(command.scopeId());
        Operation replay = operation(command.operationId());
        if (replay != null) return sameOperation(command, replay);
        ConversationMeta current = requireConversation(command.conversationId());
        checkRevision(current, command.expectedHistoryRevision());
        MessageRecord selected = command.leafId() == null ? null : requireMessage(command.conversationId(), command.leafId());
        if (selected != null && selected.isMutable()) throw error("message_running", "cannot select an unfinished branch");
        ConversationMeta next = evolve(current, 1, 0, selected == null ? null : selected.messageId(), current.title(), current.defaults(),
            null, null, selected == null ? "" : selected.plainText(), 0, false);
        return commit(command, command.operationId(), CommitReceipt.Kind.BRANCH_SELECTED, next, selected == null ? null : selected.turnId(), null,
            List.of(), List.of(), List.of(), null, null);
    }

    @Override public synchronized CommitReceipt forkConversation(ForkConversation command) {
        prepare(command.scopeId());
        Operation replay = operation(command.operationId());
        if (replay != null) return sameOperation(command, replay);
        validateId(command.conversationId());
        if (loadConversation(command.scopeId(), command.conversationId()).isPresent()) throw error("conversation_exists", "The destination conversation already exists");
        ConversationMeta source = requireConversation(command.sourceConversationId());
        List<MessageRecord> messages = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, TurnRecord> turns = new LinkedHashMap<>();
        String cursor = command.sourceLeafId(); long textBytes = 0;
        while (cursor != null) {
            if (!seen.add(cursor)) throw error("history_corrupt", "Conversation ancestry contains a cycle");
            MessageRecord message = requireMessage(command.sourceConversationId(), cursor);
            if (message.isMutable()) throw error("message_running", "Cannot copy unfinished history");
            textBytes += message.plainText().getBytes(StandardCharsets.UTF_8).length;
            if (messages.size() >= 100_000 || textBytes > 128L * 1024 * 1024) throw error("fork_too_large", "The selected history exceeds the fork size limit");
            messages.add(message);
            turns.computeIfAbsent(message.turnId(), id -> turn(command.sourceConversationId(), id)
                .orElseThrow(() -> error("history_corrupt", "A source turn is missing")));
            cursor = message.parentMessageId();
        }
        // Preserve immutable revision and turn-user references even when they are off the selected visible branch.
        for (int index = 0; index < messages.size(); index++) {
            MessageRecord message = messages.get(index);
            List<String> references = new ArrayList<>();
            if (message.parentMessageId() != null) references.add(message.parentMessageId());
            if (message.revisesMessageId() != null) references.add(message.revisesMessageId());
            references.add(turns.get(message.turnId()).userMessageId());
            for (String id : references) if (seen.add(id)) {
                MessageRecord referenced = requireMessage(command.sourceConversationId(),id);
                if (referenced.isMutable()) throw error("message_running","A referenced message is unfinished");
                textBytes += referenced.plainText().getBytes(StandardCharsets.UTF_8).length;
                if (messages.size() >= 100_000 || textBytes > 128L * 1024 * 1024) throw error("fork_too_large","The selected history exceeds the fork size limit");
                messages.add(referenced);
                turns.computeIfAbsent(referenced.turnId(), turnId -> turn(command.sourceConversationId(),turnId).orElseThrow());
            }
        }
        List<TurnRecord> copiedTurns = turns.values().stream().map(turn -> new TurnRecord(turn.turnId(), command.conversationId(),
            turn.userMessageId(), turn.assistantAttemptIds().stream().filter(seen::contains).toList(), turn.requestId(), turn.requestHash(),
            turn.predecessorTurnId(), turn.executionSnapshot(), turn.runId(), turn.acceptedOperationId())).toList();
        long now = System.currentTimeMillis();
        ConversationMeta destination = new ConversationMeta(FORMAT, command.conversationId(), command.scopeId(), 1, 1,
            command.sourceLeafId(), command.title() == null || command.title().isBlank() ? source.title() : clip(command.title().strip(), 200),
            now, now, source.defaults(), null, null, messages.isEmpty() ? "" : clip(messages.get(0).plainText(),240), messages.size(), false);
        return commit(command, command.operationId(), CommitReceipt.Kind.CONVERSATION_FORKED, destination,
            messages.isEmpty() ? null : messages.get(0).turnId(), null, messages, copiedTurns, List.of(), null, null);
    }

    @Override public synchronized CommitReceipt updateTitle(UpdateTitle command) {
        prepare(command.scopeId());
        Operation replay = operation(command.operationId());
        if (replay != null) return sameOperation(command, replay);
        ConversationMeta current = requireConversation(command.conversationId());
        if (current.metadataRevision() != command.expectedMetadataRevision()) throw error("metadata_conflict", "metadata changed");
        if (blank(command.title()) || command.title().length() > 200) throw error("bad_request", "title must contain 1..200 characters");
        ConversationMeta next = evolve(current, 0, 1, current.activeLeafId(), command.title().trim(), current.defaults(),
            current.latestRunId(), current.latestRunStatus(), current.preview(), 0, false);
        return commit(command, command.operationId(), CommitReceipt.Kind.METADATA_UPDATED, next, null, null,
            List.of(), List.of(), List.of(), null, null);
    }
    @Override public synchronized CommitReceipt acceptRetry(RetryTurn command) {
        prepare(command.scopeId()); validateId(command.runId());
        if (command.execution() == null || blank(command.requestId()) || blank(command.requestHash())) throw error("bad_request", "execution, requestId and requestHash are required");
        Operation replay = operation(command.operationId()); if (replay != null) return sameOperation(command, replay);
        Operation request = readOptional(requestPath(command.requestId()), Operation.class).orElse(null);
        if (request != null) {
            if (!command.requestHash().equals(request.requestHash())) throw error("idempotency_conflict", "requestId belongs to another submission");
            return request.receipt();
        }
        ConversationMeta current = requireConversation(command.conversationId()); checkRevision(current, command.expectedHistoryRevision());
        MessageRecord user = requireMessage(command.conversationId(), command.userMessageId());
        if (user.role() != MessageRecord.Role.USER || user.isMutable() || !command.turnId().equals(user.turnId())) throw error("invalid_retry", "retry requires an immutable user message in this turn");
        TurnRecord previous = turn(command.conversationId(), command.turnId()).orElseThrow(() -> error("turn_not_found", "turn not found"));
        if (previous.activeAttemptId() != null && requireMessage(command.conversationId(), previous.activeAttemptId()).isMutable()) throw error("predecessor_incomplete", "stop the current attempt before retrying");
        long now = System.currentTimeMillis(); String answerId = newId("message");
        MessageRecord answer = new MessageRecord(FORMAT, answerId, user.messageId(), previous.activeAttemptId(), previous.turnId(), command.runId(),
            MessageRecord.Role.ASSISTANT, List.of(), MessageRecord.Status.PENDING, 1, now, now);
        List<String> attempts = new ArrayList<>(previous.assistantAttemptIds()); attempts.add(answerId);
        TurnRecord nextTurn = new TurnRecord(previous.turnId(), previous.conversationId(), user.messageId(), attempts, command.requestId(),
            command.requestHash(), previous.predecessorTurnId(), command.execution(), command.runId(), command.operationId());
        ConversationMeta next = evolve(current, 1, 0, answerId, current.title(), command.execution(), command.runId(), "QUEUED", user.plainText(), 1, false);
        return commit(command, command.operationId(), CommitReceipt.Kind.TURN_ACCEPTED, next, previous.turnId(), command.runId(),
            List.of(answer), List.of(nextTurn), List.of(), command.requestId(), command.requestHash());
    }

    @Override public synchronized CommitReceipt appendRunInput(AppendRunInput command) {
        prepare(command.scopeId());
        Operation replay = operation(command.operationId());
        if (replay != null) return sameOperation(command, replay);
        validateId(command.inputId());
        ConversationMeta current = requireConversation(command.conversationId());
        TurnRecord turn = requireTurn(command.conversationId(), command.turnId(), command.runId());
        MessageRecord pending = requireMessage(command.conversationId(), turn.activeAttemptId());
        if (!pending.isMutable()) throw error("message_final", "cannot apply input after the turn ended");
        long now = System.currentTimeMillis();
        String messageId = "input_" + digest(command.runId() + "\0" + command.inputId()).substring(0, 32);
        if (message(command.conversationId(), messageId).isPresent()) throw error("input_conflict", "input was committed under another operationId");
        MessageRecord input = new MessageRecord(FORMAT, messageId, pending.parentMessageId(), null, command.turnId(), command.runId(),
            MessageRecord.Role.USER, command.contentParts(), MessageRecord.Status.COMPLETED, 1, now, now);
        MessageRecord answer = new MessageRecord(FORMAT, pending.messageId(), messageId, pending.revisesMessageId(), pending.turnId(),
            pending.runId(), pending.role(), pending.contentParts(), pending.status(), pending.revision() + 1, pending.createdAt(), now);
        ConversationMeta next = evolve(current, 1, 0, current.activeLeafId(), current.title(), current.defaults(), current.latestRunId(),
            current.latestRunStatus(), plain(command.contentParts()), 1, false);
        return commit(command, command.operationId(), CommitReceipt.Kind.RUN_INPUT_COMMITTED, next, command.turnId(), command.runId(),
            List.of(input, answer), List.of(), List.of(), null, null);
    }

    @Override public synchronized CommitReceipt recordRunState(RunState command) {
        prepare(command.scopeId());
        Operation replay = operation(command.operationId());
        if (replay != null) return sameOperation(command, replay);
        ConversationMeta current = requireConversation(command.conversationId());
        ConversationMeta next = command.runId().equals(current.latestRunId())
            ? evolve(current, 0, 1, current.activeLeafId(), current.title(), current.defaults(), current.latestRunId(),
                command.status(), current.preview(), 0, false) : current;
        return commit(command, command.operationId(), CommitReceipt.Kind.RUN_STATE_UPDATED, next, null, command.runId(),
            List.of(), List.of(), List.of(), null, null);
    }

    @Override public synchronized CommitReceipt deleteConversation(DeleteConversation command) {
        prepare(command.scopeId());
        Operation replay = operation(command.operationId());
        if (replay != null) return sameOperation(command, replay);
        ConversationMeta current = requireConversation(command.conversationId());
        checkRevision(current, command.expectedHistoryRevision());
        ConversationMeta next = evolve(current, 1, 0, current.activeLeafId(), current.title(), current.defaults(),
            current.latestRunId(), current.latestRunStatus(), "", 0, true);
        return commit(command, command.operationId(), CommitReceipt.Kind.CONVERSATION_DELETED, next, null, null,
            List.of(), List.of(), List.of(), null, null);
    }

    @Override public synchronized CommitReceipt saveExecutionRecord(String scope, String operationId, ExecutionSearchRecord record) {
        prepare(scope);
        Operation replay = operation(operationId);
        if (replay != null) return sameOperation(record, replay);
        validateId(record.recordId());
        if (record.sourceRevision() < 1 || record.text() != null && record.text().length() > 1_000_000) {
            throw error("bad_request", "invalid execution search record");
        }
        ConversationMeta current = requireConversation(record.conversationId());
        var old = executionRecord(record.conversationId(), record.recordId()).orElse(null);
        if (old != null && old.sourceRevision() >= record.sourceRevision()) throw error("revision_conflict", "execution record revision did not advance");
        return commit(record, operationId, CommitReceipt.Kind.EXECUTION_RECORD_COMMITTED, current, null, record.runId(),
            List.of(), List.of(), List.of(record), null, null);
    }

    @Override public synchronized Optional<ExecutionSearchRecord> findExecutionRecord(String scope, String conversationId, String recordId) {
        prepare(scope);
        requireConversation(conversationId);
        return executionRecord(conversationId, recordId);
    }

    @Override public synchronized Optional<CommitReceipt> findOperation(String scope, String operationId) {
        prepare(scope);
        return Optional.ofNullable(operation(operationId)).map(Operation::receipt);
    }

    @Override public synchronized ChangePage readCommittedChanges(String scope, long cursor, int limit) {
        prepare(scope);
        if (cursor < 0 || cursor > head.sequence()) throw error("invalid_cursor", "invalid change cursor");
        List<CommittedChange> result = new ArrayList<>();
        long end = Math.min(head.sequence(), cursor + boundedLimit(limit));
        for (long seq = cursor + 1; seq <= end; seq++) {
            Transaction tx = read(journalPath(seq), Transaction.class);
            result.add(new CommittedChange(seq, tx.receipt(), tx.executionRecords().stream().map(ExecutionSearchRecord::recordId).toList()));
        }
        return new ChangePage(List.copyOf(result), end, end < head.sequence());
    }

    private CommitReceipt commit(Object command, String operationId, CommitReceipt.Kind kind, ConversationMeta next,
        String turnId, String runId, List<MessageRecord> messages, List<TurnRecord> turns,
        List<ExecutionSearchRecord> executionRecords, String requestId, String requestHash) {
        if (blank(operationId) || operationId.length() > 256) throw error("bad_request", "operationId is required and bounded");
        CommitReceipt receipt = new CommitReceipt(operationId, next.conversationId(), turnId, runId,
            messages.stream().map(MessageRecord::messageId).toList(), next.historyRevision(), kind);
        Transaction transaction = new Transaction(FORMAT, head.sequence() + 1, receipt, hash(command), requestId,
            requestHash, next, messages, turns, executionRecords);
        try {
            // A failed projection is repaired from this exact transaction, never by repeating execution.
            writeJournal(journalPath(transaction.sequence()), transaction);
            project(transaction);
            return receipt;
        } catch (IOException failure) { throw unavailable(failure); }
    }

    /** Narrow fault-injection points also document the two durable boundaries. */
    protected void writeJournal(Path target, Transaction transaction) throws IOException { AtomicJsonFiles.write(json, target, transaction); }
    protected void persistHead(Head next) throws IOException { AtomicJsonFiles.write(json, base.resolve("conversations/head.json"), next); }

    private void project(Transaction transaction) throws IOException {
        if (transaction.schemaVersion() != FORMAT || transaction.sequence() != head.sequence() + 1
            || !scopeId.equals(transaction.meta().scopeId())) throw error("history_corrupt", "invalid committed transaction");
        Path directory = conversationPath(transaction.meta().conversationId());
        for (MessageRecord message : transaction.messages()) AtomicJsonFiles.write(json, directory.resolve("messages").resolve(message.messageId() + ".json"), message);
        ancestry.project(transaction.meta().conversationId(), transaction.messages());
        for (TurnRecord turn : transaction.turns()) AtomicJsonFiles.write(json, directory.resolve("turns").resolve(turn.turnId() + ".json"), turn);
        for (ExecutionSearchRecord record : transaction.executionRecords()) AtomicJsonFiles.write(json, directory.resolve("execution").resolve(record.recordId() + ".json"), record);
        Operation operation = new Operation(transaction.receipt(), transaction.payloadHash(), transaction.requestHash());
        AtomicJsonFiles.write(json, operationPath(transaction.receipt().operationId()), operation);
        if (transaction.requestId() != null) AtomicJsonFiles.write(json, requestPath(transaction.requestId()), operation);
        AtomicJsonFiles.write(json, directory.resolve("meta.json"), transaction.meta());
        Head next = new Head(FORMAT, transaction.sequence(), head.cursorSecret());
        persistHead(next);
        head = next;
        indexSummary(transaction.meta());
        // This must follow durable head publication: an unprojected transaction still needs every original byte.
        try { compactJournal(transaction); }
        catch (IOException deferred) { LoggerFactory.getLogger(getClass()).debug("Journal compaction deferred", deferred); }
        if (head.sequence() % 64 == 0) compactOldJournal();
    }

    private void compactJournal(Transaction transaction) throws IOException {
        if (transaction.sequence() > head.sequence()) return;
        boolean hasBodies = !transaction.messages().isEmpty() || !transaction.turns().isEmpty()
            || transaction.executionRecords().stream().anyMatch(record -> record.text() != null && !record.text().isEmpty());
        if (!hasBodies) return;
        var references = transaction.executionRecords().stream().map(record -> new ExecutionSearchRecord(record.recordId(),
            record.conversationId(), record.runId(), record.toolCallId(), record.sourceRevision(), record.title(), null, record.deleted())).toList();
        AtomicJsonFiles.write(json, journalPath(transaction.sequence()), new Transaction(transaction.schemaVersion(),
            transaction.sequence(), transaction.receipt(), transaction.payloadHash(), transaction.requestId(),
            transaction.requestHash(), transaction.meta(), List.of(), List.of(), references));
    }

    /** Bound migration work; old projected journals shrink gradually without scanning the entire history at startup. */
    private void compactOldJournal() {
        Path cursor = base.resolve("conversations/compacted-through.json");
        try {
            long previous = readOptional(cursor, Long.class).orElse(0L);
            if (previous < 0 || previous > head.sequence()) return;
            long end = Math.min(head.sequence(), previous + 64);
            for (long sequence = previous + 1; sequence <= end; sequence++) {
                compactJournal(read(journalPath(sequence), Transaction.class));
            }
            if (end > previous) AtomicJsonFiles.write(json, cursor, end);
        } catch (IOException | RuntimeException deferred) {
            LoggerFactory.getLogger(getClass()).debug("Older journal compaction deferred", deferred);
        }
    }

    private void recover() throws IOException {
        while (Files.exists(journalPath(head.sequence() + 1))) project(read(journalPath(head.sequence() + 1), Transaction.class));
    }
    /** Called under ProjectStorage's transaction lock before entering the public repository boundary. */
    synchronized void refreshSharedHead() {
        try {
            Head disk = readOptional(base.resolve("conversations/head.json"), Head.class).orElse(head);
            if (disk.sequence() < head.sequence()) throw error("history_corrupt", "shared history head moved backwards");
            for (long sequence = head.sequence() + 1; sequence <= disk.sequence(); sequence++) {
                // Already-projected journals retain metadata even after their large bodies are compacted.
                Transaction transaction = read(journalPath(sequence), Transaction.class);
                indexSummary(transaction.meta());
                ancestry.invalidate(transaction.meta().conversationId(), transaction.receipt().messageIds());
            }
            head = disk;
            recover();
        } catch (IOException failure) { throw unavailable(failure); }
    }
    private void prepare(String scope) {
        if (!scopeId.equals(scope)) throw error("scope_mismatch", "history belongs to another scope");
        try { recover(); } catch (IOException failure) { throw unavailable(failure); }
    }
    private ConversationMeta requireConversation(String id) {
        ConversationMeta result = meta(id).orElseThrow(() -> error("conversation_not_found", "conversation does not exist"));
        checkAlive(result);
        return result;
    }
    private Optional<ConversationMeta> meta(String id) { return readOptional(conversationPath(id).resolve("meta.json"), ConversationMeta.class); }
    private Optional<MessageRecord> message(String conversationId, String id) {
        validateId(id);
        messageReads.incrementAndGet();
        return readOptional(conversationPath(conversationId).resolve("messages").resolve(id + ".json"), MessageRecord.class);
    }
    private MessageRecord requireMessage(String conversationId, String id) {
        return message(conversationId, id).orElseThrow(() -> error("message_not_found", "message is not in this conversation"));
    }
    private Optional<TurnRecord> turn(String conversationId, String id) {
        validateId(id);
        return readOptional(conversationPath(conversationId).resolve("turns").resolve(id + ".json"), TurnRecord.class);
    }
    private TurnRecord requireTurn(String conversationId, String id, String runId) {
        TurnRecord result = turn(conversationId, id).orElseThrow(() -> error("turn_not_found", "turn does not exist"));
        if (!result.runId().equals(runId)) throw error("run_mismatch", "result belongs to another run");
        return result;
    }
    private Optional<ExecutionSearchRecord> executionRecord(String conversationId, String id) {
        validateId(id);
        return readOptional(conversationPath(conversationId).resolve("execution").resolve(id + ".json"), ExecutionSearchRecord.class);
    }
    private Operation operation(String id) {
        if (blank(id)) throw error("bad_request", "operationId is required");
        Operation result = readOptional(operationPath(id), Operation.class).orElse(null);
        if (result != null && !result.receipt().operationId().equals(id)) throw error("history_corrupt", "operation identity mismatch");
        return result;
    }
    private CommitReceipt sameOperation(Object command, Operation previous) {
        if (!previous.payloadHash().equals(hash(command))) throw error("idempotency_conflict", "operationId already belongs to a different command");
        return previous.receipt();
    }
    private <T> Optional<T> readOptional(Path path, Class<T> type) { return Files.exists(path) ? Optional.of(read(path, type)) : Optional.empty(); }
    private <T> T read(Path path, Class<T> type) {
        try { return json.readValue(Files.readAllBytes(path), type); }
        catch (IOException failure) { throw unavailable(failure); }
    }
    private Path conversationPath(String id) { validateId(id); return base.resolve("conversations/records").resolve(id); }
    private Path journalPath(long sequence) { return base.resolve("conversations/commits").resolve(String.format("%020d.json", sequence)); }
    private Path operationPath(String operationId) { return base.resolve("conversations/operations").resolve(digest(operationId) + ".json"); }
    private Path requestPath(String requestId) { return base.resolve("conversations/requests").resolve(digest(requestId) + ".json"); }
    private void indexSummary(ConversationMeta meta) {
        if (!scopeId.equals(meta.scopeId())) throw error("scope_mismatch", "history directory belongs to another scope");
        String previous = summaryKeys.remove(meta.conversationId());
        if (previous != null) summaries.remove(previous);
        if (!meta.deleted()) {
            String key = String.format("%019d", Long.MAX_VALUE - meta.updatedAt()) + "_" + meta.conversationId();
            summaries.put(key, meta);
            summaryKeys.put(meta.conversationId(), key);
        }
    }
    private static ConversationMeta evolve(ConversationMeta old, int historyDelta, int metadataDelta, String leaf,
        String title, ExecutionSpec defaults, String runId, String runStatus, String preview, int messageDelta, boolean deleted) {
        return new ConversationMeta(FORMAT, old.conversationId(), old.scopeId(), old.historyRevision() + historyDelta,
            old.metadataRevision() + metadataDelta, leaf, title, old.createdAt(), System.currentTimeMillis(), defaults,
            runId, runStatus, clip(preview, 240), old.messageCount() + messageDelta, deleted);
    }
    private static String runStatus(MessageRecord.Status status) {
        return switch (status) { case STREAMING, PENDING, PROGRESS -> "RUNNING"; default -> status.name(); };
    }
    private static void checkAlive(ConversationMeta meta) { if (meta.deleted()) throw error("conversation_deleted", "conversation was deleted"); }
    private static void checkRevision(ConversationMeta meta, long expected) {
        if (expected != meta.historyRevision()) throw error("history_conflict", "history changed; refresh before submitting");
    }
    private static void validateId(String id) { if (!Ids.isSafeResourceId(id)) throw error("bad_request", "invalid history identity"); }
    private static int boundedLimit(int limit) { if (limit < 1 || limit > 500) throw error("bad_request", "limit must be 1..500"); return limit; }
    private static String newId(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", ""); }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static String plain(List<ContentPart> parts) { return parts.stream().filter(p -> p.type() == ContentPart.Type.TEXT).map(ContentPart::text).collect(Collectors.joining("\n")); }
    private static String title(List<ContentPart> parts) { String text = plain(parts).strip().replaceAll("\\s+", " "); return text.isEmpty() ? "新会话" : clip(text, 80); }
    private static String clip(String value, int count) { return value == null ? "" : value.substring(0, Math.min(value.length(), count)); }
    private String hash(Object command) { try { return digest(json.writeValueAsString(command)); } catch (IOException failure) { throw unavailable(failure); } }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String cursorSignature(String conversationId, String leaf, String next) { return digest(head.cursorSecret() + "\0" + conversationId + "\0" + leaf + "\0" + next); }
    private static boolean constantEquals(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) {
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw error("invalid_cursor", "invalid history cursor"); }
    }
    private static ConversationException error(String code, String message) { return new ConversationException(code, message); }
    private static ConversationException unavailable(Exception failure) { return new ConversationException("history_unavailable", "history storage is unavailable; execution was not retried", failure); }
    long messageReadCount() { return messageReads.get(); }
    long ancestryLookupCount() { return ancestry.lookupCount(); }

    protected record Head(int schemaVersion, long sequence, String cursorSecret) {}
    protected record Transaction(int schemaVersion, long sequence, CommitReceipt receipt, String payloadHash,
        String requestId, String requestHash, ConversationMeta meta, List<MessageRecord> messages, List<TurnRecord> turns,
        List<ExecutionSearchRecord> executionRecords) {}
    private record Operation(CommitReceipt receipt, String payloadHash, String requestHash) {}
}
