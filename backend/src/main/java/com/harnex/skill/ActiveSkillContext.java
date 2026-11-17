package com.harnex.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.conversation.RequestContextOverlay;
import com.harnex.model.ChatMessage;
import com.harnex.model.ToolCall;
import com.harnex.tool.ToolResult;

/** Bounded, execution-local references to skills actually loaded during this task. No skill bodies are retained. */
public final class ActiveSkillContext {
    private static final int MAX_SKILLS = 8;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EXPANDED = Pattern.compile("^<skill name=\"([^\"\\r\\n]{1,128})\">");
    private static final Pattern DIRECTIVE = Pattern.compile("^/skill\\s", Pattern.CASE_INSENSITIVE);
    private final Map<String, Read> reads = new LinkedHashMap<>();
    private record Read(String name, String role, String toolCallId, String bodyHash) {}

    public synchronized void observeDirective(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (!"user".equals(message.getRole())) continue;
            if (message.getOriginalInput() == null || !DIRECTIVE.matcher(message.getOriginalInput().stripLeading()).find()) return;
            var matcher = EXPANDED.matcher(Objects.toString(message.getContent(), ""));
            if (matcher.find()) remember(matcher.group(1), message);
            return;
        }
    }

    public synchronized void observeToolResult(ToolResult result, ChatMessage message, List<ToolCall> calls) {
        if (result.isError() || !"skill_read".equals(result.getToolName()) || calls == null) return;
        for (ToolCall call : calls) {
            if (call == null) continue;
            if (!"skill_read".equals(call.getName()) || !Objects.equals(call.getId(), result.getCallId())) continue;
            try {
                String name = JSON.readTree(call.getArguments()).path("name").asText("");
                if (!name.isBlank() && name.length() <= 128) remember(name, message);
            } catch (Exception ignored) { /* A malformed argument is not proof that a skill was loaded. */ }
            return;
        }
    }

    private void remember(String name, ChatMessage message) {
        if (message.getContent() == null || message.getContent().isBlank()) return;
        reads.remove(name);
        while (reads.size() >= MAX_SKILLS) reads.remove(reads.keySet().iterator().next());
        reads.put(name, new Read(name, message.getRole(), message.getToolCallId(), RequestContextOverlay.hash(message.getContent())));
    }

    public synchronized String reminder(List<ChatMessage> messages) {
        var missing = reads.values().stream().filter(read -> messages.stream().noneMatch(message ->
            read.role().equals(message.getRole()) && Objects.equals(read.toolCallId(), message.getToolCallId())
                && read.bodyHash().equals(RequestContextOverlay.hash(Objects.toString(message.getContent(), "")))))
            .map(Read::name).toList();
        if (missing.isEmpty()) return "";
        try {
            return "<active_skill_context>Skill instructions loaded during this execution are no longer present verbatim: "
                + JSON.writeValueAsString(missing).replace("<", "\\u003c").replace(">", "\\u003e")
                + ". If a skill is still relevant, reload it with skill_read before following its exact steps; "
                + "use file_read for needed sections if the full body is too large. A summary is not the full skill. "
                + "Do not reactivate a skill that no longer applies.</active_skill_context>";
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized List<String> names() { return List.copyOf(reads.keySet()); }
}
