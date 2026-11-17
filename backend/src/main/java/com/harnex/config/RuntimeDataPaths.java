package com.harnex.config;

import java.io.IOException;
import java.nio.file.*;

/** Data ownership and Git protection, shared by early bootstrap and programmatically constructed configurations. */
public final class RuntimeDataPaths {
    private RuntimeDataPaths() {}

    public static Path projectRoot(AgentConfig config) {
        String root = config.getWorkspace() == null ? null : config.getWorkspace().getRoot();
        if (root == null || root.isBlank()) root = System.getenv("HARNEX_WORKSPACE");
        if (root == null || root.isBlank()) root = System.getProperty("user.dir", ".");
        return canonical(Path.of(root));
    }

    public static Path resolve(AgentConfig config, String configured) {
        Path user = canonical(UserDataPaths.resolve(config));
        if (config.getRuntimeProfile() == RuntimeProfile.PERSONAL) return user.resolve("personal");
        if (configured != null && !configured.isBlank()) return canonical(projectRoot(config).resolve(configured));
        return canonical(projectRoot(config).resolve(".harnex/data"));
    }

    /** Resolve existing ancestors too, so a symlink cannot turn a child data path into a repository root. */
    public static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize(), ancestor = absolute;
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor == null) return absolute;
        try { return ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize(); }
        catch (IOException failure) { throw new IllegalArgumentException("Cannot resolve storage path: " + absolute, failure); }
    }

    public static Path prepare(AgentConfig config) {
        Path shared = canonical(Path.of(config.getSharedDataDir()));
        prepareDirectory(config, shared);
        Path data = canonical(Path.of(config.getDataDir()));
        return data.equals(shared) ? data : prepareDirectory(config, data);
    }

    private static Path prepareDirectory(AgentConfig config, Path data) {
        Path user = canonical(UserDataPaths.resolve(config));
        Path project = config.getRuntimeProfile() == RuntimeProfile.PROJECT ? projectRoot(config) : null;
        if (user.startsWith(data) || project != null && (project.startsWith(data)
            || data.equals(canonical(project.resolve(".harnex"))) || data.startsWith(canonical(project.resolve(".git"))))) {
            throw new IllegalArgumentException("dataDir must be a dedicated data directory, not a user/project root or .harnex container: " + data);
        }
        if (Files.exists(data.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("dataDir must not be a Git repository root: " + data);
        }
        try {
            Files.createDirectories(data);
            Path ignore = data.resolve(".gitignore");
            if (Files.isSymbolicLink(ignore)) throw new IOException("data .gitignore must not be a symbolic link");
            String previous = Files.exists(ignore) ? Files.readString(ignore) : "";
            // Appending the final wildcard also neutralizes earlier ! exceptions without erasing user comments.
            if (!previous.stripTrailing().endsWith("\n*") && !previous.strip().equals("*")) {
                Files.writeString(ignore, previous + (previous.isEmpty() || previous.endsWith("\n") ? "" : "\n")
                    + "# Harnex runtime data; project skills belong in .harnex/skills.\n*\n");
            }
            return data;
        } catch (IOException failure) { throw new IllegalStateException("Cannot protect runtime data from Git: " + data, failure); }
    }
}
