package com.harnex.contract;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Request objects reject unknown fields even when the application's mapper is lenient. */
public interface StrictRequest {
    @JsonAnySetter
    default void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown field: " + name);
    }
}
