package com.harnex.middleware;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.model.ChatMessage;

/** Derive a short title from the first user message without spending an extra LLM call. */
@Component
@ProjectRuntime
public class TitleMiddleware implements Middleware {
    private static final int MAX_TITLE_CHARS = 32;
    private static final int MAX_FULL_TITLE_CHARS = 512;

    @Override
    public int order() {
        return MiddlewareOrder.TITLE;
    }

    @Override
    public void after(AgentCtx ctx) {
        var t = ctx.getThread();
        if (t.getTitle() == null || t.getTitle().equals("新会话") || t.getTitle().isBlank()) {
            for (var message : t.getMessages()) {
                if (!"user".equals(message.getRole())) {
                    continue;
                }
                String fullTitle = fullTitleFor(message.getContent());
                if (fullTitle.isBlank()) {
                    continue;
                }
                t.setFullTitle(fullTitle);
                t.setTitle(abbreviate(fullTitle));
                break;
            }
        }
    }

    private static String titleFor(ChatMessage m) {
        return titleFor(m.getContent());
    }

    /**
     * Derive the short thread title for a single user message's content, applying the {@value #MAX_TITLE_CHARS}-char
     * cap. Returns {@code ""} when the content yields no usable title (blank, or an internal reminder message). Exposed
     * so {@code AgentLoop} can lock in the title from the first real user turn eagerly — before history compression can
     * discard the original message — keeping the derivation logic in one place.
     */
    public static String titleFor(String content) {
        return abbreviate(fullTitleFor(content));
    }

    /** Complete derived title for tooltips and accessibility; bounded separately from the dense-list label. */
    public static String fullTitleFor(String content) {
        String s = deriveTitle(content);
        if (s.isBlank()) {
            return "";
        }
        return s.length() <= MAX_FULL_TITLE_CHARS ? s : s.substring(0, MAX_FULL_TITLE_CHARS) + "…";
    }

    private static String abbreviate(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.length() <= MAX_TITLE_CHARS ? s : s.substring(0, MAX_TITLE_CHARS) + "…";
    }

    private static String deriveTitle(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.replaceAll("(?s)\\[User attached files[^\\n]*\\].*$", "").strip();
        if (isInternalUserMessage(cleaned)) {
            return "";
        }
        for (String line : cleaned.split("\\R")) {
            String candidate = line.strip().replaceFirst("^[#>*\\-\\d.\\s]+", "").replaceAll("\\s+", " ").strip();
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return cleaned.replaceAll("\\s+", " ").strip();
    }

    /**
     * Whether a user-role message is a framework-injected reminder ({@code [system-reminder]} / {@code
     * [memory-reminder]}) rather than something the human actually typed. Exposed so other components (e.g. the
     * continuity projection in {@code AgentLoop}) can identify the real triggering question.
     */
    public static boolean isInternalUserMessage(String content) {
        if (content == null) {
            return false;
        }
        return content.startsWith("[system-reminder]") || content.startsWith("[system_reminder]")
            || content.startsWith("[memory-reminder]") || content.startsWith("[memory_reminder]")
            || content.startsWith("[plan-mode]") || content.startsWith("[goal-continuation]");
    }
}
