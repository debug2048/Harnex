package com.harnex.execution;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.harnex.contract.StrictRequest;

/** Explicit product capability boundary, independent of origin, Goal ownership and permission. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AgentSpec.class, name = "AGENT"),
    @JsonSubTypes.Type(value = ConversationSpec.class, name = "CONVERSATION")
})
public sealed interface ExecutionSpec extends StrictRequest permits AgentSpec, ConversationSpec {
    Kind kind();

    enum Kind { AGENT, CONVERSATION }
}
