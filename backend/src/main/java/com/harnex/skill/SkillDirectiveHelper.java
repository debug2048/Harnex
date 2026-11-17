package com.harnex.skill;

import java.util.List;

import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;

/**
 * Utility to detect whether the current thread turn is an explicit {@code /skill} invocation. Centralises the detection
 * logic so that {@code AgentLoop}, {@code SystemReminderMiddleware} and any future consumer share one implementation.
 */
public final class SkillDirectiveHelper {

    private SkillDirectiveHelper() {}

    /**
     * Returns {@code true} when the current turn was triggered by an explicit {@code /skill} command.
     * <p>
     * Detection strategy (ordered by reliability):
     * <ol>
     * <li>ThreadState extras flag ({@code "skillDirective"}) — set by {@code SlashCommandService.handleSkill}.</li>
     * <li>Fallback: last user message starts with {@code <skill name=} (content-based heuristic for backward compat).
     * </li>
     * </ol>
     */
    public static boolean isSkillDirective(ConversationThread thread) {
        // Priority: explicit ThreadState marker set by SlashCommandService
        if (thread.getState() != null && thread.getState().getExtras() != null) {
            Object marked = thread.getState().getExtras().get("skillDirective");
            if (Boolean.TRUE.equals(marked)) {
                return true;
            }
        }
        // Fallback: content-based detection (backward compatibility)
        List<ChatMessage> msgs = thread.getMessages();
        if (msgs != null) {
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if ("user".equals(msgs.get(i).getRole())) {
                    String content = msgs.get(i).getContent();
                    return content != null && content.startsWith("<skill name=");
                }
            }
        }
        return false;
    }
}
