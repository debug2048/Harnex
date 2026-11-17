package com.harnex.util;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Glob-pattern compilation helper that papers over two well-known JDK {@link PathMatcher} footguns when matching
 * workspace-relative paths:
 *
 * <ol>
 * <li>{@code **\/X} requires AT LEAST ONE segment before {@code X} — so a top-level {@code README.md} is not matched by
 * {@code **\/*.md}, and a top-level {@code node_modules/foo.js} is not matched by {@code **\/node_modules/**}. Files at
 * the workspace root therefore silently fall through every "find me everything" search.</li>
 * <li>{@code X/**} requires AT LEAST ONE segment AFTER {@code X/} — so the directory entry {@code target} itself is not
 * matched by {@code target/**}, leaving an empty {@code target/} entry in tar archives even though its contents are
 * correctly skipped.</li>
 * </ol>
 *
 * <p>
 * Operators consistently get bitten by these because the patterns LOOK like shell globs (where {@code **\/*.md}
 * obviously includes {@code README.md}). Rather than ask every operator to spell out every variant, this helper
 * auto-derives the siblings: a single pattern {@code **\/foo/**} expands to {@code **\/foo/**}, {@code **\/foo},
 * {@code foo/**}, and {@code foo}, so it matches the directory itself, files inside it, and the same at the workspace
 * root — all from one YAML / API entry.
 *
 * <p>
 * Returned matchers also normalise backslash to forward-slash before delegating, so Windows callers don't have to
 * remember to do it. Patterns that fail to compile are dropped silently — bad operator input must not break snapshots /
 * search.
 */
public final class GlobMatchers {

    private GlobMatchers() {}

    /** Compile one pattern (with sibling derivation) into a composite matcher. */
    public static PathMatcher compile(String pattern) {
        return compileAny(List.of(pattern));
    }

    /**
     * Compile a collection of patterns into a single matcher that returns {@code true} when ANY input pattern (or any
     * of its derived siblings) matches the relative path.
     */
    public static PathMatcher compileAny(Collection<String> patterns) {
        if (patterns == null || patterns.isEmpty())
            return p -> false;
        FileSystem fs = FileSystems.getDefault();
        LinkedHashSet<String> effective = new LinkedHashSet<>();
        for (String g : patterns) {
            if (g == null || g.isBlank())
                continue;
            effective.add(g);
            // Strip trailing `/**` so `target/**` also matches the dir entry `target` itself.
            if (g.endsWith("/**")) {
                effective.add(g.substring(0, g.length() - 3));
            }
            if (g.startsWith("**/")) {
                String rootForm = g.substring(3);
                effective.add(rootForm);
                if (rootForm.endsWith("/**")) {
                    effective.add(rootForm.substring(0, rootForm.length() - 3));
                }
            }
        }
        List<PathMatcher> compiled = new ArrayList<>(effective.size());
        for (String g : effective) {
            try {
                compiled.add(fs.getPathMatcher("glob:" + g));
            } catch (IllegalArgumentException ignored) {
                // bad pattern — skip; matching simply doesn't fire for it.
            }
        }
        if (compiled.isEmpty())
            return p -> false;
        return rel -> {
            // Normalise backslash → slash so Windows callers don't double-up.
            Path normalised = rel.toString().indexOf('\\') >= 0 ? Paths.get(rel.toString().replace('\\', '/')) : rel;
            for (PathMatcher m : compiled) {
                if (m.matches(normalised))
                    return true;
            }
            return false;
        };
    }

    /**
     * Returns true if {@code rel} (or any of its ancestor segments) matches the given matcher. Used by the snapshot
     * service so a file inside an excluded directory inherits the exclusion even when the file path itself doesn't
     * structurally match the pattern.
     */
    public static boolean matchesWithAncestors(Path rel, PathMatcher matcher) {
        Path normalised = rel.toString().indexOf('\\') >= 0 ? Paths.get(rel.toString().replace('\\', '/')) : rel;
        if (matcher.matches(normalised))
            return true;
        Path parent = normalised.getParent();
        while (parent != null) {
            if (matcher.matches(parent))
                return true;
            parent = parent.getParent();
        }
        return false;
    }
}
