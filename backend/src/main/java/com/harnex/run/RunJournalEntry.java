package com.harnex.run;

import com.harnex.util.Ids;

/** One append-only write-ahead marker for a cross-projection run operation. */
public record RunJournalEntry(
    int schemaVersion,
    String operationId,
    String runId,
    Kind kind,
    long expectedRevision,
    Phase phase,
    long ts
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunJournalEntry validated() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported run journal schemaVersion: " + schemaVersion);
        }
        if (!Ids.isSafeCallId(operationId) || !Ids.isSafeCallId(runId)) {
            throw new IllegalArgumentException("invalid run journal identity");
        }
        if (kind == null || phase == null || expectedRevision < 0 || ts <= 0) {
            throw new IllegalArgumentException("invalid run journal fields");
        }
        return this;
    }

    public enum Kind {
        CREATE_RUN,
        UPDATE_RUN
    }

    public enum Phase {
        PREPARED,
        COMMITTED,
        ABORTED
    }
}
