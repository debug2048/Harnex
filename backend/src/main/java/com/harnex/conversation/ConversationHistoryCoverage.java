package com.harnex.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.storage.AtomicJsonFiles;

/**
 * Rebuildable branch-prefix checkpoints. Every stage retains its complete source range independently
 * of its lossy quote projection; a later stage never rewrites an older stage. These are historical
 * references, not inferred current requirements. Registered facts belong in their authoritative store.
 */
@Component
public final class ConversationHistoryCoverage {
    private static final int FORMAT = 1;
    private final ConversationRepository repository;
    private final ContextTokens tokens;
    private final Path base;
    private final ObjectMapper json = new ObjectMapper();

    public ConversationHistoryCoverage(ConversationRepository repository, AgentConfig config, ContextTokens tokens) {
        this(repository, config, tokens, new ProjectStorage(config, null));
    }
    @Autowired
    public ConversationHistoryCoverage(ConversationRepository repository, AgentConfig config, ContextTokens tokens, ProjectStorage storage) {
        this.repository = repository; this.tokens = tokens;
        this.base = storage.root().resolve("conversations/coverage");
    }

    public record Source(String messageId, long revision) {}
    public record Stage(int version, String id, String previous, String first, int stageCount, long messageCount, boolean gap,
        List<Source> sources, String quotes, List<Source> quotedSources, List<ConversationHistoryDigest.Quote> snippets) {}
    private record Draft(String id, List<Source> sources, String quotes, List<Source> quotedSources, List<ConversationHistoryDigest.Quote> snippets) {}
    public record Coverage(Stage head, boolean gap) {
        public static Coverage empty() { return new Coverage(null, false); }
    }
    public record Projection(String text, List<HistoryReference> sources, boolean partial, long coveredMessages,
        List<ContextEvidence.Fragment> fragments) {
        public Projection(String text, List<HistoryReference> sources, boolean partial, long coveredMessages) {
            this(text, sources, partial, coveredMessages, List.of());
        }
        public static Projection empty() { return new Projection("", List.of(), false, 0, List.of()); }
    }

    /**
     * Cover history older than the raw beforeMessageId (or the whole explicit leaf when null). This
     * parameter is a source identity, never a repository pagination cursor. Archive pages
     * establish branch membership; an immutable matching source is the only way to reuse a checkpoint.
     * Atomic stage writes precede publishing their boundary. A failed write leaves an explicit gap.
     */
    public Coverage prepare(String scopeId, String conversationId, String leafId, String beforeMessageId, Runnable checkActive) {
        if (leafId == null) return Coverage.empty();
        checkActive.run();
        String prefixLeaf = leafId;
        if (beforeMessageId != null) {
            try {
                if (!repository.isMessageAncestor(scopeId, conversationId, beforeMessageId, leafId)) return new Coverage(null, true);
                var before = repository.findMessageHeader(scopeId, conversationId, beforeMessageId).orElse(null);
                if (before == null) return new Coverage(null, true);
                prefixLeaf = before.parentMessageId();
                if (prefixLeaf == null) return Coverage.empty();
            } catch (ConversationException unavailable) { checkActive.run(); return new Coverage(null, true); }
        }
        Path directory = directory(scopeId, conversationId);
        List<Draft> pending = new ArrayList<>();
        Stage previous = null;
        String cursor = null;
        Set<String> cursors = new HashSet<>();
        boolean gap = false;
        outer: do {
            checkActive.run();
            if (cursor != null && !cursors.add(cursor)) { gap = true; break; }
            ConversationRepository.MessagePage page;
            try { page = repository.pageMessages(scopeId, conversationId, prefixLeaf, cursor, 50); }
            catch (ConversationException unavailable) { checkActive.run(); gap = true; break; }
            if (page == null) { gap = true; break; }
            List<MessageRecord> fresh = new ArrayList<>();
            for (MessageRecord source : page.items()) {
                checkActive.run();
                // Do not mark unfinished archive data as covered; it must be revisited after finalization.
                if (source.isMutable()) { gap = true; continue; }
                Stage cached = read(directory, boundary(source));
                if (cached != null && !cached.gap() && !cached.sources().isEmpty()
                    && cached.sources().get(0).equals(new Source(source.messageId(), source.revision()))) {
                    if (!fresh.isEmpty()) pending.add(draft(fresh));
                    previous = cached;
                    break outer;
                }
                fresh.add(source);
            }
            if (!fresh.isEmpty()) pending.add(draft(fresh));
            cursor = page.nextCursor();
        } while (cursor != null);

        Collections.reverse(pending);
        for (Draft chunk : pending) {
            checkActive.run();
            String id = chunk.id();
            var stage = new Stage(FORMAT, id, previous == null ? null : previous.id(),
                previous == null ? id : previous.first(), previous == null ? 1 : previous.stageCount() + 1,
                chunk.sources().size() + (previous == null ? 0 : previous.messageCount()), gap,
                chunk.sources(), chunk.quotes(), chunk.quotedSources(), chunk.snippets());
            try { AtomicJsonFiles.write(json, directory.resolve(id + ".json"), stage); }
            catch (IOException | SecurityException failed) { return new Coverage(previous, true); }
            previous = stage;
        }
        return new Coverage(previous, gap);
    }

