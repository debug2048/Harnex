package com.harnex.image;
import java.util.*;
import org.springframework.stereotype.Service;
import com.harnex.execution.ScopeIdentity;
import com.harnex.conversation.ContentPart;
/** Durable public references for images created inside a root Agent or Goal step. */
@Service
public final class ImageResultContent {
    private final ImageOperationStore operations;
    private final ScopeIdentity scope;
    public ImageResultContent(ImageOperationStore operations, ScopeIdentity scope) { this.operations = operations; this.scope = scope; }
    public List<ContentPart> append(String runId, String stepId, List<ContentPart> content) {
        Set<ContentPart> result = new LinkedHashSet<>(content);
        for (var operation : operations.list(scope.scopeId(), runId)) {
            if (stepId != null && !stepId.equals(operation.parentStepId())) continue;
            result.add(ContentPart.imageOperation(operation.operationId()));
            operation.artifactIds().forEach(id -> result.add(ContentPart.artifact(id)));
        }
        return List.copyOf(result);
    }
}
