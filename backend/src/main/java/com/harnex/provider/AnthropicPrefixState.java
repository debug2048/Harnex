package com.harnex.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Local replay receipts. They contain hashes, never replace an upstream signature, and never go on the wire. */
final class AnthropicPrefixState {
    static final String RECEIPT = "harnex_anthropic_prefix";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> BLOCKS = new TypeReference<>() {};

    private AnthropicPrefixState() {}

    static boolean isReceipt(Object type) { return RECEIPT.equals(type); }

    static String tag(String content, Map<String, Object> request) {
        if (content == null) return null;
        try {
            var blocks = JSON.readValue(content, BLOCKS);
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("type", RECEIPT);
            receipt.put("prefix", fingerprint(request));
            receipt.put("output", hash(blocks));
            String billing = billing(request.get("system"));
            if (billing != null) receipt.put("billing", billing);
            blocks.add(0, receipt);
            return JSON.writeValueAsString(blocks);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot record Anthropic replay prefix", failure);
        }
    }

    static String compacted(List<Map<String, Object>> blocks, Map<String, Object> request, List<String> states) {
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("type", RECEIPT);
        receipt.put("output", hash(blocks));
        receipt.put("envelope", envelope(request));
        receipt.put("resume", List.copyOf(states));
        String billing = billing(request.get("system"));
        if (billing != null) receipt.put("billing", billing);
        var stored = new ArrayList<Map<String, Object>>();
        stored.add(receipt);
        stored.addAll(blocks);
        try { return JSON.writeValueAsString(stored); }
        catch (Exception failure) { throw new IllegalStateException("Cannot record compaction", failure); }
    }

    /** Copy-on-write: the durable response is also the UI/archive record and must remain intact. */
    @SuppressWarnings("unchecked")
    static List<String> sanitize(Map<String, Object> body, boolean bound) {
        String envelope = envelope(body);
        List<String> states = new ArrayList<>(List.of(envelope));
        List<Map<String, Object>> clean = new ArrayList<>();
        List<Map<String, Object>> messages = (List<Map<String, Object>>)body.get("messages");
        for (Map<String, Object> message : messages) {
            Map<String, Object> copy = new LinkedHashMap<>(message);
            Map<String, Object> receipt = null;
            if (message.get("content") instanceof List<?> raw) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                for (Object value : raw) {
                    Map<String, Object> block = (Map<String, Object>)value;
                    if (isReceipt(block.get("type"))) receipt = block;
                    else blocks.add(block);
                }
                boolean intact = receipt != null && Objects.equals(receipt.get("output"), hash(blocks));
                boolean matches = intact && states.contains(receipt.get("prefix"));
                if (bound && "assistant".equals(message.get("role")) && !matches) {
                    blocks.removeIf(block -> isThinking(block.get("type")));
                }
                if (blocks.isEmpty()) continue;
                copy.put("content", blocks);
                clean.add(copy);
                states = advance(states, copy);
                // Only the API's signed summary can translate the old prefix to a new window. This is NOT
                // available for a client-written summary, changed tools/system, or a modified compaction block.
                if (clean.size() == 1 && intact && blocks.size() == 1
                    && "compaction".equals(blocks.get(0).get("type"))
                    && envelope.equals(receipt.get("envelope")) && receipt.get("resume") instanceof List<?> resumes) {
                    var aliases = new LinkedHashSet<>(states);
                    for (Object resume : resumes) if (resume instanceof String s && s.matches("[a-f0-9]{64}")) aliases.add(s);
                    if (aliases.size() <= 64) states = new ArrayList<>(aliases);
                }
            } else {
                clean.add(copy);
                states = advance(states, copy);
            }
        }
        body.put("messages", clean);
        return List.copyOf(states);
    }

    static String fingerprint(Map<String, Object> body) {
        String state = envelope(body);
        if (body.get("messages") instanceof List<?> messages) {
            for (Object message : messages) state = hash(List.of(state, normaliseMessage((Map<?, ?>)message)));
        }
        return state;
    }

    private static List<String> advance(List<String> states, Map<String, Object> message) {
        Object normal = normaliseMessage(message);
        return states.stream().map(state -> hash(List.of(state, normal))).toList();
    }

    private static String envelope(Map<String, Object> body) {
        List<Object> tools = new ArrayList<>();
        if (body.get("tools") instanceof List<?> definitions) for (Object definition : definitions) {
            Map<String, Object> tool = new LinkedHashMap<>((Map<String, Object>)definition);
            tool.remove("cache_control");
            tools.add(tool);
        }
        return hash(List.of(normaliseContent(body.get("system")), tools));
    }

    private static Object normaliseMessage(Map<?, ?> message) {
        return Map.of("role", Objects.toString(message.get("role"), ""), "content", normaliseContent(message.get("content")));
    }

    /** Cache annotations and equivalent text-block encoding do not change the API's prefix. */
    @SuppressWarnings("unchecked")
    private static Object normaliseContent(Object content) {
        if (content == null || "".equals(content)) return List.of();
        if (content instanceof String text) return List.of(Map.of("type", "text", "text", text));
        if (!(content instanceof List<?> list)) return content;
        List<Object> result = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> raw)) { result.add(value); continue; }
            if (isReceipt(raw.get("type"))) continue;
            Map<String, Object> block = new LinkedHashMap<>((Map<String, Object>)raw);
            block.remove("cache_control");
            if ("tool_result".equals(block.get("type"))) block.put("content", normaliseContent(block.get("content")));
            result.add(block);
        }
        return result;
    }

    private static boolean isThinking(Object type) {
        return "thinking".equals(type) || "redacted_thinking".equals(type);
    }

    private static String billing(Object system) {
        if (system instanceof List<?> blocks) for (Object value : blocks) {
            if (value instanceof Map<?, ?> block && block.get("text") instanceof String text
                && text.startsWith("x-anthropic-billing-header:") && text.length() <= 512) return text;
        }
        return null;
    }

    private static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.writeValueAsString(sorted(value)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("Cannot fingerprint request prefix", failure); }
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), sorted(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(AnthropicPrefixState::sorted).toList();
        return value;
    }
}
