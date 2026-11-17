package com.harnex.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Reports {@code agent-config.yml} schema-version mismatches at startup.
 *
 * <p>
 * An unset version ({@code 0}) is treated as v1 with an INFO hint. Matching
 * {@link #CURRENT_SCHEMA_VERSION} is silent; older versions log WARN and newer versions log ERROR.
 * This check neither rewrites configuration nor rejects startup, allowing coordinated config/binary rollouts.
 */
@Slf4j
@Component
public class ConfigMigrator {

    /** Current schema version this binary understands. Bump whenever a breaking change lands. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final AgentConfig cfg;

    @Autowired
    public ConfigMigrator(AgentConfig cfg) {
        this.cfg = cfg;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        int declared = cfg.getVersion();
        if (declared == 0) {
            log.info(
                "agent-config.yml has no `harnex.version` field — treating as v{}. "
                    + "Add `harnex.version: {}` at the top of your config to silence this hint.",
                CURRENT_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
            return;
        }
        if (declared == CURRENT_SCHEMA_VERSION) {
            return;
        }
        if (declared < CURRENT_SCHEMA_VERSION) {
            log.warn(
                "agent-config.yml declares harnex.version={} but this binary expects v{}. "
                    + "No automatic migration is currently registered (this binary supports only v1). "
                    + "Re-export your config from a newer template or bump the field manually.",
                declared, CURRENT_SCHEMA_VERSION);
            return;
        }
        log.error("agent-config.yml declares harnex.version={} but this binary only understands up to v{}. "
            + "Either upgrade the binary or roll the config back. Continuing with current binary "
            + "behaviour, but unknown keys may have been silently ignored.", declared, CURRENT_SCHEMA_VERSION);
    }
}