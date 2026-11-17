package com.harnex.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.conversation.ConversationRepository.MessageHeader;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;

/**
 * Disposable, body-free ancestry projection. Binary lifting stores O(log depth) pointers per message,
 * rather than copying every ancestor. Only unfinished leaves may change parent, so finalized descendants
 * never need invalidation. The repository serializes access and enforces scope/deletion checks.
 */
final class ConversationAncestryIndex {
    private static final int FORMAT = 1;
    private final Path records;
    private final ObjectMapper json;
    private final BiFunction<String, String, Optional<MessageRecord>> source;
    private final Map<String, Node> cache = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Node> entry) { return size() > 16_384; }
    };
    /** One complete, explicitly selected prefix per conversation; never a cumulative set across branches. */
    private final Map<String, RunBranch> runBranches = new LinkedHashMap<>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, RunBranch> entry) { return size() > 8; }
    };
    private long lookups;

    ConversationAncestryIndex(Path records, ObjectMapper json,
        BiFunction<String, String, Optional<MessageRecord>> source) {
        this.records = records; this.json = json; this.source = source;
    }

    Optional<MessageHeader> header(String conversation, String id) {
        return node(conversation, id).map(Node::header);
    }

    boolean contains(String conversation, String ancestor, String leaf) {
        if (ancestor == null || leaf == null) return false;
        Node target = node(conversation, ancestor).orElse(null);
        if (target == null) return false;
        Node selected = node(conversation, leaf).orElse(null);
        return selected != null && target.depth() <= selected.depth()
            && ancestor.equals(lift(conversation, selected, selected.depth() - target.depth()).header().messageId());
    }

    Optional<MessageHeader> child(String conversation, String ancestor, String leaf) {
        if (ancestor == null || leaf == null) return Optional.empty();
        Node target = node(conversation, ancestor).orElse(null);
        if (target == null) return Optional.empty();
        Node selected = node(conversation, leaf).orElse(null);
        if (selected == null || target.depth() >= selected.depth()) return Optional.empty();
        Node child = lift(conversation, selected, selected.depth() - target.depth() - 1);
        return ancestor.equals(child.header().parentMessageId()) ? Optional.of(child.header()) : Optional.empty();
    }

    boolean completedRun(String conversation, String runId, String leaf) {
        if (runId == null || leaf == null) return false;
        validate(runId);
        Node selected = node(conversation, leaf).orElse(null);
        // A pending answer is a mutable leaf. Cache only its immutable prefix: progress/steering can
        // reparent it, and it cannot make its own run's future/unowned tool evidence visible.
        while (selected != null && selected.header().isMutable()) {
            selected = node(conversation, selected.header().parentMessageId()).orElse(null);
        }
        if (selected == null) return false;
        leaf = selected.header().messageId();
        RunBranch previous = runBranches.get(conversation);
        if (previous != null && leaf.equals(previous.leafId()) && selected.header().revision() == previous.revision()) {
            return previous.runs().contains(runId);
        }
        for (String id : readRun(conversation, runId)) {
            MessageHeader header = header(conversation, id).orElse(null);
            if (header != null && runId.equals(header.runId()) && header.isFinalAnswer() && contains(conversation, id, leaf)) return true;
        }
        // A legacy run file can be incomplete, so a negative pointer lookup cannot authorize a negative
        // result. Complete the selected prefix once; rejected search candidates then share that work.
        // Append-only continuation extends the cached set without copying an entire branch each turn.
        boolean extendsPrevious = false;
        if (previous != null) {
            Node old = node(conversation, previous.leafId()).orElse(null);
            extendsPrevious = old != null && !old.header().isMutable() && old.header().revision() == previous.revision()
                && contains(conversation, previous.leafId(), leaf);
        }
        var additions = new HashSet<String>();
        var seen = new HashSet<String>();
        for (String id = leaf; id != null;) {
            checkInterrupted();
            if (extendsPrevious && id.equals(previous.leafId())) break;
            if (!seen.add(id)) throw corrupt("Message parent cycle");
            Node node = node(conversation, id).orElse(null);
            if (node == null) throw corrupt("Message parent is missing");
            if (node.header().runId() != null && node.header().isFinalAnswer()) additions.add(node.header().runId());
            id = node.header().parentMessageId();
        }
        Set<String> completed = extendsPrevious ? previous.runs() : new HashSet<>();
        completed.addAll(additions);
        runBranches.put(conversation, new RunBranch(leaf, selected.header().revision(), completed));
        return completed.contains(runId);
    }

    /** Applied before the journal head advances, including replay after an interrupted projection. */
    void project(String conversation, List<MessageRecord> messages) throws IOException {
        invalidate(conversation, messages.stream().map(MessageRecord::messageId).toList());
        Map<String, MessageRecord> pending = new HashMap<>();
        for (MessageRecord message : messages) {
            pending.put(message.messageId(), message);
        }
        // Fork transactions need not be parent-first. Building walks pending headers, never revision references.
        for (MessageRecord message : messages) if (pending.containsKey(message.messageId())) {
            build(conversation, message.messageId(), pending, true);
        }
    }

    void invalidate(String conversation, List<String> ids) {
        for (String id : ids) cache.remove(key(conversation, id));
        RunBranch branch = runBranches.get(conversation);
        if (branch != null && ids.contains(branch.leafId())) runBranches.remove(conversation);
    }

    long lookupCount() { return lookups; }

    private Optional<Node> node(String conversation, String id) {
        if (id == null) return Optional.empty();
        lookups++;
        validate(id);
        Node existing = existing(conversation, id);
        if (existing != null) return Optional.of(existing);
        try { return Optional.ofNullable(build(conversation, id, new HashMap<>(), false)); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Iterative migration avoids overflowing the Java stack on very long legacy branches. */
    private Node build(String conversation, String id, Map<String, MessageRecord> pending, boolean durable) throws IOException {
        List<MessageRecord> missing = new ArrayList<>();
        var seen = new HashSet<String>();
        String cursor = id;
        Node parent = null;
        while (cursor != null) {
            if (!durable) checkInterrupted();
            if (!seen.add(cursor)) throw corrupt("Message parent cycle");
            validate(cursor);
            if (!pending.containsKey(cursor)) {
                parent = existing(conversation, cursor);
                if (parent != null) break;
            }
            MessageRecord message = pending.get(cursor);
            if (message == null) message = source.apply(conversation, cursor).orElse(null);
            if (message == null) {
                if (missing.isEmpty()) return null;
                throw corrupt("Message parent is missing");
            }
            missing.add(message);
            cursor = message.parentMessageId();
        }
        for (int index = missing.size() - 1; index >= 0; index--) {
            if (!durable) checkInterrupted();
            MessageRecord message = missing.get(index);
            long depth = parent == null ? 0 : Math.addExact(parent.depth(), 1);
            List<String> jumps = new ArrayList<>();
            if (parent != null) {
                if (parent.header().isMutable()) throw corrupt("A mutable answer cannot have descendants");
                jumps.add(parent.header().messageId());
                for (int level = 1; level < 63 && (1L << level) <= depth; level++) {
                    Node middle = node(conversation, jumps.get(level - 1))
                        .orElseThrow(() -> corrupt("An indexed ancestor is missing"));
                    if (middle.jumps().size() < level) throw corrupt("Invalid ancestor depth");
                    jumps.add(middle.jumps().get(level - 1));
                }
            }
            parent = new Node(FORMAT, MessageHeader.from(message), depth, List.copyOf(jumps));
            if (durable) persist(conversation, parent);
            else try { persist(conversation, parent); } catch (IOException ignored) { /* In-memory reconstruction remains usable. */ }
            cache.put(key(conversation, message.messageId()), parent);
            pending.remove(message.messageId());
        }
        return parent;
    }

    private Node lift(String conversation, Node node, long distance) {
        for (int level = 0; distance != 0; level++, distance >>>= 1) {
            if ((distance & 1) == 0) continue;
            if (node.jumps().size() <= level) throw corrupt("Invalid ancestor jump");
            long expectedDepth = node.depth() - (1L << level);
            node = node(conversation, node.jumps().get(level)).orElseThrow(() -> corrupt("An indexed ancestor is missing"));
            if (node.depth() != expectedDepth) throw corrupt("Invalid ancestor depth");
        }
        return node;
    }

    private Node existing(String conversation, String id) {
        String key = key(conversation, id);
        Node cached = cache.get(key);
        if (cached != null) return cached;
        try {
            Path path = path(conversation, id);
            if (!Files.isRegularFile(path)) return null;
            Node node = json.readValue(Files.readAllBytes(path), Node.class);
            if (!valid(node, id)) return null;
            cache.put(key, node); return node;
        } catch (IOException | IllegalArgumentException invalid) {
            return null; // Message files are authoritative; rebuild only the missing path.
        }
    }

    private static boolean valid(Node node, String id) {
        if (node == null || node.schemaVersion() != FORMAT || node.header() == null || node.jumps() == null
            || !id.equals(node.header().messageId()) || node.depth() < 0 || node.header().revision() < 1
            || node.header().role() == null || node.header().status() == null) return false;
        int levels = node.depth() == 0 ? 0 : 64 - Long.numberOfLeadingZeros(node.depth());
        if (node.jumps().size() != levels || node.jumps().stream().anyMatch(jump -> !Ids.isSafeResourceId(jump))) return false;
        return node.depth() == 0 ? node.header().parentMessageId() == null
            : Objects.equals(node.header().parentMessageId(), node.jumps().get(0));
    }

    private void persist(String conversation, Node node) throws IOException {
        AtomicJsonFiles.write(json, path(conversation, node.header().messageId()), node);
        if (node.header().runId() != null && node.header().isFinalAnswer()) indexRun(conversation, node.header());
    }

    private List<String> readRun(String conversation, String runId) {
        try {
            Path path = runPath(conversation, runId);
            if (!Files.isRegularFile(path)) return List.of();
            RunAnswers run = json.readValue(Files.readAllBytes(path), RunAnswers.class);
            return run != null && run.schemaVersion() == FORMAT && run.answers() != null
                && run.answers().stream().allMatch(Ids::isSafeResourceId) ? run.answers() : List.of();
        } catch (IOException | IllegalArgumentException invalid) { return List.of(); }
    }

    private void indexRun(String conversation, MessageHeader header) throws IOException {
        List<String> previous = readRun(conversation, header.runId());
        if (previous.contains(header.messageId())) return;
        List<String> answers = new ArrayList<>(previous); answers.add(header.messageId());
        AtomicJsonFiles.write(json, runPath(conversation, header.runId()), new RunAnswers(FORMAT, List.copyOf(answers)));
    }

    private Path path(String conversation, String id) {
        validate(conversation); validate(id);
        return records.resolve(conversation).resolve("ancestry/messages").resolve(id + ".json");
    }
    private Path runPath(String conversation, String runId) {
        validate(conversation); validate(runId);
        return records.resolve(conversation).resolve("ancestry/runs").resolve(runId + ".json");
    }
    private static String key(String conversation, String id) { return conversation + "/" + id; }
    private static void validate(String id) {
        if (!Ids.isSafeResourceId(id)) throw new ConversationException("bad_request", "Invalid history identity");
    }
    private static ConversationException corrupt(String message) { return new ConversationException("history_corrupt", message); }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("History ancestry lookup interrupted");
    }
    private record Node(int schemaVersion, MessageHeader header, long depth, List<String> jumps) {}
    private record RunAnswers(int schemaVersion, List<String> answers) {}
    private record RunBranch(String leafId, long revision, Set<String> runs) {}
}
