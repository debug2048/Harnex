package com.harnex.usage;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.image.*;
import com.harnex.net.HttpExchangeState.Acceptance;
import com.harnex.provider.*;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;

import io.swagger.v3.oas.annotations.media.Schema;

/** Business accounting, independent of the bounded diagnostic writer. Each attempt has exactly one durable owner. */
@Service
public final class UsageLedger {
    private final Path base;
    private final ResolvedProviderRegistry providers;
    private final ObjectMapper json = new ObjectMapper();
    private final long processStartedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
    @Autowired public UsageLedger(AgentConfig config, ResolvedProviderRegistry providers) {
        this(Path.of(config.getDataDir()), providers);
    }
    public UsageLedger(Path directory, ResolvedProviderRegistry providers) {
        this.base = directory.toAbsolutePath().normalize().resolve("usage-ledger"); this.providers = providers;
    }
    @Schema(name = "UsagePrice", requiredProperties = {"basis", "rates"})
    public record Price(String version, String currency, String basis, Map<String, BigDecimal> rates) {
        public Price { rates = Map.copyOf(rates); }
    }
    @Schema(name = "UsageEntry", requiredProperties = {"attemptId", "callId", "runId", "purpose", "kind", "provider", "model", "revision", "startedAt", "state", "acceptance", "quantities", "usageComplete", "costComplete", "knownAmount"})
    public record Entry(String attemptId, String callId, String runId, String conversationId, String stepId,
        String operationId, String purpose, String kind, String provider, String model, long revision, long startedAt,
        Long finishedAt, ModelCallRecord.State state, Acceptance acceptance, Map<String, Long> quantities,
        Price price, boolean usageComplete, boolean costComplete, BigDecimal knownAmount) {
        public Entry { quantities = Map.copyOf(quantities); }
    }
    @Schema(name = "UsageDocument", requiredProperties = {"schemaVersion", "runId", "entries"})
    public record Document(int schemaVersion, String runId, List<Entry> entries) {
        public Document { entries = List.copyOf(entries); }
    }
    @Schema(name = "UsageSummary", requiredProperties = {"runId", "attempts", "unknownUsageAttempts", "unknownCostAttempts", "usageComplete", "costComplete", "promptTokens", "completionTokens", "images", "knownAmounts", "calculatedAt"})
    public record Summary(String runId, int attempts, int unknownUsageAttempts, int unknownCostAttempts,
        boolean usageComplete, boolean costComplete, long promptTokens, long completionTokens, long images,
        Map<String, BigDecimal> knownAmounts, long calculatedAt) {}
    @Schema(name = "UsageReport", requiredProperties = {"summary", "entries"})
    public record Report(Summary summary, List<Entry> entries) {}
    public synchronized Report report(String runId) { return new Report(summary(runId), read(runId).entries()); }

