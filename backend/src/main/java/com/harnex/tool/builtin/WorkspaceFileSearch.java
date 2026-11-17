package com.harnex.tool.builtin;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import com.harnex.process.ShellPaths;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.util.GlobMatchers;

/** Shared candidate selection for glob and grep. Glob matching stays relative to the workspace after pruning. */
final class WorkspaceFileSearch {
    private static final int MAX_WALK_DEPTH = 12;
    private static final Set<String> VCS_DIRS = Set.of(".git", ".hg", ".svn");
    private static final Set<String> NOISE_DIRS = Set.of(
        "node_modules", "target", "build", "dist", "out", ".idea", ".gradle", ".mvn",
        "__pycache__", ".venv", "venv", ".tox", ".next", ".nuxt", ".cache", ".pytest_cache", ".mypy_cache",
        ".artifacts", ".vscode-test", ".jdt-bin", "target_test-classes", "dist-ide");

    private final WorkspacePathResolver paths;
    private final String pattern;
    private final PathMatcher matcher;

    WorkspaceFileSearch(WorkspacePathResolver paths, String pattern) {
        this.paths = paths;
        this.pattern = pattern;
        // GlobMatchers deliberately tolerates bad configuration globs; tool arguments must fail explicitly.
        FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        this.matcher = GlobMatchers.compile(pattern);
    }

    void walk(String threadId, BooleanSupplier cancelled, Predicate<Path> onFile) throws IOException {
        BooleanSupplier stopped = () -> Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
        if (stopped.getAsBoolean()) return;
        Path root = paths.workspaceFor(threadId);
        Path start = startPath(root);
        int depth = depth(root.relativize(start));
        if (depth > MAX_WALK_DEPTH || stopped.getAsBoolean()) return;
        var access = paths.searchAccess(threadId);
        PathMatcher exclusions = GlobMatchers.compileAny(paths.searchExcludeGlobs());
        // A literal directory cannot match a file below it; do not walk it just to return no matches.
        if (!hasGlobSyntax(pattern) && Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) return;

        // Starting inside a subtree must not bypass a symlink, private-data gate or exclusion on its ancestors.
        Path ancestor = root;
        Path relativeStart = root.relativize(start);
        for (int index = 0; index < depth; index++) {
            if (stopped.getAsBoolean() || !traversable(ancestor, root, start, access, exclusions)) return;
            ancestor = ancestor.resolve(relativeStart.getName(index));
        }
        if (stopped.getAsBoolean()) return;
        Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), MAX_WALK_DEPTH - depth,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (stopped.getAsBoolean()) return FileVisitResult.TERMINATE;
                    return allowedDirectory(dir, root, start, access, exclusions)
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (stopped.getAsBoolean()) return FileVisitResult.TERMINATE;
                    Path relative = root.relativize(file);
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink() || !matcher.matches(relative)
                        || GlobMatchers.matchesWithAncestors(relative, exclusions)) return FileVisitResult.CONTINUE;
                    // A fresh check at publication preserves live policy-root/symlink checks after scoped caching.
                    if (paths.classify(file, threadId) == WorkspacePathResolver.Zone.RED) return FileVisitResult.CONTINUE;
                    if (stopped.getAsBoolean()) return FileVisitResult.TERMINATE;
                    return onFile.test(file) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return stopped.getAsBoolean() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });
    }

    private boolean traversable(Path dir, Path root, Path start, WorkspacePathResolver.SearchAccess access,
        PathMatcher exclusions) {
        try {
            var attrs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isSymbolicLink()) return false;
            return allowedDirectory(dir, root, start, access, exclusions);
        } catch (IOException missing) {
            return false;
        }
    }

    private boolean allowedDirectory(Path dir, Path root, Path start, WorkspacePathResolver.SearchAccess access,
        PathMatcher exclusions) {
        if (!dir.equals(root)) {
            String name = dir.getFileName().toString();
            if (ShellPaths.isWindows()) name = name.toLowerCase(Locale.ROOT);
            if (VCS_DIRS.contains(name) || (NOISE_DIRS.contains(name) && !start.startsWith(dir))
                || GlobMatchers.matchesWithAncestors(root.relativize(dir), exclusions)) return false;
        }
        return access.classify(dir) != WorkspacePathResolver.Zone.RED || access.isOwnUploadsAncestor(dir);
    }

    private Path startPath(Path root) {
        // Backslash is a glob escape, not a portable separator. Fall back rather than reinterpret its semantics.
        int special = -1;
        for (int i = 0; i < pattern.length(); i++) {
            if ("*?[{\\".indexOf(pattern.charAt(i)) >= 0) { special = i; break; }
        }
        String literal = special < 0 ? pattern : pattern.substring(0, pattern.lastIndexOf('/', special) + 1);
        if (literal.isEmpty()) return root;
        Path prefix = Path.of(literal);
        if (prefix.isAbsolute()) throw new IllegalArgumentException("search patterns must be workspace-relative");
        // Parent components must not let a narrowed traversal leave the workspace, including through symlinks.
        for (Path part : prefix) {
            if (part.toString().equals("..")) throw new IllegalArgumentException("search patterns must not contain '..'");
        }
        Path start = root.resolve(prefix).normalize();
        if (!start.startsWith(root)) throw new IllegalArgumentException("search patterns must stay inside the workspace");
        return start;
    }

    private static int depth(Path relative) { return relative.toString().isEmpty() ? 0 : relative.getNameCount(); }

    private static boolean hasGlobSyntax(String pattern) {
        return pattern.chars().anyMatch(c -> "*?[{\\".indexOf(c) >= 0);
    }
}
