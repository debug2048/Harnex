package com.harnex.execution;

import java.util.List;
import com.harnex.conversation.ContentPart;
import com.harnex.conversation.MessageRecord;
import com.harnex.run.RunNotification;

/** One root execution capability. Only the caller commits its public result and terminal Run state. */
public interface RunExecutor {
    boolean supports(ExecutionSpec spec);
    default ExecutionSpec resolve(ExecutionSpec spec) { validate(spec); return spec; }
    default Preparation prepare(ExecutionSpec spec) { return new Preparation(resolve(spec), null); }
    default void validate(ExecutionSpec spec) {}
    Result execute(Request request, RunExecutionContext context);
    /** Reconstruct only already received results. Must never invoke a model or a workspace tool. */
    default Result recover(Request request, RunExecutionContext context) { return null; }

    record Request(ExecutionSpec execution, String input, List<String> attachmentIds, String parentMessageId,
        String channel, String configurationRef, String retryUserMessageId, String predecessorTurnId,
        RunNotification.Mode notificationMode) {
        public Request { attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds); }
        public Request(ExecutionSpec execution, String input, List<String> attachmentIds, String parentMessageId,
            String channel, String configurationRef, String retryUserMessageId, String predecessorTurnId) {
            this(execution, input, attachmentIds, parentMessageId, channel, configurationRef, retryUserMessageId, predecessorTurnId, null);
        }
        public Request(ExecutionSpec execution, String input, List<String> attachmentIds, String parentMessageId, String channel) {
            this(execution, input, attachmentIds, parentMessageId, channel, null, null, null);
        }
        public Request(ExecutionSpec execution, String input, List<String> attachmentIds, String parentMessageId, String channel, String configurationRef) {
            this(execution, input, attachmentIds, parentMessageId, channel, configurationRef, null, null);
        }
        public Request(ExecutionSpec execution, String input, List<String> attachmentIds, String parentMessageId, String channel, String configurationRef, String retryUserMessageId) {
            this(execution, input, attachmentIds, parentMessageId, channel, configurationRef, retryUserMessageId, null);
        }
    }
    /** Server-owned immutable configuration reference, never accepted from a public request. */
    record Preparation(ExecutionSpec execution, String configurationRef) {}
    record Result(List<ContentPart> content, MessageRecord.Status status, String errorCode, String detail) {
        public Result {
            content = List.copyOf(content);
            if (status == null || status == MessageRecord.Status.PENDING || status == MessageRecord.Status.STREAMING
                || status == MessageRecord.Status.PROGRESS) throw new IllegalArgumentException("executor must return a final attempt result");
        }
        public static Result completed(String text) {
            return new Result(List.of(ContentPart.text(text)), MessageRecord.Status.COMPLETED, null, null);
        }
    }
}
