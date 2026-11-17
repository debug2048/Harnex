package com.harnex.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact source text already present in a request. A source locator on a lossy summary is attribution,
 * not proof that the original message was shown. Callers therefore add only text they actually render.
 */
public final class ContextEvidence {
    private ContextEvidence() {}

    public record Fragment(String scopeId, HistoryReference source, String text, boolean complete) {}
    private record Key(String scopeId, String conversationId, String messageId, String executionRecordId, long revision) {}

    public static Fragment source(String scopeId, String conversationId, MessageRecord source) {
        return new Fragment(scopeId, new HistoryReference(conversationId, source.messageId(), null, source.revision()),
            source.plainText(), true);
    }

    /** Request-local only. Insertion order is stable; evidence collection never reorders model messages. */
    public static final class Collector {
        private final Map<Key, List<Fragment>> visible = new LinkedHashMap<>();

        public Collector() {}
        public Collector(List<Fragment> fragments) { addAll(fragments); }

        public boolean covers(Fragment fragment) {
            Key key = key(fragment);
            if (key == null) return false;
            for (Fragment prior : visible.getOrDefault(key, List.of())) {
                // Completeness never licenses dropping unseen text. Even partial fragments can safely
                // cover a smaller exact quote, but only when that entire quote was actually displayed.
                if (prior.text().contains(fragment.text())) return true;
            }
            return false;
        }

        public void add(Fragment fragment) {
            Key key = key(fragment);
            if (key == null || covers(fragment)) return;
            visible.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fragment);
        }

        public void addAll(List<Fragment> fragments) {
            if (fragments != null) fragments.forEach(this::add);
        }

        public List<Fragment> uncovered(List<Fragment> fragments) {
            return fragments == null ? List.of() : fragments.stream().filter(Objects::nonNull).filter(f -> !covers(f)).toList();
        }

        public List<Fragment> fragments() {
            return visible.values().stream().flatMap(List::stream).toList();
        }

        private static Key key(Fragment fragment) {
            if (fragment == null || fragment.scopeId() == null || fragment.source() == null || fragment.text() == null
                || fragment.text().isBlank()) return null;
            HistoryReference source = fragment.source();
            if (source.conversationId() == null || source.revision() < 1
                || (source.messageId() == null) == (source.executionRecordId() == null)) return null;
            return new Key(fragment.scopeId(), source.conversationId(), source.messageId(), source.executionRecordId(), source.revision());
        }
    }
}
