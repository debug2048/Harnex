package com.harnex.middleware;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.workspace.WorkspacePathResolver;

@Component
@ProjectRuntime
public class ThreadDataMiddleware implements Middleware {
    private final WorkspacePathResolver vp;

    public ThreadDataMiddleware(WorkspacePathResolver vp) {
        this.vp = vp;
    }

    @Override
    public int order() {
        return MiddlewareOrder.THREAD_DATA;
    }

    @Override
    public void before(AgentCtx ctx) {
        String tid = ctx.getThread().getId();
        vp.uploadsFor(tid);
        if (ctx.getThread().getState().getSandboxId() == null) {
            ctx.getThread().getState().setSandboxId("local:" + tid);
        }
    }

    /**
     * Critical: subsequent tools (file_read / file_write / bash / ...) resolve every path against the directories
     * created above. If creation silently fails (disk full, permission revoked, root mis-mounted) and we let the loop
     * proceed, the user sees a wall of unrelated tool errors with no diagnostic. Failing fast in MiddlewareChain
     * surfaces the real cause as a single error event instead.
     */
    @Override
    public boolean isCritical() {
        return true;
    }
}
