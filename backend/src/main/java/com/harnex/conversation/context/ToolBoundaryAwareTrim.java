package com.harnex.conversation.context;

import java.util.List;

import com.harnex.model.ChatMessage;

/**
 * Tool-call boundary-aware history trim helper, shared by anything that needs to drop messages from the head of a
 * conversation while preserving the strict pairing rules enforced by upstream LLM providers.
 *
 * <p>
 * Both OpenAI and Anthropic reject a request whose first non-system message is a {@code tool} result with no preceding
 * {@code assistant} call carrying the matching {@code tool_call_id}, AND reject a request where an {@code assistant}
 * message has {@code tool_calls} but the immediately following messages don't include the matching {@code tool}
 * result(s). A naive {@code subList(size - keepTail, size)} routinely lands the cut INSIDE one of those pairs and turns
 * trimming into a 400-on-every-turn loop.
 *
 * <p>
 * Originally this logic lived only in {@link com.harnex.middleware.SummarizationMiddleware}; the cron history-cap path
 * in {@link com.harnex.cron.AgentCronScheduler} did not honour it and would happily slice mid-pair. Centralising the
 * rule means there's one fix-once-fix-everywhere place to extend if a future provider adds a new pairing constraint.
 */
public final class ToolBoundaryAwareTrim {

    private ToolBoundaryAwareTrim() {}

    /**
     * Walks backwards from {@code desiredTailStart} until both invariants hold: the message at the tail-start is NOT a
     * {@code tool} role, AND the message immediately preceding it is NOT an {@code assistant} carrying
     * {@code tool_calls}.
     *
     * @param msgs the message list (read-only access; never mutated)
     * @param desiredTailStart the caller's preferred cut index (inclusive: {@code msgs[i..end]} is kept)
     * @return a safe tail-start index; never less than 1 (preserves the leading system message); returns
     *         {@code Math.min(desiredTailStart, msgs.size())} clamped to a safe boundary, or 1 if no safe boundary
     *         exists short of "keep everything from index 1".
     */
    public static int safeTailStart(List<ChatMessage> msgs, int desiredTailStart) {
        if (msgs == null || msgs.isEmpty())
            return 0;
        int tailStart = Math.max(1, Math.min(desiredTailStart, msgs.size()));
        while (tailStart > 1 && tailStart < msgs.size()) {
            ChatMessage at = msgs.get(tailStart);
            ChatMessage prev = msgs.get(tailStart - 1);
            boolean tailStartsAtTool = "tool".equals(at.getRole());
            boolean prevIsAssistantWithToolCalls =
                "assistant".equals(prev.getRole()) && prev.getToolCalls() != null && !prev.getToolCalls().isEmpty();
            if (tailStartsAtTool || prevIsAssistantWithToolCalls) {
                tailStart--;
            } else {
                break;
            }
        }
        return tailStart;
    }

    /**
     * Moves a desired cut forward to the first provider-safe boundary. This variant is for strict size budgets: when a
     * desired cut lands inside a tool-result batch, the whole batch is placed in the discarded/summarised head instead
     * of expanding the retained tail backwards beyond its budget.
     *
     * @return a value in {@code [1, msgs.size()]}; {@code msgs.size()} means retain no tail messages
     */
    public static int safeTailStartAtOrAfter(List<ChatMessage> msgs, int desiredTailStart) {
        if (msgs == null || msgs.isEmpty()) {
            return 0;
        }
        int tailStart = Math.max(1, Math.min(desiredTailStart, msgs.size()));
        while (tailStart < msgs.size() && "tool".equals(msgs.get(tailStart).getRole())) {
            tailStart++;
        }
        // A malformed/incomplete history can place a non-tool message directly after assistant(tool_calls). Do not
        // retain that orphaned suffix; advance once and let the history-integrity sweep repair the source transcript.
        while (tailStart < msgs.size() && tailStart > 0 && isAssistantWithToolCalls(msgs.get(tailStart - 1))) {
            tailStart++;
            while (tailStart < msgs.size() && "tool".equals(msgs.get(tailStart).getRole())) {
                tailStart++;
            }
        }
        return tailStart;
    }

    private static boolean isAssistantWithToolCalls(ChatMessage message) {
        return message != null && "assistant".equals(message.getRole()) && message.getToolCalls() != null
            && !message.getToolCalls().isEmpty();
    }

    /**
     * Returns true if the message at {@code idx} is a {@code tool} role — used by callers that need to abort an entire
     * trim (e.g. the conversation is one giant tool chain and no safe cut exists).
     */
    public static boolean isToolMessage(List<ChatMessage> msgs, int idx) {
        if (msgs == null || idx < 0 || idx >= msgs.size())
            return false;
        return "tool".equals(msgs.get(idx).getRole());
    }
}
