package com.harnex.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.safety.SecretRedactor;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;

import io.swagger.v3.oas.annotations.media.Schema;

/** Source-verified user quotations, independent of lossy summaries and workspace-wide memory. */
@Component
public final class ConversationFactStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ConversationRepository repository;
    private final ProjectStorage storage;
    private final Path base;

    public ConversationFactStore(ConversationRepository repository, AgentConfig config) {
        this(repository, config, new ProjectStorage(config, null));
    }
    @Autowired
    public ConversationFactStore(ConversationRepository repository, AgentConfig config, ProjectStorage storage) {
        this.repository = repository; this.storage = storage;
        this.base = storage.root().resolve("conversations/facts");
    }

    @Schema(requiredProperties = {"id", "key", "qualifier", "quote", "messageId", "revision", "createdAt"})
    public record Fact(String id, String key, String qualifier, String quote, String messageId, long revision,
        @Schema(nullable = true) String supersedes, long createdAt,
        @Schema(allowableValues = {"explicit", "summary_candidate"}) String registration) {
        public Fact {
            registration = registration == null ? "explicit" : registration;
            if (!Set.of("explicit", "summary_candidate").contains(registration)) throw new IllegalArgumentException("Unknown fact registration type");
        }
        public Fact(String id, String key, String qualifier, String quote, String messageId, long revision,
            String supersedes, long createdAt) {
            this(id, key, qualifier, quote, messageId, revision, supersedes, createdAt, "explicit");
        }
    }
    private record Document(int version, String scopeId, String conversationId, List<Fact> facts) {}
    public record Projection(String text, List<HistoryReference> sources, boolean partial, int omitted,
        List<ContextEvidence.Fragment> fragments) {
        public Projection(String text, List<HistoryReference> sources, boolean partial, int omitted) {
            this(text, sources, partial, omitted, List.of());
        }
        public static Projection empty() { return new Projection("", List.of(), false, 0, List.of()); }
    }

    /** Register an exact quote. Replacing a prior record is explicit and scoped; nothing is semantically inferred. */
    public synchronized Fact register(String scopeId, String conversationId, String leafId, String sourceMessageId,
        long revision, String key, String qualifier, String quote, String supersedes) {
        return register(scopeId, conversationId, leafId, sourceMessageId, revision, key, qualifier, quote, supersedes, "explicit");
    }
    synchronized Fact registerCandidate(String scopeId, String conversationId, String leafId, SummaryFactCandidates.Candidate candidate) {
        return register(scopeId, conversationId, leafId, candidate.sourceMessageId(), candidate.sourceRevision(),
            candidate.key(), candidate.qualifier(), candidate.quote(), null, "summary_candidate");
    }
    private Fact register(String scopeId, String conversationId, String leafId, String sourceMessageId,
        long revision, String key, String qualifier, String quote, String supersedes, String registration) {
        return storage.transaction(() -> {
            ConversationMeta meta = requireConversation(scopeId, conversationId);
            String leaf = leafId == null ? meta.activeLeafId() : leafId;
            if (key == null || key.isBlank() || quote == null || quote.isBlank()) throw bad("key and exact source quote are required");
            String topic = Objects.toString(qualifier, "").strip();
            MessageRecord source = repository.findMessage(scopeId, conversationId, sourceMessageId)
                .orElseThrow(() -> bad("source message is unavailable"));
            if (source.role() != MessageRecord.Role.USER || source.isMutable() || source.revision() != revision
                || !source.plainText().contains(quote)
                || !repository.isMessageAncestor(scopeId, conversationId, sourceMessageId, leaf)) {
                throw bad("quote must match a current user-source revision on this branch");
            }
            if ("summary_candidate".equals(registration) && !wholeSourceUnit(source.plainText(), quote)) {
                throw bad("automatic quote must include complete original lines or paragraphs, preserving surrounding conditions");
            }
            List<Fact> facts = new ArrayList<>(read(scopeId, conversationId).facts());
            if ("summary_candidate".equals(registration)) {
                // Models can vary their proposed key across retries. The same exact scoped source quote stays one fact.
                for (Fact existing : facts) if (sourceMessageId.equals(existing.messageId()) && revision == existing.revision()
                    && topic.equals(existing.qualifier()) && quote.equals(existing.quote())) return existing;
            }
            String previousId = supersedes == null || supersedes.isBlank() ? null : supersedes;
            Fact previous = null;
            if (previousId != null) {
                previous = facts.stream().filter(f -> f.id().equals(previousId)).findFirst()
                    .orElseThrow(() -> bad("superseded fact is unavailable"));
                if (!previous.key().equals(key.strip()) || !previous.qualifier().equals(topic)
                    || !repository.isMessageAncestor(scopeId, conversationId, previous.messageId(), sourceMessageId)) {
                    throw bad("replacement must identify an earlier fact with the same key and scope on this branch");
                }
            }
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("message", sourceMessageId); identity.put("revision", revision);
            identity.put("key", key.strip()); identity.put("qualifier", topic);
            identity.put("quote", quote); identity.put("supersedes", previousId);
            String id = "fact_" + IdempotencyReceiptStore.requestHash(identity);
            for (int i = 0; i < facts.size(); i++) if (facts.get(i).id().equals(id)) {
                Fact existing = facts.get(i);
                if ("explicit".equals(registration) && "summary_candidate".equals(existing.registration())) {
                    Fact explicit = new Fact(existing.id(), existing.key(), existing.qualifier(), existing.quote(), existing.messageId(),
                        existing.revision(), existing.supersedes(), existing.createdAt(), "explicit");
                    facts.set(i, explicit); write(scopeId, conversationId, facts); return explicit;
                }
                return existing;
            }
            if (previous != null && facts.stream().anyMatch(f -> previousId.equals(f.supersedes())
                && repository.isMessageAncestor(scopeId, conversationId, f.messageId(), leaf))) {
                throw bad("the selected fact already has a replacement on this branch; replace the latest record explicitly");
            }
            Fact fact = new Fact(id, key.strip(), topic, quote, sourceMessageId, revision, previousId, System.currentTimeMillis(), registration);
            facts.add(fact);
            write(scopeId, conversationId, facts);
            return fact;
        });
    }

    public synchronized List<Fact> active(String scopeId, String conversationId, String leafId) {
        if (!Files.isRegularFile(path(scopeId, conversationId))) return List.of();
        return storage.transaction(() -> {
            ConversationMeta meta = requireConversation(scopeId, conversationId);
            String leaf = leafId == null ? meta.activeLeafId() : leafId;
            List<Fact> visible = new ArrayList<>();
            for (Fact fact : read(scopeId, conversationId).facts()) {
                if (!repository.isMessageAncestor(scopeId, conversationId, fact.messageId(), leaf)) continue;
                var source = repository.findMessageHeader(scopeId, conversationId, fact.messageId()).orElse(null);
                if (source != null && source.role() == MessageRecord.Role.USER && !source.isMutable()
                    && source.revision() == fact.revision()) visible.add(fact);
            }
            Set<String> replaced = new HashSet<>();
            List<Fact> active = new ArrayList<>();
            // Replacements can only reference an existing record. Walk backwards so repeated corrections
            // validate only the surviving source bodies; headers still check every historical revision.
            for (int i = visible.size() - 1; i >= 0; i--) {
                Fact fact = visible.get(i);
                if (replaced.contains(fact.id())) {
                    if (fact.supersedes() != null) replaced.add(fact.supersedes());
                    continue;
                }
                var source = repository.findMessage(scopeId, conversationId, fact.messageId()).orElse(null);
                if (source == null || source.isMutable() || source.role() != MessageRecord.Role.USER
                    || source.revision() != fact.revision() || !source.plainText().contains(fact.quote())) continue;
                active.add(fact);
                if (fact.supersedes() != null) replaced.add(fact.supersedes());
            }
            Collections.reverse(active);
            return List.copyOf(active);
        });
    }

    public Projection project(String scopeId, String conversationId, String leafId, ContextTokens tokens,
        String model, int tokenBudget) {
        return project(scopeId, conversationId, leafId, tokens, model, tokenBudget, new ContextEvidence.Collector());
    }
    public Projection project(String scopeId, String conversationId, String leafId, ContextTokens tokens,
        String model, int tokenBudget, ContextEvidence.Collector visible) {
        return projectSnapshot(active(scopeId, conversationId, leafId), scopeId, conversationId, tokens, model, tokenBudget, visible);
    }
    /** Pure request preview: safe-point source validation is supplied by active(), never repeated during rendering. */
    public Projection projectSnapshot(List<Fact> facts, String scopeId, String conversationId, ContextTokens tokens,
        String model, int tokenBudget, ContextEvidence.Collector visible) {
        if (facts == null || facts.isEmpty()) return Projection.empty();
        String header = "<registered_conversation_facts>\nRegistered user-source quotes for this branch. "
            + "Preserve their scope, conditions, negations and units. Registration proves provenance, not approval of "
            + "a hypothetical example. summary_candidate entries are unreviewed model extraction suggestions. "
            + "Latest human instructions govern; only explicit replacements supersede older records.\n";
        String end = "\n</registered_conversation_facts>";
        List<String> entries = new ArrayList<>();
        List<HistoryReference> sources = new ArrayList<>();
        List<ContextEvidence.Fragment> fragments = new ArrayList<>();
        Map<List<String>, Set<String>> values = new LinkedHashMap<>();
        for (Fact fact : facts) values.computeIfAbsent(List.of(fact.key(), fact.qualifier()), ignored -> new HashSet<>()).add(fact.quote());
        boolean hasRelations = values.values().stream().anyMatch(quotes -> quotes.size() > 1)
            || facts.stream().anyMatch(fact -> fact.supersedes() != null);
        int omitted = 0, omittedRelations = 0, includedQuotes = 0;
        for (int i = facts.size() - 1; i >= 0; i--) {
            Fact fact = facts.get(i);
            HistoryReference source = new HistoryReference(conversationId, fact.messageId(), null, fact.revision());
            var fragment = new ContextEvidence.Fragment(scopeId, source, fact.quote(), false);
            boolean alreadyVisible = visible != null && visible.covers(fragment);
            boolean unresolved = values.get(List.of(fact.key(), fact.qualifier())).size() > 1;
            if (alreadyVisible && !unresolved && fact.supersedes() == null) continue;
            String entry;
            try {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("id", fact.id()); fields.put("key", fact.key()); fields.put("scope", fact.qualifier());
                fields.put("registration", fact.registration()); fields.put("sourceVerified", true);
                if (unresolved) fields.put("unresolvedSameKeyScope", true);
                if (fact.supersedes() != null) fields.put("supersedes", fact.supersedes());
                if (alreadyVisible) fields.put("exactQuoteAlreadyVisible", true);
                else fields.put("quote", fact.quote());
                fields.put("sourceMessageId", fact.messageId()); fields.put("sourceRevision", fact.revision());
                entry = JSON.writeValueAsString(fields).replace("<", "\\u003c");
                entry = SecretRedactor.redact(entry);
            } catch (IOException impossible) { throw new IllegalStateException(impossible); }
            String candidate = header + String.join("\n", entries) + "\n" + entry
                + omittedMarker(facts.size()) + (hasRelations ? relationshipMarker(facts.size()) : "") + end;
            if (tokens.text(candidate, model) > tokenBudget) {
                if (alreadyVisible) omittedRelations++;
                else omitted++;
                continue;
            }
            entries.add(entry); sources.add(source);
            if (!alreadyVisible) includedQuotes++;
            if (!alreadyVisible && SecretRedactor.redact(fact.quote()).equals(fact.quote())) fragments.add(fragment);
        }
        if (entries.isEmpty() && omitted == 0 && omittedRelations == 0) return Projection.empty();
        Collections.reverse(entries); Collections.reverse(sources); Collections.reverse(fragments);
        String marker = (omitted == 0 ? "" : omittedMarker(omitted)) + (omittedRelations == 0 ? "" : relationshipMarker(omittedRelations));
        String text = header + String.join("\n", entries) + marker + end;
        if (tokens.text(text, model) > tokenBudget) return new Projection("", List.of(), true, omitted + includedQuotes);
        return new Projection(text, List.copyOf(sources), omitted > 0 || omittedRelations > 0, omitted, List.copyOf(fragments));
    }

    private static String omittedMarker(int omitted) {
        return "\nCoverage incomplete: " + omitted
            + " registered quotes do not fit this request. They remain stored; use session_fact(action=list) or inspect original sources when needed.";
    }
    private static String relationshipMarker(int omitted) {
        return "\nRelationships incomplete: " + omitted + " replacement/conflict markers do not fit; their exact quotes are already visible."
            + " Do not infer replacement; use session_fact(action=list) to inspect the records.";
    }
    private static boolean wholeSourceUnit(String source, String quote) {
        for (int start = source.indexOf(quote); start >= 0; start = source.indexOf(quote, start + 1)) {
            int end = start + quote.length();
            int lineStart = start == 0 ? 0 : source.lastIndexOf('\n', start - 1) + 1;
            int lineEnd = source.indexOf('\n', end);
            if (lineEnd < 0) lineEnd = source.length();
            if (source.substring(lineStart, start).isBlank() && source.substring(end, lineEnd).isBlank()) return true;
        }
        return false;
    }

    private ConversationMeta requireConversation(String scope, String conversation) {
        return repository.loadConversation(scope, conversation).filter(meta -> !meta.deleted())
            .orElseThrow(() -> new ConversationException("conversation_not_found", "Conversation is unavailable"));
    }
    private void write(String scope, String conversation, List<Fact> facts) {
        try { AtomicJsonFiles.write(JSON, path(scope, conversation), new Document(1, scope, conversation, List.copyOf(facts))); }
        catch (IOException failure) { throw new ConversationException("memory_unavailable", "Could not persist registered conversation fact", failure); }
    }
    synchronized void saveExtraction(SummaryFactExtraction.Report report) {
        storage.transaction(() -> {
            requireConversation(report.scopeId(), report.conversationId());
            try { AtomicJsonFiles.write(JSON, extractionPath(report.scopeId(), report.conversationId(), report.id()), report); }
            catch (IOException failure) { throw new ConversationException("memory_unavailable", "Could not persist extraction coverage", failure); }
            return null;
        });
    }
    public synchronized Optional<SummaryFactExtraction.Report> extraction(String scopeId, String conversationId, String id) {
        return storage.transaction(() -> {
            requireConversation(scopeId, conversationId);
            Path path = extractionPath(scopeId, conversationId, id);
            if (!Files.isRegularFile(path)) return Optional.empty();
            try {
                var report = JSON.readValue(Files.readAllBytes(path), SummaryFactExtraction.Report.class);
                return report != null && report.version() == 1 && scopeId.equals(report.scopeId()) && conversationId.equals(report.conversationId())
                    && id.equals(report.id()) && report.considered() != null && report.included() != null && report.omitted() != null
                    && report.acceptedFactIds() != null && report.rejected() != null && report.state() != null
                    && Set.of("attempted", "not_extracted").contains(report.state())
                    ? Optional.of(report) : Optional.empty();
            } catch (IOException | IllegalArgumentException invalid) { return Optional.empty(); }
        });
    }
    private Path extractionPath(String scope, String conversation, String id) {
        if (!Ids.isSafeResourceId(id)) throw bad("Invalid extraction identity");
        return base.resolve("extractions").resolve(IdempotencyReceiptStore.requestHash(Map.of("scope", scope, "conversation", conversation))).resolve(id + ".json");
    }
    private Document read(String scope, String conversation) {
        Path path = path(scope, conversation);
        if (!Files.isRegularFile(path)) return new Document(1, scope, conversation, List.of());
        try {
            Document document = JSON.readValue(Files.readAllBytes(path), Document.class);
            if (document.version() != 1 || !scope.equals(document.scopeId()) || !conversation.equals(document.conversationId())
                || document.facts() == null) throw bad("invalid conversation fact store");
            return document;
        } catch (IOException failure) { throw new ConversationException("memory_unavailable", "Could not read registered conversation facts", failure); }
    }
    private Path path(String scope, String conversation) {
        return base.resolve(IdempotencyReceiptStore.requestHash(Map.of("scope", scope, "conversation", conversation)) + ".json");
    }
    private static ConversationException bad(String message) { return new ConversationException("invalid_memory_fact", message); }
}
