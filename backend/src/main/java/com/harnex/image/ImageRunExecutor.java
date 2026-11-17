package com.harnex.image;

import java.util.ArrayList;
import org.springframework.stereotype.Component;
import com.harnex.conversation.ContentPart;
import com.harnex.conversation.MessageRecord;
import com.harnex.execution.ConversationSpec;
import com.harnex.execution.ExecutionSpec;
import com.harnex.execution.RunExecutionContext;
import com.harnex.execution.RunExecutor;
import com.harnex.execution.ScopeIdentity;

@Component
public final class ImageRunExecutor implements RunExecutor {
    private final ImageServiceRegistry services;
    private final ImageRequestValidator validator;
    private final ImageGenerationService generation;
    private final ScopeIdentity scope;
    public ImageRunExecutor(ImageServiceRegistry services, ImageRequestValidator validator, ImageGenerationService generation, ScopeIdentity scope) {
        this.services = services; this.validator = validator; this.generation = generation; this.scope = scope;
    }
    @Override public boolean supports(ExecutionSpec spec) {
        return spec instanceof ConversationSpec conversation && (conversation.action() == ConversationSpec.Action.IMAGE_GENERATE
            || conversation.action() == ConversationSpec.Action.IMAGE_EDIT);
    }
    @Override public Preparation prepare(ExecutionSpec spec) {
        ConversationSpec conversation = (ConversationSpec) spec;
        ImageServiceSnapshot snapshot = services.freeze(conversation.image().serviceId());
        var image = validator.resolve(scope.scopeId(), snapshot, conversation.image());
        return new Preparation(new ConversationSpec(conversation.action(), null, null, image), snapshot.snapshotId());
    }
    @Override public Result execute(Request request, RunExecutionContext context) {
        ConversationSpec conversation = (ConversationSpec) request.execution();
        ImageOperationRecord operation = generation.execute("image_" + context.runId(), request.configurationRef(), conversation.image(),
            request.input(), null, context);
        return result(operation, context);
    }
    @Override public Result recover(Request request, RunExecutionContext context) {
        return generation.recover(context.scopeId(), "image_" + context.runId(), context).map(operation -> result(operation, context)).orElse(null);
    }
    private Result result(ImageOperationRecord operation, RunExecutionContext context) {
        ArrayList<ContentPart> content = new ArrayList<>(); content.add(ContentPart.imageOperation(operation.operationId()));
        operation.artifactIds().forEach(id -> content.add(ContentPart.artifact(id)));
        if (operation.detail() != null) content.add(ContentPart.text(operation.detail()));
        MessageRecord.Status status = context.control().isRunCancelRequested() ? MessageRecord.Status.CANCELLED : switch (operation.state()) {
            case SUCCEEDED -> MessageRecord.Status.COMPLETED;
            case CANCELLED -> MessageRecord.Status.CANCELLED;
            case FAILED -> MessageRecord.Status.FAILED;
            default -> MessageRecord.Status.INCOMPLETE;
        };
        return new Result(content, status, operation.errorCode(), operation.detail());
    }
}
