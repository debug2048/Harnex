package com.harnex.config;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.harnex.conversation.ContinuationSummary;
import com.harnex.image.ImageServiceConfig;

import jakarta.annotation.PostConstruct;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Data
@Component
@ConfigurationProperties(prefix = "harnex")
public class AgentConfig {
    private static final Logger LOG = LoggerFactory.getLogger(AgentConfig.class);
    @JsonIgnore
    @ToString.Exclude
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private transient volatile RuntimeConfiguration runtimeView;
    @JsonIgnore
    @ToString.Exclude
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private final transient ConcurrentMap<Object, Object> derivedSettings = new ConcurrentHashMap<>();

    void attachRuntimeView(RuntimeConfiguration runtime) { runtimeView = runtime; }
    private AgentConfig settingsView() { return runtimeView == null ? this : runtimeView.configuration(); }

    /** Derived matchers, presets and executors belong to the same snapshot as the settings that created them. */
    @SuppressWarnings("unchecked")
    public <T> T derivedSettings(Object owner, Function<AgentConfig, T> create) {
        AgentConfig view = settingsView();
        return (T)view.derivedSettings.computeIfAbsent(owner, ignored -> create.apply(view));
    }

    public void closeDerivedSettings(Object owner) {
        closeResource(settingsView().derivedSettings.remove(owner));
        if (runtimeView != null) closeResource(derivedSettings.remove(owner));
    }

    void closeDerivedSettings() {
        derivedSettings.values().forEach(AgentConfig::closeResource);
        derivedSettings.clear();
    }

    private static void closeResource(Object value) {
        if (value instanceof AutoCloseable closeable) {
            try { closeable.close(); }
            catch (Exception failure) { LOG.warn("Configuration resource close failed ({})", failure.getClass().getSimpleName()); }
        }
    }

    // The bound Spring bean is a view; published snapshot DTOs have no runtimeView and remain ordinary values.
    public Map<String, ProviderConfig> getProviders() { AgentConfig c = settingsView(); return c == this ? providers : c.getProviders(); }
    public Map<String, ProviderProfileDef> getProviderProfiles() { AgentConfig c = settingsView(); return c == this ? providerProfiles : c.getProviderProfiles(); }
    public Router getRouter() { AgentConfig c = settingsView(); return c == this ? router : c.getRouter(); }
    public Summarizer getSummarizer() { AgentConfig c = settingsView(); return c == this ? summarizer : c.getSummarizer(); }
    public Safety getSafety() { AgentConfig c = settingsView(); return c == this ? safety : c.getSafety(); }
    public Workspace getWorkspace() { AgentConfig c = settingsView(); return c == this ? workspace : c.getWorkspace(); }
    public Toolsets getToolsets() { AgentConfig c = settingsView(); return c == this ? toolsets : c.getToolsets(); }
    public Hooks getHooks() { AgentConfig c = settingsView(); return c == this ? hooks : c.getHooks(); }
    public WebSearch getWebSearch() { AgentConfig c = settingsView(); return c == this ? webSearch : c.getWebSearch(); }
    public Map<String, ImageServiceConfig> getImageServices() { AgentConfig c = settingsView(); return c == this ? imageServices : c.getImageServices(); }
    public String getDefaultImageService() { AgentConfig c = settingsView(); return c == this ? defaultImageService : c.getDefaultImageService(); }
    public HttpConfig getHttp() { AgentConfig c = settingsView(); return c == this ? http : c.getHttp(); }
    public Mcp getMcp() { AgentConfig c = settingsView(); return c == this ? mcp : c.getMcp(); }
    public Execution getExecution() { AgentConfig c = settingsView(); return c == this ? execution : c.getExecution(); }

    /**
     * Validate sub-section invariants once Spring has finished binding {@code @ConfigurationProperties}. Each
     * sub-section owns its own {@code validate(Logger)} method (see {@link Summarizer#validate}); this hook is the
     * single startup-time entry point. Failures throw and abort startup so an operator with a typo notices BEFORE the
     * agent is taking traffic, rather than seeing a runtime stack trace deep inside a middleware on the first long
     * conversation.
     */
    @PostConstruct
    void postBind() {
        validateConfiguration();
        RuntimeDomainPaths.id(this);
        RuntimeDomainPaths.resolve(this);
        if (runtimeOwnerKind == null
            || !Set.of("web", "tauri", "vscode", "cli", "standalone").contains(runtimeOwnerKind)) {
            throw configError("runtime-owner-kind must be web, tauri, vscode, cli or standalone");
        }
        if (summarizer != null)
            summarizer.validate(LOG);
        if (subagent != null)
            subagent.validate(LOG);
    }

    /** Validate configuration sections that do not depend on runtime protocol resolution. */
    public void validateConfiguration() {
        if (sandbox != null && !sandbox.isEmpty()) throw configError(
            "sandbox.* has been removed; use execution.environment=host|docker and SSH Runtime connections");
        if (execution != null) execution.validate();
        if (vision != null)
            vision.validate();
    }

    private static IllegalArgumentException configError(String message) {
        return new IllegalArgumentException("Invalid harnex configuration: " + message);
    }

    /**
     * Schema version of {@code agent-config.yml}. Bump this in lock-step with
     * {@link com.harnex.config.ConfigMigrator#CURRENT_SCHEMA_VERSION} whenever a structural change requires a
     * migration. The migrator runs at startup and logs a clear WARN/ERROR if the on-disk version is older or newer than
     * what this binary supports.
     *
     * <p>
     * {@code 0} (default) means "no version declared" and is treated as v1.
     */
    private int version = 0;
    private String dataDir;
    /** The personal archive is user-owned and never falls back to the process working directory. */
    public String getDataDir() {
        return RuntimeDataPaths.resolve(this, dataDir).toString();
    }
    /** Public history remains at the original project path; execution caches belong to one host slot. */
    private String sharedDataDir = "";
    public String getSharedDataDir() {
        return RuntimeDataPaths.resolve(this, sharedDataDir == null || sharedDataDir.isBlank() ? dataDir : sharedDataDir).toString();
    }
    private RuntimeProfile runtimeProfile = RuntimeProfile.PROJECT;
    /**
     * Stable user-level control-plane data. Blank resolves to ~/.harnex and is intentionally independent from the
     * active workspace dataDir so Auth/Boot Token/user settings survive a workspace JVM replacement.
     */
    private String userDataDir = "";
    /**
     * Lifecycle namespace owned by the host that spawned this Runtime. Hosts should provide a stable, opaque id for
     * their execution slot. A directly launched development JVM receives a process-scoped standalone id so it still
     * never falls back to shared user-root runtime files.
     */
    private String runtimeDomainId = "";
    /**
     * Host-owned directory for ephemeral runtime state (boot token, manifest and logs). Blank resolves to
     * {@code <userDataDir>/runtime-domains/<runtimeDomainId>}.
     */
    private String runtimeDomainDir = "";
    /** Diagnostic owner kind written into the dataDir lock (web, tauri, vscode, cli or standalone). */
    private String runtimeOwnerKind = "standalone";
    private String skillsDir = ".harnex/skills";
    /** General per-AgentLoop ceiling used by autonomous goals and as the inherited sub-agent ceiling. */
    private int maxIterations = 200;
    /**
     * Additional ceiling for a top-level interactive chat run. A positive value is combined with
     * {@link #maxIterations} using the lower bound; 0 or negative disables the interactive-specific bound. Goal and
     * sub-agent loops deliberately ignore it because they already own independent lifecycle/budget controls.
     */
    private int ordinaryMaxIterations = 64;
    /**
     * Extra no-tool LLM calls allowed after {@link #maxIterations} work iterations are exhausted. These calls exist
     * only to let the model read the last tool results and produce a current best-effort answer instead of ending the
     * stream on a tool message.
     */
    private int finalizationGraceIterations = 2;
    /**
     * Maximum lifetime of one SSE subscription. Timing out detaches only that transport connection; it never cancels
     * the run, and clients resume through {@code events?afterSeq=...}. The Spring MVC async timeout should remain above
     * this value so the emitter's timeout callback can detach the subscription cleanly.
     */
    private long sseTimeoutMs = 900_000L;
    private int toolParallelism = 8;
    /**
     * Bounded backlog for the parallel-tool executor pool. When all worker threads are stuck (e.g. on interrupt-deaf
     * I/O) and this queue fills, additional tool dispatches are rejected with a structured "tool pool saturated" error
     * rather than silently piling up across requests — the same back-pressure pattern as
     * {@code subagent.queue-capacity}. Sized as a multiple of {@code toolParallelism} so the default scales with
     * parallelism.
     */
    private int toolQueueCapacity = 64;

