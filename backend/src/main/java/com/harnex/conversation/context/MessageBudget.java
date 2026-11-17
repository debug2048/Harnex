package com.harnex.conversation.context;

import com.harnex.model.ChatMessage;
import com.harnex.model.ToolCall;

/**
 * Fast, conservative approximation of how many characters a {@link ChatMessage} will contribute to the provider context
 * window.
 *
 * <p>
 * This is intentionally <em>not</em> a tokenizer: it is a cheap proxy used by the hard-ceiling guard and token-limit
 * recovery so that we can decide whether to compress without paying tokenizer overhead on every iteration.
 *
 * <p>
 * What counts:
 * <ul>
 * <li>{@code content} length.</li>
 * <li>Reasoning text, whether stored on an assistant message or as a standalone timeline node. Capability-enabled
 * adapters may send either shape back to the provider.</li>
 * <li>Assistant {@code toolCalls} payload (name + arguments JSON + a small envelope), or the provider-native output
 * item JSON when present.</li>
 * </ul>
 */
public final class MessageBudget {

    private MessageBudget() {}

    /** Approximate provider-context character weight of a single message. */
    public static int approximateChars(ChatMessage m) {
        if (m == null) {
            return 0;
        }
        if (m.isReasoningBlock()) {
            // Provider routing is not available at this layer. Count the text conservatively: providers such as Kimi
            // replay it, while providers that strip it merely trigger compression slightly earlier.
            return m.getReasoning() == null ? 0 : m.getReasoning().length();
        }
        // Complete provider output replaces the reconstructed content/tool-call request shape. Count its actual JSON
        // size instead of counting the duplicated presentation fields, otherwise encrypted reasoning / program items
        // would be invisible to the context ceiling and long-running Responses threads could overflow unexpectedly.
        if (m.getResponseOutputItems() != null && !m.getResponseOutputItems().isBlank()) {
            return m.getResponseOutputItems().length();
        }
        int n = m.getContent() == null ? 0 : m.getContent().length();
        // Interrupted and legacy turns may keep replayable reasoning directly on the assistant message rather than in
        // a standalone block. Count it for the same reason as the block path above.
        n += m.getReasoning() == null ? 0 : m.getReasoning().length();
        n += toolCallChars(m);
        return n;
    }

    private static int toolCallChars(ChatMessage m) {
        if (m.getToolCalls() == null || m.getToolCalls().isEmpty()) {
            return 0;
        }
        int n = 0;
        int count = 0;
        for (ToolCall tc : m.getToolCalls()) {
            if (tc == null) {
                continue;
            }
            count++;
            if (tc.getName() != null) {
                n += tc.getName().length();
            }
            if (tc.getArguments() != null) {
                n += tc.getArguments().length();
            }
        }
        // Small overhead per non-null call to approximate the JSON envelope / whitespace.
        return n + 32 * count;
    }
}
