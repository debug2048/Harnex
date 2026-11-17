package com.harnex.conversation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.harnex.model.ChatMessage;
import com.harnex.run.IdempotencyReceiptStore;

/** Validates optional candidates from an already-performed summary call and journals its exact source coverage. */
@Component
public final class SummaryFactExtraction {
    private static final Logger LOG = LoggerFactory.getLogger(SummaryFactExtraction.class);
    private final ConversationRepository repository;
    private final ConversationFactStore facts;
    public SummaryFactExtraction(ConversationRepository repository, ConversationFactStore facts) {
        this.repository = repository; this.facts = facts;
    }
    public record Source(String messageId, long revision, String text, boolean complete) {
        public Source(String messageId, long revision, String text) { this(messageId, revision, text, false); }
    }
    public record Input(String id, String scopeId, String conversationId, String leafId,
        List<Source> considered, List<Source> included, boolean earlierCoverageUnknown) {}
    public record SourceRange(String messageId, long revision, String textHash, int characters, boolean completeSource) {}
    public record Rejected(String messageId, long revision, String reason) {}
    public record Report(int version, String id, String scopeId, String conversationId, String leafId,
        List<SourceRange> considered, List<SourceRange> included, List<SourceRange> omitted,
        List<String> acceptedFactIds, List<Rejected> rejected, String state, String reason, boolean earlierCoverageUnknown) {}

    public Input input(String scopeId, String conversationId, String leafId,
        List<MessageRecord> considered, List<MessageRecord> included) {
        return inputSources(scopeId, conversationId, leafId, userSources(considered), userSources(included), false);
    }
    public Input inputSources(String scopeId, String conversationId, String leafId,
        List<Source> considered, List<Source> included, boolean earlierCoverageUnknown) {
        checkInterrupted();
        var meta = repository.loadConversation(scopeId, conversationId)
            .orElseThrow(() -> new ConversationException("conversation_not_found", "Conversation is unavailable"));
        String leaf = leafId == null ? meta.activeLeafId() : leafId;
        List<Source> requested = distinct(considered), supplied = distinct(included);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("protocol", SummaryFactCandidates.PROTOCOL_VERSION);
        identity.put("scope", scopeId); identity.put("conversation", conversationId); identity.put("leaf", leaf);
        identity.put("considered", ranges(requested)); identity.put("included", ranges(supplied));
        identity.put("earlierCoverageUnknown", earlierCoverageUnknown);
        return new Input("extraction_" + IdempotencyReceiptStore.requestHash(identity), scopeId, conversationId, leaf,
            requested, supplied, earlierCoverageUnknown);
    }

    /** Only direct USER text is eligible. References attached to summaries/recall are never treated as raw text. */
    public List<Source> directSources(String scopeId, String conversationId, String leafId,
        List<ChatMessage> messages) {
        List<Source> result = new ArrayList<>();
        for (var message : messages) {
            checkInterrupted();
            if (message == null || !"user".equals(message.getRole()) || message.getContextSources() == null) continue;
            String text = message.getOriginalInput() == null || message.getOriginalInput().isBlank()
                ? message.getContent() : message.getOriginalInput();
            if (text == null || text.isBlank()) continue;
            for (HistoryReference reference : message.getContextSources()) {
                if (!conversationId.equals(reference.conversationId()) || reference.messageId() == null || reference.executionRecordId() != null) continue;
                var source = repository.findMessage(scopeId, conversationId, reference.messageId()).orElse(null);
                if (source == null || source.role() != MessageRecord.Role.USER || source.isMutable()
                    || reference.revision() != source.revision() || !source.plainText().contains(text)
                    || !repository.isMessageAncestor(scopeId, conversationId, source.messageId(), leafId)) continue;
                result.add(new Source(source.messageId(), source.revision(), text, source.plainText().equals(text)));
                break; // One explicit label per direct user message; recall/summary manifests are not extra raw input.
            }
        }
        return distinct(result);
    }
    public String runLeaf(String scopeId, String conversationId, String turnId, String runId) {
        var history = repository.captureRunHistory(scopeId, conversationId, turnId, runId);
        if (history.answer() == null) throw new ConversationException("history_not_found", "Run history is unavailable");
        return history.answer().messageId();
    }

