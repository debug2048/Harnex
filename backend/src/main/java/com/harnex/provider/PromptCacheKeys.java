package com.harnex.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.harnex.model.ChatMessage;

/** Builds privacy-preserving cache-routing keys from a stable conversation scope or reusable request prefix. */
final class PromptCacheKeys {

    private PromptCacheKeys() {}

    static String forRequest(String provider, String model, List<ChatMessage> messages,
        List<Map<String, Object>> tools) {
        return forRequest(provider, model, null, messages, tools);
    }

    static String forRequest(String provider, String model, String conversationScope,
        List<ChatMessage> messages, List<Map<String, Object>> tools) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, provider);
            update(digest, model);
            if (conversationScope != null && !conversationScope.isBlank()) {
                // Vendors recommend a session/task identifier for sticky routing. Hash it with provider/model so
                // raw internal ids are not disclosed and cannot collide across endpoints.
                update(digest, "conversation");
                update(digest, conversationScope);
                return key(digest);
            }
            // Only the leading instruction prefix belongs in the routing key. Later system reminders are dynamic
            // turn state and must not churn a key shared by otherwise-identical requests.
            if (messages != null) {
                for (ChatMessage message : messages) {
                    if (message == null || !"system".equals(message.getRole())) {
                        break;
                    }
                    update(digest, message.getContent());
                }
            }
            if (tools != null) {
                // Tool schemas often arrive in HashMap-backed structures. Canonicalise every object level so the
                // same schema gets the same routing key across JVM runs regardless of map iteration order.
                update(digest, AbstractHttpProvider.M.writeValueAsString(
                    canonicalise(AbstractHttpProvider.M.valueToTree(tools))));
            }
            return key(digest);
        } catch (Exception ex) {
            throw new ProviderException("config", "could not build prompt_cache_key: " + ex.getMessage());
        }
    }

    private static String key(MessageDigest digest) {
        return "harnex-" + HexFormat.of().formatHex(digest.digest(), 0, 16);
    }

    private static JsonNode canonicalise(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode out = AbstractHttpProvider.M.createArrayNode();
            node.forEach(value -> out.add(canonicalise(value)));
            return out;
        }
        ObjectNode out = AbstractHttpProvider.M.createObjectNode();
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        node.fields().forEachRemaining(field -> sorted.put(field.getKey(), field.getValue()));
        sorted.forEach((key, value) -> out.set(key, canonicalise(value)));
        return out;
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        int length = bytes == null ? -1 : bytes.length;
        digest.update((byte)(length >>> 24));
        digest.update((byte)(length >>> 16));
        digest.update((byte)(length >>> 8));
        digest.update((byte)length);
        if (bytes != null) {
            digest.update(bytes);
        }
    }
}