    private Draft draft(List<MessageRecord> sources) {
        var digest = ConversationHistoryDigest.create(sources, tokens, null, 2048, 6000);
        return new Draft(boundary(sources.get(0)), sources.stream().map(m -> new Source(m.messageId(), m.revision())).toList(),
            digest == null ? "" : digest.text(), digest == null ? List.of()
                : digest.sources().stream().map(m -> new Source(m.messageId(), m.revision())).toList(),
            digest == null ? List.of() : digest.quotes());
    }

    /** Bounded projection keeps oldest and newest stages; omitted stages are explicitly discoverable. */
    public Projection project(String scopeId, String conversationId, Coverage coverage, String model, int maxTokens, int maxChars) {
        return project(scopeId, conversationId, coverage, model, maxTokens, maxChars, new ContextEvidence.Collector());
    }

    public Projection project(String scopeId, String conversationId, Coverage coverage, String model, int maxTokens, int maxChars,
        ContextEvidence.Collector visible) {
        if (coverage == null || maxTokens < 96 || maxChars < 256) return Projection.empty();
        Stage head = coverage.head();
        if (head == null && !coverage.gap()) return Projection.empty();
        Path directory = directory(scopeId, conversationId);
        Map<String, Stage> selected = new LinkedHashMap<>();
        boolean gap = coverage.gap();
        if (head != null) {
            Stage first = Objects.equals(head.id(), head.first()) ? head : read(directory, head.first());
            if (first != null) selected.put(first.id(), first); else gap = true;
            selected.put(head.id(), head);
            // Read only as many recent checkpoint files as this projection can plausibly display.
            String previous = head.previous();
            for (int count = 0; previous != null && count < Math.max(1, maxTokens / 192); count++) {
                Stage stage = read(directory, previous);
                if (stage == null) { gap = true; break; }
                selected.putIfAbsent(stage.id(), stage);
                previous = stage.previous();
            }
        }
        String header = "Historical coverage checkpoints (quoted references, not current task state). "
            + "Newer messages override older excerpts. Original sources remain authoritative.\n"
            + "Archived source messages=" + (head == null ? 0 : head.messageCount())
            + "; stages=" + (head == null ? 0 : head.stageCount()) + ". "
            + "This is a partial projection; consult session_search/session_read for unquoted details."
            + (gap ? " Coverage gap: some history has not been checkpointed; do not assume it is absent." : "") + "\n";
        StringBuilder out = new StringBuilder(header);
        List<ContextEvidence.Fragment> fragments = new ArrayList<>();
        int remaining = selected.size();
        List<Stage> accepted = new ArrayList<>();
        Map<String, String> rendered = new LinkedHashMap<>();
        Map<String, List<ContextEvidence.Fragment>> shown = new LinkedHashMap<>();
        for (Stage stage : selected.values()) {
            int left = maxTokens - tokens.text(out.toString(), model) - 16;
            int charLeft = maxChars - out.length() - 16;
            int share = Math.max(0, left / Math.max(1, remaining));
            int charShare = Math.max(0, charLeft / Math.max(1, remaining--));
            String label = "\n[stage=" + stage.id() + "; sources=" + stage.sources().size()
                + "; newest=" + stage.sources().get(0).messageId() + "; oldest="
                + stage.sources().get(stage.sources().size() - 1).messageId() + "]\n";
            StringBuilder quote = new StringBuilder();
            List<ContextEvidence.Fragment> stageFragments = new ArrayList<>();
            for (var snippet : snippets(stage)) {
                var fragment = snippet.fragment(scopeId, conversationId);
                if (visible != null && visible.covers(fragment)) continue;
                String candidate = quote + snippet.render() + "\n";
                if (candidate.length() > Math.max(0, charShare - label.length())
                    || tokens.text(candidate, model) > Math.max(0, share - tokens.text(label, model))) continue;
                quote.append(snippet.render()).append('\n'); stageFragments.add(fragment);
            }
            if (quote.isEmpty()) continue;
            String item = label + quote + "\n";
            if (tokens.text(out + item, model) > maxTokens || out.length() + item.length() > maxChars) continue;
            out.append(item); accepted.add(stage); rendered.put(stage.id(), item);
            shown.put(stage.id(), List.copyOf(stageFragments));
        }
        // A stage is immutable; chronological presentation prevents old state appearing after a correction.
        accepted.sort(Comparator.comparingInt(Stage::stageCount));
        out = new StringBuilder(header);
        for (Stage stage : accepted) {
            out.append(rendered.get(stage.id()));
            fragments.addAll(shown.get(stage.id()));
        }
        if (tokens.text(out.toString(), model) > maxTokens || out.length() > maxChars) return Projection.empty();
        return new Projection(out.toString(), fragments.stream().map(ContextEvidence.Fragment::source).distinct().toList(),
            true, head == null ? 0 : head.messageCount(), List.copyOf(fragments));
    }

