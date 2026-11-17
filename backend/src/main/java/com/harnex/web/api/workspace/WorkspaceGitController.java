package com.harnex.web.api.workspace;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.harnex.config.ProjectRuntime;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.workspace.git.GitWorkspaceInspector;

/** Read-only live branch shared by GUI and TUI, including SSH Runtime workspaces. */
@RestController
@ProjectRuntime
public class WorkspaceGitController {
    private final WorkspacePathResolver paths;
    private final GitWorkspaceInspector git;

    public WorkspaceGitController(WorkspacePathResolver paths, GitWorkspaceInspector git) {
        this.paths = paths;
        this.git = git;
    }

    @GetMapping("/api/v1/workspace/git")
    public ResponseEntity<GitWorkspaceInspector.BranchView> branch() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(git.inspectBranch(paths.getProjectRoot()));
    }
}
