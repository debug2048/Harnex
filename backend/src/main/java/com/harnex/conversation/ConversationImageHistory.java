package com.harnex.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.image.MediaInvocationService;
import com.harnex.model.ChatMessage;
import com.harnex.model.ToolCall;
import com.harnex.util.Ids;

/** Replays a recorded image invocation and its result together; never executes historical calls. */
final class ConversationImageHistory {
    private final Path calls;
    private final ObjectMapper json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    ConversationImageHistory(AgentConfig config) { calls = Path.of(config.getDataDir()).resolve("images/invocations/calls"); }

    List<ChatMessage> project(String conversationId, MessageRecord source) {
        if (source.role() != MessageRecord.Role.ASSISTANT || source.isMutable() || !Ids.isSafeResourceId(source.runId())
            || source.contentParts().stream().noneMatch(part -> part.type() == ContentPart.Type.IMAGE_OPERATION_REF)) return List.of();
        try (var input = Files.newInputStream(calls.resolve(source.runId() + ".json"))) {
            byte[] bytes = input.readNBytes(128_001);
            if (bytes.length > 128_000) return List.of();
            var invocation = json.readValue(bytes, MediaInvocationService.Invocation.class);
            if (!source.runId().equals(invocation.runId()) || !conversationId.equals(invocation.conversationId())
                || !MediaInvocationService.isImageTool(invocation.toolName()) || !Ids.isSafeCallId(invocation.toolCallId())
                || invocation.arguments() == null || invocation.operationId() == null
                || invocation.arguments().operation() == null || !Set.of("generate", "edit").contains(invocation.arguments().operation())
                || invocation.arguments().prompt() == null || invocation.arguments().prompt().isBlank()
                || invocation.artifactIds() == null || invocation.artifactIds().size() > 16
                || invocation.state() == null || !Set.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN", "SAVING").contains(invocation.state())
                || source.contentParts().stream().noneMatch(part -> invocation.operationId().equals(part.imageOperationId()))) return List.of();
            var archived = source.contentParts().stream().filter(part -> part.type() == ContentPart.Type.ARTIFACT_REF)
                .map(ContentPart::artifactId).collect(Collectors.toSet());
            if (!archived.equals(new HashSet<>(invocation.artifactIds()))) return List.of();
            var assistant = ChatMessage.assistant("");
            assistant.setToolCalls(List.of(new ToolCall(invocation.toolCallId(), invocation.toolName(), json.writeValueAsString(invocation.arguments()))));
            var result = new LinkedHashMap<String, Object>();
            result.put("state", invocation.state()); result.put("artifactIds", invocation.artifactIds());
            return List.of(assistant, ChatMessage.tool(invocation.toolCallId(), json.writeValueAsString(result), !"SUCCEEDED".equals(invocation.state())));
        } catch (IOException | IllegalArgumentException unavailable) {
            // Optional history metadata: old/imported conversations retain their ordinary image references.
            return List.of();
        }
    }
}
