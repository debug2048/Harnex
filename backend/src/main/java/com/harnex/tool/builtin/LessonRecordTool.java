package com.harnex.tool.builtin;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.memory.CoreMemory;
import com.harnex.middleware.SensitiveDataMiddleware;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;

/**
 * Append a cross-thread "lesson" to LESSONS.md so the next conversation can avoid the same mistake. The note is
 * auto-injected into the system prompt of every future thread.
 *
 * <p>
 * Use sparingly: one short bullet per genuine pitfall. The file is auto-pruned to 2× HARD_CAP — oldest bullets fall off
 * first.
 * </p>
 */
@Component
@ProjectRuntime
public class LessonRecordTool implements Tool {

    private final CoreMemory mem;
    /**
     * Optional secret/PII scrubber. Models occasionally embed verbatim error messages or paths into the lesson text
     * they want recorded; scrub before persisting so a leaked API key never lands in LESSONS.md and gets re-injected
     * into every future system prompt. {@code @Autowired(required=false)} keeps the tool wiring trivial when the
     * middleware bean is absent (test contexts, deployments that disable both redact flags).
     */
    private final SensitiveDataMiddleware sensitiveData;

    public LessonRecordTool(CoreMemory mem, @Autowired(required = false) SensitiveDataMiddleware sensitiveData) {
        this.mem = mem;
        this.sensitiveData = sensitiveData;
    }

    @Override
    public String getName() {
        return "lesson_record";
    }

    @Override
    public String getDescription() {
        return "Record a short lesson learned in LESSONS.md so future threads avoid the same pitfall. "
            + "One concise sentence; describe what failed and what to do instead.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.WRITES_FILESYSTEM);
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties",
                Map.of("lesson",
                    Map.of("type", "string", "description", "One sentence: what went wrong + the correct approach.")),
                "required", new String[] {"lesson"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        Object v = input.get("lesson");
        if (v == null) {
            return ToolResult.builder().error(true).content("'lesson' required").build();
        }
        String lesson = String.valueOf(v).strip();
        if (lesson.isEmpty()) {
            return ToolResult.builder().error(true).content("'lesson' is empty").build();
        }
        if (lesson.length() > 500) {
            lesson = lesson.substring(0, 500) + "…";
        }
        if (sensitiveData != null) {
            lesson = sensitiveData.redactToolContent(lesson);
        }
        try {
            mem.appendLesson(lesson);
            return ToolResult.builder().content("Recorded.").build();
        } catch (Exception e) {
            return ToolResult.builder().error(true).content("write failed: " + e.getMessage()).build();
        }
    }
}
