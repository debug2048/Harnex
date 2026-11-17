package com.harnex.workspace;

import org.springframework.stereotype.Component;
import com.harnex.config.ProjectRuntime;
import com.harnex.config.RuntimeProfile;
import com.harnex.execution.ScopeIdentity;

/** Project-facing projection of the one immutable identity owned by this Runtime. */
@Component
@ProjectRuntime
public final class WorkspaceIdentity {
    private final ScopeIdentity scope;
    public WorkspaceIdentity(ScopeIdentity scope) {
        if (scope.profile() != RuntimeProfile.PROJECT) throw new IllegalArgumentException("Project scope required");
        this.scope = scope;
    }
    public String workspaceId() { return scope.scopeId(); }
    public String instanceId() { return scope.instanceId(); }
}
