package com.harnex.web.api.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.harnex.config.AgentConfig;
import com.harnex.contract.runtime.RuntimeMetaExtension;
import com.harnex.observability.HarnexMetrics;
import com.harnex.config.RuntimeDataPaths;
import com.harnex.config.RuntimeDomainPaths;
import com.harnex.config.RuntimeProfile;
import com.harnex.config.UserDataPaths;
import com.harnex.provider.*;
import com.harnex.attachment.DocumentTextExtractor;
import com.harnex.workspace.WorkspaceReadService;

@RestController
@RequestMapping("/api/v1")
public final class RuntimeMetaController {
    private final AgentConfig cfg;
    private final ProviderRouter router;
    private final ResolvedProviderRegistry providerRegistry;
    private final HarnexMetrics metrics;
    private final List<RuntimeMetaExtension> extensions;
    public RuntimeMetaController(AgentConfig cfg, ProviderRouter router, ResolvedProviderRegistry providers,
        HarnexMetrics metrics, List<RuntimeMetaExtension> extensions) {
        this.cfg = cfg; this.router = router; this.providerRegistry = providers; this.metrics = metrics;
        this.extensions = List.copyOf(extensions);
    }
    @GetMapping("/models")
    public Map<String, Object> models() {
        // This is a selectable provider/model catalogue, not a failover chain. Interactive Chat routes an explicit
        // selection directly and uses only the reserved `fallback` provider on failure, so every enabled provider is
        // safe to publish even when it is not router.default-provider.
        List<String> provNames = router.listAvailableProviderNames();
        Map<String, ResolvedProvider> configured = new LinkedHashMap<>();
        for (ResolvedProvider provider : providerRegistry.providers()) {
            if (provider != null && provider.getName() != null) {
                configured.put(provider.getName().toLowerCase(Locale.ROOT), provider);
            }
        }
        List<Map<String, Object>> detailed = new ArrayList<>();
        for (String providerName : provNames) {
            ResolvedProvider p = configured.get(providerName.toLowerCase(Locale.ROOT));
            if (p == null) {
                continue;
            }
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.getName());
            pm.put("label", p.getLabel() == null || p.getLabel().isBlank() ? p.getName() : p.getLabel());
            pm.put("profile", p.getProfile() == null ? "" : p.getProfile());
            pm.put("type", p.getType() == null ? "openai-compatible" : p.getType());
            pm.put("routable", true);
            pm.put("defaultModel", p.getDefaultModel() == null ? "" : p.getDefaultModel());
            ProviderModelCapabilities providerCapabilities = ProviderModelCapabilities.from(cfg, p);
            List<Map<String, Object>> models = new ArrayList<>();
            boolean hasDefaultModel = false;
            if (p.getModels() != null) {
                for (AgentConfig.ModelDef md : p.getModels()) {
                    if (md == null || md.getId() == null || md.getId().isBlank()) {
                        continue;
                    }
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("id", md.getId());
                    mm.put("provider", p.getName());
                    mm.put("ref", p.getName() + ":" + md.getId());
                    mm.put("label", md.getLabel() == null || md.getLabel().isBlank() ? md.getId() : md.getLabel());
                    if (p.getDefaultModel() != null && md.getId().trim().equalsIgnoreCase(p.getDefaultModel().trim())) {
                        mm.put("default", true);
                        hasDefaultModel = true;
                    }
                    if (md.getTier() != null && !md.getTier().isBlank()) {
                        mm.put("tier", md.getTier());
                    }
                    // costHint is intentionally returned but not yet rendered by the UI \u2014 see
                    // ModelDef javadoc. Operators can populate it now and we'll surface it later.
                    if (md.getCostHint() != null && !md.getCostHint().isBlank()) {
                        mm.put("costHint", md.getCostHint());
                    }
                    if (md.getDescription() != null && !md.getDescription().isBlank()) {
                        mm.put("description", md.getDescription());
                    }
                    if (md.getInputModalities() != null && !md.getInputModalities().isEmpty()) {
                        mm.put("inputModalities", md.getInputModalities());
                    }
                    publishReasoningCapabilities(mm, providerCapabilities.profile(md.getId()), p.getEffort());
                    publishContextBudget(mm, p, md.getId());
                    models.add(mm);
                }
            }
            // Defensive fallback for snapshots created outside ProviderConfigurationResolver (mainly component tests
            // and future reload implementations). The resolver normally guarantees this invariant.
            if (!hasDefaultModel && p.getDefaultModel() != null && !p.getDefaultModel().isBlank()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("id", p.getDefaultModel());
                mm.put("provider", p.getName());
                mm.put("ref", p.getName() + ":" + p.getDefaultModel());
                mm.put("label", p.getDefaultModel());
                mm.put("default", true);
                publishReasoningCapabilities(mm, providerCapabilities.profile(p.getDefaultModel()), p.getEffort());
                publishContextBudget(mm, p, p.getDefaultModel());
                models.add(mm);
            }
            pm.put("models", models);
            detailed.add(pm);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order", provNames);
        m.put("providers", provNames);
        m.put("providersDetailed", detailed);
        return m;
    }

