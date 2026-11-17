package com.harnex.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harnex.config.AgentConfig;
import com.harnex.image.MediaInvocationService;
import com.harnex.model.ChatMessage;
import com.harnex.model.ToolCall;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;

/** Private provider continuation. Valid only for the exact immutable answer it was produced with. */
@Component
public final class ConversationContinuations {
    private final Path base;
    private final ObjectMapper json = new ObjectMapper();
    public ConversationContinuations(AgentConfig config) { base = Path.of(config.getDataDir()).resolve("conversations/continuations"); }
    public void save(String conversationId, String messageId, String text, Map<String, Object> end, String targetIdentity) {
        if (!end.containsKey("reasoningProvider") && !end.containsKey("responseOutputProvider")) return;
        State state = new State(fingerprint(text), targetIdentity, value(end, "reasoning"), value(end, "reasoningProvider"),
            value(end, "reasoningToken"), value(end, "responseOutputProvider"), value(end, "responseOutputItems"),
            end.get("toolCalls") instanceof List<?> calls ? json.convertValue(calls, new TypeReference<List<ToolCall>>() {}) : List.of(),
            value(end, "content"));
        try { AtomicJsonFiles.write(json, path(conversationId, messageId), state); }
        catch (IOException failure) { throw new ConversationException("continuation_unavailable", "could not save provider continuation", failure); }
    }
    public void apply(String conversationId, MessageRecord source, ChatMessage target, String targetIdentity) {
        Path path = path(conversationId, source.messageId());
        boolean imageCall = target.getToolCalls() != null && !target.getToolCalls().isEmpty();
        if (source.role() != MessageRecord.Role.ASSISTANT || source.isMutable()
            || source.status() != MessageRecord.Status.COMPLETED && !imageCall || !Files.isRegularFile(path)) return;
        try {
            State state = json.readValue(Files.readAllBytes(path), State.class);
            if (!state.fingerprint().equals(fingerprint(source.plainText())) || !Objects.equals(state.targetIdentity(), targetIdentity)) return;
            boolean storedCall = state.toolCalls() != null && !state.toolCalls().isEmpty();
            if (imageCall != storedCall || imageCall && !sameCalls(target.getToolCalls(), state.toolCalls())) return;
            if (imageCall) {
                // Preserve the exact original arguments for provider-native replay, including omitted optional fields.
                target.setToolCalls(state.toolCalls()); target.setContent(state.assistantText() == null ? "" : state.assistantText());
            }
            target.setReasoning(state.reasoning()); target.setReasoningProvider(state.reasoningProvider());
            target.setReasoningToken(state.reasoningToken()); target.setResponseOutputProvider(state.responseOutputProvider());
            target.setResponseOutputItems(state.responseOutputItems());
        } catch (IOException failure) { throw new ConversationException("continuation_unavailable", "could not read provider continuation", failure); }
    }
    private boolean sameCalls(List<ToolCall> projected, List<ToolCall> stored) throws IOException {
        if (projected.size() != stored.size()) return false;
        for (int index = 0; index < projected.size(); index++) {
            ToolCall left = projected.get(index), right = stored.get(index);
            if (right == null || !Objects.equals(left.getId(), right.getId()) || !Objects.equals(left.getName(), right.getName())
                || !comparableArguments(left).equals(comparableArguments(right))) return false;
        }
        return true;
    }
    private JsonNode comparableArguments(ToolCall call) throws IOException {
        if (call.getArguments() == null || call.getArguments().isBlank()) return json.getNodeFactory().nullNode();
        var value = json.readTree(call.getArguments());
        if (value instanceof ObjectNode object) {
            if (!object.hasNonNull("operation") && MediaInvocationService.isImageTool(call.getName()))
                object.put("operation", MediaInvocationService.EDIT_TOOL.equals(call.getName()) ? "edit" : "generate");
            var fields = object.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getValue().isNull() || "sourceArtifactIds".equals(field.getKey()) && field.getValue().isArray() && field.getValue().isEmpty()) fields.remove();
            }
        }
        return value == null ? json.getNodeFactory().nullNode() : value;
    }
    private Path path(String conversation, String message) {
        if (!Ids.isSafe(conversation) || !Ids.isSafeCallId(message)) throw new IllegalArgumentException("invalid continuation identity");
        return base.resolve(conversation).resolve(message + ".json");
    }
    private static String fingerprint(String text) { return IdempotencyReceiptStore.requestHash(Map.of("text", text)); }
    private static String value(Map<String, Object> source, String key) { return source.get(key) instanceof String text ? text : null; }
    public record State(String fingerprint, String targetIdentity, String reasoning, String reasoningProvider, String reasoningToken,
        String responseOutputProvider, String responseOutputItems, List<ToolCall> toolCalls, String assistantText) {}
}
