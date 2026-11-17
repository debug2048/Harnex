package com.harnex.process;

import java.io.IOException;
import java.util.Locale;

/**
 * Single boundary for JVM child-process environment hygiene. The desktop host injects short-lived macOS Keychain
 * material only so Spring can resolve Provider configuration; no command, MCP server, Git helper or extractor spawned
 * by the Runtime may inherit those variables.
 */
public final class ChildProcessEnvironment {

    public static final String KEYCHAIN_PROVIDER_PREFIX = "HARNEX_KEYCHAIN_PROVIDER_";

    private ChildProcessEnvironment() {}

    public static ProcessBuilder sanitize(ProcessBuilder builder) {
        builder.environment().keySet().removeIf(name -> name.startsWith(KEYCHAIN_PROVIDER_PREFIX)
            || name.startsWith("HARNEX_INTERNAL_") || name.startsWith("HARNEX_AUTH_")
            || name.equals("HARNEX_DOMAIN_MANIFEST") || name.equals("HARNEX_RUNTIME_DOMAIN_DIR"));
        return builder;
    }

    public static Process start(ProcessBuilder builder) throws IOException {
        return sanitize(builder).start();
    }

    /** Agent commands must not inherit Runtime control credentials or model-provider secrets. */
    public static ProcessBuilder sanitizeCommand(ProcessBuilder builder) {
        sanitize(builder);
        builder.environment().keySet().removeIf(name -> {
            String key = name.toUpperCase(Locale.ROOT);
            return key.startsWith("HARNEX_INTERNAL_") || key.startsWith("HARNEX_AUTH_")
                || key.equals("HARNEX_DOMAIN_MANIFEST") || key.equals("HARNEX_RUNTIME_DOMAIN_DIR")
                || key.equals("HARNEX_CONFIG_FILES") || key.equals("SPRING_CONFIG_ADDITIONAL_LOCATION")
                || key.matches("(?:OPENAI|ANTHROPIC|GEMINI|MOONSHOT|XAI|FALLBACK|DEEPSEEK)_(?:API_KEY|ACCESS_TOKEN|AUTH_TOKEN)");
        });
        return builder;
    }
}
