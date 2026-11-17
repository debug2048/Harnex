package com.harnex.run;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.harnex.conversation.CommitReceipt;
import com.harnex.model.StreamEvent;

import io.swagger.v3.oas.annotations.media.Schema;

/** Bounded current display, independent of replay buffer retention and the permanent conversation archive. */
public final class RunPresentationState {
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final LinkedHashMap<String, ToolActivity> tools = new LinkedHashMap<>();
    private final Set<String> pendingTools = new HashSet<>();
    private final List<ProcessMessage> messages = new ArrayList<>();
    private int messageChars;
    private final LinkedHashMap<String, ResourceRevision> images = new LinkedHashMap<>();
    private final LinkedHashMap<String, ResourceRevision> calls = new LinkedHashMap<>();
    private boolean truncated;
    private long messageStartSeq;
    private String afterMessageId;
    private ProcessMessage progressMessage;
    public record ResourceRevision(String id, long revision) {}
    public record ToolActivity(String id, String name, String subagentConversationId, String state, String summary, long seq,
        String arguments, String content, Long durationMs, boolean resultPending, String afterMessageId, String purpose) {}
    public record ProcessMessage(long seq, String text, String thinking, String afterMessageId) {}
    @Schema(requiredProperties = {"text", "thinking", "messageStartSeq", "truncated", "tools", "imageOperations", "modelCalls"})
    public record Snapshot(String text, String thinking, long messageStartSeq, boolean truncated, List<ToolActivity> tools,
        List<ResourceRevision> imageOperations, List<ResourceRevision> modelCalls, List<ProcessMessage> messages) {}
    public static Snapshot empty() { return new Snapshot("", "", 0, false, List.of(), List.of(), List.of(), List.of()); }
    public Snapshot snapshot() { return new Snapshot(text.toString(), thinking.toString(), messageStartSeq, truncated,
        List.copyOf(tools.values()), List.copyOf(images.values()), List.copyOf(calls.values()), List.copyOf(messages)); }
    ToolActivity apply(StreamEvent event) {
        progressMessage = null;
        Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
        String type = event.getType();
        if (type == null) return null;
        ToolActivity updatedTool = null;
        if (data.get("receipt") instanceof CommitReceipt receipt
            && receipt.messageIds().size() > 1) afterMessageId = receipt.messageIds().get(0);
        switch (type) {
            case "message_start" -> { text.setLength(0); thinking.setLength(0); messageStartSeq = event.getSeq(); }
            case "text_delta" -> append(text, string(data.get("text")), 2_000_000);
            case "thinking_delta" -> append(thinking, string(data.get("text")), 256_000);
            case "thinking_end" -> {
                String value = thinking.isEmpty() ? string(data.get("text")) : thinking.toString();
                progress(event.getSeq(), "", value); thinking.setLength(0);
            }
            case "message_end" -> { if (data.get("content") instanceof String content) { text.setLength(0); append(text, content, 2_000_000); } }
            case "message_committed", "agent_step_committed" -> { text.setLength(0); thinking.setLength(0); }
            case "tool_use_queued", "tool_use_start" -> {
                progress(event.getSeq(), text.toString(), thinking.toString());
                text.setLength(0); thinking.setLength(0);
                updatedTool = tool(type, data, event.getSeq(), null);
            }
            case "tool_use_end", "tool_use_status" -> updatedTool = tool(type, data, event.getSeq(), null);
            case "subagent_event" -> {
                String childType = string(data.get("childEventType"));
                if (childType.startsWith("tool_use_")) updatedTool = tool(childType, data, event.getSeq(), string(data.get("subagentThreadId")));
            }
            case "image_operation_updated" -> reference(images, data, "operationId");
            case "model_call_updated" -> reference(calls, data, "callId");
            default -> {}
        }
        return updatedTool;
    }
    private void progress(long seq, String content, String reasoning) {
        if (content.isBlank() && reasoning.isBlank()) return;
        progressMessage = new ProcessMessage(seq, content, reasoning, afterMessageId);
        String safeContent = bounded(content, 100_000), safeReasoning = bounded(reasoning, 100_000);
        if (safeContent.length() < content.length() || safeReasoning.length() < reasoning.length()) truncated = true;
        messages.add(new ProcessMessage(seq, safeContent, safeReasoning, afterMessageId));
        messageChars += safeContent.length() + safeReasoning.length();
        while (messages.size() > 500 || messageChars > 2_000_000) {
            var removed = messages.remove(0); messageChars -= removed.text().length() + removed.thinking().length(); truncated = true;
        }
    }
    ProcessMessage progressMessage() { return progressMessage; }
    private void append(StringBuilder target, String value, int max) {
        int room = max - target.length(); target.append(value, 0, Math.min(room, value.length()));
        if (value.length() > room) truncated = true;
    }
    private ToolActivity tool(String type, Map<String, Object> data, long seq, String child) {
        String id = string(data.getOrDefault("id", data.get("callId")));
        if (id.isEmpty()) return null;
        String prefix = (child == null ? "" : child) + ":" + id + ":";
        String key = tools.entrySet().stream().filter(entry -> !"tool_use_queued".equals(type)
            && entry.getKey().startsWith(prefix) && pendingTools.contains(entry.getKey())
            && (!"tool_use_start".equals(type) || "QUEUED".equals(entry.getValue().state())))
            .map(Map.Entry::getKey).findFirst().orElse(prefix + seq);
        var previous = tools.get(key);
        String name = string(data.getOrDefault("name", data.get("toolName")));
        if (name.isEmpty() && previous != null) name = previous.name();
        String state = "tool_use_queued".equals(type) ? "QUEUED" : "tool_use_start".equals(type) ? "RUNNING"
            : Boolean.TRUE.equals(data.get("error")) ? "FAILED" : "COMPLETED";
        // Keep the start position and queued arguments when later frames only carry a result.
        ToolActivity updated = new ToolActivity(id, name, child, state,
            bounded(string(data.getOrDefault("activitySummary", data.get("summary"))), 2000), previous == null ? seq : previous.seq(),
            data.containsKey("arguments") ? bounded(string(data.get("arguments")), 16_000) : previous == null ? null : previous.arguments(),
            data.containsKey("content") ? bounded(string(data.get("content")), 16_000) : previous == null ? null : previous.content(),
            data.get("durationMs") instanceof Number duration ? Long.valueOf(duration.longValue()) : previous == null ? null : previous.durationMs(),
            !"tool_use_end".equals(type), previous == null ? afterMessageId : previous.afterMessageId(),
            data.get("purpose") instanceof String purpose && !purpose.isBlank() ? purpose : previous == null ? null : previous.purpose());
        tools.put(key, updated);
        if ("tool_use_end".equals(type)) pendingTools.remove(key); else pendingTools.add(key);
        while (tools.size() > 500) {
            // Keep in-flight identities so a late result updates its original occurrence.
            // Child diagnostics must not evict the parent's recent calls during a large delegation.
            String oldest = tools.entrySet().stream().filter(entry -> !entry.getKey().equals(key)
                && !pendingTools.contains(entry.getKey()) && entry.getValue().subagentConversationId() != null)
                .map(Map.Entry::getKey).findFirst().orElseGet(() -> tools.keySet().stream()
                    .filter(candidate -> !candidate.equals(key) && !pendingTools.contains(candidate)).findFirst().orElse(null));
            if (oldest == null) break;
            tools.remove(oldest); truncated = true;
        }
        return updated;
    }
    private void reference(LinkedHashMap<String, ResourceRevision> target, Map<String, Object> data, String key) {
        String id = string(data.get(key)); if (id.isEmpty()) return;
        long revision = data.get("revision") instanceof Number number ? number.longValue() : 0;
        var previous = target.get(id);
        if (previous == null || previous.revision() < revision) target.put(id, new ResourceRevision(id, revision));
        while (target.size() > 500) { target.remove(target.keySet().iterator().next()); truncated = true; }
    }
    private static String bounded(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
}
