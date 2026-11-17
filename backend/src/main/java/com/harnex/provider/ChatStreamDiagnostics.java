package com.harnex.provider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.net.HttpExchangeState;

/** Bounded failure/compatibility diagnostics; ordinary successes are DEBUG-only. No text or tool arguments. */
final class ChatStreamDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String requestId;
    private final String provider;
    private final String model;
    private final Integer maxOutputTokens;
    private final long startedAt = System.nanoTime();
    private long lastFrameAt;
    private final Deque<Map<String, Object>> tail = new ArrayDeque<>();
    private int chunks;
    private int contentChunks;
    private String responseId;
    private String responseModel;

    ChatStreamDiagnostics(String requestId, String provider, String model, Integer maxOutputTokens) {
        this.requestId = requestId;
        this.provider = provider;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    void frame(JsonNode node) {
        lastFrameAt = System.nanoTime();
        chunks++;
        if (node == null) return;
        if (node.path("id").isTextual()) responseId = text(node.path("id"));
        if (node.path("model").isTextual()) responseModel = text(node.path("model"));
        JsonNode choices = node.path("choices");
        JsonNode choice = choices.path(0);
        JsonNode delta = choice.path("delta");
        JsonNode content = delta.path("content");
        if (content.isTextual() && !content.asText().isEmpty()) contentChunks++;
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("index", chunks);
        frame.put("afterMs", (lastFrameAt - startedAt) / 1_000_000L);
        frame.put("fields", fields(node));
        frame.put("choices", choices.isArray() ? choices.size() : null);
        frame.put("choiceFields", fields(choice));
        frame.put("deltaFields", fields(delta));
        frame.put("contentType", content.getNodeType().name());
        frame.put("contentChars", content.isTextual() ? content.asText().length() : 0);
        frame.put("finishReasonType", choice.path("finish_reason").getNodeType().name());
        frame.put("finishReason", text(choice.path("finish_reason")));
        frame.put("hasUsage", node.path("usage").isObject() && !node.path("usage").isEmpty());
        tail.addLast(frame);
        if (tail.size() > 4) tail.removeFirst();
    }

    void finish(Logger log, HttpExchangeState exchange, String finishReason, boolean sawDone, boolean cleanEof,
        int textChars, int toolSlots, ProviderTokenUsage usage, ProviderException failure) {
        finish(log, exchange, finishReason, sawDone, cleanEof, textChars, toolSlots, usage, failure, false);
    }

    void finish(Logger log, HttpExchangeState exchange, String finishReason, boolean sawDone, boolean cleanEof,
        int textChars, int toolSlots, ProviderTokenUsage usage, ProviderException failure, boolean acceptedTextOnlyDone) {
        boolean warn = failure != null || acceptedTextOnlyDone;
        if (warn ? !log.isWarnEnabled() : !log.isDebugEnabled()) return;
        try {
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("requestId", requestId);
            var identity = ProviderCallScope.currentCall();
            if (identity != null) {
                end.put("callId", identity.callId());
                end.put("attemptId", identity.attemptId());
            }
            end.put("provider", bounded(provider));
            end.put("model", bounded(model));
            end.put("httpProtocol", exchange == null ? null : exchange.protocol());
            end.put("upstreamRequestId", exchange == null ? null : bounded(exchange.requestId()));
            end.put("responseId", responseId);
            end.put("responseModel", responseModel);
            end.put("finishReason", bounded(finishReason));
            end.put("finishReasonSource", finishReason == null ? "missing" : "upstream");
            end.put("completionSource", acceptedTextOnlyDone ? "done_sentinel"
                : failure == null ? "finish_reason" : "unconfirmed");
            end.put("sawDone", sawDone);
            end.put("cleanEof", cleanEof);
            end.put("chunks", chunks);
            end.put("contentChunks", contentChunks);
            end.put("textChars", textChars);
            end.put("toolSlots", toolSlots);
            end.put("maxOutputTokens", maxOutputTokens);
            end.put("outputLimitMode", maxOutputTokens == null ? "server_default" : "request_limit");
            end.put("completionTokens", usage == null ? null : usage.completion());
            end.put("errorCode", failure == null ? null : failure.code);
            end.put("failureType", failureType(failure));
            long finishedAt = System.nanoTime();
            end.put("durationMs", (finishedAt - startedAt) / 1_000_000L);
            end.put("lastFrameAgeMs", chunks == 0 ? null : (finishedAt - lastFrameAt) / 1_000_000L);
            end.put("tailFrames", List.copyOf(tail));
            String metadata = ProviderStreamLogger.scrubBody(JSON.writeValueAsString(end));
            if (warn) log.warn("Provider stream terminal {}", metadata);
            else log.debug("Provider stream terminal {}", metadata);
        } catch (Exception diagnosticFailure) {
            // Diagnostics must never change whether the actual model response succeeds.
            log.warn("Could not record provider stream terminal metadata for request {}", requestId);
        }
    }

    private static String failureType(Throwable failure) {
        if (failure == null) return null;
        for (int depth = 0; depth < 8 && failure.getCause() != null; depth++) failure = failure.getCause();
        return bounded(failure.getClass().getName());
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        var fields = node.fieldNames();
        while (fields.hasNext() && names.size() < 12) names.add(bounded(fields.next()));
        return names;
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? bounded(node.asText()) : null;
    }

    private static String bounded(String value) {
        return value == null ? null : value.substring(0, Math.min(128, value.length()));
    }
}
