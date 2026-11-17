package com.harnex.image;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.harnex.artifact.ArtifactException;
import com.harnex.artifact.ArtifactRecord;
import com.harnex.artifact.ArtifactStore;
import com.harnex.execution.ImageSpec;
import com.harnex.execution.RunExecutionContext;
import com.harnex.image.ImageOperationRecord.State;
import com.harnex.model.StreamEvent;
import com.harnex.net.HttpExchangeState;
import com.harnex.net.HttpExchangeState.Acceptance;
import com.harnex.provider.ModelCallRecord;
import com.harnex.provider.ModelCallStore;
import com.harnex.provider.ProviderCallScope;
import com.harnex.provider.ProviderTokenUsage;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.usage.UsageLedger;
import lombok.extern.slf4j.Slf4j;

/** Shared image operation execution for explicit conversation, AUTO, and Agent tools. Never creates another root Run. */
@Slf4j
@Service
public final class ImageGenerationService {
    private final ImageServiceRegistry services;
    private final ImageOperationStore operations;
    private final ArtifactStore assets;
    private final ModelCallStore calls;
    @Autowired(required = false)
    private UsageLedger usageLedger;
    private final Map<String, ImageProvider> providers;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    public ImageGenerationService(ImageServiceRegistry services, ImageOperationStore operations, ArtifactStore assets,
        ModelCallStore calls, List<ImageProvider> providers) {
        this.services = services; this.operations = operations; this.assets = assets; this.calls = calls;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(ImageProvider::profile, value -> value));
    }
    public ImageOperationRecord execute(String operationId, String configurationRef, ImageSpec spec, String prompt,
        String parentToolCallId, RunExecutionContext context) {
        ImageServiceSnapshot snapshot = services.snapshot(configurationRef);
        String hash = IdempotencyReceiptStore.requestHash(Map.of("configuration", configurationRef, "spec", spec, "prompt", prompt,
            "scope", context.scopeId(), "run", context.runId(), "toolCall", parentToolCallId == null ? "" : parentToolCallId));
        long now = System.currentTimeMillis();
        var owner = ProviderCallScope.current();
        String stepId = owner != null && context.runId().equals(owner.runId()) ? owner.stepId() : null;
        ImageOperationRecord initial = new ImageOperationRecord(1, operationId, context.scopeId(), context.conversationId(),
            context.runId(), parentToolCallId, hash, configurationRef, spec, State.QUEUED, 1, now, now, Acceptance.NOT_SENT,
            null, List.of(), Map.of(), false, null, null, stepId);
        ImageOperationRecord operation = operations.create(initial);
        if (!active.add(operationId)) return operation;
        HttpExchangeState exchange = new HttpExchangeState();
        Long[] firstOutput = {null};
        long startedAt = System.currentTimeMillis();
        Map<String,Long> timing = new LinkedHashMap<>();
        var queue = ProviderCallScope.takeQueueTiming();
        if (queue != null) { timing.put("queuedAt",queue.queuedAt()); timing.put("workerStartedAt",queue.workerStartedAt()); timing.put("queueMillis",queue.queueMillis()); }
        boolean attempted = false;
        try {
            if (operation.state() != State.QUEUED) return recover(operation, context);
            context.checkActive();
            if (!new ImageRequestValidator(assets).resolve(context.scopeId(), snapshot, spec).equals(spec)) {
                throw new ImageServiceException("image_request_invalid", "Image parameters must be resolved before starting the operation");
            }
            var connection = services.connection(snapshot);
            ImageProvider provider = providers.get(snapshot.profile());
            if (provider == null) throw new ImageServiceException("image_profile_unsupported", "The configured image profile is not available");
            context.budget().acquireImageOperation(); context.budget().acquireModelCall();
            // Persist uncertain transmission BEFORE dispatch. A crash at this boundary must never cause a second POST.
            operation = save(operation.update(State.RUNNING, Acceptance.SENT_UNKNOWN, null, List.of(), Map.of(), false, null, null), context);
            attempted = true;
            timing.put("dispatchedAt",System.currentTimeMillis());
            trace(operation, snapshot, ModelCallRecord.State.RUNNING, startedAt, null, null, context, timing);
            ImageProvider.Output output = provider.generate(new ImageProvider.Input(snapshot, connection, spec, prompt), context, exchange,
                (index, writer) -> {
                    operations.stage(context.scopeId(), operationId, index, writer);
                    if (firstOutput[0] == null) firstOutput[0] = Math.max(0, System.currentTimeMillis() - startedAt);
                    publishCandidates(operations.find(context.scopeId(), operationId).orElseThrow(), context);
                });
            operation = operations.find(context.scopeId(), operationId).orElseThrow();
            operation = save(operation.update(State.SAVING, exchange.acceptance(), output.providerHandle() == null ? exchange.requestId() : output.providerHandle(),
                operation.artifactIds(), output.usage(), output.complete(), output.complete() ? null : "image_result_incomplete",
                output.complete() ? null : "The image service returned an incomplete result"), context);
            operation = publishCandidates(operation, context);
            if (operation.state() == State.SUCCEEDED || operation.state() == State.PARTIAL_SUCCESS) services.succeeded(snapshot);
            else if (operation.state() == State.FAILED) services.failed(snapshot, new ImageServiceException(operation.errorCode(), operation.detail(), operation.acceptance(), 0));
            return operation;
        } catch (Exception failure) {
            ImageServiceException typed = failure instanceof ImageServiceException image ? image
                : new ImageServiceException(failure instanceof CancellationException ? "image_cancelled" : "image_execution_failed",
                    failure instanceof CancellationException ? "Image operation cancelled locally" : "The image operation could not finish", exchange.acceptance(), 0);
            // Transport facts override an exception from parsing, staging, or callbacks after acceptance.
            Acceptance acceptance = exchange.acceptance() == Acceptance.NOT_SENT ? typed.acceptance() : exchange.acceptance();
            boolean cancelled = context.control().isRunCancelRequested() || "image_cancelled".equals(typed.code());
            State state = acceptance == Acceptance.NOT_SENT || acceptance == Acceptance.REJECTED
                ? cancelled ? State.CANCELLED : State.FAILED : State.UNKNOWN;
            ImageOperationRecord current = operations.find(context.scopeId(), operationId).orElse(operation);
            // A storage failure after the completed response was committed is recoverable purely from staged files.
            if (current.responseComplete()) {
                operation = save(current.update(State.SAVING, current.acceptance(), current.providerHandle(), current.artifactIds(),
                    current.usage(), true, "image_saving_pending", "The result was received; local image saving must be resumed"), context);
            } else {
                operation = save(current.update(state, acceptance, exchange.requestId(), current.artifactIds(), current.usage(), false,
                    typed.code(), typed.getMessage()), context);
                operation = publishCandidates(operation, context);
            }
            if (Set.of("image_model_unavailable", "image_access_denied", "image_rate_limited", "image_service_unavailable",
                "image_transport_interrupted").contains(typed.code())) services.failed(snapshot, typed);
            return operation;
        } finally {
            try {
                if (attempted) {
                    ImageOperationRecord latest = operations.find(context.scopeId(), operationId).orElse(operation);
                    ModelCallRecord.State state = latest.responseComplete() ? ModelCallRecord.State.SUCCEEDED
                        : latest.state() == State.CANCELLED ? ModelCallRecord.State.CANCELLED
                        : latest.acceptance() == Acceptance.NOT_SENT || latest.acceptance() == Acceptance.REJECTED ? ModelCallRecord.State.FAILED : ModelCallRecord.State.UNKNOWN;
                    timing.putAll(exchange.timings());
                    trace(latest, snapshot, state, startedAt, System.currentTimeMillis(), firstOutput[0], context, timing);
                }
            } finally { active.remove(operationId); }
        }
    }
    public ImageOperationRecord recover(ImageOperationRecord operation, RunExecutionContext context) {
        if (operation.state() == State.RUNNING || operation.state() == State.CANCEL_REQUESTED) {
            operation = save(operation.update(State.UNKNOWN, operation.acceptance(), operation.providerHandle(), operation.artifactIds(),
                operation.usage(), false, "image_result_unknown", "The Runtime stopped before the upstream image result was confirmed"), context);
        }
        ImageOperationRecord recovered = publishCandidates(operation, context);
        recordUsage(recovered);
        return recovered;
    }
    public Optional<ImageOperationRecord> recover(String scope, String operationId, RunExecutionContext context) {
        return operations.find(scope, operationId).map(operation -> recover(operation, context));
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStagedResults() {
        // Recovery never dispatches providers or changes an already finished root Run.
        for (ImageOperationRecord operation : operations.all()) {
            if (active.contains(operation.operationId())) continue;
            try { recover(operation, null); }
            catch (RuntimeException failure) { log.warn("Image operation {} awaits storage recovery", operation.operationId()); }
        }
    }
    private ImageOperationRecord publishCandidates(ImageOperationRecord operation, RunExecutionContext context) {
        List<String> ids = new ArrayList<>(operation.artifactIds());
        int invalid = 0;
        boolean pending = false;
        for (var candidate : operations.candidates(operation.scopeId(), operation.operationId())) {
            if (ids.contains(candidate.artifactId())) {
                releasePublishedCandidate(operation, candidate);
                continue;
            }
            try (var bytes = operations.openCandidate(operation.scopeId(), operation.operationId(), candidate)) {
                var asset = assets.save(operation.scopeId(), new ArtifactStore.SaveRequest(candidate.artifactId(),
                    "generated-" + (candidate.index() + 1) + "." + operation.requestSnapshot().parameters().format(), null, new ArtifactRecord.Creator("IMAGE_OPERATION", operation.runId(), operation.operationId()),
                    operation.requestSnapshot().sourceArtifactIds(), true), bytes);
                ids.add(asset.artifactId());
                operation = save(operation.update(operation.state(), operation.acceptance(), operation.providerHandle(), List.copyOf(ids),
                    operation.usage(), operation.responseComplete(), operation.errorCode(), operation.detail()), context);
            } catch (ArtifactException failure) {
                if (Set.of("invalid_image", "invalid_image_dimensions", "artifact_empty", "artifact_too_large").contains(failure.code())) invalid++;
                else pending = true;
            } catch (Exception failure) { pending = true; }
            releasePublishedCandidate(operation, candidate);
        }
        State state = operation.state();
        String code = operation.errorCode(), detail = operation.detail();
        if (pending) { state = State.SAVING; code = "image_saving_pending"; detail = "Received image files are waiting for local storage recovery"; }
        else if (operation.responseComplete()) {
            state = ids.isEmpty() ? State.FAILED : invalid > 0 || ids.size() < operation.requestSnapshot().parameters().count() ? State.PARTIAL_SUCCESS : State.SUCCEEDED;
            if (state != State.SUCCEEDED) { code = ids.isEmpty() ? "image_no_output" : "image_partial_output"; detail = ids.isEmpty() ? "The service returned no valid image" : "Some image candidates are unavailable; completed images were preserved"; }
            else { code = null; detail = null; }
        } else if (!ids.isEmpty() && state == State.SAVING) state = State.PARTIAL_SUCCESS;
        else if (state == State.SAVING) state = State.UNKNOWN;
        if (state == operation.state() && Objects.equals(code, operation.errorCode()) && ids.equals(operation.artifactIds())) return operation;
        return save(operation.update(state, operation.acceptance(), operation.providerHandle(), List.copyOf(ids), operation.usage(),
            operation.responseComplete(), code, detail), context);
    }
    private void releasePublishedCandidate(ImageOperationRecord operation, ImageOperationStore.Candidate candidate) {
        if (!operation.artifactIds().contains(candidate.artifactId())
            || !operations.hasCandidateBytes(operation.scopeId(), operation.operationId(), candidate)) return;
        // Verify the canonical copy before removing any recovery bytes; run after the candidate input stream closes.
        try (var content = assets.open(operation.scopeId(), candidate.artifactId())) {
            if (content.metadata().bytes() == candidate.bytes() && content.metadata().checksum().equals(candidate.checksum())) {
                operations.discardPublishedCandidate(operation.scopeId(), operation.operationId(), candidate);
            }
        } catch (Exception deferred) { log.debug("Published image candidate cleanup deferred for {}", operation.operationId()); }
    }
    private ImageOperationRecord save(ImageOperationRecord record, RunExecutionContext context) {
        ImageOperationRecord committed = operations.save(record);
        recordUsage(committed);
        if (context != null) context.events().accept(StreamEvent.of("image_operation_updated", Map.of("operationId", committed.operationId(), "revision", committed.revision(), "operation", committed)));
        return committed;
    }
    private void recordUsage(ImageOperationRecord record) {
        if (usageLedger != null) usageLedger.recordImage(record, services.snapshot(record.configurationRef()),
            operations.candidates(record.scopeId(), record.operationId()).size());
    }
    private void trace(ImageOperationRecord operation, ImageServiceSnapshot snapshot, ModelCallRecord.State state,
        long started, Long finished, Long firstOutput, RunExecutionContext context, Map<String,Long> timings) {
        ProviderTokenUsage usage = operation.usage().isEmpty() ? null : new ProviderTokenUsage(operation.usage().getOrDefault("input_tokens", 0L),
            operation.usage().getOrDefault("output_tokens", 0L), operation.usage().getOrDefault("input_tokens_details.cached_tokens", 0L), 0, 0);
        String callId = "call_" + operation.operationId();
        String purpose = operation.requestSnapshot().sourceArtifactIds().isEmpty() ? "image-generation" : "image-edit";
        var record = new ModelCallRecord(1, callId, operation.runId(), operation.conversationId(), operation.parentStepId(), purpose,
            snapshot.serviceId(), snapshot.model(), state, finished == null ? 1 : 2, started, finished,
            List.of(new ModelCallRecord.Attempt(callId + "_1", 1, snapshot.serviceId(), snapshot.model(), snapshot.profile(), started,
                finished, firstOutput, state, operation.acceptance(), operation.providerHandle(), state == ModelCallRecord.State.SUCCEEDED ? null : operation.errorCode(), 0, usage, null, timings)),
            usage != null || operation.acceptance() == Acceptance.NOT_SENT || operation.acceptance() == Acceptance.REJECTED);
        calls.record(record);
        if (context != null) context.events().accept(StreamEvent.of("model_call_updated", Map.of("callId", record.callId(), "revision", record.revision(), "call", record)));
    }
}
