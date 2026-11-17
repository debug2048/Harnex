package com.harnex.contract.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.harnex.execution.ExecutionSpec;
import com.harnex.run.RunNotification;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/** The strict public submission envelope. Execution kind is never inferred from omitted fields. */
@Data
@Schema(requiredProperties = {"execution", "clientRequestId"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class RunCreateRequest {
    private String conversationId;
    private String input;
    private List<String> attachmentIds;
    private String clientRequestId;
    private Long expectedHistoryRevision;
    private String parentMessageId;
    private String channel;
    private boolean stream;
    private ExecutionSpec execution;
    private RunNotification.Mode notificationMode;
    @JsonIgnore @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();
    @JsonAnySetter public void captureUnknownField(String field, Object value) { unknownFields.put(field, value); }
    @JsonIgnore public Set<String> unknownFieldNames() { return Set.copyOf(unknownFields.keySet()); }
    public String effectiveInput() { return input == null ? "" : input; }
}
