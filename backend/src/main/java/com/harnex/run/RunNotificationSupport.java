package com.harnex.run;

/** Optional application adapter. Run admission owns the snapshot; delivery never owns execution. */
public interface RunNotificationSupport {
    RunNotification capture(RunNotification.Mode mode);
    void track(RunHandle run);
}
