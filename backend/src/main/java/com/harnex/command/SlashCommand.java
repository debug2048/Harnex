package com.harnex.command;

/**
 * Metadata for a single slash command, surfaced to the frontend via {@code GET /api/commands} so it can render
 * autocomplete. Pure presentation — the actual expansion/execution lives in {@link SlashCommandService}.
 *
 * @param name display + match name without the leading slash (e.g. {@code "skill"}).
 * @param description one-line help shown in the autocomplete dropdown.
 * @param argHint hint for the argument portion (e.g. {@code "<name> [input]"}); may be empty.
 * @param completion optional secondary-completion source key the frontend understands ({@code "skills"} → complete the
 *            first arg against {@code GET /api/skills}); {@code null} for none.
 */
public record SlashCommand(String name, String description, String argHint, String completion) {

    public static SlashCommand of(String name, String description, String argHint, String completion) {
        return new SlashCommand(name, description, argHint, completion);
    }
}