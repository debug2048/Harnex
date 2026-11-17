package com.harnex.interaction;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"kind", "conversationId", "callId", "createdAt", "payload"})
public record PendingInteraction(String kind, String conversationId, String callId, long createdAt, Map<String, Object> payload) {
    public PendingInteraction { payload = payload == null ? Map.of() : Map.copyOf(payload); }
}
