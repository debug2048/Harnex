package com.harnex.execution;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class RunExecutorRegistry {
    private final List<RunExecutor> executors;
    public RunExecutorRegistry(List<RunExecutor> executors) { this.executors = List.copyOf(executors); }
    public RunExecutor resolve(ExecutionSpec spec) {
        if (spec == null) throw new IllegalArgumentException("execution is required");
        List<RunExecutor> matches = executors.stream().filter(executor -> executor.supports(spec)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("execution is unavailable in this Runtime");
        return matches.get(0);
    }
}
