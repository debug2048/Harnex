package com.harnex.core;

import com.harnex.execution.PlanReviewPolicy;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independent, immutable execution inputs. No task/source preset selects an Agent algorithm. */
public record AgentExecutionOptions(PermissionContext permissions, StepLimits limits, String interactionTarget,
    PlanReviewPolicy planReview) {
    public static final String LOCAL_API = "local-api";
    public static final String IM = "im";

    public AgentExecutionOptions {
        if (planReview == null) planReview = PlanReviewPolicy.REQUIRED;
        if (permissions == null || limits == null) throw new IllegalArgumentException("permissions and limits required");
        if (interactionTarget != null && !LOCAL_API.equals(interactionTarget) && !IM.equals(interactionTarget)) {
            throw new IllegalArgumentException("unknown interactionTarget: " + interactionTarget);
        }
    }

    public AgentExecutionOptions(PermissionContext permissions, StepLimits limits, String interactionTarget) {
        this(permissions, limits, interactionTarget, PlanReviewPolicy.REQUIRED);
    }

    public AgentExecutionOptions withLimits(StepLimits value) {
        return new AgentExecutionOptions(permissions, value, interactionTarget, planReview);
    }

    public String instructions() {
        String planning = planReview == PlanReviewPolicy.AUTOMATIC
            ? "Plans are self-managed unless the user explicitly requires review or an active Plan gate requires it. Choose the best plan, record it with submit_plan, and continue without human plan approval; set require_review=true for an explicit user review requirement. "
            : "Submitted plans require human review before execution; unreviewed plans remain read-only. ";
        return planning + "Plan acceptance never grants tool, command or path permissions. "
            + (interactionTarget == null
                ? "No reply handler is available. Decide optional details within existing authority; use ask_user(required=true) only for indispensable missing information."
                : "A reply handler is available. Distinguish indispensable questions (required=true) from optional clarifications (required=false).");
    }

    public String subtaskInstructions() {
        return "Your audience is the parent agent. Plan internally within the delegated scope and existing permissions. "
            + "Do not ask the user questions or start a separate user plan review. If indispensable context is missing, "
            + "ask_user(required=true) returns that requirement to your parent; optional details should be decided locally. "
            + "Use submit_plan(require_review=true) to hand a proposal to the parent without executing it. "
            + "A planning-only task ends by returning its plan. Tool/path approvals remain separate and cannot be self-granted.";
    }

    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("permissions", permissions);
        view.put("limits", limits);
        view.put("interactionTarget", interactionTarget);
        view.put("planReview", planReview.name());
        return view;
    }
}