    /**
     * The only user-facing provider collection. Map keys are stable provider ids. The highest-precedence configuration
     * document that declares this map owns the complete collection; concrete providers do not merge across documents.
     */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    private Map<String, ImageServiceConfig> imageServices = new LinkedHashMap<>();
    private String defaultImageService;
    /**
     * Maintainer-owned protocol profiles. A profile combines the adapter type, wire capabilities, an internal
     * capability/suggestion catalogue, model rules, and safe operator defaults. Its catalogue is not automatically
     * exposed by a concrete provider.
     */
    private Map<String, ProviderProfileDef> providerProfiles = new LinkedHashMap<>();
    private Router router = new Router();
    private Toolsets toolsets = new Toolsets();
    private Budget budget = new Budget();
    private Approval approval = new Approval();
    private Execution execution = new Execution();
    /** Bind only to reject obsolete configuration instead of silently running on the host. */
    private Map<String, Object> sandbox;
    private Moa moa = new Moa();
    private Auth auth = new Auth();
    private Subagent subagent = new Subagent();
    private Gateway gateway = new Gateway();
    private Summarizer summarizer = new Summarizer();
    private Mcp mcp = new Mcp();
    /**
     * Host-injected, process-local MCP bridge (for example the owning VS Code Extension Host).
     * Kept separate from the user-owned {@link Mcp#servers} list so a command-line bridge never
     * replaces or reorders configured user MCP servers. It is not persisted by ConfigControlService.
     */
    private McpServer runtimeMcpBridge;
    private Verify verify = new Verify();
    private Vision vision = new Vision();
    private Snapshot snapshot = new Snapshot();
    private Goal goal = new Goal();
    private Plan plan = new Plan();
    private WebSearch webSearch = new WebSearch();
    private Ask ask = new Ask();
    /**
     * VSCode-style HTTP egress configuration shared by LLM providers, WebFetch, and other outbound components (see
     * {@link com.harnex.net.ProxyResolution}).
     *
     * <p>
     * Defaults: no proxy, empty bypass, {@code proxySupport=fallback}. Unconfigured users see no behaviour change.
     * </p>
     */
    private HttpConfig http = new HttpConfig();
    private Cors cors = new Cors();
    private Upload upload = new Upload();
    private Safety safety = new Safety();
    private Workspace workspace = new Workspace();

    /** Public BYOK provider DTO. The map key under {@code harnex.providers} is the stable provider id. */
    @Data
    public static class ProviderConfig {
        /** Human-readable frontend label; the map key remains the stable routing id. */
        private String label;
        /** Required entry under {@code harnex.provider-profiles}; lookup is case-insensitive. */
        private String profile;
        /** Disabled providers remain in source configuration but are excluded from the runtime registry. */
        private boolean enabled = true;
        private String baseUrl;
        private String apiKey;
        /**
         * Default upstream model id; null inherits the profile default. Capability lookup is case-insensitive, while
         * the configured spelling is preserved for the upstream request.
         */
        private String defaultModel;
        /**
         * Models this concrete endpoint exposes to the runtime picker. Null/empty means the effective
         * {@link #defaultModel} only; entries with canonical ids inherit matching profile metadata/capabilities.
         */
        private List<ModelDef> models;
        /** Optional per-provider HTTP proxy override. */
        private String proxy;
        /**
         * Optional output-token limit override. Positive values override the model default; null/zero/negative values
         * mean automatic: use a declared {@code default-max-tokens}, otherwise omit the optional request limit.
         */
        private Integer maxTokens;
        /** Default context window in tokens for models without an explicit model-local capacity. */
        private Integer contextWindow;
        /**
         * Optional strict-relay client identity: {@code none}, {@code codex}, or {@code claude-code}. This is an
         * endpoint concern, not a model capability or profile default.
         */
        private String clientEmulation = "none";
        /** Optional advertised CLI version used only when client emulation is enabled. */
        private String clientVersion;
        private ProviderFeatures features = new ProviderFeatures();
    }

    /** Common user preferences. Endpoint support remains capability-gated after these intents are expanded. */
    @Data
    public static class ProviderFeatures {
        private String attachments = "auto";
        private String promptCache = "auto";
        /**
         * {@code auto}/{@code on}: prefer model-native web search when declared, otherwise leave any available local
         * {@code web_search} function in place. {@code off}: never advertise the provider-native search tool; it does
         * not disable the independently configured local third-party search tool.
         */
        private String webSearch = "auto";
        private String reasoning = "auto";
        private String effort = "auto";
        /**
         * Advanced historical-reasoning policy: {@code auto}, {@code preserve}, or {@code discard}. Auto follows the
         * selected model's declared requirement/default and is the only value ordinary configuration UIs need expose.
         */
        private String reasoningHistory = "auto";
    }

