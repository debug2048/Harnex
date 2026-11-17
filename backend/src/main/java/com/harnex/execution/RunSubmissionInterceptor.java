package com.harnex.execution;

import com.harnex.run.RunOrigin;
import com.harnex.contract.run.RunCreateRequest;
import com.harnex.run.RunHandle;

/** Explicit command adapters may route a submission to an existing business resource, such as /goal resume. */
public interface RunSubmissionInterceptor {
    RunHandle intercept(RunCreateRequest request, RunOrigin origin);
}
