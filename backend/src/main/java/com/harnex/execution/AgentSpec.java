package com.harnex.execution;

import java.util.List;
import com.harnex.contract.StrictRequest;

/** Agent-only configuration. None of these settings grant a permission the host does not possess. */
public record AgentSpec(TextTarget text, List<String> toolsets, String permissionMode, boolean readOnly,
    Limits limits, PlanReviewPolicy planReview, String interactionTarget, String ideContext) implements ExecutionSpec {
    public AgentSpec {
        toolsets = toolsets == null ? null : List.copyOf(toolsets);
        if (interactionTarget != null && !interactionTarget.equals("local-api") && !interactionTarget.equals("im")) {
            throw new IllegalArgumentException("unsupported interactionTarget");
        }
        if (permissionMode != null && !List.of("ask", "accept-edits", "yolo").contains(permissionMode)) {
            throw new IllegalArgumentException("invalid permissionMode");
        }
    }

    @Override public Kind kind() { return Kind.AGENT; }

    public record Limits(Integer maxWorkIterations) implements StrictRequest {
        public Limits {
            if (maxWorkIterations != null && maxWorkIterations < 1) {
                throw new IllegalArgumentException("maxWorkIterations must be positive");
            }
        }
    }
}
