package com.harnex.core;

/**
 * Minimal core-facing view of thread execution state.
 *
 * <p>The Agent loop only needs to know whether a thread was deleted while it was running. Keeping that
 * capability behind this port prevents the core loop from depending on the application-layer run registry.</p>
 */
@FunctionalInterface
public interface ThreadExecutionGuard {

    boolean isTombstoned(String threadId);
}
