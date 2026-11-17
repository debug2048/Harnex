package com.harnex.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.execution.TextTarget;
import com.harnex.net.HttpExchangeState;
import com.harnex.net.OutboundProxyResolver;
import com.harnex.provider.*;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.storage.AtomicJsonFiles;

/** Durable configuration identity for accepted conversation turns, including queued turns. No secrets on disk. */
@Component
public final class ConversationTextSnapshots {
    public static final String POLICY = "conversation-context-v3";
    private final AgentConfig config;
    private final ResolvedProviderRegistry providers;
    private final Path base;
    private final ObjectMapper json = new ObjectMapper();

    public ConversationTextSnapshots(AgentConfig config, ResolvedProviderRegistry providers) {
        this.config = config; this.providers = providers;
        base = Path.of(config.getDataDir()).resolve("conversations/text-configurations");
    }
    public Snapshot freeze(TextTarget requested) { return freeze(requested,false); }
    public Snapshot freezeAgent(TextTarget requested) { return freeze(requested,true); }
    private Snapshot freeze(TextTarget requested, boolean agentCatalogue) {
        List<Target> targets = new ArrayList<>();
        targets.add(target(requested.provider(), requested.model()));
        providers.find(ProviderRouter.FALLBACK_PROVIDER).filter(p -> !p.getName().equalsIgnoreCase(requested.provider())).ifPresent(p -> {
            String model = p.getModels().stream().anyMatch(m -> m.getId().equalsIgnoreCase(requested.model()))
                ? requested.model() : p.getDefaultModel();
            if (model != null && !model.isBlank()) targets.add(target(p.getName(), model));
        });
        var settings = config.getSummarizer();
        SummaryTargets.Target dedicated = null;
        SummaryTargets.Target fallback = null;
        Map<String, String> summaryProviders = new LinkedHashMap<>();
        if (settings.isEnabled()) {
            if (settings.getProvider() != null && !settings.getProvider().isBlank()
                || settings.getModel() != null && !settings.getModel().isBlank()) {
                dedicated = SummaryTargets.resolve(providers, config.getRouter().getDefaultProvider(),
                    settings.getProvider(), settings.getModel()).orElseThrow(() -> changed("Summary provider is unavailable"));
            }
            fallback = SummaryTargets.resolve(providers, null, ProviderRouter.FALLBACK_PROVIDER, null).orElse(null);
            for (var summaryTarget : SummaryTargets.ordered(dedicated, fallback, null, SummaryTargets.Target::key)) {
                summaryProviders.put(summaryTarget.provider().toLowerCase(Locale.ROOT),
                    fingerprint(providers.find(summaryTarget.provider()).orElseThrow()));
            }
        }
        SummaryPolicy summary = new SummaryPolicy(settings.isEnabled(), settings.getSummaryMaxChars(),
            settings.getMaxOutputTokens(), settings.getTimeoutMs(), dedicated, fallback,
            summaryProviders, settings.getAttemptTimeoutMs());
        Map<String,String> fingerprints = new LinkedHashMap<>();
        if (agentCatalogue) for (var provider : providers.providers()) fingerprints.put(provider.getName().toLowerCase(Locale.ROOT),fingerprint(provider));
        String id = "textcfg_" + IdempotencyReceiptStore.requestHash(Map.of("targets", targets, "policy", POLICY,
            "summary", summary, "vision", config.getVision().isEnabled(),"agentProviders",fingerprints)).substring(0, 32);
        Snapshot snapshot = new Snapshot(id, POLICY, targets, summary, config.getVision().isEnabled(),fingerprints);
        try { if (!Files.isRegularFile(base.resolve(id + ".json"))) AtomicJsonFiles.write(json, base.resolve(id + ".json"), snapshot); }
        catch (IOException failure) { throw new ConversationException("text_configuration_unavailable", "Could not freeze the text configuration", failure); }
        return snapshot;
    }
    public Snapshot load(String id) {
        if (id == null || !id.matches("textcfg_[a-f0-9]{32}")) throw new ConversationException("text_configuration_unavailable", "The submitted text configuration is missing");
        try { return json.readValue(Files.readAllBytes(base.resolve(id + ".json")), Snapshot.class); }
        catch (IOException failure) { throw new ConversationException("text_configuration_unavailable", "The submitted text configuration is unavailable", failure); }
    }
    public Target requireTarget(Snapshot snapshot, String provider, String model) {
        if (!POLICY.equals(snapshot.policyVersion())) throw changed("The submitted output-budget policy changed; submit a new task");
        if (!snapshot.agentProviders().isEmpty()) {
            var current = providers.find(provider).orElseThrow(() -> changed("The submitted text provider is no longer enabled"));
            if (!fingerprint(current).equals(snapshot.agentProviders().get(provider.toLowerCase(Locale.ROOT)))) throw changed("The Agent provider configuration changed; submit a new task to use it");
            return target(provider,model);
        }
        Target saved = snapshot.targets().stream().filter(t -> t.provider().equalsIgnoreCase(provider) && t.model().equalsIgnoreCase(model))
            .findFirst().orElseThrow(() -> changed("The execution target was not part of the submitted configuration"));
        ResolvedProvider current = providers.find(provider).orElseThrow(() -> changed("The submitted text provider is no longer enabled"));
        if (!saved.connectionFingerprint().equals(fingerprint(current))) throw changed("The text provider configuration changed; submit a new turn to use it");
        return saved;
    }
    public Target currentTarget(String provider,String model) { return target(provider,model); }

