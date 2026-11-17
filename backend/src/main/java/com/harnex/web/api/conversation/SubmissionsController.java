package com.harnex.web.api.conversation;

import com.harnex.auth.ClientIdentity;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.execution.ExecutionSpec;
import com.harnex.contract.StrictRequest;
import com.harnex.contract.run.RunCreateRequest;
import com.harnex.run.RunNotification;
import com.harnex.submission.*;

import io.swagger.v3.oas.annotations.media.Schema;

@RestController @RequestMapping("/api/v1/conversations/{conversationId}/submissions")
public final class SubmissionsController {
    private final PendingSubmissionService submissions;
    public SubmissionsController(PendingSubmissionService submissions) { this.submissions = submissions; }
    @Schema(name = "PendingSubmissionCreateRequest", requiredProperties = {"clientRequestId", "predecessorTurnId", "input", "execution"})
    public record Create(String clientRequestId, String predecessorTurnId, String input, List<String> attachmentIds,
        ExecutionSpec execution, String channel, RunNotification.Mode notificationMode) implements StrictRequest {
        public Create(String clientRequestId, String predecessorTurnId, String input, List<String> attachmentIds, ExecutionSpec execution, String channel) {
            this(clientRequestId, predecessorTurnId, input, attachmentIds, execution, channel, null);
        }
    }
    @Schema(name = "PendingSubmissionPage", requiredProperties = {"items"})
    public record Page(List<PendingSubmission> items) {}
    @PostMapping public ResponseEntity<PendingSubmission> enqueue(@PathVariable String conversationId, @RequestBody Create body) {
        var request = new RunCreateRequest(); request.setClientRequestId(body.clientRequestId()); request.setInput(body.input());
        request.setAttachmentIds(body.attachmentIds()); request.setExecution(body.execution()); request.setChannel(body.channel());
        request.setNotificationMode(body.notificationMode());
        return ResponseEntity.accepted().body(submissions.enqueue(conversationId, body.predecessorTurnId(), request, ClientIdentity.actor()));
    }
    @GetMapping public Page list(@PathVariable String conversationId) { return new Page(submissions.list(conversationId)); }
    @PostMapping("/{submissionId}/cancel") public PendingSubmission cancel(@PathVariable String conversationId, @PathVariable String submissionId) {
        return submissions.cancel(conversationId, submissionId);
    }
}
