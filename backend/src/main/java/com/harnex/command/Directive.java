package com.harnex.command;

/**
 * Result of {@link SlashCommandService#handle}. Tells the run request service / CLI adapter how to proceed:
 *
 * <ul>
 * <li>{@link Kind#PASSTHROUGH} — not a command (or unknown command); run the original message as a normal turn.</li>
 * <li>{@link Kind#EXPAND} — the command expanded into a message ({@link #text}) that should be run as a normal agent
 * turn (e.g. {@code /skill} pre-injects the skill body, {@code /plan <task>} runs the task in plan-mode).</li>
 * <li>{@link Kind#RESPOND} — the command was fully handled server-side; {@link #text} is an informational message to
 * surface to the user and <strong>no</strong> agent loop runs (no token spend). Used for {@code /skill} (no args)
 * listing, {@code /goal} status, error messages, etc.</li>
 * <li>{@link Kind#GOAL_ADAPTER} — parsed Goal arguments that a transport must pass to the structured Goal service;
 * this directive never mutates Thread state or starts an execution itself.</li>
 * </ul>
 *
 * <p>
 * {@link #notice} is an optional one-line message the caller should surface (as a {@code command_result} event /
 * printed line) regardless of kind — e.g. "Plan mode ON" alongside an EXPAND.
 * </p>
 *
 * <p>
 * Deliberately side-effect-free for display: {@code handle} returns this record rather than emitting events itself, so
 * the same logic drives WebUI (SSE), CLI (stdout) and future entrypoints, and is trivially unit-testable.
 * </p>
 */
public record Directive(Kind kind, String text, String notice) {

    public enum Kind {
        PASSTHROUGH, EXPAND, RESPOND, GOAL_ADAPTER
    }

    public static Directive passthrough() {
        return new Directive(Kind.PASSTHROUGH, null, null);
    }

    public static Directive expand(String text) {
        return new Directive(Kind.EXPAND, text, null);
    }

    public static Directive expand(String text, String notice) {
        return new Directive(Kind.EXPAND, text, notice);
    }

    public static Directive respond(String message) {
        return new Directive(Kind.RESPOND, message, null);
    }

    public static Directive goalAdapter(String arguments) {
        return new Directive(Kind.GOAL_ADAPTER, arguments, null);
    }
}
