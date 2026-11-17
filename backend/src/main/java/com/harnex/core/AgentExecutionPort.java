package com.harnex.core;

/** Shared execution boundary for ordinary tasks, Goal steps and delegated work. */
public interface AgentExecutionPort {
    StepResult executeStep(StepRequest request, AgentExecutionContext context);
}