    public Report accept(Input input, SummaryFactCandidates.Parsed parsed) {
        return accept(input, parsed, SummaryFactExtraction::checkInterrupted);
    }
    public Report accept(Input input, SummaryFactCandidates.Parsed parsed, Runnable checkActive) {
        checkActive.run(); checkInterrupted();
        if (parsed == null || parsed.state() != SummaryFactCandidates.State.COMPLETE) {
            return gap(input, parsed != null && parsed.state() == SummaryFactCandidates.State.MALFORMED
                ? "malformed_candidates" : "missing_candidates", checkActive);
        }
        List<String> accepted = new ArrayList<>(); List<Rejected> rejected = new ArrayList<>();
        try {
            for (var candidate : chronological(input, parsed.candidates(), checkActive)) {
                checkActive.run(); checkInterrupted();
                boolean considered = input.considered().stream().anyMatch(source -> same(source, candidate));
                boolean supplied = input.included().stream().anyMatch(source -> same(source, candidate)
                    && source.text() != null && candidate.quote() != null && source.text().contains(candidate.quote()));
                if (!considered || !supplied) {
                    rejected.add(new Rejected(candidate.sourceMessageId(), candidate.sourceRevision(), "outside_summary_input"));
                    continue;
                }
                try {
                    var registered = facts.registerCandidate(input.scopeId(), input.conversationId(), input.leafId(), candidate);
                    if (!accepted.contains(registered.id())) accepted.add(registered.id());
                } catch (ConversationException invalid) {
                    if (!"invalid_memory_fact".equals(invalid.code())) throw invalid;
                    rejected.add(new Rejected(candidate.sourceMessageId(), candidate.sourceRevision(), invalid.code()));
                } catch (IllegalArgumentException invalid) {
                    rejected.add(new Rejected(candidate.sourceMessageId(), candidate.sourceRevision(), "invalid_source"));
                }
            }
            var report = report(input, accepted, rejected, "attempted", null);
            checkActive.run(); checkInterrupted(); facts.saveExtraction(report);
            return report;
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException unavailable) {
            checkActive.run(); checkInterrupted();
            LOG.warn("Summary fact registration unavailable for conversation={}: {}", input.conversationId(), unavailable.getClass().getSimpleName());
            return persistGap(input, accepted, rejected, "registration_unavailable", checkActive);
        }
    }
    public Report gap(Input input, String reason) { return gap(input, reason, SummaryFactExtraction::checkInterrupted); }
    public Report gap(Input input, String reason, Runnable checkActive) {
        return persistGap(input, List.of(), List.of(), reason, checkActive);
    }
    private Report persistGap(Input input, List<String> accepted, List<Rejected> rejected, String reason, Runnable checkActive) {
        checkActive.run(); checkInterrupted();
        Report report = report(input, accepted, rejected, "not_extracted", reason);
        try {
            // A failed replay must not erase a completed extraction record from an earlier retry.
            var existing = facts.extraction(input.scopeId(), input.conversationId(), input.id());
            if (existing.isPresent() && "attempted".equals(existing.get().state())) return existing.get();
            checkActive.run(); checkInterrupted(); facts.saveExtraction(report);
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException unavailable) {
            checkActive.run(); checkInterrupted();
            LOG.warn("Summary extraction coverage unavailable for conversation={}: {}", input.conversationId(), unavailable.getClass().getSimpleName());
        }
        return report;
    }
    private static Report report(Input input, List<String> accepted, List<Rejected> rejected, String state, String reason) {
        List<Source> omitted = input.considered().stream().filter(source -> !"attempted".equals(state)
            || rejected.stream().anyMatch(rejection -> source.messageId().equals(rejection.messageId()) && source.revision() == rejection.revision())
            || !input.included().stream().anyMatch(included ->
            Objects.equals(source.messageId(), included.messageId()) && source.revision() == included.revision()
                && source.complete() && included.text().contains(source.text()))).toList();
        return new Report(1, input.id(), input.scopeId(), input.conversationId(), input.leafId(), ranges(input.considered()),
            ranges(input.included()), ranges(omitted), List.copyOf(accepted), List.copyOf(rejected), state, reason, input.earlierCoverageUnknown());
    }
    private static boolean same(Source source, SummaryFactCandidates.Candidate candidate) {
        return source.messageId().equals(candidate.sourceMessageId()) && source.revision() == candidate.sourceRevision();
    }
    private List<SummaryFactCandidates.Candidate> chronological(Input input, List<SummaryFactCandidates.Candidate> candidates, Runnable checkActive) {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (Source source : input.included()) order.putIfAbsent(source.messageId() + ":" + source.revision(), order.size());
        Map<String, String> originalText = new LinkedHashMap<>();
        for (var candidate : candidates) {
            checkActive.run(); checkInterrupted();
            String key = candidate.sourceMessageId() + ":" + candidate.sourceRevision();
            if (!order.containsKey(key) || originalText.containsKey(key)) continue;
            var source = repository.findMessage(input.scopeId(), input.conversationId(), candidate.sourceMessageId()).orElse(null);
            originalText.put(key, source == null ? "" : source.plainText());
        }
        return candidates.stream().sorted(Comparator
            .comparingInt((SummaryFactCandidates.Candidate candidate) -> order.getOrDefault(candidate.sourceMessageId() + ":" + candidate.sourceRevision(), Integer.MAX_VALUE))
            .thenComparingInt(candidate -> {
                String source = originalText.getOrDefault(candidate.sourceMessageId() + ":" + candidate.sourceRevision(), "");
                int offset = candidate.quote() == null ? -1 : source.indexOf(candidate.quote());
                return offset < 0 ? Integer.MAX_VALUE : offset;
            })
            .thenComparing(candidate -> Objects.toString(candidate.key(), ""))
            .thenComparing(candidate -> Objects.toString(candidate.qualifier(), ""))).toList();
    }
    private static List<Source> userSources(List<MessageRecord> messages) {
        if (messages == null) return List.of();
        return messages.stream().filter(Objects::nonNull).filter(message -> message.role() == MessageRecord.Role.USER && !message.isMutable())
            .map(message -> new Source(message.messageId(), message.revision(), message.plainText(), true)).toList();
    }
    private static List<Source> distinct(List<Source> sources) {
        if (sources == null) return List.of();
        var distinct = new LinkedHashSet<Source>();
        for (Source source : sources) if (source != null && source.messageId() != null && source.revision() > 0
            && source.text() != null && !source.text().isBlank()) distinct.add(source);
        return List.copyOf(distinct);
    }
    private static List<SourceRange> ranges(List<Source> sources) {
        return sources.stream().map(source -> new SourceRange(source.messageId(), source.revision(),
            IdempotencyReceiptStore.requestHash(Map.of("text", source.text())), source.text().length(), source.complete())).toList();
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Summary fact extraction cancelled");
    }
}
