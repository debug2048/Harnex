package com.harnex.run;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.harnex.util.Ids;

/** Atomic per-Run projection containing all durable inputs in server sequence order. */
public record RunInputMailboxRecord(
    int schemaVersion,
    String runId,
    String threadId,
    long revision,
    long nextSequence,
    List<RunInputRecord> inputs
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunInputMailboxRecord {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }

    static RunInputMailboxRecord empty(String runId, String threadId) {
        return new RunInputMailboxRecord(CURRENT_SCHEMA_VERSION, runId, threadId, 0L, 1L, List.of());
    }

    public RunInputMailboxRecord validated() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported run input mailbox schemaVersion: " + schemaVersion);
        }
        if (!Ids.isSafeCallId(runId) || !Ids.isSafe(threadId) || revision < 1 || nextSequence < 1) {
            throw new IllegalArgumentException("invalid run input mailbox identity or counters");
        }
        Set<String> ids = new HashSet<>();
        Set<Long> sequences = new HashSet<>();
        long previous = 0L;
        for (RunInputRecord input : inputs) {
            input.validated();
            if (!runId.equals(input.runId()) || !threadId.equals(input.threadId())
                || !ids.add(input.inputId()) || !sequences.add(input.sequence()) || input.sequence() <= previous) {
                throw new IllegalArgumentException("invalid or duplicate run input mailbox entry");
            }
            previous = input.sequence();
        }
        if (nextSequence <= previous) {
            throw new IllegalArgumentException("mailbox nextSequence does not exceed its entries");
        }
        return this;
    }
}