    private void publishContextBudget(Map<String, Object> model, ResolvedProvider provider, String modelId) {
        try {
            var budget = ProviderContextBudget.resolve(cfg, provider, modelId, null);
            var settings = cfg.getSummarizer();
            model.put("contextBudget", Map.of("windowTokens", budget.contextWindow(), "source", budget.source(),
                "inputTokens", budget.inputTokens(), "outputReserveTokens", budget.output().reserve(),
                "softThresholdTokens", budget.inputTokens() * settings.getSoftThresholdPercent() / 100,
                "hardThresholdTokens", budget.inputTokens() * settings.getHardThresholdPercent() / 100,
                "targetTokens", budget.inputTokens() * settings.getTargetPercent() / 100));
        } catch (ProviderException invalid) {
            // One invalid output/window combination must not hide other selectable models.
            model.put("contextBudgetError", invalid.getMessage());
        }
    }

    private static void publishReasoningCapabilities(Map<String, Object> model,
        ProviderModelCapabilities.Profile capabilities, String configuredDefault) {
        model.put("reasoningEfforts", new ArrayList<>(capabilities.reasoningEfforts()));
        model.put("reasoningSupported", capabilities.reasoningSupported());
        model.put("defaultReasoningEffort",
            configuredDefault == null || configuredDefault.isBlank() ? "auto" : configuredDefault);
        if (capabilities.functionToolReasoningEffort() != null) {
            model.put("functionToolReasoningEffort", capabilities.functionToolReasoningEffort());
        }
    }

