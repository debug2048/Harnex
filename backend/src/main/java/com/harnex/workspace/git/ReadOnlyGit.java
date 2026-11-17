package com.harnex.workspace.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Hardens application-owned Git inspections; explicit user shell commands retain their chosen Git behavior. */
public final class ReadOnlyGit {
    private ReadOnlyGit() {}

    public static ProcessBuilder prepare(ProcessBuilder builder) {
        List<String> original = builder.command();
        if (original.isEmpty() || !Set.of("git", "git.exe").contains(original.get(0))) return builder;
        List<String> command = new ArrayList<>(List.of(original.get(0), "--no-pager", "--no-optional-locks",
            "-c", "core.fsmonitor=false"));
        int operation = 1;
        while (operation < original.size() && original.get(operation).startsWith("-")) {
            operation += Set.of("-C", "-c", "--git-dir", "--work-tree").contains(original.get(operation)) ? 2 : 1;
        }
        for (int i = 1; i < original.size(); i++) {
            command.add(original.get(i));
            if (i == operation && Set.of("diff", "show", "log").contains(original.get(i)))
                command.addAll(List.of("--no-ext-diff", "--no-textconv"));
        }
        builder.command(command);
        builder.environment().keySet().removeIf(key -> key.equals("GIT_EXTERNAL_DIFF") || key.equals("GIT_PAGER")
            || key.equals("GIT_CONFIG_PARAMETERS") || key.equals("GIT_CONFIG_COUNT")
            || key.startsWith("GIT_CONFIG_KEY_") || key.startsWith("GIT_CONFIG_VALUE_"));
        return builder;
    }
}
