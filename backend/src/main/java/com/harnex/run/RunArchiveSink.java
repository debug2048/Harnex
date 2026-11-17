package com.harnex.run;

import com.harnex.conversation.CommitReceipt;

/** Application-owned archive hooks. RunControl remains the sole writer of lifecycle state. */
public interface RunArchiveSink {
    CommitReceipt beforeTerminal(RunHandle run, RunStatus status, String detail);
    default void stateChanged(RunHandle run) {}
}