    /**
     * Maintainer-owned provider template. It always defines one adapter and wire-capability contract and may also
     * provide safe official endpoint/default-model values plus an internal model capability/suggestion catalogue.
     * A concrete provider supplies the key and its selectable model subset.
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ProviderProfileDef extends ProviderCapabilities {
        /** Short configuration-editor name; the map key remains the profile reference. */
        private String label;
        private String type;
        private String baseUrl;
        private String defaultModel;
        private List<ModelCapabilityRule> modelCapabilityRules = new ArrayList<>();
        /** Internal exact capability/presentation templates and configuration-editor suggestions. */
        private List<ModelDef> models = new ArrayList<>();
        private String proxy;
        private Integer maxTokens;
        /** Optional provider-wide operator default; distinct from the inherited wire-level thinking capability. */
        private String defaultThinking;
        private String effort;
        private Boolean promptCaching;
        private Boolean promptCacheKey;
        private Boolean promptCacheExplicit;
        private List<String> betas = new ArrayList<>();
        private Boolean sendTemperature;
        /** Optional provider-level operator intent; wire support remains the inherited native-web-search capability. */
        private boolean defaultNativeWebSearch;
    }

    /**
     * Workspace / file-access model. The agent always operates directly on a real project directory — the project root
     * resolved from {@link Workspace#root} → {@code HARNEX_WORKSPACE} env → the JVM working directory
     * ({@code user.dir}) — mirroring mainstream coding agents (Claude Code, Cursor, Aider). The graduated
     * GREEN/YELLOW/RED access zones gate the file tools' reach: project files are free (GREEN), arbitrary other paths
     * are readable and optionally write-gated (YELLOW), and sensitive credential/key/system paths are always refused
     * (RED).
     */
    @Data
    public static class Workspace {
        /**
         * Real project root the agent works on. Blank → the JVM working directory ({@code user.dir}). Resolution order:
         * this value → {@code HARNEX_WORKSPACE} env → {@code user.dir}. The desktop shell passes the user-picked folder
         * here.
         */
        private String root = "";
        /**
         * YELLOW-zone write policy: deny = hard block, ask = require a live per-call approval, allow = follow the
         * ordinary thread permission mode. RED remains denied under every value. No path allowlist is used because
         * multi-repository and temporary-directory workflows are intentionally open-ended.
         */
        private String outsideWritePolicy = "ask";
        /** Additional workspace-relative glob exclusions shared by glob/grep; these do not change file access rights. */
        private List<String> searchExcludeGlobs = new ArrayList<>();
        /**
         * Forward-slash globs ({@code **}, {@code *}, {@code ?}) matched against the absolute real path. Any match is a
         * RED zone: reads AND writes are refused regardless of the outside-write policy. Covers credential stores, key
         * material and this app's own config. Note: these only bite OUTSIDE the project root — in-project secrets stay
         * readable (GREEN) but are still write-protected by {@code harnex.safety.protected-globs}.
         */
        private List<String> sensitiveGlobs =
            new ArrayList<>(List.of("**/.ssh/**", "**/.aws/**", "**/.gnupg/**", "**/.kube/**", "**/.docker/config.json",
                "**/.env", "**/.env.*", "**/*.pem", "**/*.key", "**/id_rsa", "**/id_ed25519", "**/.git/config",
                "**/.netrc", "**/.npmrc", "**/.pypirc", "**/.harnex/**", "**/.harness/**",
                // macOS privacy stores. These rules apply only outside the active workspace; a repository
                // that happens to contain an ordinary Library/ folder is not reclassified.
                "**/Library/Keychains/**", "**/Library/Mail/**", "**/Library/Messages/**",
                "**/Library/Safari/**", "**/Library/Cookies/**", "**/Library/Containers/**",
                "**/Library/Group Containers/**", "**/Library/Mobile Documents/**"));
    }

    /**
     * File mutation guards: exact context matching for edits, tracked reads for whitespace-tolerant edits and
     * whole-file overwrites, and project-level protected paths ({@code .harnexignore} or {@link #protectedGlobs}).
     *
     * <p>
     * {@code force=true} allows whitespace tolerance without a tracked read; {@code overwrite=true} acknowledges a
     * whole-file replacement. Neither flag bypasses protected paths, and edit commit checks remain unconditional.
     */
    @Data
    public static class Safety {
        /**
         * Maximum tracked-read entries per thread (LRU). Bounded to avoid unbounded growth from pathological
         * glob/grep+read loops.
         */
        private int trackerMaxEntriesPerThread = 256;
        /**
         * Ant-style globs (relative to workspace root) that are NEVER editable by the LLM via
         * {@code file_edit}/{@code file_write}. Matches use forward slashes. Empty = no extra protection beyond
         * {@code .harnexignore}.
         */
        private List<String> protectedGlobs = new ArrayList<>(List.of("**/.env", "**/.env.*", "**/secrets/**",
            "**/*.pem", "**/*.key", "**/id_rsa", "**/id_ed25519", "**/.git/**"));
        /**
         * If true, also honour a {@code .harnexignore} file in the workspace root: lines are gitignore-style globs
         * (one per line, {@code #} comments). Reloaded automatically when the file's mtime changes.
         */
        private boolean respectIgnoreFile = true;
        /** Scrub common credentials before tool results are displayed, persisted, or reused by the model. */
        private boolean redactSecretsInToolResults = true;
        /** Optional compliance mode for common PII patterns; disabled by default to reduce false positives. */
        private boolean redactPiiInToolResults = false;
    }

    /**
     * CORS configuration. Replaces the per-controller {@code @CrossOrigin} (which defaults to "*") with a config-driven
     * allow-list. Empty list disables CORS entirely (recommended when the SPA is served from the same origin via
     * nginx).
     */
    @Data
    public static class Cors {
        /** Origins permitted to call /api/**. Wildcards "*" allowed but not recommended. */
        private List<String> allowedOrigins =
            new ArrayList<>(List.of("http://localhost:18081", "http://127.0.0.1:18081"));
        private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        private List<String> allowedHeaders = new ArrayList<>(List.of("*"));
        private boolean allowCredentials = false;
        private long maxAgeSeconds = 3600;
    }

    /**
     * Upload-related limits beyond the per-thread token-bucket enforced by {@code UploadService}.
     */
    @Data
    public static class Upload {
        /** Cumulative size of all uploaded files for a single thread. 0 = unlimited. Default 1 GB. */
        private long perThreadMaxBytes = 1024L * 1024 * 1024;
        /** Per-file hard cap. 0 = unlimited. Default 200 MiB — enough for typical PDFs / videos. */
        private long maxSingleFileBytes = 200L * 1024 * 1024;
    }

    /**
     * Native multimodal handling for uploaded images/documents. Each adapter emits its own protocol shape (Chat
     * {@code image_url}, Responses {@code input_image/input_file}, Anthropic {@code image/document}) rather than
     * assuming the OpenAI Chat convention is universal.
     *
     * <p>
     * Model support must be declared with {@link ModelDef#inputModalities} on an exact catalog entry. Unknown/custom
     * relay ids therefore remain attachment-disabled until the endpoint contract is explicitly configured. All other
     * cases degrade to a visible {@code file_read}/OCR hint; support is never inferred from provider type, vendor
     * prefix, or a model-like relay alias.
     *
     * <p>
     * OCR is offered as an opt-in fallback for the no-vision case via {@code ocrFallback}. Upload remains storage-only;
     * when a model follows the visible {@code file_read}/OCR fallback hint, {@code file_read} invokes the bounded local
     * extractor and caches a sidecar. Native-capable models keep the original-byte path and pay no OCR cost.
     */
    @Data
    public static class Vision {
        /** Master switch. Set to false on cost-sensitive deployments. */
        private boolean enabled = true;
        /** Maximum bytes of any single native inline attachment. */
        private long maxAttachmentBytes = 10L * 1024 * 1024; // 10 MB
        /** Vision detail hint ("auto" | "low" | "high" | "original") — passed verbatim to providers. */
        private String detail = "auto";
        /** Maximum inline attachments per message/request. */
        private int maxAttachmentsPerMessage = 8;
        /** When true, allow file_read to OCR uploaded images on demand for non-vision models. */
        private boolean ocrFallback = false;
        /** Shell template for the OCR fallback hint. {file} is substituted with the upload path. */
        private String ocrCommand = "tesseract \"{file}\" - -l eng+chi_sim";
        /**
         * Tesseract language pack(s) used by the upload-time server-side OCR (when {@code ocrFallback=true} AND
         * tesseract is installed). Format is the standard Tesseract {@code -l} value: a single lang ({@code eng}) or
         * "+"-joined list ({@code eng+chi_sim+jpn}). Requires the corresponding {@code tessdata} files.
         */
        private String ocrLanguage = "eng+chi_sim";
        /** Hard cap on a single-image OCR run, ms. Crash-safe: on timeout sidecar is skipped. */
        private long ocrTimeoutMs = 60_000L;

        private void validate() {
            if (enabled && maxAttachmentBytes <= 0) {
                throw configError(
                    "harnex.vision.max-attachment-bytes must be > 0 when native attachments are enabled");
            }
            if (enabled && maxAttachmentsPerMessage <= 0) {
                throw configError(
                    "harnex.vision.max-attachments-per-message must be > 0 when native attachments are enabled");
            }
            String normalisedDetail = detail == null ? "" : detail.trim().toLowerCase(Locale.ROOT);
            if (!Set.of("auto", "low", "high", "original").contains(normalisedDetail)) {
                throw configError("harnex.vision.detail must be auto|low|high|original, got: " + detail);
            }
            detail = normalisedDetail;
            if (ocrFallback && ocrTimeoutMs <= 0) {
                throw configError("harnex.vision.ocr-timeout-ms must be > 0 when OCR fallback is enabled");
            }
        }
    }

    /**
     * Configurable hard-validation checks (compile / typecheck / lint / test). Listed under
     * {@code harnex.verify.checks} as name -> shell command. The {@code verify} tool runs the requested checks (or all)
     * in the workspace sandbox and returns a structured pass/fail summary.
     */
    @Data
    public static class Verify {
        private Map<String, String> checks = new LinkedHashMap<>();
        /** Per-check timeout in ms. */
        private long timeoutMs = 180_000L;
    }

    /**
     * Autonomous-goal ({@code /goal}) guard rails. A goal is an auto-continuation loop, so these caps are the safety
     * brake when explicitly configured. Turn, token and wall-clock budgets are
     * opt-in (0 = unlimited, overridable per-goal via {@code /goal … +500k}).
     */
    @Data
    public static class Goal {
        /** Optional cap on Goal continuation turns. 0 = unlimited. */
        private int maxTurns = 0;
        /** Default cumulative token budget across the goal. 0 = unlimited unless {@code /goal … +budget} is given. */
        private long defaultTokenBudget = 0L;
        /** Default wall-clock budget across the goal, ms. 0 = unlimited. */
        private long wallClockBudgetMs = 0L;
    }

    /**
     * {@code /plan} review settings. {@code submit_plan} suspends the agent loop until the user approves / rejects the
     * plan over {@code POST /api/plan-review/...}; this timeout bounds that wait so an abandoned review cannot pin a
     * tool-executor worker forever. Headless callers (no SSE emitter) auto-approve and never wait.
     */
    @Data
    public static class Plan {
        /** Max time to wait for a plan-review decision before giving up (and staying in plan-mode). Default 30 min. */
        private long reviewTimeoutMs = 1_800_000L;
    }

    /**
     * {@code web_search} tool configuration. The tool delegates to a pluggable
     * {@link com.harnex.provider.search.SearchBackend} (v1: Tavily). Because it depends on an optional, usually paid,
     * third-party API, the tool gates its own advertisement on {@code enabled && apiKey present && provider resolvable}
     * via {@code Tool.available()} — a fresh install with no key never shows {@code web_search} to the model at all
     * (zero prompt noise). Runtime health (a key that lapses mid-deployment) is handled separately by
     * {@link com.harnex.provider.search.WebSearchHealth}.
     */
    @Data
    public static class WebSearch {
        /** Master switch. Even when true, the tool stays hidden until {@link #apiKey} is set (see Tool.available()). */
        private boolean enabled = true;
        /** Backend key — matched against {@code SearchBackend.name()}. v1 supports {@code tavily}. */
        private String provider = "tavily";
        /** API key for the selected provider. Blank → the tool is not advertised. */
        private String apiKey = "";
        /** Optional endpoint override. Blank → the backend's built-in default (e.g. Tavily's public endpoint). */
        private String endpoint = "";
        /** Optional language for AnySearch/TinyFish. Blank follows the selected service's default. */
        private String language = "";
        /** Default / max number of results requested when the caller doesn't specify {@code maxResults}. */
        private int maxResults = 10;
        /** Per-request HTTP timeout (ms). Kept at/under the tool's executor watchdog budget. */
        private long timeoutMs = 15_000L;
        /** Consecutive terminal failures before the health circuit-breaker opens. */
        private int circuitThreshold = 3;
        /** Circuit-breaker cooldown (ms) once open; calls fast-fail with terminal guidance during this window. */
        private long circuitCooldownMs = 600_000L;
    }

    /**
     * {@code ask_user} tool configuration. The tool suspends the agent loop until the user answers over
     * {@code POST /api/question/...}; {@link #timeoutMs} bounds that wait (headless callers short-circuit to a sentinel
     * immediately). {@link #maxPerTurn} is an anti-nag guard — once the model has asked this many questions in a single
     * user turn, further {@code ask_user} calls return "proceed with best judgment" without prompting.
     */
    @Data
    public static class Ask {
        /** Max time to wait for an answer before returning the "proceed" sentinel. Default 30 min. */
        private long timeoutMs = 1_800_000L;
        /** Hard cap on ask_user calls per user turn (anti-nag). Excess calls short-circuit. */
        private int maxPerTurn = 2;
    }

    /** Configuration-owned model-id matcher; Java adapters never embed vendor/model patterns. */
    @Data
    public static class ModelCapabilityRule {
        /** Java regular expression evaluated with case-insensitive full-match semantics. */
        private String match;
        /** Optional reusable provider profile applied on top of the provider-level profile. */
        private String profile;
        /** Rule-local capability overrides. */
        private ProviderCapabilities capabilities = new ProviderCapabilities();
    }

    /**
     * Operator-curated metadata for a single model exposed to the picker. Presentation fields do not influence routing;
     * {@link #inputModalities} is the one runtime capability contract used to avoid sending unsupported multimodal
     * inputs. Tier is a coarse three-bucket categorisation ({@code high} / {@code medium} / {@code low}) so users have a
     * quick sense of capability without the framework trying to maintain a per-vendor benchmark table.
     */
    @Data
    public static class ModelDef {
        /**
         * Model identifier sent verbatim to the upstream provider. Exact capability lookup and duplicate detection are
         * case-insensitive. Required.
         */
        private String id;
        /** Human-readable display name. Falls back to {@link #id} when empty. */
        private String label;
        /**
         * Coarse capability bucket: {@code high} (frontier reasoning, expensive), {@code medium} (general-purpose,
         * balanced), {@code low} (cheap / fast). Free-form string \u2014 unrecognised values render as a neutral grey
         * badge.
         */
        private String tier;
        /**
         * Reserved for future cost-display features (e.g. {@code "$0.15/M in"} or {@code "1\u00d7"}). The current
         * frontend ignores this field; populating it now lets you switch to a cost-visible UI later without re-editing
         * every provider config.
         */
        private String costHint;
        /** Numeric USD rates used by durable Goal cost budgets; null means cost accounting is unavailable. */
        private Double inputCostPerMillion;
        private Double outputCostPerMillion;
        private Double cachedInputCostPerMillion;
        private Double cacheWriteInputCostPerMillion;
        /** Explicit deployment context limit in tokens; null uses the provider/global fallback. */
        private Integer contextWindow;
        /** Optional documented deployment output limit; distinct from a preferred/default generation length. */
        private Integer maxOutputTokens;
        /** Optional one-line tooltip / sub-label. */
        private String description;
        /**
         * Optional model-specific profile override. Useful for mixed relays such as OpenRouter;
         * matching is exact and case-insensitive, never by a model-name substring.
         */
        private String profile;
        /**
         * Optional canonical model id used only to match this provider's capability rules. The actual {@link #id}
         * is still sent upstream. This lets an explicitly configured relay alias reuse a known family contract
         * without copying its capability fields.
         */
        private String capabilityModel;
        /** Exact model capability overrides applied after the provider profile/defaults. */
        private ProviderCapabilities capabilities = new ProviderCapabilities();
        /**
         * Input modalities explicitly supported by this model id, for example {@code [text, image, file]} for OpenAI,
         * {@code [text, image, pdf]} for Anthropic, or {@code [text, image, audio, video, pdf]} for Gemini. Empty means
         * unknown, not "assume supported". A relay/custom
         * alias must be listed explicitly; unsupported attachments remain local and are surfaced through a visible
         * {@code file_read}/OCR hint.
         */
        private List<String> inputModalities = new ArrayList<>();
    }

    /**
     * Configuration-owned provider/model capability fragment.  All fields are nullable so
     * fragments can be merged into a provider profile, a matching-rule profile, or an exact-model profile.
     * Resolution is compositional: provider profile -> first full-match rule -> exact-model overlay. Java only
     * validates finite wire policies; model identifiers remain data in YAML.
     */
    @Data
    public static class ProviderCapabilities {
        /** Chat-only message role for leading application instructions. Responses/Anthropic reject this field. */
        private String instructionRole;
        /** {@code max_tokens}, {@code max_completion_tokens}, or {@code max_output_tokens}. */
        private String maxTokensField;
        /**
         * Model-specific default output-token request value. Unlike the provider-level {@code max-tokens} override,
         * this field composes through capability rules and exact-model overlays.
         */
        private Integer defaultMaxTokens;
        /** Estimated server-default output for context planning only; never sent as a request limit. */
        private Integer outputReserveTokens;
        /** Chat input_audio.data encoding: base64 (default) or data_url; modalities still require an exact model. */
        private String audioInputEncoding;
        private Boolean streaming;
        /** Whether Chat streaming accepts {@code stream_options.include_usage}. */
        private Boolean streamUsage;
        private Boolean functionTools;
        /** {@code always}, {@code when_thinking_off}, or {@code omit}. */
        private String samplingPolicy;
        /** Null = undeclared; empty = explicitly unsupported; otherwise exact accepted values. */
        private List<String> reasoningEfforts;
        /** {@code never}, {@code tool_calls}, {@code all}, or {@code native_items}. */
        private String reasoningReplay;
        /**
         * Historical reasoning contract: {@code none}, {@code optional}, or {@code required}. Optional/required Chat
         * models must declare full reasoning replay so the runtime can safely honour the operator's history policy.
         */
        private String reasoningHistory;
        /** Optional top-level boolean request field controlling historical-reasoning preservation. */
        private String reasoningHistoryField;
        /**
         * Optional endpoint combination constraint: effective reasoning effort required whenever client function tools
         * are present. This does not reduce the model's standalone {@link #reasoningEfforts}; it models a wire/API
         * restriction such as the observed GPT-5.6 Sol Chat Completions behavior requiring {@code none} for function
         * tools. Use
         * {@code unconstrained} on a child fragment to clear an inherited constraint.
         */
        private String functionToolReasoningEffort;
        /** Anthropic effort values that require thinking to remain enabled; null inherits, empty clears. */
        private List<String> thinkingRequiredEfforts;
        /** {@code none}, {@code auto_prefix}, {@code key}, {@code openai_explicit}, or {@code content_cache_control}. */
        private String promptCacheMode;
        /** Anthropic-only: buffer visible output until a validated terminal event (needed for late refusal). */
        private Boolean strictTerminal;
        /** Whether this exact/provider profile supports a native server-side web-search tool. */
        private Boolean nativeWebSearch;
        /** Native tool type/version, for example {@code web_search} or {@code web_search_20260318}. */
        private String nativeWebSearchType;
        /** Generic thinking request field/value policy. */
        private ThinkingCapabilities thinking = new ThinkingCapabilities();
        /**
         * Trusted, static top-level extension fields merged into the request body.  Reserved
         * protocol-owned keys (model/messages/input/tools/stream/...) are rejected by adapters.
         */
        private Map<String, Object> requestOptions = new LinkedHashMap<>();
    }

    /** Generic top-level on/off thinking wire shape used by compatible Chat and Anthropic adapters. */
    @Data
    public static class ThinkingCapabilities {
        /** Top-level request field, for example {@code thinking} or {@code enable_thinking}. */
        private String field;
        /** {@code off}, {@code on}, or {@code always_on}; null means no thinking control. */
        private String defaultState;
        /** Scalar/boolean wire value (for example Qwen's {@code true}). */
        private Object enabledValue;
        private Object disabledValue;
        /** Map-shaped wire value for Spring YAML binding (for example {@code {type: enabled, keep: all}}). */
        private Map<String, Object> enabledObject = new LinkedHashMap<>();
        private Map<String, Object> disabledObject = new LinkedHashMap<>();
        /** Send the enabled/disabled value even when the operator omitted {@code thinking}. */
        private Boolean sendDefault;
    }

    @Data
    public static class Router {
        /**
         * Provider used when a request does not explicitly select one. Blank means the first enabled non-fallback
         * provider in declaration order. This is a default, never a failover chain.
         */
        private String defaultProvider = "";
        /**
         * Failover trigger codes. {@code "parse"} is INTENTIONALLY excluded — a malformed SSE chunk almost never
         * recovers by switching providers; failing over wastes the next provider's quota and may produce duplicate
         * visible text. See {@code ProviderRouter.DEFAULT_FAILOVER_CODES}.
         *
         * <p>
         * {@code stream_ttft_timeout} covers the case where a provider completes TLS + HTTP 200 but never sends a
         * content chunk within {@code -Dharnex.provider.streamTtftMs} (90s default). Without it in this list, the
         * documented {@code total-budget-ms} would be defeated by a wedged-but-not-dead upstream that hits the
         * provider's own 10-min idle readTimeout instead of failing over.
         * </p>
         */
        private List<String> failoverOn =
            List.of("429", "500", "502", "503", "504", "529", "timeout", "network", "stream_ttft_timeout");
        /**
         * Wall-clock budget across the entire failover sequence (ms). Once exceeded, the router stops sleeping for the
         * next backoff and rethrows the last error. 0 = unlimited (legacy). Default 120s leaves ample room under the
         * 15-min SSE timeout while preventing pathological 3-provider × 2s-backoff × per-provider-timeout pile-ups.
         */
        private long totalBudgetMs = 120_000L;
        /**
         * EWMA-based health diagnostics. EWMA never changes the user's selected/default provider; it is an
         * observability signal only. Circuit-breaker admission is controlled independently below.
         *
         * <p>
         * The router maintains a per-provider exponentially-weighted moving average (EWMA) of recent failures. When a
         * provider's failure rate crosses {@link #ewmaDegradeThreshold}, diagnostics report it as degraded. A complete
         * success contributes a recovery sample and resets the consecutive-failure circuit burst.
         */
        private boolean ewmaEnabled = true;
        /** EWMA smoothing factor [0,1]. Closer to 1 = more weight on recent samples. */
        private double ewmaAlpha = 0.3;
        /** Degrade providers whose EWMA failure rate exceeds this fraction. Range [0,1]. */
        private double ewmaDegradeThreshold = 0.5;
        /** Maximum transient attempts against the explicitly selected Chat provider before using {@code fallback}. */
        private int selectedProviderMaxAttempts = 5;
        /** Whether CLOSED/OPEN/HALF_OPEN admission protects provider/model targets. Independent from EWMA. */
        private boolean circuitBreakerEnabled = true;
        /** Consecutive transient failures required to open a provider/model circuit. */
        private int circuitFailureThreshold = 5;
        /** OPEN-state cooldown before one single-flight HALF_OPEN recovery probe is admitted. */
        private long circuitOpenMs = 30_000L;
    }

    @Data
    public static class Toolsets {
        private Map<String, List<String>> presets = new LinkedHashMap<>();
        private List<String> defaultSet = new ArrayList<>();
    }

    @Data
    public static class Budget {
        private int perToolChars = 8000;
        private int perTurnChars = 30000;
        private int previewChars = 1200;
        /**
         * Hard upper bound on a single {@code file_write} call's content (bytes). Without this an LLM that loops on
         * file_write with growing payloads can fill the disk before any other guardrail fires. Default 10 MB is
         * generous for source files / docs while still bounded.
         */
        private long perFileWriteBytes = 10L * 1024 * 1024;
        /**
         * Real-token cap for a single tool result. {@code 0} (default) = disabled, in which case {@link #perToolChars}
         * is the only gate. When &gt; 0, {@link com.harnex.tool.budget.BudgetEnforcer} counts BPE tokens via
         * {@link com.harnex.tool.budget.TokenCounter} (jtokkit for OpenAI families, conservative script-aware fallback
         * for Anthropic / local models) and truncates whichever fires first — the char or the token cap.
         *
         * <p>
         * Rationale: an 8000-char Chinese or source-code response routinely costs 4–6k real tokens, blowing past the
         * implicit budget operators set in {@code perToolChars} thinking it was a token figure. This field gives a
         * billing-aligned ceiling without breaking deployments that depend on the legacy char numbers.
         */
        private int perToolTokens = 0;
        /** Real-token cap for the aggregate of all tool results in one turn. {@code 0} = disabled (chars only). */
        private int perTurnTokens = 0;

        /**
         * LLM-facing (model) per-tool char cap, decoupled from the UI-preview {@link #perToolChars}. The UI caps
         * above govern only the SMALL preview shown in the UI and persisted into {@code thread.messages}
         * historically; that coupling meant the MODEL itself received the truncated preview, silently losing most of
         * a large tool result. This cap gives the model the FULL result up to a deliberately LARGER ceiling. Default
         * {@code 50000} so everyday reads/greps/bash output reach the model intact while oversized payloads are
         * persisted and replaced by a retrievable reference before they can refill a freshly compacted context.
         * {@code 0} = no per-tool truncation for the model (only {@link #perTurnCharsForLlm} and the summariser then
         * constrain it). The full scrubbed content is ALWAYS persisted to
         * {@code <dataDir>/agent/tool-results/<thread>/<sha>.txt} whenever either cap fires, so nothing is lost.
         */
        private int perToolCharsForLlm = 50000;
        /**
         * Per-turn aggregate char cap for the LLM versions of all tool results in one turn. The default 80000 bounds a
         * wide parallel batch; {@code 0} disables the aggregate cap.
         */
        private int perTurnCharsForLlm = 80000;
        /**
         * Real-token cap for the LLM version of a single tool result. {@code 0} (default) = disabled (chars only).
         * Symmetric with {@link #perToolTokens}: when &gt; 0 the more restrictive of char / token cap wins.
         */
        private int perToolTokensForLlm = 0;
        /**
         * Real-token cap for the aggregate of all LLM tool-result versions in one turn. {@code 0} (default) =
         * disabled. Symmetric with {@link #perTurnTokens}.
         */
        private int perTurnTokensForLlm = 0;
    }

    private Cron cron = new Cron();

    /**
     * Cron-scheduler safety guard rails. A cron job runs an unattended agent loop with full toolset access — if the
     * toolset list is unbounded and the prompt embeds {@code rm -rf /}, the scheduler becomes a scheduled-RCE backdoor.
     * {@link #allowedToolsets} restricts which toolset presets a cron job may use (empty = unrestricted, but logs a
     * startup WARN); {@link #maxJobs} caps the total number of jobs to stop a runaway client from registering
     * thousands.
     */
    @Data
    public static class Cron {
        /** When non-empty, only these toolset preset names may be assigned to a cron job. */
        private List<String> allowedToolsets = new ArrayList<>();
        /** Hard cap on how many cron jobs may be persisted at once. 0 = unlimited (not recommended). */
        private int maxJobs = 100;
        /**
         * When true (default — secure-by-default), restoring a persisted cron job whose effective toolset list is no
         * longer in {@link #allowedToolsets} marks the job as {@code disabled} on disk and skips scheduling it. The
         * previous behaviour was to log a WARN and schedule anyway, which meant tightening {@code allowed-toolsets}
         * after a deployment had created jobs left them firing unattended with the old (now-disallowed) permissions —
         * silently re-introducing the very risk the allow-list was meant to close. Operators who depend on the old
         * behaviour can set this to {@code false} to keep legacy jobs running with a WARN; the recommended migration is
         * to leave this true, let the restore quarantine the offending jobs, then explicitly re-enable each via
         * {@code POST /api/cron/{id}/enable} after auditing its toolsets.
         */
        private boolean quarantineLegacyDisallowedJobs = true;
    }

    @Data
    public static class Approval {
        private String mode = "default"; // bypass | default | strict
        /**
         * Operator-supplied EXTRA dangerous-command regex patterns. These are APPENDED to the hardened built-in
         * defaults (see {@code ApprovalService} ctor) — they no longer replace them. The previous "non-empty list
         * silently replaces defaults" semantics meant that any deployment whose yaml carried even one legacy pattern
         * (the shipped agent-config.yml did) dropped the entire hardened baseline (e.g. the broadened
         * {@code rm -rf /home}, {@code chmod -R 777 /}, fork-bomb-with-whitespace variants). To preserve the escape
         * hatch for an operator who deliberately needs to relax a baseline pattern, set
         * {@link #replaceDefaultPatterns}=true and provide the full desired list explicitly.
         */
        private List<String> dangerousPatterns = new ArrayList<>();
        /**
         * When {@code true}, {@link #dangerousPatterns} REPLACES the hardened defaults entirely (legacy behaviour).
         * Default {@code false} (additive) so an operator who customises one pattern doesn't silently lose all the
         * other baseline protections.
         */
        private boolean replaceDefaultPatterns = false;
    }

    @Data
    public static class Execution {
        /** Host is the Runtime's own machine, including when reached through SSH. */
        private String environment = "host";
        /** Isolation is opt-in; ordinary development retains the user's installed toolchain. */
        private String sandboxMode = "none";
        private String sandboxNetwork = "deny";
        /** Optional package directory for the pinned sandbox runtime; blank discovers a global srt installation. */
        private String sandboxRuntime = "";
        private List<String> writableRoots = new ArrayList<>();
        private String image = "debian:stable-slim";
        private String memory = "512m";
        private String cpus = "1";
        private int pidsLimit = 256;

        public void validate() {
            if (!Set.of("host", "docker").contains(environment == null ? "" : environment))
                throw configError("execution.environment must be host or docker; SSH connects whole Runtime workspaces");
            if (!Set.of("none", "read-only", "workspace-write").contains(sandboxMode == null ? "" : sandboxMode))
                throw configError("execution.sandbox-mode must be none, read-only or workspace-write");
            if (!Set.of("deny", "allow").contains(sandboxNetwork == null ? "" : sandboxNetwork))
                throw configError("execution.sandbox-network must be deny or allow");
            if (image == null || !image.matches("[A-Za-z0-9][A-Za-z0-9_./:@-]{0,255}"))
                throw configError("execution.image is not a valid container image reference");
            if (memory == null || !memory.matches("[1-9][0-9]*[bkmgBKMG]?"))
                throw configError("execution.memory must be a positive Docker memory limit");
            try {
                double value = Double.parseDouble(cpus);
                if (!Double.isFinite(value) || value <= 0 || value > 1024) throw new NumberFormatException();
            } catch (Exception e) { throw configError("execution.cpus must be a positive number <= 1024"); }
            if (pidsLimit < 1 || pidsLimit > 65536) throw configError("execution.pids-limit must be 1..65536");
            if (writableRoots == null) throw configError("execution.writable-roots must be a list");
            if (sandboxRuntime == null || !sandboxRuntime.isBlank() && !Path.of(sandboxRuntime).isAbsolute())
                throw configError("execution.sandbox-runtime must be an absolute package directory or blank");
            for (String root : writableRoots) {
                if (root == null || root.isBlank() || !Path.of(root).isAbsolute())
                    throw configError("execution.writable-roots must contain absolute paths on the Runtime host");
            }
        }
    }

    /** Snapshot / undo / branch settings for short-lived AI undo insurance. */
    @Data
    public static class Snapshot {
        private boolean conversation = true;
        private boolean fileRollback = true;
        private boolean strictCaptureForWrites = false;
        private boolean respectGitignore = true;
        private long maxFileBytes = 5L * 1024 * 1024;
        private int maxFilesPerSnapshot = 200;
        private long captureTimeoutMs = 1_500L;
        private long gitTimeoutMs = 2_000L;
        private int maxSnapshotsPerThread = 20;
        private List<String> protectGlobs =
            new ArrayList<>(List.of(".env", ".env.*", "config/**/*.yml", "config/**/*.yaml"));
        /**
         * Glob patterns (relative to workspace root) that are never captured as file rollback blobs. Defaults skip the
         * usual heavy/regenerable directories and VCS internals.
         */
        private List<String> excludeGlobs =
            new ArrayList<>(List.of("**/node_modules/**", "**/.git/**", "**/target/**", "**/build/**", "**/dist/**",
                "**/.gradle/**", "**/.idea/**", "**/.vscode/**", "**/__pycache__/**", "**/.venv/**", "**/venv/**",
                "**/.next/**", "**/.cache/**", "**/*.log", "**/*.tmp", "**/*.pyc", "**/*.class"));
        /**
         * Global disk budget across the entire snapshots/ tree. When the directory exceeds this size the oldest blobs
         * (across all threads) are pruned before a new snapshot is appended. Set to {@code <=0} to disable the cap.
         * Default 256 MiB across conversation blobs and selective per-file before blobs.
         */
        private long totalDiskBudgetBytes = 256L * 1024 * 1024;
    }

    @Data
    public static class Moa {
        private boolean enabled;
        private List<String> referenceModels = new ArrayList<>();
        private String aggregatorModel;
    }

    @Data
    public static class Auth {
        /**
         * Optional remote/shared-deployment API authentication. The primary product is a local, user-level Agent and
         * therefore defaults to the current OS user's trust boundary without interactive login.
         */
        private boolean enabled;
        /** Accepted static API tokens (Bearer). Rotate via env. */
        private List<String> tokens = new ArrayList<>();
        /** Paths never requiring auth (prefix match). */
        private List<String> publicPaths = new ArrayList<>(List.of(
            // Local API capability discovery + the whole auth resource (docs/03 §3.14): clients must
            // be able to probe the runtime and exchange credentials (boot-token or login) BEFORE they
            // hold a session. No user data exposed; each endpoint proves identity its own way.
            "/api/v1/protocol", "/api/v1/scope", "/api/v1/auth",
            // Health + liveness/readiness probes — must be reachable by container orchestrators.
            "/actuator/health", "/actuator/info"
        // /actuator/prometheus is NOT public by default: it leaks JVM internals + endpoint URIs.
        // For local single-user scraping either bind management to 127.0.0.1 (set
        // management.server.address: 127.0.0.1 in application.yml) or add it back to this list
        // and gate via a reverse proxy.
        ));
        /**
         * Remote addresses (direct peers) whose {@code X-Forwarded-For} header is trusted. Empty set (default) = never
         * trust XFF; always use the direct peer address. Set this to the IP of your reverse proxy (nginx, Cloudflare
         * edge, ALB, etc).
         */
        private Set<String> trustedProxies = new HashSet<>();
    }

    @Data
    public static class Subagent {
        private int maxPerThread = 3;
        private int maxDepth = 2;
        /**
         * Work-iteration ceiling for each child agent. The bounded default is 64; {@code 0} explicitly inherits
         * {@code harnex.max-iterations} for deployments that need the historical behavior.
         * A model-supplied {@code delegate.maxIterations} may only lower the effective ceiling.
         *
         * <p>
         * An iteration is an LLM round trip, not a file chunk. Large documents should normally be narrowed with
         * search/range reads or read in parallel; raising this value is a last-mile escape hatch, not the primary
         * document-size control.
         */
        private int maxIterations = 64;
        /**
         * Family-wide ceiling on how many sub-agents ONE user turn may spawn in total (across all depths and the whole
         * agent tree). Distinct from {@link #maxPerThread}, which only bounds <em>concurrent</em> in-flight delegations:
         * once a sub-agent finishes its concurrency permit is released, so without this total cap a parent (and each
         * descendant) could delegate sequentially across its iterations without bound. 0 = unlimited.
         */
        private int maxRunsPerTurn = 32;
        /** Opt-in hard execution limit, excluding queue wait; default 0 inherits only parent/Goal limits. */
        private long timeoutMs = 0L;
        /** Safe-point progress checkpoints; 0 disables time-based checkpoints. Does not cancel healthy work. */
        private long softTimeoutMs = 1_200_000L;
        /** Maximum wait for a worker, independent from the execution limit. Must remain positive. */
        private long queueTimeoutMs = 120_000L;
        /**
         * Bounded queue for the shared delegate pool. Once full, new submissions are <b>rejected</b> with
         * {@link java.util.concurrent.RejectedExecutionException} (AbortPolicy) and {@code SubAgentExecutor.delegate}
         * returns a structured "[error] sub-agent queue full" result. The earlier CallerRunsPolicy silently bypassed
         * the per-delegation timeout by running the rejected runner on the parent's thread — see SubAgentExecutor for
         * the rationale.
         */
        private int queueCapacity = 8;

        /**
         * Guard the shape of the sub-agent tree. {@code maxRunsPerTurn} is the family-wide total-run ceiling;
         * {@code maxDepth} limits recursion and {@code maxPerThread} limits concurrent fan-out. A fat-fingered
         * {@code maxDepth: 20, maxPerThread: 20} would still create a very large runnable tree before
         * {@code maxRunsPerTurn} is reached, so clamp both structural knobs to sane ceilings at startup and warn.
         */
        void validate(Logger log) {
            final int MAX_DEPTH_CEIL = 5;
            final int MAX_PER_THREAD_CEIL = 10;
            if (maxDepth < 0) {
                log.warn("harnex.subagent.max-depth={} is negative; clamping to 0 (no delegation).", maxDepth);
                maxDepth = 0;
            } else if (maxDepth > MAX_DEPTH_CEIL) {
                log.warn("harnex.subagent.max-depth={} exceeds the {} ceiling; clamping. Deep recursion multiplies the "
                    + "agent-tree size exponentially.", maxDepth, MAX_DEPTH_CEIL);
                maxDepth = MAX_DEPTH_CEIL;
            }
            if (maxPerThread < 1) {
                log.warn("harnex.subagent.max-per-thread={} is < 1; clamping to 1.", maxPerThread);
                maxPerThread = 1;
            } else if (maxPerThread > MAX_PER_THREAD_CEIL) {
                log.warn("harnex.subagent.max-per-thread={} exceeds the {} ceiling; clamping. Wide fan-out multiplies "
                    + "the agent-tree size.", maxPerThread, MAX_PER_THREAD_CEIL);
                maxPerThread = MAX_PER_THREAD_CEIL;
            }
            if (maxRunsPerTurn < 0) {
                log.warn("harnex.subagent.max-runs-per-turn={} is negative; treating as 0 (unlimited).", maxRunsPerTurn);
                maxRunsPerTurn = 0;
            }
            if (maxIterations < 0) {
                log.warn("harnex.subagent.max-iterations={} is negative; using the bounded default 64.",
                    maxIterations);
                maxIterations = 64;
            }
            if (timeoutMs < 0) timeoutMs = 0L;
            if (softTimeoutMs < 0) softTimeoutMs = 1_200_000L;
            if (queueTimeoutMs <= 0) queueTimeoutMs = 120_000L;
        }
    }

    @Data
    public static class Gateway {
        /**
         * HMAC SHA-256 secret verifying X-Harnex-Signature on the generic /api/gateway/inbound endpoint. Empty =
         * fail-closed.
         */
        private String webhookSecret = "";
        /** DingTalk outgoing-webhook signature secret (HMAC-SHA256 of "{timestamp}\n{secret}"). Empty = disabled. */
        private String dingtalkSecret = "";
        /** WeCom (企业微信) bot signature: token used in sha1(token+timestamp+nonce). Empty = disabled. */
        private String wecomToken = "";
        /**
         * Feishu/Lark event-callback signature secret — the <b>Encrypt Key</b> from the Feishu/Lark developer console
         * (NOT the verification token; that's {@link #feishuVerificationToken}).
         *
         * <p>
         * <b>Algorithm note:</b> Feishu's signature is a <em>plain SHA-256</em> digest of
         * {@code timestamp + nonce + encrypt_key + raw_body} — NOT HMAC-SHA256. See the official spec at <a href=
         * "https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscription/data-decrypt-and-verification">data-decrypt-and-verification</a>
         * and the implementation in {@code FeishuWebhookController.verify(...)}.
         *
         * <p>
         * <b>Empty value (fail-closed):</b> when blank, real {@code event_callback} POSTs are rejected with HTTP 503
         * ({@code feishu gateway disabled}). The {@code url_verification} handshake (Phase 1) still works — that path
         * needs no signature so a freshly registered URL can complete the console-side verification before the operator
         * sets the secret.
         */
        private String feishuSecret = "";
        /**
         * Optional Feishu verification token returned during the first url_verification challenge — Feishu itself
         * accepts any 200, but if you set this, we additionally compare the token from the payload against this value.
         */
        private String feishuVerificationToken = "";
        /**
         * Hard cap on a single channel-side reply, characters. The raw answer is still persisted in full in
         * thread.json; this only trims what's echoed back to the IM platform (most platforms have their own 2-4 KB
         * message limit).
         */
        private int replyMaxChars = 3500;
        /**
         * DingTalk outbound push (custom group robot) — the channel the final agent reply is delivered through after
         * the inbound webhook has already ACK'd. Required for the DingTalk gateway to operate; when {@code webhookUrl}
         * is empty the gateway refuses inbound messages with 503.
         */
        private DingTalkOutbound dingtalkOutbound = new DingTalkOutbound();
        /**
         * Feishu/Lark outbound push (custom group robot). Same role as {@link #dingtalkOutbound} for the Feishu
         * channel.
         */
        private FeishuOutbound feishuOutbound = new FeishuOutbound();
        /**
         * WeCom outbound push (group robot key). Same role as {@link #dingtalkOutbound} for the WeCom channel.
         */
        private WeComOutbound wecomOutbound = new WeComOutbound();
        /**
         * Sender allowlist for inbound IM and webhook messages.
         *
         * <p>
         * Each entry is either:
         * <ul>
         * <li>{@code "user-id"} — matches that user on ANY channel; or</li>
         * <li>{@code "channel:user-id"} — matches only when the inbound channel matches too (e.g.
         * {@code "feishu:ou_abc123"}).</li>
         * </ul>
         *
         * <p>
         * Comparison is case-insensitive on the channel and the literal user id. The literal value {@code "*"} (a
         * single-entry list) means "allow everyone".
         *
         * <p>
         * <b>Empty value (fail-closed):</b> the runtime — see {@code GatewayDispatcher.senderAllowed(...)} — denies
         * <em>every</em> inbound sender when this list is empty / unset and logs a WARN per (channel, user) pair. The
         * earlier "empty == allow-all" semantics were wrong for IM/Webhook entrypoints because the platform secret only
         * proves the request came from the IM bridge, not that the human is authorised. Operators that genuinely want
         * unrestricted access must set the wildcard {@code "*"} explicitly. The shipped {@code agent-config.yml} does
         * so for a frictionless first-run experience; production deployments should override via
         * {@code HARNEX_GATEWAY_ALLOWED_SENDERS} with explicit ids.
         *
         * <p>
         * When non-empty (and not {@code "*"}), the gateway dispatcher rejects unknown senders with a one-line
         * "{@code 未授权的发送者}" reply and a WARN-level audit log. Changes to this deployment setting require a restart.
         */
        private List<String> allowedSenders = new ArrayList<>();
        /**
         * Dedicated HMAC-SHA256 secret for {@code POST /api/gateway/alertmanager}. Independent of
         * {@link #webhookSecret} so an operator can rotate the alerting pipeline credentials without touching the
         * generic webhook bridge. Empty = endpoint fail-closed (returns 401).
         */
        private String alertmanagerSecret = "";
        /**
         * Per-channel default toolset preset / model. Applied on the FIRST turn of a brand-new thread
         * minted by an IM gateway; subsequent turns honour whatever toolsets the user / model later set. Channel keys
         * are lowercase channel names (`feishu` / `dingtalk` / `wecom` / `webhook` / `alertmanager`). Unconfigured
         * channels keep their prior behaviour (empty toolsets → resolve uses {@code default-set}).
         *
         * <p>
         * Example:
         * 
         * <pre>
         * gateway:
         *   channel-defaults:
         *     feishu:       { toolsets: [read] }       # consumer Q&A — read-only by default
         *     dingtalk:     { toolsets: [inspect] }    # ops on-call channel — inspect mode
         *     alertmanager: { toolsets: [inspect], model: "deepseek-chat" }
         * </pre>
         */
        private Map<String, ChannelDefault> channelDefaults = new LinkedHashMap<>();
    }

    /** Per-channel preset / model override row referenced from {@link Gateway#getChannelDefaults()}. */
    @Data
    public static class ChannelDefault {
        private List<String> toolsets = new ArrayList<>();
        private String model;
    }

    /**
     * DingTalk outbound push config — custom group-robot URL plus optional sign secret.
     *
     * <p>
     * {@code webhookUrl} example: {@code https://oapi.dingtalk.com/robot/send?access_token=XXX}. When the bot has "签名"
     * security enabled, also set {@code signSecret} (the same "SECxxxxxx" string shown on the DingTalk console next to
     * the bot).
     */
    @Data
    public static class DingTalkOutbound {
        private String webhookUrl = "";
        private String signSecret = "";
    }

    /**
     * Feishu/Lark outbound push config — custom group-robot URL plus optional sign secret.
     *
     * <p>
     * {@code webhookUrl} example: {@code https://open.feishu.cn/open-apis/bot/v2/hook/XXX}. When the bot has "签名校验"
     * security enabled, also set {@code signSecret}.
     */
    @Data
    public static class FeishuOutbound {
        private String webhookUrl = "";
        private String signSecret = "";
    }

    /**
     * WeCom (企业微信) outbound push config — group-robot URL only. The robot key is part of the URL itself
     * ({@code ?key=...}) so no separate secret field is needed; rotation = create a new robot in the group.
     *
     * <p>
     * {@code webhookUrl} example: {@code https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=XXX}.
     */
    @Data
    public static class WeComOutbound {
        private String webhookUrl = "";
    }

    /**
     * Dedicated side-channel model for compressing long histories. It may be cheaper/faster than the main task model,
     * but should still be capable enough to preserve continuation state: a bad summary affects every later turn.
     */
    @Data
    public static class Summarizer {
        /**
         * Default ON. When false, SummarizationMiddleware still runs but only with a deterministic priority digest (no
         * LLM call). Set false only on cost-sensitive deployments; deterministic mode is an emergency continuity guard,
         * not an equivalent semantic summary.
         */
        private boolean enabled = true;
        /**
         * Map key of an enabled entry in {@code harnex.providers}. Empty means use the task model that is actually
         * serving this agent, then the reserved fallback provider. A configured target is tried before fallback and
         * the task model.
         */
        private String provider = "";
        /** Model name passed to that provider. Empty = provider default. */
        private String model = "";
        /** Fallback window in tokens when neither the selected model nor its provider declares a capacity. */
        private int contextWindow = 128_000;
        /** Output capacity kept free from the context window. */
        private int outputReserveTokens = 16_000;
        /** Additional safety reserve beyond the explicitly counted messages/tools and protocol calibration. */
        private int safetyReserveTokens = 0;
        /** Proactive request/tool-boundary watermark as a percentage of usable input capacity. */
        private int softThresholdPercent = 75;
        /** Synchronous pre-request compaction watermark as a percentage of usable input capacity. */
        private int hardThresholdPercent = 90;
        /** Desired post-compaction occupancy, not a required success/finalization threshold. */
        private int targetPercent = 40;
        /** Minimum genuinely new context before another proactive compaction may run. */
        private int minNewTokens = 16_000;
        /** Alternative model-relative minimum; the lower of this and min-new-tokens is used. */
        private int minNewContextPercent = 15;
        /** Minimum reduction accepted when a rewrite remains above soft but fits within the hard watermark. */
        private int minimumReductionPercent = 20;
        /** Stop compaction thrash and ask AgentLoop to finalise after this many ineffective rewrites. */
        private int maxIneffectiveCompressions = 3;
        /** Optional cap on recent verbatim messages; 0 uses the available token budget without a count cap. */
        private int keepTail = 0;
        /**
         * Approximate character budget for the verbatim tail. The effective tail must satisfy both this budget and
         * {@link #keepTail}; 0 disables the character constraint. A complete tool-call/result batch may be moved wholly
         * into the summarised head rather than splitting the provider-required pair.
         */
        private int keepTailChars = 0;
        /** How many leading messages (incl. system prompt) to protect from compression. */
        private int keepHead = 2;
        /** Character cap on saved summary prose; protected original messages and fact candidates are separate. */
        private int summaryMaxChars = 24_000;
        /**
         * Provider-side output ceiling for a summary request, including reasoning tokens where the protocol counts
         * them together. This is deliberately much smaller than an interactive reasoning model's normal allowance.
         */
        private int maxOutputTokens = 16_384;
        /** Maximum simultaneous summary calls across independent Runs; excess calls fail fast to deterministic mode. */
        private int maxConcurrent = 2;
        /** Total time for one compression's model attempts; exhaustion uses local compression and leaves the task running. */
        private long timeoutMs = 180_000;
        /** Per-model attempt ceiling; the shared deadline and owning task's remaining time still take precedence. */
        private long attemptTimeoutMs = 90_000;
        /** Reuse a compatible main-request prefix for soft-watermark summaries when it fits. */
        private boolean prefixReuse = false;
        /**
         * Validate / clamp Summarizer config at bind-time. Misconfigured values used to silently blow up at runtime —
         * e.g. {@code keepTail < 0} pushed {@code tailStart} past {@code msgs.size()} and made
         * {@link com.harnex.middleware.SummarizationMiddleware} throw {@link IndexOutOfBoundsException} on the next
         * compression; {@code summaryMaxChars < 0} threw {@link StringIndexOutOfBoundsException} from
         * {@code String.substring(0, negative)}. We clamp obvious typos and reject incoherent token watermarks up
         * front, where the operator can still see the failure.
         *
         * <p>
         * Called from {@link AgentConfig#postBind()} so it runs once at startup AND once per
         * {@code POST /api/workspace/reload} if that endpoint ever rebinds config (today it only reloads AGENTS.md, but
         * the validation is cheap and idempotent).
         */
        void validate(Logger log) {
            if (keepTail < 0) {
                log.warn("harnex.summarizer.keep-tail={} is negative; clamping to 0", keepTail);
                keepTail = 0;
            }
            if (keepTailChars < 0) {
                log.warn("harnex.summarizer.keep-tail-chars={} is negative; clamping to 0 (disabled)", keepTailChars);
                keepTailChars = 0;
            }
            if (keepHead < 1) {
                log.warn("harnex.summarizer.keep-head={} is less than 1; clamping to 1 (system prompt must be preserved)", keepHead);
                keepHead = 1;
            }
            if (summaryMaxChars < ContinuationSummary.MIN_MAX_CHARS) {
                log.warn("harnex.summarizer.summary-max-chars={} is too small; clamping to {}", summaryMaxChars,
                    ContinuationSummary.MIN_MAX_CHARS);
                summaryMaxChars = ContinuationSummary.MIN_MAX_CHARS;
            }
            if (maxOutputTokens <= 0) {
                log.warn("harnex.summarizer.max-output-tokens={} must be > 0; clamping to default 16384",
                    maxOutputTokens);
                maxOutputTokens = 16_384;
            }
            if (maxConcurrent < 1 || maxConcurrent > 8) {
                int clamped = Math.max(1, Math.min(8, maxConcurrent));
                log.warn("harnex.summarizer.max-concurrent={} must be between 1 and 8; clamping to {}",
                    maxConcurrent, clamped);
                maxConcurrent = clamped;
            }
            if (timeoutMs <= 0) {
                log.warn("harnex.summarizer.timeout-ms={} must be > 0; clamping to default 180000", timeoutMs);
                timeoutMs = 180_000L;
            }
            if (attemptTimeoutMs <= 0) attemptTimeoutMs = 90_000L;
            if (contextWindow <= 0) {
                log.warn("harnex.summarizer.context-window={} must be > 0; clamping to 128000",
                    contextWindow);
                contextWindow = 128_000;
            }
            outputReserveTokens = Math.max(0, outputReserveTokens);
            safetyReserveTokens = Math.max(0, safetyReserveTokens);
            if (outputReserveTokens + safetyReserveTokens >= contextWindow) {
                throw new IllegalStateException("harnex.summarizer output+safety reserves must be smaller than "
                    + "context-window");
            }
            softThresholdPercent = clampPercent(log, "soft-threshold-percent", softThresholdPercent, 75);
            hardThresholdPercent = clampPercent(log, "hard-threshold-percent", hardThresholdPercent, 90);
            targetPercent = clampPercent(log, "target-percent", targetPercent, 40);
            if (!(targetPercent < softThresholdPercent && softThresholdPercent < hardThresholdPercent)) {
                throw new IllegalStateException("harnex.summarizer token watermarks must satisfy target < soft < hard");
            }
            minNewTokens = Math.max(0, minNewTokens);
            minNewContextPercent = Math.max(0, Math.min(100, minNewContextPercent));
            minimumReductionPercent = Math.max(0, Math.min(100, minimumReductionPercent));
            maxIneffectiveCompressions = Math.max(1, maxIneffectiveCompressions);
        }

        private static int clampPercent(Logger log, String name, int value, int fallback) {
            if (value > 0 && value < 100) {
                return value;
            }
            log.warn("harnex.summarizer.{}={} must be between 1 and 99; clamping to {}", name, value, fallback);
            return fallback;
        }
    }

    /** Configurable fine-grained hooks. */
    @Data
    public static class Hooks {
        private List<HookRule> rules = new ArrayList<>();
    }

    /**
     * A single hook rule. Tool hooks match tool names at preToolCall / postToolCall; onError handles execution errors.
     * <p>
     * Supported actions: log, veto (preToolCall only), notify.
     * </p>
     */
    @Data
    public static class HookRule {
        /** Human-readable name for this rule (for logging). */
        private String name;
        /** Phase: preToolCall | postToolCall | onError */
        private String phase = "preToolCall";
        /** Glob pattern matched against tool name. e.g. "bash", "mcp__*", "*" */
        private String toolPattern = "*";
        /** Action: log | veto | notify */
        private String action = "log";
        /** When action=veto, the reason message. */
        private String vetoReason = "Blocked by hook rule";
        /** When true, this rule is disabled. */
        private boolean disabled = false;
        /** Optional: only match when arguments contain this regex. */
        private String argsMatch;
    }

    private Hooks hooks = new Hooks();

    /** MCP (Model Context Protocol) client configuration. */
    @Data
    public static class Mcp {
        private List<McpServer> servers = new ArrayList<>();
    }

    @Data
    public static class McpServer {
        private String name;
        /** Transport type: stdio | sse | streamable-http (alias: http). */
        private String transport = "stdio";
        /** For stdio: executable command. */
        private String command;
        /** For stdio: command arguments. */
        private List<String> args = new ArrayList<>();
        /** For stdio: extra environment variables. */
        private Map<String, String> env = new LinkedHashMap<>();
        /** For sse / streamable-http: HTTP endpoint URL. */
        private String url;
        /** For sse / streamable-http: extra HTTP headers (e.g. Authorization). */
        private Map<String, String> headers = new LinkedHashMap<>();
        /**
         * Toolset presets into which this server's discovered tools are auto-injected. Defaults to {@code [act]} when
         * null/empty so MCP tools cannot silently widen the read-only {@code read} / {@code inspect} presets. Operators
         * opt in explicitly per server (see user manual § MCP).
         */
        private List<String> exposeToPresets;
    }

    /**
     * VSCode-style outbound HTTP proxy configuration, applied to LLM providers (and, in future steps, to
     * {@code webfetch} / {@code harnex upgrade}).
     *
     * <p>
     * Design rationale (see {@code docs/04_部署方案.md} &sect; "代理与出网安全"):
     * <ul>
     * <li>Single URL string &mdash; identical syntax to VSCode's {@code http.proxy}, {@code curl}, {@code npm},
     * {@code git}; trivially diff-able and copy-paste-able. Credentials live in the URL userinfo and are scrubbed on
     * every log/audit path via {@link com.harnex.safety.SecretRedactor}.</li>
     * <li>Spring property-source ordering decides where the value comes from (CLI {@code --harnex.http.proxy=...} &gt;
     * system property &gt; env &gt; YAML &gt; default). VSCode plugin integrations therefore reuse the platform's
     * {@code http.proxy} setting by spawning the backend with a single CLI flag &mdash; no extra config required on the
     * user side.</li>
     * <li>{@link #proxySupport} {@code = fallback} (default) probes the upstream once at startup; if direct works the
     * proxy is bypassed for that host. This is the VSCode {@code http.proxySupport: fallback} contract.</li>
     * </ul>
     */
    @Data
    public static class HttpConfig {
        /**
         * VSCode-style proxy URL. {@code null}/empty/{@code "off"} &rarr; no proxy. Accepts
         * {@code http://[user:pass@]host:port} or {@code socks5://host:port}.
         */
        private String proxy;
        /**
         * Hosts that should NEVER be routed through {@link #proxy}. Glob syntax (e.g. {@code "*.deepseek.com"},
         * {@code "10.*"}). Loopback / RFC1918 / link-local addresses are bypassed unconditionally and need not be
         * listed; this list is for specific public hosts that the operator wants to reach directly.
         */
        private List<String> proxyBypass = new ArrayList<>();
        /**
         * VSCode {@code http.proxySupport} semantics:
         * <ul>
         * <li>{@code on} &mdash; always use {@link #proxy} (unless bypass matches);</li>
         * <li>{@code off} &mdash; never use {@link #proxy};</li>
         * <li>{@code fallback} (default) &mdash; probe direct connection once; use proxy only if direct fails.</li>
         * </ul>
         */
        private String proxySupport = "fallback";
    }
}
