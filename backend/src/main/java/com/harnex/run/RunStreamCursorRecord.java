package com.harnex.run;

import com.harnex.util.Ids;

/** Durable sequence high-water mark kept separate from the business {@link RunRecord#revision()}. */
public record RunStreamCursorRecord(
    int schemaVersion,
    String runId,
    long cursorRevision,
    long streamEpoch,
    long reservedThrough
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunStreamCursorRecord validated() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported run stream schemaVersion: " + schemaVersion);
        }
        if (!Ids.isSafeCallId(runId)) throw new IllegalArgumentException("invalid stream runId: " + runId);
        if (cursorRevision < 1 || streamEpoch < 1 || reservedThrough < 0) {
            throw new IllegalArgumentException("invalid run stream cursor counters");
        }
        return this;
    }
}
