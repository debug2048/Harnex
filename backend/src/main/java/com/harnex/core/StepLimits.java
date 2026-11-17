package com.harnex.core;

import com.harnex.config.AgentConfig;

/** Finite local work limits; family and Goal resource budgets apply in addition. */
public record StepLimits(int maxWorkIterations, int finalizationGraceIterations) {
    public StepLimits {
        if (maxWorkIterations < 1 || finalizationGraceIterations < 0
            || (long) maxWorkIterations + finalizationGraceIterations > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid step limits");
        }
    }

    public static StepLimits resolve(AgentConfig config, Integer requested, int defaultIterations) {
        int ceiling = config.getMaxIterations();
        if (ceiling < 1) throw new IllegalArgumentException("max-iterations must be positive");
        int work = requested == null ? Math.min(defaultIterations, ceiling) : requested;
        if (work < 1 || work > ceiling) {
            throw new IllegalArgumentException("maxWorkIterations must be 1.." + ceiling);
        }
        return new StepLimits(work, Math.max(0, config.getFinalizationGraceIterations()));
    }
}
