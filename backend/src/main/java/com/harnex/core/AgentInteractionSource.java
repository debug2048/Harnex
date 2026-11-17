package com.harnex.core;

import java.util.*;
import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.interaction.*;
import com.harnex.tool.approval.ApprovalService;
import com.harnex.tool.approval.UserPromptService;

@Component
@ProjectRuntime
public final class AgentInteractionSource implements RunInteractionSource {
    private final ApprovalService approvals;
    private final PlanReviewService plans;
    private final UserPromptService prompts;
    public AgentInteractionSource(ApprovalService approvals, PlanReviewService plans, UserPromptService prompts) {
        this.approvals = approvals; this.plans = plans; this.prompts = prompts;
    }
    @Override
    public List<PendingInteraction> collect(String id) {
        List<PendingInteraction> values = new ArrayList<>();
        for (var pending : approvals.listPending(id)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", pending.toolName() == null ? "" : pending.toolName());
            payload.put("arguments", pending.arguments() == null ? "" : pending.arguments()); payload.put("outsideWrite", pending.outsideWrite());
            if (pending.sourceCallId() != null) payload.put("sourceCallId", pending.sourceCallId());
            if (pending.subagentThreadId() != null) payload.put("subagentThreadId", pending.subagentThreadId());
            values.add(new PendingInteraction("approval", pending.threadId(), pending.callId(), pending.createdAt(), payload));
        }
        plans.listPending(id).forEach(pending -> values.add(new PendingInteraction("plan_review", pending.threadId(), pending.callId(), pending.createdAt(), pending.payload())));
        prompts.listPending(id).forEach(pending -> values.add(new PendingInteraction("question", pending.threadId(), pending.callId(), pending.createdAt(), pending.payload())));
        values.sort(Comparator.comparingLong(PendingInteraction::createdAt)); return List.copyOf(values);
    }
}
