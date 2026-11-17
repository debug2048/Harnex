package com.harnex.config;

/** Encode a filesystem directory as literal text in Logback's rolling filename language. */
final class LogArchivePattern {
    private LogArchivePattern() {}

    static String forDirectory(String directory) {
        // FileNamePattern turns backslashes into '/' before parsing, so \% cannot escape a path.
        // A date formatter containing only '%' emits that constant. Mark it auxiliary so
        // the real yyyy-MM-dd token still determines the rollover period and retention.
        return directory.replace('\\', '/').replace("%", "%d{'%',aux}")
            + "/harnex-%d{yyyy-MM-dd}.%i.log.gz";
    }
}