    /**
     * Static diagnostics aggregation (docs/03 §3.14): configuration-level health a client or `harnex doctor` can read
     * in one call. Deliberately does NOT live-probe providers (that costs tokens / hangs on bad proxies) — it reports
     * what is configured; live connectivity surfaces through actual runs and /actuator/health.
     */
    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("protocolVersion", ProtocolController.PROTOCOL_VERSION);
        m.put("authEnabled", cfg.getAuth() != null && cfg.getAuth().isEnabled());
        List<Map<String, Object>> providers = new ArrayList<>();
        for (ResolvedProvider p : providerRegistry.providers()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.getName());
            pm.put("label", p.getLabel() == null || p.getLabel().isBlank() ? p.getName() : p.getLabel());
            pm.put("profile", p.getProfile() == null ? "" : p.getProfile());
            pm.put("type", p.getType() == null ? "openai-compatible" : p.getType());
            pm.put("defaultModel", p.getDefaultModel() == null ? "" : p.getDefaultModel());
            pm.put("modelCount", p.getModels() == null ? 0 : p.getModels().size());
            providers.add(pm);
        }
        m.put("providers", providers);
        m.put("providerConfiguration", providerRegistry.diagnostics());
        m.put("defaultProvider", router.defaultProviderName().orElse(null));
        extensions.forEach(extension -> m.putAll(extension.diagnostics()));
        boolean dataDirWritable;
        try {
            Path probe = Path.of(cfg.getDataDir(), ".diag-probe");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            dataDirWritable = true;
        } catch (Exception e) {
            dataDirWritable = false;
        }
        m.put("dataDirWritable", dataDirWritable);
        m.put("storage", Map.of(
            "dataDir", cfg.getDataDir(),
            "sharedDataDir", cfg.getSharedDataDir(),
            "userDataDir", UserDataPaths.resolve(cfg).toString(),
            "projectSkillsDir", cfg.getRuntimeProfile() == RuntimeProfile.PROJECT
                && cfg.getSkillsDir() != null && !cfg.getSkillsDir().isBlank()
                ? RuntimeDataPaths.projectRoot(cfg).resolve(cfg.getSkillsDir()).normalize().toString() : "",
            "canOpenDirectory", !RuntimeDomainPaths.id(cfg).startsWith("remote-")));
        return m;
    }

    /** Only the current, configured data owner can be opened; clients cannot submit an arbitrary filesystem path. */
    @PostMapping("/storage/open")
    public Map<String, Object> openDataDirectory() {
        if (RuntimeDomainPaths.id(cfg).startsWith("remote-")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Remote storage is located on the remote host");
        }
        Path path = Path.of(cfg.getDataDir());
        WorkspaceReadService.openWithSystem(path, "opening the runtime data directory");
        return Map.of("opened", true);
    }

    /**
     * Statusline payload — small JSON the frontend polls (or fetches once at thread load) to render the cockpit-bar:
     * default model, sandbox backend, workspace AGENTS.md indicator. Token usage is delivered incrementally via the
     * {@code usage} SSE event from {@code AgentLoop}.
     */
    @GetMapping("/statusline")
    public Map<String, Object> statusline() {
        Map<String, Object> m = new LinkedHashMap<>();
        ResolvedProvider defaultProvider = router.defaultProviderName().flatMap(providerRegistry::find).orElse(null);
        m.put("provider", defaultProvider == null ? null : defaultProvider.getName());
        m.put("model", defaultProvider == null ? null : defaultProvider.getDefaultModel());
        extensions.forEach(extension -> m.putAll(extension.statusline()));
        m.put("providers", router.listAvailableProviderNames());
        // Lifetime cumulative token counters across stream + non-stream paths (incl. MoA aggregator,
        // summariser). Surfaced here so the cockpit-bar can display totals even when a turn used a
        // non-streaming side-channel that never emitted a `usage` SSE event.
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("prompt", (long)metrics.promptTokens.count());
        tokens.put("completion", (long)metrics.completionTokens.count());
        tokens.put("total", (long)(metrics.promptTokens.count() + metrics.completionTokens.count()));
        tokens.put("cachedInput", (long)metrics.cachedInputTokens.count());
        tokens.put("cacheWriteInput", (long)metrics.cacheWriteInputTokens.count());
        tokens.put("reasoning", (long)metrics.reasoningTokens.count());
        m.put("tokens", tokens);
        // Upload limits — single source of truth for the SPA so it stops hard-coding 50 MB
        // pre-flight checks that drift from the backend's actual harnex.upload.maxSingleFileBytes
        // (default 200 MiB). Operators who tune harnex.upload need the UI guard to follow.
        AgentConfig.Upload upload = cfg.getUpload();
        if (upload != null) {
            Map<String, Object> limits = new LinkedHashMap<>();
            limits.put("maxSingleFileBytes", Math.min(upload.getMaxSingleFileBytes(), 64L * 1024 * 1024));
            limits.put("perThreadMaxBytes", upload.getPerThreadMaxBytes());
            AgentConfig.Vision vision = cfg.getVision();
            if (vision != null) {
                limits.put("maxAttachmentsPerMessage", vision.getMaxAttachmentsPerMessage());
                limits.put("nativeMaxAttachmentBytes", vision.getMaxAttachmentBytes());
                limits.put("nativeAttachmentsEnabled", vision.isEnabled());
                limits.put("ocrFallbackEnabled", vision.isOcrFallback());
            }
            limits.put("localExtractionMaxFileBytes", DocumentTextExtractor.MAX_PARSE_FILE_BYTES);
            m.put("upload", limits);
        }
        return m;
    }
}
