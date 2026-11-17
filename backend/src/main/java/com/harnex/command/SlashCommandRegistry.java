package com.harnex.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;

/**
 * Registry of the slash commands the agent understands. Seeded once at construction with the three built-ins
 * ({@code /skill}, {@code /plan}, {@code /goal}); {@link #register} is reserved for a future custom-command framework.
 *
 * <p>
 * Writes happen only at construction (single-threaded); {@link #list()} returns a defensive copy so concurrent readers
 * (request threads hitting {@code GET /api/commands}) are never exposed to a half-built map.
 * </p>
 */
@Component
@ProjectRuntime
public class SlashCommandRegistry {

    private final Map<String, SlashCommand> byName = new LinkedHashMap<>();

    public SlashCommandRegistry() {
        register(
            SlashCommand.of("skill", "Reference a skill: pre-inject its body, then your input. No args → list skills.",
                "<name> [input]", "skills"));
        register(SlashCommand.of("plan",
            "Enter plan mode: explore read-only, then submit a plan for your approval before executing.",
            "[task] | cancel", null));
        register(SlashCommand.of("goal",
            "Set an autonomous goal; the agent keeps working (with budget guard rails) until it is met.",
            "<objective> [+budget] | clear | pause | resume", null));
    }

    /** Register (or override) a command. Intended for the future custom-command framework; built-ins seeded in ctor. */
    public void register(SlashCommand cmd) {
        if (cmd != null && cmd.name() != null) {
            byName.put(cmd.name(), cmd);
        }
    }

    public Collection<SlashCommand> list() {
        return new ArrayList<>(byName.values());
    }

    public SlashCommand get(String name) {
        return name == null ? null : byName.get(name);
    }
}
