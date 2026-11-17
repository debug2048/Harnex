package com.harnex.run;

import java.util.LinkedHashMap;
import java.util.Map;

/** Durable exactly-once result for a control-plane write operation. */
public record IdempotencyReceiptRecord(
    int schemaVersion,
    String scope,
    String key,
    String requestHash,
    Map<String, Object> result,
    String resourceId,
    long committedRevision,
    long createdAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public IdempotencyReceiptRecord {
        result = result == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(result));
    }

    public IdempotencyReceiptRecord validated() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported idempotency receipt schemaVersion: " + schemaVersion);
        }
        if (blank(scope) || blank(key) || blank(requestHash) || blank(resourceId)) {
            throw new IllegalArgumentException("incomplete idempotency receipt identity");
        }
        if (committedRevision < 1 || createdAt <= 0) {
            throw new IllegalArgumentException("invalid idempotency receipt counters");
        }
        return this;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
