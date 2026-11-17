package com.harnex.conversation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.model.ChatMessage;
import com.harnex.model.MessageAttachment;
import com.harnex.provider.ModelProvider;
import com.harnex.tool.budget.TokenCounter;

/** Shared model-aware estimates. Provider-private payloads are counted once, never added to their text projection. */
@Component
public final class ContextTokens {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TokenCounter counter;
    public ContextTokens(TokenCounter counter) { this.counter = counter; }
    private static final class Defaults { static final ContextTokens INSTANCE = new ContextTokens(new TokenCounter()); }
    public static ContextTokens defaults() { return Defaults.INSTANCE; }
    public int text(String value, String model) { return counter.count(value, model); }
    public long message(ChatMessage message, String model) {
        long total = 32;
        if (message.getResponseOutputItems() != null && !message.getResponseOutputItems().isBlank()) {
            total += text(message.getResponseOutputItems(), model);
        } else {
            total += text(message.getContent(), model) + text(message.getReasoning(), model)
                + text(message.getReasoningToken(), model) + text(message.getToolCallId(), model);
            if (message.getToolCalls() != null) for (var call : message.getToolCalls()) {
                if (call != null) total += 16L + text(call.getName(), model) + text(call.getArguments(), model);
            }
        }
        total += text(message.getRecalledHistory(), model);
        var overlay = message.getRequestContextOverlay();
        if (overlay != null && overlay.validFor(message)) total += text("\n\n" + overlay.text(), model);
        if (message.getAttachments() != null) for (var attachment : message.getAttachments()) total += attachment(attachment);
        return total;
    }
    public long messages(List<ChatMessage> messages, String model) {
        return messages == null ? 0 : messages.stream().filter(Objects::nonNull).mapToLong(m -> message(m, model)).sum();
    }
    public long tools(List<Map<String, Object>> definitions, String model) {
        if (definitions == null || definitions.isEmpty()) return 0;
        try { return text(JSON.writeValueAsString(definitions), model); }
        catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Tool metadata is not serializable", invalid);
        }
    }
    /** The same prepared input estimate is used for admission, diagnostics and compaction watermarks. */
    public long request(ModelProvider.ChatRequest request, String model) {
        return messages(request.getMessages(), model) + tools(request.getTools(), model);
    }
    public static long attachment(MessageAttachment attachment) {
        return attachment.isImage() ? 8192L : Math.max(8192L, attachment.getSize());
    }
    public String prefix(String value, String model, int maxTokens) {
        if (value == null || maxTokens <= 0) return "";
        int chars = Math.min(value.length(), counter.charsForTokens(value, model, maxTokens));
        while (chars > 0 && text(value.substring(0, chars), model) > maxTokens) chars--;
        // Do not split a UTF-16 surrogate pair.
        if (chars > 0 && chars < value.length() && Character.isHighSurrogate(value.charAt(chars - 1))) chars--;
        return value.substring(0, chars);
    }
    public String suffix(String value, String model, int maxTokens) {
        if (value == null || maxTokens <= 0) return "";
        int low = 0, high = value.length();
        while (low < high) {
            int length = low + (high - low + 1) / 2;
            String candidate = ContinuationSummary.safeSuffix(value, length);
            if (text(candidate, model) <= maxTokens) low = length;
            else high = length - 1;
        }
        String result = ContinuationSummary.safeSuffix(value, low);
        while (!result.isEmpty() && text(result, model) > maxTokens) {
            result = result.substring(Character.charCount(result.codePointAt(0)));
        }
        return result;
    }
    /** Runtime-only data is projected into a copy of its owning user message; no new human turn is invented. */
    public static ChatMessage render(ChatMessage message) {
        var overlay = message.getRequestContextOverlay();
        if (overlay != null && !overlay.validFor(message)) overlay = null;
        boolean recalled = message.getRecalledHistory() != null && !message.getRecalledHistory().isBlank();
        if (!recalled && overlay == null) return message;
        String content = Objects.toString(message.getContent(), "");
        if (recalled) content += "\n\n" + message.getRecalledHistory();
        if (overlay != null) content += "\n\n" + overlay.text();
        return message.toBuilder().content(content).recalledHistory(null).requestContextOverlay(null)
            .preparedContext(overlay == null ? null : new RequestContextOverlay.Prepared(message, null, overlay, false)).build();
    }
}
