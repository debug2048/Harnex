package com.harnex.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Event pushed to the frontend over SSE. Types: message_start, text_delta, tool_use_start, tool_use_end,
 * thinking_delta, approval_required, error, done.
 *
 * <p>
 * Envelope fields ({@code runId}/{@code threadId}/{@code seq}/{@code ts}) are stamped by the run event pipeline
 * ({@code RunCoordinator}/{@code RunEventBus}) per the Local API contract (docs/03 §3.14). They may be {@code null} only
 * on child/internal paths that intentionally bypass the root event pipeline; the stable wire format on
 * {@code /api/v1/runs} always carries them.
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamEvent {
    private String type;
    private Map<String, Object> data;
    private String runId;
    @JsonProperty("conversationId")
    private String threadId;
    private Long seq;
    private Long streamEpoch;
    private Long ts;

    public StreamEvent(String type, Map<String, Object> data) {
        this.type = type;
        this.data = data;
    }

    public static StreamEvent of(String type, Map<String, Object> data) {
        return new StreamEvent(type, data);
    }
}
