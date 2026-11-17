package com.harnex.command;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.PlanModeService;
import com.harnex.core.PlanReviewService;
import com.harnex.model.ConversationThread;
import com.harnex.skill.Skill;
import com.harnex.skill.SkillLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * The single server-side slash-command expander. {@code RunRequestService} handles calls from GUI, native TUI
 * and headless clients so command expansion stays inside the Runtime.
 *
 * <p>
 * Parsing: after stripping leading whitespace, a message that starts with {@code /} whose first token matches a
 * registered command is handled; otherwise {@link Directive#passthrough()} (so unknown {@code /xxx} and ordinary text
 * are never swallowed). The first token is the command name; the remainder (original internal spacing preserved) is the
 * argument string.
 * </p>
 */
@Slf4j
@Component
@ProjectRuntime
public class SlashCommandService {
    private final SkillLoader skillLoader;
    private final PlanModeService planMode;
    private final PlanReviewService planReview;
    private final SlashCommandRegistry registry;

    public SlashCommandService(SkillLoader skillLoader, PlanModeService planMode, PlanReviewService planReview,
        SlashCommandRegistry registry) {
        this.skillLoader = skillLoader;
        this.planMode = planMode;
        this.planReview = planReview;
        this.registry = registry;
    }

    /**
     * Resolve a raw user message into a {@link Directive}. Goal commands are parsing-only here and are executed by
     * GoalSlashService through the same structured Goal resource used by REST clients.
     */
    public Directive handle(ConversationThread thread, String raw) {
        if (raw == null) {
            return Directive.passthrough();
        }
        String trimmed = raw.stripLeading();
        if (!trimmed.startsWith("/")) {
            return Directive.passthrough();
        }
        String[] split = splitFirstToken(trimmed.substring(1));
        String name = split[0].toLowerCase(Locale.ROOT);
        String args = split[1];
        // `/skills` is a convenience alias for the `/skill` listing.
        if ("skills".equals(name)) {
            name = "skill";
            args = "";
        }
        if (registry.get(name) == null) {
            return Directive.passthrough();
        }
        return switch (name) {
            case "skill" -> handleSkill(thread, args);
            case "plan" -> handlePlan(thread, args);
            case "goal" -> handleGoal(thread, args);
            default -> Directive.passthrough();
        };
    }

    // ── /skill ──────────────────────────────────────────────────────────
    private Directive handleSkill(ConversationThread thread, String args) {
        String a = args == null ? "" : args.strip();
        if (a.isEmpty()) {
            return Directive.respond(renderSkillList(null));
        }
        String[] parts = splitFirstToken(a);
        String name = parts[0];
        String input = parts[1];
        Optional<Skill> skill = skillLoader.get(name);
        if (skill.isEmpty()) {
            return Directive.respond("Unknown skill: " + name + "\n\n" + renderSkillList("Available skills"));
        }
        String body;
        try {
            body = skillLoader.readBody(name);
        } catch (Exception e) {
            log.warn("/skill readBody {} failed: {}", name, e.toString());
            return Directive.respond("Failed to read skill '" + name + "': " + e.getMessage());
        }
        // Inject skill base path so agent resolves relative script references correctly
        String basePath = skill
            .map(Skill::getPath)
            .map(Path::getParent)
            .map(Path::toString)
            .orElse(null);
        if (basePath != null) {
            body = "[skill-base-path: " + basePath + "]\n\n" + body;
        }
        // Neutralise any literal closing tag in the body so a crafted SKILL.md cannot "escape" the
        // wrapper and confuse the model (mirrors PromptBuilder.appendSection's defence).
        String safeBody = body == null ? "" : body.replace("</skill>", "<\u200b/skill>");
        String expanded = "<skill name=\"" + name + "\">\n" + safeBody + "\n</skill>\n\nTask: "
            + (input.isBlank() ? "Apply the above skill." : input);
        // Mark ThreadState so downstream components (PromptBuilder, SystemReminderMiddleware) can
        // detect /skill invocation without re-parsing the message content.
        thread.getState().getExtras().put("skillDirective", true);
        return Directive.expand(expanded);
    }

    private String renderSkillList(String heading) {
        var skills = skillLoader.list();
        if (skills.isEmpty()) {
            return "No skills found. Drop a SKILL.md under your skills directory and rescan.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(heading == null ? "Available skills (use /skill <name> [input]):" : heading).append('\n');
        skills.stream().sorted(Comparator.comparing(Skill::getName)).forEach(s -> sb.append("- ").append(s.getName())
            .append(": ").append(s.getDescription() == null ? "" : s.getDescription()).append('\n'));
        return sb.toString().stripTrailing();
    }

    // ── /plan ───────────────────────────────────────────────────────────
    private Directive handlePlan(ConversationThread thread, String args) {
        String tid = thread.getId();
        String task = args == null ? "" : args.strip();
        // Extract the first token so that "/plan cancel extra-text" still hits the cancel branch.
        String firstToken = task.isEmpty() ? "" : splitFirstToken(task)[0].toLowerCase(Locale.ROOT);
        switch (firstToken) {
            case "cancel", "off", "exit", "stop", "clear" -> {
                if (planMode.isActive(tid)) {
                    planMode.disable(tid);
                    planReview.cancelPendingForThread(tid);
                    return Directive.respond("Plan mode cancelled. No plan will be executed.");
                }
                return Directive.respond("Plan mode is not active.");
            }
            default -> {
                // Continue below.
            }
        }
        planMode.enable(tid);
        String notice = "Plan mode ON — I will only use read-only tools, then submit a plan for your approval before "
            + "executing.";
        if (task.isEmpty()) {
            return Directive.respond(notice + " Tell me what you'd like me to plan.");
        }
        // Run the task in plan-mode: the system prompt (<plan_mode_state>) tells the agent to explore
        // read-only, then call submit_plan.
        return Directive.expand(task, notice);
    }

    // ── /goal ───────────────────────────────────────────────────────────
    private Directive handleGoal(ConversationThread thread, String args) {
        return Directive.goalAdapter(args == null ? "" : args.strip());
    }

    // ── parsing helper ──────────────────────────────────────────────────
    /**
     * Split into {@code [head, rest]} on the first run of whitespace. The separating whitespace is consumed, but
     * everything after it (the argument string) keeps its original internal spacing.
     */
    static String[] splitFirstToken(String s) {
        int i = 0;
        int len = s.length();
        while (i < len && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        String head = s.substring(0, i);
        int j = i;
        while (j < len && Character.isWhitespace(s.charAt(j))) {
            j++;
        }
        return new String[] {head, s.substring(j)};
    }

    /** Visible for tests: comma-joined registered command names. */
    String registeredNames() {
        StringJoiner sj = new StringJoiner(",");
        registry.list().forEach(c -> sj.add(c.name()));
        return sj.toString();
    }
}
