package com.harnex.skill;

import java.nio.file.Path;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A single SKILL.md unit. Body is loaded on demand. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    /** Stable slug derived from the containing directory. */
    private String name;
    /** One-line summary (front-matter `description` or first body line). */
    private String description;
    /** Optional hint describing when to use (front-matter `when_to_use`). */
    private String whenToUse;
    /** On-disk path to the SKILL.md. */
    private Path path;
    /** Body (without front-matter). Null until {@link SkillLoader#readBody} is called. */
    private String body;
    /** USER or WORKSPACE. */
    private String source;
    /** True when a higher-precedence skill with the same name exists. */
    private boolean overridden;
    /** Explicit source-local switch; defaults to true. */
    private boolean enabled = true;
    /** Selected by precedence and enabled. */
    private boolean effective = true;

    public Skill(String name, String description, String whenToUse, Path path, String body) {
        this.name = name;
        this.description = description;
        this.whenToUse = whenToUse;
        this.path = path;
        this.body = body;
    }
}