    public Price textPrice(String providerId, String modelId) {
        ResolvedProvider provider = providers.find(providerId).orElse(null);
        AgentConfig.ModelDef model = provider == null || modelId == null ? null : provider.getModels().stream()
            .filter(item -> item != null && modelId.equalsIgnoreCase(item.getId())).findFirst().orElse(null);
        if (model == null) return null;
        Map<String, BigDecimal> rates = new TreeMap<>();
        put(rates, "input", model.getInputCostPerMillion()); put(rates, "output", model.getOutputCostPerMillion());
        put(rates, "cached", model.getCachedInputCostPerMillion() == null ? model.getInputCostPerMillion() : model.getCachedInputCostPerMillion());
        put(rates, "cacheWrite", model.getCacheWriteInputCostPerMillion() == null ? model.getInputCostPerMillion() : model.getCacheWriteInputCostPerMillion());
        return new Price(IdempotencyReceiptStore.requestHash(rates), "USD", "TEXT_TOKENS", rates);
    }
    public boolean supportsText(String provider, String model) {
        Price price = textPrice(provider, model);
        return price != null && price.rates().keySet().containsAll(Set.of("input", "output", "cached", "cacheWrite"));
    }
    public synchronized void record(ModelCallRecord call) {
        if (call.attempts().isEmpty()) return;
        String runId = call.runId() == null ? "utility" : call.runId();
        List<Entry> entries = new ArrayList<>(read(runId).entries());
        boolean changed = false;
        for (var attempt : call.attempts()) {
            int index = index(entries, attempt.attemptId());
            Entry previous = index < 0 ? null : entries.get(index);
            if (previous != null && previous.revision() >= call.revision()) continue;
            Price price = previous == null ? textPrice(attempt.provider(), attempt.model()) : previous.price();
            Map<String, Long> quantities = new LinkedHashMap<>();
            if (attempt.usage() != null) {
                ProviderTokenUsage usage = attempt.usage();
                quantities.put("prompt", usage.prompt()); quantities.put("completion", usage.completion());
                quantities.put("cached", usage.cachedInput()); quantities.put("cacheWrite", usage.cacheWriteInput());
                quantities.put("reasoning", usage.reasoning());
            }
            boolean noCharge = noCharge(attempt.state(), attempt.acceptance());
            boolean complete = noCharge || attempt.finishedAt() != null && attempt.usage() != null;
            Cost cost = noCharge ? new Cost(BigDecimal.ZERO, true) : textCost(price, quantities, complete);
            Entry entry = new Entry(attempt.attemptId(), call.callId(), runId, call.threadId(), call.stepId(), null,
                call.purpose(), "TEXT", attempt.provider(), attempt.model(), call.revision(), attempt.startedAt(),
                attempt.finishedAt(), attempt.state(), attempt.acceptance(), quantities, price, complete, cost.complete(), cost.amount());
            if (index < 0) entries.add(entry); else entries.set(index, entry);
            changed = true;
        }
        if (changed) write(new Document(1, runId, entries));
    }
    public synchronized void recordImage(ImageOperationRecord operation, ImageServiceSnapshot snapshot, int receivedImages) {
        String runId = operation.runId(), callId = "call_" + operation.operationId(), attemptId = callId + "_1";
        List<Entry> entries = new ArrayList<>(read(runId).entries());
        int index = index(entries, attemptId); Entry previous = index < 0 ? null : entries.get(index);
        if (previous != null && previous.revision() >= operation.revision()) return;
        Price price = previous == null ? imagePrice(snapshot) : previous.price();
        Map<String, Long> quantities = new LinkedHashMap<>(operation.usage());
        quantities.put("images", (long)receivedImages);
        ModelCallRecord.State state = switch (operation.state()) {
            case QUEUED, RUNNING, CANCEL_REQUESTED -> ModelCallRecord.State.RUNNING;
            case SUCCEEDED, PARTIAL_SUCCESS, SAVING -> operation.responseComplete() ? ModelCallRecord.State.SUCCEEDED : ModelCallRecord.State.UNKNOWN;
            case CANCELLED -> ModelCallRecord.State.CANCELLED;
            case FAILED -> ModelCallRecord.State.FAILED;
            case UNKNOWN -> ModelCallRecord.State.UNKNOWN;
        };
        boolean noCharge = noCharge(state, operation.acceptance());
        Cost cost = noCharge ? new Cost(BigDecimal.ZERO, true) : imageCost(price, quantities, operation.responseComplete());
        boolean complete = noCharge || operation.responseComplete() && (price != null && "IMAGE_COUNT".equals(price.basis()) ||
            operation.usage().containsKey("input_tokens") && operation.usage().containsKey("output_tokens"));
        if (!noCharge && "openai-responses-image".equals(snapshot.profile())) complete = false;
        Entry entry = new Entry(attemptId, callId, runId, operation.conversationId(), operation.parentStepId(), operation.operationId(),
            operation.requestSnapshot().sourceArtifactIds().isEmpty() ? "image-generation" : "image-edit",
            "IMAGE", snapshot.serviceId(), snapshot.model(), operation.revision(), operation.createdAt(),
            state == ModelCallRecord.State.RUNNING ? null : operation.updatedAt(), state, operation.acceptance(), quantities,
            price, complete, cost.complete(), cost.amount());
        if (index < 0) entries.add(entry); else entries.set(index, entry);
        write(new Document(1, runId, entries));
    }
    public synchronized Document read(String runId) {
        Path path = path(runId);
        if (!Files.exists(path)) return new Document(1, runId, List.of());
        try {
            Document document = json.readValue(Files.readAllBytes(path), Document.class);
            if (document.schemaVersion() != 1 || !runId.equals(document.runId())) throw new IllegalStateException("Invalid usage ledger identity");
            return new Document(1, runId, document.entries().stream().map(entry -> entry.startedAt() >= processStartedAt || entry.state() != ModelCallRecord.State.RUNNING ? entry
                : new Entry(entry.attemptId(), entry.callId(), entry.runId(), entry.conversationId(), entry.stepId(), entry.operationId(),
                    entry.purpose(), entry.kind(), entry.provider(), entry.model(), entry.revision(), entry.startedAt(), null,
                    ModelCallRecord.State.UNKNOWN, entry.acceptance(), entry.quantities(), entry.price(), false, false, entry.knownAmount())).toList());
        }
        catch (IOException failure) { throw new IllegalStateException("Usage ledger unavailable", failure); }
    }
    public synchronized Summary summary(String runId) {
        List<Entry> entries = read(runId).entries();
        int missingUsage = 0, missingCost = 0; long prompt = 0, completion = 0, images = 0;
        Map<String, BigDecimal> amounts = new TreeMap<>();
        for (Entry entry : entries) {
            if (!entry.usageComplete()) missingUsage++;
            if (!entry.costComplete()) missingCost++;
            prompt += entry.quantities().getOrDefault("TEXT".equals(entry.kind()) ? "prompt" : "input_tokens", 0L);
            completion += entry.quantities().getOrDefault("TEXT".equals(entry.kind()) ? "completion" : "output_tokens", 0L);
            images += entry.quantities().getOrDefault("images", 0L);
            if (entry.price() != null && entry.price().currency() != null) amounts.merge(entry.price().currency(), entry.knownAmount(), BigDecimal::add);
        }
        return new Summary(runId, entries.size(), missingUsage, missingCost, missingUsage == 0, missingCost == 0,
            prompt, completion, images, Map.copyOf(amounts), System.currentTimeMillis());
    }
    private void write(Document document) {
        try { AtomicJsonFiles.write(json, path(document.runId()), document); }
        catch (IOException failure) { throw new IllegalStateException("Usage ledger could not be saved", failure); }
    }
    private Path path(String runId) {
        if (!Ids.isSafeResourceId(runId)) throw new IllegalArgumentException("invalid runId");
        return base.resolve(runId + ".json");
    }
    private static int index(List<Entry> entries, String id) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).attemptId().equals(id)) return i;
        return -1;
    }
    private static boolean noCharge(ModelCallRecord.State state, Acceptance acceptance) {
        return state != ModelCallRecord.State.RUNNING && (acceptance == Acceptance.NOT_SENT || acceptance == Acceptance.REJECTED);
    }
    private static Price imagePrice(ImageServiceSnapshot snapshot) {
        var configured = snapshot.pricing(); if (configured == null) return null;
        Map<String, BigDecimal> rates = new TreeMap<>();
        put(rates, "image", configured.perImage()); put(rates, "textInput", configured.textInputPerMillion());
        put(rates, "imageInput", configured.imageInputPerMillion()); put(rates, "imageOutput", configured.imageOutputPerMillion());
        // A fixed per-image price is the complete operation rate, never added to the token alternative.
        String basis = configured.perImage() == null ? "IMAGE_TOKENS" : "IMAGE_COUNT";
        if ("openai-responses-image".equals(snapshot.profile())) basis = "RESPONSES_" + basis;
        return new Price(configured.version(), configured.currency(), basis, rates);
    }
    private record Cost(BigDecimal amount, boolean complete) {}
    private static Cost textCost(Price price, Map<String, Long> q, boolean complete) {
        if (price == null || q.isEmpty()) return new Cost(BigDecimal.ZERO, false);
        long prompt = q.getOrDefault("prompt", 0L), cached = Math.min(prompt, q.getOrDefault("cached", 0L));
        long write = Math.min(prompt - cached, q.getOrDefault("cacheWrite", 0L));
        return priced(price, Map.of("input", prompt - cached - write, "cached", cached, "cacheWrite", write,
            "output", q.getOrDefault("completion", 0L)), complete, true);
    }
    private static Cost imageCost(Price price, Map<String, Long> q, boolean complete) {
        if (price == null || price.currency() == null || price.currency().isBlank()) return new Cost(BigDecimal.ZERO, false);
        // Responses top-level usage describes the text model; it is not interchangeable with image-tool usage.
        if ("RESPONSES_IMAGE_TOKENS".equals(price.basis())) return new Cost(BigDecimal.ZERO, false);
        if ("RESPONSES_IMAGE_COUNT".equals(price.basis())) return priced(price, Map.of("image", q.getOrDefault("images", 0L)), false, false);
        if ("IMAGE_COUNT".equals(price.basis())) return priced(price, Map.of("image", q.getOrDefault("images", 0L)), complete, false);
        Map<String, Long> quantities = new LinkedHashMap<>();
        quantities.put("textInput", q.getOrDefault("input_tokens_details.text_tokens", 0L));
        quantities.put("imageInput", q.getOrDefault("input_tokens_details.image_tokens", 0L));
        quantities.put("imageOutput", q.getOrDefault("output_tokens", 0L));
        complete &= q.containsKey("input_tokens_details.text_tokens") && q.containsKey("input_tokens_details.image_tokens") && q.containsKey("output_tokens");
        return priced(price, quantities, complete, true);
    }
    private static Cost priced(Price price, Map<String, Long> quantities, boolean complete, boolean perMillion) {
        BigDecimal amount = BigDecimal.ZERO;
        for (var quantity : quantities.entrySet()) {
            BigDecimal rate = price.rates().get(quantity.getKey());
            if (rate == null) { if (quantity.getValue() > 0) complete = false; }
            else amount = amount.add(rate.multiply(BigDecimal.valueOf(quantity.getValue())));
        }
        return new Cost(perMillion ? amount.movePointLeft(6) : amount, complete);
    }
    private static void put(Map<String, BigDecimal> rates, String name, Double value) {
        if (value != null && Double.isFinite(value) && value >= 0) rates.put(name, BigDecimal.valueOf(value));
    }
}