    private static List<ConversationHistoryDigest.Quote> snippets(Stage stage) {
        if (stage.snippets() != null) return stage.snippets();
        // Old checkpoint files retain their source ledger. Recover only explicit displayed quote blocks;
        // never use the whole quotedSources list as if every source survived the request's clipping.
        if (stage.quotes() == null || stage.quotes().isBlank()) return List.of();
        var labels = Pattern.compile("(?m)^\\[(USER|ASSISTANT) source=([A-Za-z0-9_-]+) revision=(\\d+)\\]\\R> ")
            .matcher(stage.quotes());
        record Label(String id, long revision, MessageRecord.Role role, int start, int content) {}
        List<Label> found = new ArrayList<>();
        while (labels.find()) {
            long revision;
            try { revision = Long.parseLong(labels.group(3)); } catch (NumberFormatException invalid) { continue; }
            found.add(new Label(labels.group(2), revision, MessageRecord.Role.valueOf(labels.group(1)), labels.start(), labels.end()));
        }
        List<ConversationHistoryDigest.Quote> result = new ArrayList<>();
        String partial = "\n> [Partial excerpt; consult the original source for omitted text.]";
        for (int i = 0; i < found.size(); i++) {
            Label label = found.get(i);
            if (!stage.quotedSources().contains(new Source(label.id(), label.revision()))) continue;
            String value = stage.quotes().substring(label.content(), i + 1 < found.size() ? found.get(i + 1).start() : stage.quotes().length()).stripTrailing();
            if (value.endsWith(partial)) value = value.substring(0, value.length() - partial.length());
            value = value.replace("\r\n> ", "\n").replace("\n> ", "\n");
            if (!value.isBlank()) result.add(new ConversationHistoryDigest.Quote(label.id(), label.revision(), label.role(), value, false));
        }
        return List.copyOf(result);
    }

    private Stage read(Path directory, String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) return null;
        Path path = directory.resolve(id + ".json");
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 100_000) return null;
            Stage stage = json.readValue(Files.readAllBytes(path), Stage.class);
            return stage.version() == FORMAT && id.equals(stage.id()) && stage.stageCount() > 0
                && stage.sources() != null && !stage.sources().isEmpty() && stage.quotedSources() != null ? stage : null;
        } catch (IOException | RuntimeException invalid) { return null; }
    }
    private static String boundary(MessageRecord source) {
        return hash(Map.of("message", source.messageId(), "revision", source.revision(), "content", source.contentParts(),
            "parent", Objects.toString(source.parentMessageId(), ""), "policy", FORMAT));
    }
    private Path directory(String scopeId, String conversationId) {
        return base.resolve(hash(Map.of("scope", scopeId, "conversation", conversationId)));
    }
    private static String hash(Object value) { return IdempotencyReceiptStore.requestHash(Map.of("content", value)); }
}
