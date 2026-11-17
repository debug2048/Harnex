package com.harnex.conversation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** A rebuildable, quoted projection of archive facts; it never infers current task or completion state. */
final class ConversationHistoryDigest {
    private ConversationHistoryDigest() {}
    static final String POLICY = "conversation-history-reference-v1";
    private static final String HEADER = "Historical quotes only; current task/status is not inferred. Newer messages override these excerpts.\n";
    private static final String PARTIAL = "\n> [Partial excerpt; consult the original source for omitted text.]";

    record Digest(String text, List<MessageRecord> sources, List<Quote> quotes) {
        List<ContextEvidence.Fragment> fragments(String scope, String conversation) {
            return quotes.stream().map(quote -> quote.fragment(scope, conversation)).toList();
        }
    }
    /** Original source characters actually included, independently of the surrounding quote formatting. */
    record Quote(String messageId, long revision, MessageRecord.Role role, String text, boolean complete) {
        String render() {
            return "[" + role + " source=" + messageId + " revision=" + revision + "]\n> " + quote(text)
                + (complete ? "" : PARTIAL);
        }
        ContextEvidence.Fragment fragment(String scope, String conversation) {
            return new ContextEvidence.Fragment(scope, new HistoryReference(conversation, messageId, null, revision), text, complete);
        }
    }
    private record Candidate(MessageRecord source, String label, int minChars, int minTokens) {}

    static Digest create(List<MessageRecord> newestFirst, ContextTokens tokens, String model, int maxTokens, int maxChars) {
        if (newestFirst.isEmpty() || maxTokens <= 0 || maxChars <= HEADER.length()) return null;
        List<MessageRecord> users = newestFirst.stream().filter(m -> m.role() == MessageRecord.Role.USER && !m.plainText().isBlank()).toList();
        LinkedHashSet<MessageRecord> priority = new LinkedHashSet<>();
        // Keep the newest correction first, then the original constraints; presentation remains chronological.
        if (!users.isEmpty()) { priority.add(users.get(0)); priority.add(users.get(users.size() - 1)); }
        priority.addAll(users);
        newestFirst.stream().filter(m -> !m.plainText().isBlank()).forEach(priority::add);
        List<Candidate> candidates = new ArrayList<>();
        int charsRemaining = maxChars - HEADER.length();
        int tokensRemaining = maxTokens - tokens.text(HEADER, model);
        for (MessageRecord source : priority) {
            String label = "[" + source.role() + " source=" + source.messageId() + " revision=" + source.revision() + "]\n> ";
            String complete = label + quote(source.plainText().strip());
            String firstUnit = label + quote(units(source.plainText())[0]) + PARTIAL;
            String minimum = firstUnit.length() < complete.length() ? firstUnit : complete;
            int chars = minimum.length() + 2, cost = tokens.text(minimum + "\n\n", model);
            if (chars > charsRemaining || cost > tokensRemaining) continue;
            candidates.add(new Candidate(source, label, chars, cost));
            charsRemaining -= chars; tokensRemaining -= cost;
        }
        List<Quote> excerpts = new ArrayList<>();
        List<MessageRecord> sources = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            MessageRecord source = candidate.source();
            int slots = candidates.size() - i;
            int charShare = candidate.minChars() + charsRemaining / slots;
            int tokenShare = candidate.minTokens() + tokensRemaining / slots;
            Quote excerpt = excerpt(source, tokens, model, charShare, tokenShare);
            if (excerpt == null) continue;
            excerpts.add(excerpt); sources.add(source);
            charsRemaining -= excerpt.render().length() + 2 - candidate.minChars();
            tokensRemaining -= tokens.text(excerpt.render() + "\n\n", model) - candidate.minTokens();
        }
        if (sources.isEmpty()) return null;
        // Render original chronological order, even though selection favors the oldest and newest user facts.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) order.add(i);
        order.sort((a, b) -> Integer.compare(newestFirst.indexOf(sources.get(b)), newestFirst.indexOf(sources.get(a))));
        StringBuilder text = new StringBuilder(HEADER);
        List<MessageRecord> ordered = new ArrayList<>();
        List<Quote> quotes = new ArrayList<>();
        for (int index : order) {
            Quote excerpt = excerpts.get(index);
            text.append('\n').append(excerpt.render()).append('\n'); ordered.add(sources.get(index)); quotes.add(excerpt);
        }
        if (text.length() > maxChars || tokens.text(text.toString(), model) > maxTokens) return null;
        return new Digest(text.toString().strip(), List.copyOf(ordered), List.copyOf(quotes));
    }

    private static Quote excerpt(MessageRecord source, ContextTokens tokens, String model, int maxChars, int maxTokens) {
        String original = source.plainText().strip();
        Quote complete = new Quote(source.messageId(), source.revision(), source.role(), original, true);
        if (fits(complete.render(), tokens, model, maxChars, maxTokens)) return complete;
        // Never cut a constraint in the middle merely to fit a character/token quota. Retain complete leading
        // sentences/lines only and explicitly mark missing context; if even the first unit cannot fit, skip it.
        var boundaries = Pattern.compile("(?<=[。！？])\\s*|(?<=[.!?])\\s+|\\R+").matcher(original);
        Quote result = null;
        while (boundaries.find()) {
            String prefix = original.substring(0, boundaries.end()).stripTrailing();
            if (prefix.isBlank()) continue;
            Quote candidate = new Quote(source.messageId(), source.revision(), source.role(), prefix, false);
            if (!fits(candidate.render(), tokens, model, maxChars, maxTokens)) break;
            result = candidate;
        }
        return result;
    }

    private static String quote(String text) { return text.replaceAll("\\R", "\n> "); }
    private static String[] units(String text) { return text.strip().split("(?<=[。！？])\\s*|(?<=[.!?])\\s+|\\R+"); }
    private static boolean fits(String text, ContextTokens tokens, String model, int maxChars, int maxTokens) {
        return text.length() + 2 <= maxChars && tokens.text(text + "\n\n", model) <= maxTokens;
    }
}
