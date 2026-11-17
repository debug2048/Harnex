package com.harnex.core;

import java.util.*;
import org.springframework.stereotype.Component;
import com.harnex.config.ProjectRuntime;
import com.harnex.conversation.*;
import com.harnex.net.HttpExchangeState;
import com.harnex.provider.*;
import com.harnex.run.*;
import com.harnex.tool.budget.TokenCounter;

/** Records the Agent's existing context projection without importing project instructions into ordinary conversation. */
@Component @ProjectRuntime
public final class AgentContextAdapter {
    private final ConversationTextSnapshots snapshots;
    private final ContextPlanStore plans;
    private final RunRegistry runs;
    private final ConversationRepository archive;
    private final ContextTokens tokenEstimates;
    public AgentContextAdapter(ConversationTextSnapshots snapshots,ContextPlanStore plans,RunRegistry runs,ConversationRepository archive,TokenCounter counter) {
        this.snapshots = snapshots; this.plans = plans; this.runs = runs; this.archive = archive;
        this.tokenEstimates = new ContextTokens(counter);
    }
    public void validate(String runId,String provider,String model) {
        var run = runs.get(runId);
        if (run == null || run.getSubmission() == null) return;
        String reference = run.getSubmission().request().configurationRef();
        if (reference == null) throw new ProviderException("text_configuration_unavailable","The submitted Agent configuration is missing",
            HttpExchangeState.Acceptance.NOT_SENT,null,0);
        snapshots.requireTarget(snapshots.load(reference),provider,model);
    }
    public void record(String runId,String provider,String model,ModelProvider.ChatRequest request) {
        try { recordContext(runId,provider,model,request); }
        catch (ProviderException failure) {
            if (Set.of("context_length_exceeded","unsupported_attachment").contains(failure.code)) throw failure;
        } catch (RuntimeException ignored) { /* Diagnostic failure cannot repeat or cancel a provider request. */ }
    }
    private void recordContext(String runId,String provider,String model,ModelProvider.ChatRequest request) {
        var owner = ProviderCallScope.current(); var call = ProviderCallScope.currentCall();
        if (owner == null || call == null) return;
        var target = snapshots.currentTarget(provider,model,request.getMaxTokens());
        List<ConversationContextPlanner.Block> blocks = new ArrayList<>(); long tokens = 0; int index = 0;
        for (var message : request.getMessages()) {
            long estimate = tokenEstimates.message(message, model);
            tokens += estimate;
            blocks.add(new ConversationContextPlanner.Block("agent-message",message.getRole() + ":" + index++,
                IdempotencyReceiptStore.requestHash(message),true,null,estimate));
        }
        long schema = tokenEstimates.tools(request.getTools(), model);
        tokens += schema; blocks.add(new ConversationContextPlanner.Block("tool-schema","agent-tools",IdempotencyReceiptStore.requestHash(request.getTools()),true,null,schema));
        var budget = snapshots.outputBudget(provider,model,request.getMaxTokens());
        int output = budget.reserve();
        String leaf = null; List<String> messages = List.of();
        var run = runs.get(runId);
        if (run != null && run.getSubmission() != null) {
            var submission = run.getSubmission(); leaf = submission.request().parentMessageId();
            var turn = archive.findTurn(submission.scopeId(),run.getThreadId(),submission.turnId()).orElse(null);
            if (turn != null) messages = List.of(turn.userMessageId());
        }
        String id = "ctx_" + UUID.randomUUID().toString().replace("-","");
        plans.record(new ConversationContextPlanner.Trace(id,runId,"agent-existing-context-v1",provider,model,messages,leaf,
            Math.max(0,target.contextWindow()-output-target.safetyReserve()),tokens,false,"tokenizer_or_script_estimate_with_protocol_and_modality_reserves"
                + (budget.estimated() ? ";server_default_output_reserve_estimate" : ""),
            output,target.safetyReserve(),null,blocks,call.callId(),call.attemptId(),System.currentTimeMillis()));
        request.setContextPlanId(id);
        if (run != null && run.getSubmission() != null && tokens + output + target.safetyReserve() > target.contextWindow()) {
            throw new ProviderException("context_length_exceeded","The Agent context exceeds the actual target model's input budget",HttpExchangeState.Acceptance.NOT_SENT,null,0);
        }
        for (var message : request.getMessages()) {
            if ("harnex-image-inspection".equals(message.getChannel()) && !target.modalities().contains("image")) {
                throw new ProviderException("unsupported_attachment","The fallback model cannot inspect the supplied images",HttpExchangeState.Acceptance.NOT_SENT,null,0);
            }
        }
    }
}
