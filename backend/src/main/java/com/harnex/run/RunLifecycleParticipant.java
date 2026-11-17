package com.harnex.run;

import java.util.Set;
import com.harnex.execution.ExecutionSpec;

/** Optional execution-specific lifecycle state. The root coordinator owns the common reservation. */
public interface RunLifecycleParticipant {
    boolean supports(ExecutionSpec.Kind kind);
    boolean tryReserve(String conversationId);
    void release(String conversationId);
    boolean cancel(String conversationId);
    boolean interrupt(String conversationId);
    Set<String> activeConversationIds();
    default String deletionRejection(String conversationId) { return null; }
}
