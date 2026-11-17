package com.harnex.interaction;

import java.util.List;

/** Optional execution-specific pending decisions, captured at the common presentation boundary. */
public interface RunInteractionSource {
    List<PendingInteraction> collect(String conversationId);
}
