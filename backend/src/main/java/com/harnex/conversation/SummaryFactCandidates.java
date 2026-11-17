package com.harnex.conversation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Optional, versioned trailer on the existing summary response; never part of the rolling summary body. */
public final class SummaryFactCandidates {
    public static final String PROTOCOL_VERSION = "summary-facts-v1";
    public static final String START = "<summary_fact_candidates_v1>";
    public static final String END = "</summary_fact_candidates_v1>";
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> FIELDS = Set.of("sourceMessageId", "sourceRevision", "key", "qualifier", "quote");
    private SummaryFactCandidates() {}
    public enum State { COMPLETE, MISSING, MALFORMED }
    public record Candidate(String sourceMessageId, long sourceRevision, String key, String qualifier, String quote) {}
    public record Parsed(String summaryText, List<Candidate> candidates, State state) {
        public Parsed {
            summaryText = summaryText == null ? "" : summaryText;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            state = state == null ? State.MISSING : state;
        }
    }

    public static String instructions() {
        return """

            After the six Markdown summary sections, append exactly one versioned JSON trailer:
            <summary_fact_candidates_v1>{"candidates":[{"sourceMessageId":"the exact source id","sourceRevision":1,"key":"stable subject","qualifier":"task/environment scope","quote":"exact complete user-source quotation"}]}</summary_fact_candidates_v1>
            This trailer is separate from the Markdown character limit. Extract important constraints, decisions,
            parameters, corrections and commitments only from source-labelled immutable USER text supplied for
            extraction in the history being compressed. Preserve conditions, scope, negations and units in each quote.
            Copy complete source lines or paragraphs, never an interior phrase. For a single-line message, copy that
            complete line; otherwise negations or scope restrictions outside a short phrase could reverse its meaning.
            Do not extract from assistant prose, tool output, recalled excerpts, old summaries, runtime reminders or
            sources whose original text is not supplied. Copy the labelled message id and revision; never invent them.
            A quote proves what the user said, not that a hypothetical example was approved. Keep that distinction.
            Candidates are unreviewed extraction suggestions. Do not invent approval, semantic certainty or replacement
            links. Different statements for the same key/scope remain unresolved until explicitly reconciled.
            Use {"candidates":[]} if no qualifying source fact is present. No additional JSON fields or code fences.
            """;
    }

    public static Parsed parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        int start = text.indexOf(START);
        if (start < 0) return new Parsed(text, List.of(), State.MISSING);
        String body = text.substring(0, start).stripTrailing();
        if (text.indexOf(START, start + START.length()) >= 0 || !text.endsWith(END)) {
            return new Parsed(body, List.of(), State.MALFORMED);
        }
        String payload = text.substring(start + START.length(), text.length() - END.length()).strip();
        try {
            JsonNode root = JSON.readTree(payload);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("candidates") || !root.get("candidates").isArray()) {
                return new Parsed(body, List.of(), State.MALFORMED);
            }
            List<Candidate> candidates = new ArrayList<>();
            for (JsonNode item : root.get("candidates")) {
                if (!item.isObject() || item.size() != FIELDS.size()) return new Parsed(body, List.of(), State.MALFORMED);
                var names = item.fieldNames();
                while (names.hasNext()) if (!FIELDS.contains(names.next())) return new Parsed(body, List.of(), State.MALFORMED);
                for (String name : List.of("sourceMessageId", "key", "qualifier", "quote")) {
                    if (!item.path(name).isTextual() || !"qualifier".equals(name) && item.path(name).asText().isBlank()) {
                        return new Parsed(body, List.of(), State.MALFORMED);
                    }
                }
                if (!item.path("sourceRevision").isIntegralNumber() || !item.path("sourceRevision").canConvertToLong()
                    || item.path("sourceRevision").longValue() < 1) return new Parsed(body, List.of(), State.MALFORMED);
                candidates.add(new Candidate(item.get("sourceMessageId").textValue(), item.get("sourceRevision").longValue(),
                    item.get("key").textValue(), item.get("qualifier").textValue(), item.get("quote").textValue()));
            }
            return new Parsed(body, candidates, State.COMPLETE);
        } catch (IOException | IllegalArgumentException invalid) {
            return new Parsed(body, List.of(), State.MALFORMED);
        }
    }
}
