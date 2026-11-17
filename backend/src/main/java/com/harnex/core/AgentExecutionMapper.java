package com.harnex.core;

import java.util.List;
import com.harnex.config.AgentConfig;
import com.harnex.execution.AgentSpec;
import com.harnex.execution.TextTarget;

/** Converts the public execution specification at the Agent boundary, using the task's selected configuration. */
public final class AgentExecutionMapper {
    private AgentExecutionMapper() {}

    public static AgentExecutionOptions options(AgentSpec spec, AgentConfig config) {
        return new AgentExecutionOptions(spec.readOnly() ? PermissionContext.READ_ONLY : PermissionContext.UNRESTRICTED,
            StepLimits.resolve(config, spec.limits() == null ? null : spec.limits().maxWorkIterations(),
                config.getOrdinaryMaxIterations()), spec.interactionTarget(), spec.planReview());
    }

    public static AgentSpec spec(AgentExecutionOptions options, TextTarget target,
        List<String> toolsets, String permissionMode) {
        return new AgentSpec(target, toolsets, permissionMode, !options.permissions().allowMutations(),
            new AgentSpec.Limits(options.limits().maxWorkIterations()), options.planReview(), options.interactionTarget(), null);
    }
}
