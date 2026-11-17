package com.harnex.workspace.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.process.ChildProcessEnvironment;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ProjectRuntime
public class GitWorkspaceInspector {

    private final long timeoutMs;

    public GitWorkspaceInspector(AgentConfig cfg) {
        this.timeoutMs = cfg.getSnapshot() == null ? 2_000L : Math.max(250L, cfg.getSnapshot().getGitTimeoutMs());
    }

    public boolean isGitRepo(Path workspace) {
        return "true".equals(runText(workspace, "rev-parse", "--is-inside-work-tree").trim());
    }

    /** Current worktree identity only: never scan files or refresh the index for UI polling. */
    public BranchView inspectBranch(Path workspace) {
        ExecResult repository = run(workspace, true, "rev-parse", "--is-inside-work-tree");
        if (!repository.ok) {
            return new BranchView(repository.stderr.contains("not a git repository") ? "none" : "unavailable", null, null);
        }
        if (!"true".equals(repository.text.trim())) return new BranchView("none", null, null);
        ExecResult branch = run(workspace, true, "symbolic-ref", "--quiet", "--short", "HEAD");
        if (branch.ok && !branch.text.isBlank()) return new BranchView("branch", branch.text.trim(), null);
        if (branch.timedOut) return new BranchView("unavailable", null, null);
        ExecResult commit = run(workspace, true, "rev-parse", "--verify", "--short=8", "HEAD");
        return commit.ok && !commit.text.isBlank()
            ? new BranchView("detached", null, commit.text.trim())
            : new BranchView("unavailable", null, null);
    }

    public record BranchView(String state, String branch, String commit) {}

    public GitSnapshot inspectBefore(Path workspace) {
        return inspect(workspace);
    }

    public GitSnapshot inspectAfter(Path workspace) {
        return inspect(workspace);
    }

    public List<Path> changedTrackedPaths(GitSnapshot before, GitSnapshot after) {
        if (after == null || after.timedOut || after.dirtyTrackedPaths == null) {
            return List.of();
        }
        Set<String> alreadyDirty = before == null || before.dirtyTrackedPaths == null ? Set.of() : before.dirtyTrackedPaths;
        List<Path> out = new ArrayList<>();
        for (String rel : after.dirtyTrackedPaths) {
            if (!alreadyDirty.contains(rel)) {
                out.add(Path.of(rel));
            }
        }
        return out;
    }

    public List<Path> newUntrackedPaths(GitSnapshot before, GitSnapshot after) {
        if (after == null || after.timedOut || after.untrackedPaths == null) {
            return List.of();
        }
        Set<String> alreadyUntracked = before == null || before.untrackedPaths == null ? Set.of() : before.untrackedPaths;
        List<Path> out = new ArrayList<>();
        for (String rel : after.untrackedPaths) {
            if (!alreadyUntracked.contains(rel)) {
                out.add(Path.of(rel));
            }
        }
        return out;
    }

    public Optional<String> head(Path workspace) {
        String h = runText(workspace, "rev-parse", "HEAD").trim();
        return h.isBlank() ? Optional.empty() : Optional.of(h);
    }

    public Optional<byte[]> readHeadBlob(Path workspace, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Optional.empty();
        }
        ExecResult r = run(workspace, false, "show", "HEAD:" + relativePath.replace('\\', '/'));
        return r.ok ? Optional.of(r.stdout) : Optional.empty();
    }

    private GitSnapshot inspect(Path workspace) {
        GitSnapshot s = new GitSnapshot();
        s.head = head(workspace).orElse(null);
        ExecResult status = run(workspace, false, "status", "--porcelain=v1", "-z", "--untracked-files=all");
        s.timedOut = status.timedOut;
        if (status.ok) parsePorcelainZ(status.stdout(), s);
        return s;
    }

    private void parsePorcelainZ(byte[] bytes, GitSnapshot snapshot) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        List<String> records = splitNul(bytes);
        for (int i = 0; i < records.size(); i++) {
            String record = records.get(i);
            if (record.length() < 4) {
                continue;
            }
            String status = record.substring(0, 2);
            String path = record.substring(3);
            if ((status.charAt(0) == 'R' || status.charAt(1) == 'R'
                || status.charAt(0) == 'C' || status.charAt(1) == 'C') && i + 1 < records.size()) {
                i++; // With porcelain -z, rename/copy records are followed by the source path.
            }
            if (!path.isBlank()) {
                String normalized = path.replace('\\', '/');
                if ("??".equals(status)) {
                    snapshot.untrackedPaths.add(normalized);
                } else {
                    snapshot.dirtyTrackedPaths.add(normalized);
                }
            }
        }
    }

    private static List<String> splitNul(byte[] bytes) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                if (i > start) {
                    out.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                }
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            out.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
        }
        return out;
    }

    private String runText(Path workspace, String... args) {
        ExecResult result = run(workspace, true, args);
        return result.ok ? result.text : "";
    }

    private ExecResult run(Path workspace, boolean text, String... args) {
        Process p = null;
        try {
            if (workspace == null || !Files.isDirectory(workspace)) {
                return ExecResult.error(false);
            }
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(workspace.toAbsolutePath().normalize().toString());
            for (String arg : args) {
                cmd.add(arg);
            }
            ProcessBuilder builder = ReadOnlyGit.prepare(new ProcessBuilder(cmd));
            builder.environment().put("LC_ALL", "C");
            p = start(builder);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outputDrainer = drain(p.getInputStream(), stdout, "git-inspector-stdout");
            Thread errorDrainer = drain(p.getErrorStream(), stderr, "git-inspector-stderr");
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                return ExecResult.timeout();
            }
            outputDrainer.join(Duration.ofMillis(200).toMillis());
            errorDrainer.join(Duration.ofMillis(200).toMillis());
            boolean ok = p.exitValue() == 0;
            byte[] bytes = stdout.toByteArray();
            return new ExecResult(ok, false, bytes,
                text ? new String(bytes, StandardCharsets.UTF_8) : "", stderr.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("git inspector failed: {}", e.toString());
            return ExecResult.error(false);
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    Process start(ProcessBuilder builder) throws IOException {
        return ChildProcessEnvironment.start(builder);
    }

    private static Thread drain(InputStream input, ByteArrayOutputStream output, String name) {
        Thread drainer = new Thread(() -> {
            try (input) {
                input.transferTo(output);
            } catch (IOException ignored) {
            }
        }, name);
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }

    private record ExecResult(boolean ok, boolean timedOut, byte[] stdout, String text, String stderr) {
        static ExecResult timeout() {
            return new ExecResult(false, true, new byte[0], "", "");
        }

        static ExecResult error(boolean timedOut) {
            return new ExecResult(false, timedOut, new byte[0], "", "");
        }
    }

    @Data
    public static class GitSnapshot {
        public String head;
        public boolean timedOut;
        public Set<String> dirtyTrackedPaths = new HashSet<>();
        public Set<String> untrackedPaths = new HashSet<>();
    }
}
