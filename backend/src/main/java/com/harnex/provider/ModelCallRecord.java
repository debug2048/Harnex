package com.harnex.provider;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.harnex.net.HttpExchangeState.Acceptance;

/** Metadata only: a logical model call and every actual attempt, including failures before the first token. */
public record ModelCallRecord(int schemaVersion, String callId, String runId,
    @JsonProperty("conversationId") String threadId, String stepId,
    String purpose, String requestedProvider, String requestedModel, State state, long revision, long createdAt,
    Long finishedAt, List<Attempt> attempts, boolean usageComplete) {
    public ModelCallRecord { attempts = List.copyOf(attempts); }
    public enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }
    public record Attempt(String attemptId, int index, String provider, String model, String protocol,
        long startedAt, Long finishedAt, Long firstOutputAfterMillis, State state, Acceptance acceptance,
        String upstreamRequestId, String errorCode, long retryAfterMillis, ProviderTokenUsage usage,
        String contextPlanId, Map<String,Long> timings) {
        public Attempt { timings = timings == null ? Map.of() : Map.copyOf(timings); }
        public Attempt(String attemptId,int index,String provider,String model,String protocol,long startedAt,Long finishedAt,Long firstOutputAfterMillis,
            State state,Acceptance acceptance,String upstreamRequestId,String errorCode,long retryAfterMillis,ProviderTokenUsage usage) {
            this(attemptId,index,provider,model,protocol,startedAt,finishedAt,firstOutputAfterMillis,state,acceptance,upstreamRequestId,errorCode,retryAfterMillis,usage,null,Map.of());
        }
    }
}
