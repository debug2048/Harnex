package com.harnex.core;

import java.util.Set;
import org.springframework.stereotype.Component;
import com.harnex.config.ProjectRuntime;
import com.harnex.execution.ExecutionSpec;
import com.harnex.run.ActiveAgentRegistry;
import com.harnex.run.RunLifecycleParticipant;
import com.harnex.session.SessionStore;

/** Agent-only reservation, permission and deletion lifecycle extension. */
@Component
@ProjectRuntime
public final class AgentRunLifecycle implements RunLifecycleParticipant {
    private final ActiveAgentRegistry active;
    private final SessionStore sessions;
    private final PlanModeService plans;
    private final PlanReviewService reviews;
    public AgentRunLifecycle(ActiveAgentRegistry active, SessionStore sessions, PlanModeService plans, PlanReviewService reviews) {
        this.active = active; this.sessions = sessions; this.plans = plans; this.reviews = reviews;
    }
    @Override public boolean supports(ExecutionSpec.Kind kind) { return kind == ExecutionSpec.Kind.AGENT; }
    @Override public boolean tryReserve(String id) { return active.tryReserve(id); }
    @Override public void release(String id) { active.releaseReservation(id); }
    @Override public boolean cancel(String id) {
        boolean signalled = active.cancel(id);
        plans.disable(id); reviews.cancelPendingForThread(id);
        return signalled;
    }
    @Override public boolean interrupt(String id) { return active.interruptExecution(id); }
    @Override public Set<String> activeConversationIds() { return active.activeThreadIds(); }
    @Override public String deletionRejection(String id) {
        return active.isTombstoned(id) ? "thread_deleted" : sessions.isDeletedRecently(id) ? "thread_deleted_persisted" : null;
    }
}
