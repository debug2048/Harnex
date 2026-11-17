package com.harnex.run;

/** Process-local execution attachment. Public run ownership never contains a model or Agent context. */
public interface RunWorker extends AutoCloseable {
    void cancel();
    default void interruptExecution() { cancel(); }
    default boolean cancelled() { return false; }
    default boolean pauseAcknowledged() { return false; }
    default boolean stepCancelAcknowledged() { return false; }
    default boolean failed() { return false; }
    @Override void close();
}
