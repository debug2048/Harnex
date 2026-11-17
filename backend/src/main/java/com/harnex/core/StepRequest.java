package com.harnex.core;

import com.harnex.model.ConversationThread;

public record StepRequest(ConversationThread thread, AgentExecutionOptions options) {
    public StepRequest {
        if (thread == null || options == null) throw new IllegalArgumentException("thread and execution options required");
    }
}
