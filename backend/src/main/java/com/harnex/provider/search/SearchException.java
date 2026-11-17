package com.harnex.provider.search;

/**
 * Failure raised by a {@link SearchBackend}. The {@code terminal} flag is the key signal handed up to
 * {@code WebSearchTool}: it decides whether the model is told "do NOT retry — answer from your own knowledge / use
 * ask_user" (terminal: no key, 401/403 auth, 402 billing, unknown provider) versus "transient — may retry once or
 * continue" (timeout, 5xx, 429, network). Misclassifying a terminal failure as transient is the main failure mode we
 * guard against: the model would otherwise keep re-issuing a query that can never succeed.
 */
public class SearchException extends Exception {

    private final boolean terminal;

    public SearchException(String message, boolean terminal) {
        super(message);
        this.terminal = terminal;
    }

    public SearchException(String message, boolean terminal, Throwable cause) {
        super(message, cause);
        this.terminal = terminal;
    }

    /** @return true if retrying cannot help (auth / billing / misconfig); false for transient faults. */
    public boolean isTerminal() {
        return terminal;
    }
}
