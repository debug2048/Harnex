package com.harnex.goal;

import java.util.List;

import com.harnex.conversation.RequestContextOverlay;
import com.harnex.model.ChatMessage;

/** Extracts the current structured Goal contract from explicit continuation reminders in model history. */
public final class GoalPromptContext {
    /** Set only by the Goal orchestrator; a human-supplied marker alone does not enable Goal tools. */
    public static final String CHANNEL = "goal-continuation";
    private static final String OPEN = "<goal_objective>";
    private static final String CLOSE = "</goal_objective>";

    private GoalPromptContext() { }

    public static boolean active(List<ChatMessage> messages) {
        return objective(messages) != null;
    }

    public static String objective(List<ChatMessage> messages) {
        if (messages == null) return null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (message == null) continue;
            // A server-derived context carrier is not a new human turn or a new Goal contract.
            if (RequestContextOverlay.isCarrier(message)) continue;
            // A normal user turn after the last Goal continuation starts a different execution context. Historical
            // Goal reminders must not keep update_goal enabled forever on a reused Thread.
            if ("user".equals(message.getRole()) && !isContinuation(message)) return null;
            // Only server-owned continuation metadata (or the legacy system reminder) activates Goal tools.
            // Ordinary user text cannot opt in by including XML-looking markers.
            if (!isContinuation(message)) continue;
            String content = message.getContent();
            if (content == null) continue;
            int start = content.indexOf(OPEN);
            if (start < 0) continue;
            int end = content.indexOf(CLOSE, start + OPEN.length());
            if (end < 0) continue;
            String value = content.substring(start + OPEN.length(), end).trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    public static boolean isContinuation(ChatMessage message) {
        return message != null && message.getContent() != null && message.getContent().startsWith("[system_reminder]")
            && ("system".equals(message.getRole()) && message.getContent().contains(OPEN)
                || "user".equals(message.getRole()) && CHANNEL.equals(message.getChannel()));
    }

    public static boolean hasContract(ChatMessage message) {
        return isContinuation(message) && message.getContent().contains(OPEN) && message.getContent().contains(CLOSE);
    }
}
