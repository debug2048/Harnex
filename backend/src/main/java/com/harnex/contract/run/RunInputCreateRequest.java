package com.harnex.contract.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.harnex.run.RunInputMode;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/** Body of {@code POST /api/v1/runs/{runId}/inputs}. */
@Data
public class RunInputCreateRequest {
    private String mode;
    private String text;
    private List<String> attachmentIds;
    private String channel;
    private String clientRequestId;

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknownField(String field, Object value) {
        unknownFields.put(field, value);
    }

    @JsonIgnore
    public Set<String> unknownFieldNames() {
        return Set.copyOf(unknownFields.keySet());
    }

    public RunInputMode resolvedMode() {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("run input mode is required");
        }
        try {
            return RunInputMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid run input mode: " + mode, invalid);
        }
    }
}
