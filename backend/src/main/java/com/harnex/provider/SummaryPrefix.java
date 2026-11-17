package com.harnex.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.conversation.ContextTokens;
import com.harnex.model.ChatMessage;

/** A run-local copy of the last successful request; never a second durable conversation history. */
public final class SummaryPrefix {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SummaryPrefix() {}

    public static ModelProvider.ChatRequest snapshot(ModelProvider.ChatRequest request, String provider, String model) {
        return request.toBuilder().provider(provider).model(model)
            .messages(request.getMessages().stream().map(SummaryPrefix::wireMessage).toList())
            .tools(JSON.convertValue(tools(request), new TypeReference<List<Map<String, Object>>>() {}))
            .targetPreparation(null).idempotencyKey(null).build();
    }

    /** Require the entire previously sent prefix to survive. A changed branch, overlay or tool catalogue opts out. */
    public static ModelProvider.ChatRequest prepare(ModelProvider.ChatRequest previous, ModelProvider.ChatRequest current) {
        if (previous == null || current == null || previous.isNativeWebSearchActive()
            || previous.getMessages().isEmpty() || current.getMessages().size() < previous.getMessages().size()
            || !JSON.valueToTree(tools(previous)).toString().equals(JSON.valueToTree(tools(current)).toString())) return null;
        var messages = current.getMessages().stream().map(SummaryPrefix::wireMessage).toList();
        for (int i = 0; i < previous.getMessages().size(); i++) {
            if (!previous.getMessages().get(i).equals(messages.get(i))) return null;
        }
        return previous.toBuilder().messages(messages).webSearchAllowed(false).build();
    }

    public static ModelProvider.ChatRequest append(ModelProvider.ChatRequest prefix, String instruction) {
        var messages = new ArrayList<>(prefix.getMessages()); messages.add(ChatMessage.user(instruction));
        return prefix.toBuilder().messages(List.copyOf(messages)).build();
    }

    private static List<Map<String, Object>> tools(ModelProvider.ChatRequest request) {
        return request.getTools() == null ? List.of() : request.getTools();
    }

    private static ChatMessage wireMessage(ChatMessage message) {
        // These fields are local provenance, not provider-visible tokens. Render overlays before detaching them.
        var rendered = ContextTokens.render(message).toBuilder().originalInput(null).channel(null).source(null)
            .runInputIds(null).contextSources(null).contextEvidence(null).contextSourcesPartial(false)
            .requestContextOverlay(null).preparedContext(null).recalledHistory(null).build();
        return JSON.convertValue(rendered, ChatMessage.class);
    }
}