    public void validateSummaryTarget(Snapshot snapshot, SummaryTargets.Target target) {
        String expected = snapshot.summary().providerFingerprints().get(target.provider().toLowerCase(Locale.ROOT));
        if (expected == null) return; // Actual task targets are checked by requireTarget before planning.
        var current = providers.find(target.provider()).orElseThrow(() -> changed("Summary provider is no longer enabled"));
        if (!expected.equals(fingerprint(current))) throw changed("Summary provider configuration changed");
    }
    public Target currentTarget(String provider,String model,Integer requestOverride) { return target(provider,model,requestOverride); }
    public ProviderCapabilityRequest.OutputBudget outputBudget(String provider,String model,Integer requestOverride) {
        Target target = target(provider, model, requestOverride);
        return new ProviderCapabilityRequest.OutputBudget(target.maxOutputTokens(), target.outputReserve(), target.outputReserveEstimated());
    }
    private static ProviderException changed(String message) {
        return new ProviderException("text_configuration_changed", message, HttpExchangeState.Acceptance.NOT_SENT, null, 0);
    }
    private Target target(String provider, String model) {
        return target(provider, model, null);
    }
    private Target target(String provider, String model, Integer requestOverride) {
        ResolvedProvider configured = providers.find(provider).orElseThrow(() -> changed("Text provider is unavailable"));
        var capacity = ProviderContextBudget.resolve(config, configured, model, requestOverride);
        int window = capacity.contextWindow(), safety = capacity.safetyReserve();
        var profile = ProviderModelCapabilities.from(config, configured).forModel(model);
        var budget = capacity.output();
        List<String> modalities = List.of("image", "pdf", "audio", "video", "file").stream().filter(profile::supportsModality).toList();
        return new Target(configured.getName(), model, configured.getType(), fingerprint(configured), window, budget.reserve(), safety,
            modalities, profile.functionTools(), budget.requestLimit(), budget.estimated());
    }
    private String fingerprint(ResolvedProvider provider) {
        Map<String, Object> facts = new LinkedHashMap<>();
        // Hash the whole resolved protocol configuration and resolved credential version, retaining only the digest.
        facts.put("provider", json.convertValue(provider, Map.class));
        facts.put("resolvedBase", OutboundProxyResolver.resolveEnv(provider.getBaseUrl()));
        facts.put("resolvedKey", OutboundProxyResolver.resolveEnv(provider.getApiKey()));
        return IdempotencyReceiptStore.requestHash(facts);
    }
    public record Snapshot(String snapshotId, String policyVersion, List<Target> targets, SummaryPolicy summary, boolean visionEnabled, Map<String,String> agentProviders) {
        public Snapshot { targets = List.copyOf(targets); agentProviders = agentProviders == null ? Map.of() : Map.copyOf(agentProviders); }
        public Snapshot(String snapshotId,String policyVersion,List<Target> targets,SummaryPolicy summary,boolean visionEnabled) { this(snapshotId,policyVersion,targets,summary,visionEnabled,Map.of()); }
    }
    public record Target(String provider, String model, String protocol, String connectionFingerprint, int contextWindow,
        int outputReserve, int safetyReserve, List<String> modalities, boolean functionTools,
        Integer maxOutputTokens, boolean outputReserveEstimated) {
        public Target {
            modalities = List.copyOf(modalities);
            if (maxOutputTokens == null) outputReserveEstimated = true;
        }
        public Target(String provider, String model, String protocol, String connectionFingerprint, int contextWindow,
            int outputReserve, int safetyReserve, List<String> modalities, boolean functionTools) {
            this(provider, model, protocol, connectionFingerprint, contextWindow, outputReserve, safetyReserve, modalities,
                functionTools, null, true);
        }
        public int inputBudget() { return contextWindow - outputReserve - safetyReserve; }
        public String identity() { return connectionFingerprint + ":" + model; }
    }
    public record SummaryPolicy(boolean enabled, int maxCharacters, int maxOutputTokens, long timeoutMillis,
        SummaryTargets.Target dedicatedTarget, SummaryTargets.Target fallbackTarget,
        Map<String, String> providerFingerprints, long attemptTimeoutMillis) {
        public SummaryPolicy {
            providerFingerprints = providerFingerprints == null ? Map.of() : Map.copyOf(providerFingerprints);
        }
        /** Policy without a dedicated summary target. */
        public SummaryPolicy(boolean enabled, int maxCharacters, int maxOutputTokens, long timeoutMillis) {
            this(enabled, maxCharacters, maxOutputTokens, timeoutMillis, null, null, Map.of());
        }
        public SummaryPolicy(boolean enabled, int maxCharacters, int maxOutputTokens, long timeoutMillis,
            SummaryTargets.Target dedicatedTarget, SummaryTargets.Target fallbackTarget, Map<String, String> providerFingerprints) {
            this(enabled, maxCharacters, maxOutputTokens, timeoutMillis, dedicatedTarget, fallbackTarget, providerFingerprints, 0);
        }
        /** Older frozen configurations retain their original total-budget-only policy. */
        public long attemptBudgetMillis() { return attemptTimeoutMillis > 0 ? attemptTimeoutMillis : timeoutMillis; }
        public List<SummaryTargets.Target> targetsFor(Target actual) {
            return SummaryTargets.ordered(dedicatedTarget, fallbackTarget,
                new SummaryTargets.Target(actual.provider(), actual.model()), SummaryTargets.Target::key);
        }
    }
}
