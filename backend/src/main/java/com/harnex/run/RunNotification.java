package com.harnex.run;

import io.swagger.v3.oas.annotations.media.Schema;

/** Server-owned, credential-free delivery intent frozen with Run admission. */
@Schema(name = "RunNotification")
public record RunNotification(Mode mode, Recipient recipient) {
    @Schema(name = "NotificationMode")
    public enum Mode { OFF, ALL, FAILURES;
        public static Mode orOff(Mode value) { return value == null ? OFF : value; }
    }
    @Schema(name = "NotificationRecipient")
    public record Recipient(String id, String channel, String account, String userId, String chatId,
        String label, String chatType) {}
}
