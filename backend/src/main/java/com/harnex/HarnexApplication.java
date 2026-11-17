package com.harnex;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.harnex.remote.RemoteRuntimeBootstrap;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class HarnexApplication {

    /**
     * Spring Boot's default config search locations include {@code optional:file:./} and
     * {@code optional:file:./config/}. Because every launcher runs the JVM with cwd = the user's project root (so
     * AGENTS.md discovery and relative file ops work), that default would silently absorb a real Spring project's own
     * {@code ./application.yml} / {@code ./config/application.yml} as Harnex configuration — and since the agent can
     * create/edit those ordinary project files, a restart would turn them into a control-plane injection vector.
     *
     * <p>
     * We pin the search location to classpath-only so the project-root {@code application.yml} is no longer scanned.
     * The project-local {@code agent-config.yml} (config-import method C) is still loaded — it is imported explicitly
     * via {@code spring.config.import} in application.yml and is control-plane write-protected. User-level overrides at
     * {@code ~/.harnex} keep working because launchers inject them via {@code spring.config.additional-location}, which
     * is additive and unaffected by this pin. An operator who deliberately sets {@code spring.config.location} (env
     * var, system property, or CLI arg) is never second-guessed.
     */
    static void lockConfigLocations(String[] args) {
        if (System.getProperty("spring.config.location") != null || System.getenv("SPRING_CONFIG_LOCATION") != null) {
            return;
        }
        for (String a : args) {
            if (a != null && a.startsWith("--spring.config.location")) {
                return;
            }
        }
        System.setProperty("spring.config.location", "optional:classpath:/,optional:classpath:/config/");
    }

    public static void main(String[] args) {
        if (Arrays.stream(args).anyMatch(arg -> "--cli".equals(arg) || arg != null && arg.startsWith("--cli="))) {
            System.err.println("Java --cli has been removed. Use harnex tui for interactive work or harnex cli -p <prompt> for headless execution.");
            System.exit(2);
            return;
        }
        if (args.length > 0 && "--harnex-remote".equals(args[0])) {
            RemoteRuntimeBootstrap.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        lockConfigLocations(args);
        System.setProperty("harnex.hosted", "true");
        SpringApplication.run(HarnexApplication.class, args);
    }
}
