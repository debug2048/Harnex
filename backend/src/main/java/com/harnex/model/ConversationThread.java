package com.harnex.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

@Data
public class ConversationThread {
    private String id = UUID.randomUUID().toString();
    private String title = "新会话";
    /** Unabridged generated title used for hover/accessibility while {@link #title} stays compact in dense lists. */
    private String fullTitle;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private List<ChatMessage> messages = new ArrayList<>();
    /** Small, runtime-only projection rebuilt from the selected public history before each root step. */
    @JsonIgnore
    private HumanTurnContext humanTurnContext;
    private ThreadState state = new ThreadState();
    private String model;
    /** Explicit provider affinity — see {@link com.harnex.execution.TextTarget#provider()}. */
    private String provider;
    /** Sticky per-conversation reasoning override; null delegates to the Provider configuration. */
    private String reasoningEffort;
    private List<String> toolsets = new ArrayList<>();
    private int subagentDepth = 0;
    /** Runtime-only owner for user interactions emitted by a hidden sub-agent thread. */
    @JsonIgnore
    private String interactionRootThreadId;
    /**
     * Per-thread iteration cap override. {@code 0} (default) makes a normal/root thread inherit
     * {@code harnex.max-iterations}. {@link com.harnex.subagent.SubAgentExecutor} always sets a positive effective
     * value from {@code harnex.subagent.max-iterations} (optionally lowered by the delegate call), so child threads do
     * not inherit the root ceiling. Read by {@link com.harnex.core.AgentLoop#run}.
     */
    private int maxIterations = 0;
}
