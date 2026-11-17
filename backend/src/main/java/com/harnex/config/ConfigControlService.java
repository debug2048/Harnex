package com.harnex.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.harnex.conversation.ContinuationSummary;
import com.harnex.image.ImageServiceConfig;
import com.harnex.net.OutboundProxyResolver;
import com.harnex.provider.ProviderConfigurationResolver;
import com.harnex.provider.ProviderFactory;
import com.harnex.storage.SharedFileTransaction;
import com.harnex.util.FilePerms;

/**
 * Revisioned user configuration control plane. Publishes task settings and reports pending connection changes.
 */
@Service
public class ConfigControlService {

    public static final String SECRET_PLACEHOLDER = "••••••••";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Set<String> EDITABLE = RuntimeSettingsPolicy.EDITABLE;
    private static final Set<String> WIRE_FIELDS =
        Set.of("request-options", "enabled-object", "disabled-object", "enabled-value", "disabled-value");

    private final AgentConfig cfg;
    private final ProviderConfigurationResolver providerResolver;
    private final Path userFile;
    private final Map<String, Object> packagedHarnex;
    private final Map<String, Object> startupUserHarnex;
    private final AgentConfig startupConfiguration;
    @Autowired(required = false)
    private ObjectProvider<RuntimeConfiguration> runtimeConfiguration;
    @Autowired(required = false)
    private ConfigurableEnvironment environment;

    @Autowired
    public ConfigControlService(AgentConfig cfg, ProviderConfigurationResolver providerResolver) {
        this.cfg = cfg;
        this.providerResolver = providerResolver;
        this.userFile = UserDataPaths.resolve(cfg).resolve("agent-config.yml");
        this.packagedHarnex = packagedHarnex();
        this.startupUserHarnex = harnex(readDocument(userFile));
        this.startupConfiguration = RuntimeConfiguration.copyConfiguration(cfg);
    }

    /** Focused-test constructor. */
    public ConfigControlService(AgentConfig cfg) {
        this(cfg, new ProviderConfigurationResolver());
    }

    public Map<String, Object> schema() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("providers", Map.of("type", "map", "scope", "user",
            "description", "Complete user-owned Provider collection: profile, endpoint, key and models."));
        fields.put("router", Map.of("type", "object", "scope", "user",
            "description", "Default provider, retry budget and independent circuit-breaker policy."));
        fields.put("image-services", Map.of("type", "map", "scope", "user",
            "description", "Image models: independent protocol, endpoint, credential and model configuration. Saving does not generate an image."));
        fields.put("default-image-service", Map.of("type", "string", "scope", "user"));
        fields.put("summarizer", Map.of("type", "object", "scope", "user",
            "description", "Dedicated long-history summarisation provider, model and compression thresholds."));
        fields.put("http", Map.of("type", "object", "scope", "user",
            "description", "HTTP/SOCKS proxy, support mode and bypass list."));
        fields.put("web-search", Map.of("type", "object", "scope", "user",
            "description", "Optional third-party search for project Agents. Personal conversations use native search only."));
        fields.put("workspace", Map.of("type", "object", "scope", "user",
            "editableFields", List.of("outside-write-policy"),
            "description", "Policy for writes outside the active project: deny, ask, or allow."));
        fields.put("safety", Map.of("type", "object", "scope", "user"));
        fields.put("execution", Map.of("type", "object", "scope", "user",
            "description", "Runtime host/container toolchain and optional command isolation. Normal development uses host execution."));
        fields.put("toolsets", Map.of("type", "object", "scope", "user"));
        fields.put("mcp", Map.of("type", "object", "scope", "user"));
        fields.put("hooks", Map.of("type", "object", "scope", "user"));
        fields.replaceAll((name, definition) -> {
            Map<String, Object> described = mapValue(definition);
            described.put("reloadPolicy", RuntimeSettingsPolicy.reloadPolicy(name));
            return described;
        });
        return Map.of("schemaVersion", 2, "editableSections", EDITABLE, "fields", fields,
            "secretPlaceholder", SECRET_PLACEHOLDER);
    }

    public Map<String, Object> read(String scope) {
        String normalizedScope = normalizeScope(scope);
        Path file = userFile;
        Map<String, Object> source = harnex(readDocument(file));
        Map<String, Object> values = selectEditable(source, EDITABLE);
        // Do not put packaged providers into the user values document: saving an unrelated setting must not
        // accidentally claim/freeze the concrete Provider collection. The frontend uses `effective.providers` as the
        // initial visual catalogue and copies the whole list into values only after the user edits a Provider.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", normalizedScope);
        out.put("revision", revision(file));
        out.put("values", maskSecrets(values));
        out.put("effective", effective());
        out.put("reloadPolicy", "restart");
        out.put("restartRequired", true);
        out.put("fileExists", Files.isRegularFile(file));
        RuntimeConfiguration live = liveRuntime();
        if (live != null) out.putAll(live.status());
        return out;
    }

    public Map<String, Object> save(String scope, String expectedRevision, Map<String, Object> submitted) {
        return save(scope, expectedRevision, submitted, Set.of());
    }

    public Map<String, Object> save(String scope, String expectedRevision, Map<String, Object> submitted,
        Set<String> credentialChanges) {
        String normalizedScope = normalizeScope(scope);
        Path file = userFile;
        Map<String, Object> saved = SharedFileTransaction.withExclusiveLock(file, () -> {
            String currentRevision = revision(file);
            if (expectedRevision != null && !expectedRevision.isBlank() && !expectedRevision.equals(currentRevision)) {
                throw new RevisionConflictException(currentRevision);
            }
            Map<String, Object> source = submitted == null ? Map.of() : submitted;
            Map<String, Object> values = selectEditable(source, EDITABLE);
            List<String> errors = validate(values);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(String.join("; ", errors));
            }
            Map<String, Object> document = new LinkedHashMap<>(readDocument(file));
            Map<String, Object> existingHarnex = new LinkedHashMap<>(harnex(document));
            Map<String, Object> merged = preserveSecretPlaceholders(values, effectiveExisting(existingHarnex));
            for (String key : EDITABLE) {
                if (merged.containsKey(key)) {
                    existingHarnex.put(key, merged.get(key));
                }
            }
            if (credentialChanges != null && !credentialChanges.isEmpty()) {
                Set<String> references = keychainReferences(existingHarnex);
                Map<String, Object> revisions = mapValue(existingHarnex.get("runtime-credential-revisions"));
                revisions.keySet().retainAll(references);
                for (String reference : credentialChanges) {
                    if (references.contains(reference)) revisions.put(reference, UUID.randomUUID().toString());
                }
                existingHarnex.put("runtime-credential-revisions", revisions);
            }
            existingHarnex.putIfAbsent("version", 1);
            document.put("harnex", existingHarnex);
            writeDocument(file, document);
            return Map.of("saved", true, "scope", normalizedScope, "revision", revision(file),
                "reloadPolicy", "restart", "restartRequired", true);
        });
        RuntimeConfiguration live = liveRuntime();
        if (live == null) return saved;
        live.refresh(true);
        Map<String, Object> result = new LinkedHashMap<>(saved);
        result.putAll(live.status());
        return result;
    }

    public Map<String, Object> reloadSettings() {
        RuntimeConfiguration live = liveRuntime();
        if (live == null) return Map.of("reloaded", false, "restartRequired", true, "reloadPolicy", "restart");
        live.refresh(true);
        Map<String, Object> result = new LinkedHashMap<>(live.status());
        result.put("reloaded", String.valueOf(result.get("reloadError")).isEmpty());
        return result;
    }

    private RuntimeConfiguration liveRuntime() {
        return runtimeConfiguration == null ? null : runtimeConfiguration.getIfAvailable();
    }

    public record RuntimeSource(String revision, AgentConfig configuration, Set<String> credentialDeferrals) {}

    /** Reads the same user document in every local Runtime; nothing is written or probed by this reader. */
    public RuntimeSource runtimeSource(String knownRevision) {
        try {
            byte[] bytes = Files.isRegularFile(userFile) ? Files.readAllBytes(userFile) : new byte[0];
            String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (revision.equals(knownRevision)) return null;
            Map<String, Object> source = bytes.length == 0 ? Map.of() : harnex(YAML.readValue(bytes, MAP));
            AgentConfig candidate = environment == null ? bindStandaloneConfiguration(source) : bindLiveConfiguration(bytes);
            Set<String> credentialDeferrals = new LinkedHashSet<>();
            Map<String, Object> revisions = mapValue(source.get("runtime-credential-revisions"));
            Map<String, Object> startupRevisions = mapValue(startupUserHarnex.get("runtime-credential-revisions"));
            for (String section : List.of("providers", "image-services", "web-search")) {
                Object requested = source.containsKey(section) ? source.get(section) : packagedHarnex.get(section);
                if (keychainReferences(requested).stream().anyMatch(reference ->
                    !Objects.equals(revisions.get(reference), startupRevisions.get(reference))
                        || ProviderFactory.resolveEnv(reference) == null || ProviderFactory.resolveEnv(reference).isBlank())) {
                    credentialDeferrals.add(section);
                }
            }
            return new RuntimeSource(revision, candidate, Set.copyOf(credentialDeferrals));
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("cannot read runtime configuration", failure); }
    }

    /** Fixture fallback; production always rebinds the real Spring property sources with their original precedence. */
    private AgentConfig bindStandaloneConfiguration(Map<String, Object> source) throws IOException {
        AgentConfig candidate = RuntimeConfiguration.copyConfiguration(startupConfiguration);
        Map<String, Object> overrides = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (EDITABLE.contains(key) || "provider-profiles".equals(key)) overrides.put(toCamelCase(key),
                Set.of("providers", "image-services", "provider-profiles").contains(key) ? normalizeNamedMap(value) : normalizeKeys(value));
        });
        JSON.readerForUpdating(candidate).readValue(JSON.writeValueAsBytes(overrides));
        if (source.containsKey("providers")) candidate.setProviders(convert(normalizeNamedMap(normalizeProviderCollection(source.get("providers"))),
            new TypeReference<Map<String, AgentConfig.ProviderConfig>>() {}));
        for (AgentConfig.ProviderConfig provider : candidate.getProviders().values()) {
            provider.setBaseUrl(ProviderFactory.resolveEnv(provider.getBaseUrl()));
            provider.setApiKey(ProviderFactory.resolveEnv(provider.getApiKey()));
            provider.setDefaultModel(ProviderFactory.resolveEnv(provider.getDefaultModel()));
            provider.setProxy(OutboundProxyResolver.resolveEnv(provider.getProxy()));
        }
        return candidate;
    }

    private AgentConfig bindLiveConfiguration(byte[] bytes) throws IOException {
        var live = new StandardEnvironment();
        var sources = live.getPropertySources();
        sources.stream().map(PropertySource::getName).toList().forEach(sources::remove);
        live.setActiveProfiles(environment.getActiveProfiles());
        var replacement = bytes.length == 0 ? List.<PropertySource<?>>of()
            : new YamlPropertySourceLoader().load("live-user-configuration", new ByteArrayResource(bytes));
        boolean replaced = false;
        for (PropertySource<?> original : environment.getPropertySources()) {
            // This wrapper points at the old Environment and must not participate in the private rebind.
            if ("configurationProperties".equals(original.getName())) continue;
            PropertySource<?> source = ProviderMapReplacementEnvironmentPostProcessor.unfiltered(original);
            if (isUserConfigurationSource(source)) {
                if (!replaced) for (PropertySource<?> item : replacement) sources.addLast(item);
                replaced = true;
            } else sources.addLast(source);
        }
        if (!replaced) {
            String firstDocument = sources.stream().filter(OriginTrackedMapPropertySource.class::isInstance)
                .map(PropertySource::getName).findFirst().orElse(null);
            for (PropertySource<?> item : replacement) {
                if (firstDocument == null) sources.addLast(item); else sources.addBefore(firstDocument, item);
            }
        }
        ProviderMapReplacementEnvironmentPostProcessor.replaceLowerPrecedenceProviderMaps(sources);
        // This private Binder does not discover startup's @ConfigurationPropertiesBinding beans.
        // Docker, Supervisor and desktop launchers pass HARNEX_WORKSPACE as a scalar project path.
        var conversion = new ApplicationConversionService();
        conversion.addConverter(new WorkspaceRootConverter());
        return new Binder(ConfigurationPropertySources.get(live), new PropertySourcesPlaceholdersResolver(live),
            conversion).bindOrCreate("harnex", AgentConfig.class);
    }

    private boolean isUserConfigurationSource(PropertySource<?> source) {
        if (!(source instanceof OriginTrackedMapPropertySource document)) return false;
        for (String name : document.getPropertyNames()) {
            if (OriginLookup.getOrigin(document, name) instanceof TextResourceOrigin origin) {
                try {
                    Path path = origin.getResource().getFile().toPath().toAbsolutePath().normalize();
                    return path.equals(userFile) || Files.exists(path) && Files.exists(userFile) && Files.isSameFile(path, userFile);
                } catch (IOException notAFile) { return false; }
            }
        }
        return false;
    }

    private static Set<String> keychainReferences(Object value) {
        Set<String> references = new LinkedHashSet<>();
        collectKeychainReferences(value, references);
        return references;
    }

    private static void collectKeychainReferences(Object value, Set<String> references) {
        if (value instanceof String text && text.startsWith("${HARNEX_KEYCHAIN_PROVIDER_") && text.endsWith("}")) references.add(text);
        else if (value instanceof Map<?, ?> map) map.forEach((key, item) -> {
            if (!"runtime-credential-revisions".equals(key)) collectKeychainReferences(item, references);
        });
        else if (value instanceof List<?> list) list.forEach(item -> collectKeychainReferences(item, references));
    }

    public String revealProviderApiKey(String providerId) {
        if (providerId == null || !ID.matcher(providerId).matches()) {
            throw new IllegalArgumentException("invalid provider id: " + providerId);
        }
        return revealConfiguredApiKey("providers", providerId);
    }

    public String revealImageServiceApiKey(String serviceId) {
        if (serviceId == null || !ID.matcher(serviceId).matches()) {
            throw new IllegalArgumentException("invalid image service id: " + serviceId);
        }
        return revealConfiguredApiKey("image-services", serviceId);
    }

    private String revealConfiguredApiKey(String section, String id) {
        Object providerBlock = normalizeProviderCollection(harnex(readDocument(userFile)).get(section));
        if (!(providerBlock instanceof Map<?, ?> providers)
            || !(providers.get(id) instanceof Map<?, ?> provider)) {
            return "";
        }
        Object value = provider.containsKey("api-key") ? provider.get("api-key") : provider.get("apiKey");
        return value == null ? "" : String.valueOf(value);
    }

    public List<String> validate(Map<String, Object> values) {
        values = new LinkedHashMap<>(values);
        if (values.containsKey("providers")) {
            values.put("providers", normalizeProviderCollection(values.get("providers")));
        }
        List<String> errors = new ArrayList<>();
        if (values.containsKey("web-search")) {
            try { resolveWebSearchSettings(values.get("web-search")); }
            catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
        }
        validateImageServices(values.get("image-services"), errors);
        Object providerBlock = values.get("providers");
        if (providerBlock instanceof Map<?, ?> providers) {
            providers.forEach((rawId, rawValue) -> {
                String id = String.valueOf(rawId);
                if (!ID.matcher(id).matches()) {
                    errors.add("invalid provider id: " + id);
                }
                if ("fallback".equalsIgnoreCase(id) && !"fallback".equals(id)) {
                    errors.add("reserved provider id fallback must use lowercase spelling");
                }
                if (!(rawValue instanceof Map<?, ?> provider)) {
                    errors.add("provider " + id + " must be an object");
                    return;
                }
                if (blank(provider.get("profile"))) {
                    errors.add("provider " + id + " requires profile");
                }
                validateHttpUrl(provider.get("base-url"), "provider " + id + " base-url", errors, true);
                validateHttpUrl(provider.get("baseUrl"), "provider " + id + " baseUrl", errors, true);
            });
        }
        Object httpBlock = values.get("http");
        if (httpBlock instanceof Map<?, ?> http) {
            Object proxy = http.containsKey("proxy") ? http.get("proxy") : null;
            if (proxy != null && !String.valueOf(proxy).isBlank()
                && !Set.of("off", "system").contains(String.valueOf(proxy).toLowerCase(Locale.ROOT))) {
                try {
                    URI uri = URI.create(String.valueOf(proxy));
                    if (!Set.of("http", "https", "socks5").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT))) {
                        errors.add("http.proxy must use http, https, socks5, system or off");
                    }
                } catch (RuntimeException e) {
                    errors.add("http.proxy is not a valid URI");
                }
            }
        }
        validateRouter(values.get("router"), errors);
        validateSummarizer(values.get("summarizer"), errors);
        validateWorkspace(values.get("workspace"), errors);
        if (values.containsKey("execution")) {
            try { convert(normalizeKeys(values.get("execution")), AgentConfig.Execution.class).validate(); }
            catch (RuntimeException e) { errors.add("execution: " + e.getMessage()); }
        }
        if (errors.isEmpty()) {
            try {
                validateResolvedCandidate(values);
            } catch (RuntimeException e) {
                errors.add(e.getMessage() == null ? "provider configuration is invalid" : e.getMessage());
            }
        }
        return errors;
    }

    /**
     * Resolve an unsaved settings document for an ephemeral runtime operation. Secret placeholders are replaced from
     * the persisted user document, but nothing is written and the returned map is never exposed by a controller.
     */
    public Map<String, Object> resolveCandidateValues(Map<String, Object> submitted) {
        Map<String, Object> values = selectEditable(submitted == null ? Map.of() : submitted, EDITABLE);
        List<String> errors = validate(values);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return preserveSecretPlaceholders(values,
            effectiveExisting(harnex(readDocument(userFile))));
    }

    /** Resolve a search draft in isolation, preserving saved secret placeholders without changing live beans. */
    public AgentConfig.WebSearch resolveWebSearchSettings(Object draft) {
        if (!(draft instanceof Map<?, ?>)) throw new IllegalArgumentException("web-search must be an object");
        Map<String, Object> existing = mapValue(normalizeProviderFields(JSON.convertValue(cfg.getWebSearch(), Map.class)));
        Map<String, Object> saved = mapValue(normalizeProviderFields(harnex(readDocument(userFile)).get("web-search")));
        existing = mergeMaps(existing, saved);
        Map<String, Object> input = mapValue(normalizeProviderFields(draft));
        String previousProvider = String.valueOf(existing.getOrDefault("provider", "tavily"));
        String nextProvider = String.valueOf(input.getOrDefault("provider", previousProvider)).trim().toLowerCase(Locale.ROOT);
        if (!previousProvider.equalsIgnoreCase(nextProvider)) {
            existing.put("api-key", ""); existing.put("endpoint", "");
            if (SECRET_PLACEHOLDER.equals(input.get("api-key"))) {
                throw new IllegalArgumentException("web-search.api-key must be re-entered when changing search service");
            }
        }
        Map<String, Object> merged = mergeMaps(existing, preserveSecretPlaceholders(input, existing));
        merged.put("provider", nextProvider);
        List<String> errors = new ArrayList<>();
        if (!Set.of("tavily", "exa", "brave", "searxng", "perplexity", "anysearch", "tinyfish").contains(nextProvider)) {
            errors.add("web-search.provider must be tavily, exa, brave, searxng, perplexity, anysearch or tinyfish");
        }
        String language = String.valueOf(merged.getOrDefault("language", ""));
        if (!language.isBlank() && !language.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"))
            errors.add("web-search.language must be a language code such as zh or en");
        validateHttpUrl(merged.get("endpoint"), "web-search.endpoint", errors, true);
        String endpoint = String.valueOf(merged.getOrDefault("endpoint", ""));
        if (!endpoint.isBlank() && !endpoint.startsWith("${")) {
            try {
                URI uri = URI.create(endpoint);
                if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                    errors.add("web-search.endpoint must not contain credentials, query or fragment");
            } catch (IllegalArgumentException ignored) { /* URL error already recorded. */ }
        }
        validateIntegerRange(merged, "max-results", "maxResults", 1, Integer.MAX_VALUE, "web-search.max-results", errors);
        validateLongRange(merged, "timeout-ms", "timeoutMs", 1000, 120000, "web-search.timeout-ms", errors);
        validateIntegerRange(merged, "circuit-threshold", "circuitThreshold", 1, 100, "web-search.circuit-threshold", errors);
        validateLongRange(merged, "circuit-cooldown-ms", "circuitCooldownMs", 1000, 3600000,
            "web-search.circuit-cooldown-ms", errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        AgentConfig.WebSearch search;
        try { search = convert(normalizeKeys(merged), AgentConfig.WebSearch.class); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("web-search contains invalid settings"); }
        if (search.isEnabled()) {
            if ("searxng".equals(nextProvider) && search.getEndpoint().isBlank())
                throw new IllegalArgumentException("web-search.endpoint is required for SearXNG");
            if (!"searxng".equals(nextProvider) && search.getApiKey().isBlank())
                throw new IllegalArgumentException("web-search.api-key is required for the selected service");
        }
        return search;
    }

    public Map<String, Object> testEndpoint(String kind, String url) {
        List<String> errors = new ArrayList<>();
        validateHttpUrl(url, kind + " url", errors, false);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        long started = System.nanoTime();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return Map.of("reachable", true, "status", response.statusCode(), "latencyMs",
                Duration.ofNanos(System.nanoTime() - started).toMillis(), "kind", kind);
        } catch (Exception e) {
            return Map.of("reachable", false, "message", e.getMessage() == null ? e.getClass().getSimpleName()
                : e.getMessage(), "latencyMs", Duration.ofNanos(System.nanoTime() - started).toMillis(), "kind", kind);
        }
    }

    private Map<String, Object> effective() {
        Map<String, Object> values = new LinkedHashMap<>();
        RuntimeConfiguration live = liveRuntime();
        AgentConfig active = live == null ? cfg : live.publishedConfiguration();
        values.put("providers", JSON.convertValue(active.getProviders(), Object.class));
        values.put("imageServices", JSON.convertValue(active.getImageServices(), Object.class));
        values.put("defaultImageService", active.getDefaultImageService());
        Map<String, Object> profileDefaults = new LinkedHashMap<>();
        active.getProviderProfiles().forEach((profile, definition) -> {
            Map<String, Integer> windows = new LinkedHashMap<>();
            if (definition.getModels() != null) for (var model : definition.getModels()) {
                if (model != null && model.getId() != null && model.getContextWindow() != null) {
                    windows.put(model.getId(), model.getContextWindow());
                }
            }
            profileDefaults.put(profile, Map.of(
                "label", definition.getLabel() == null || definition.getLabel().isBlank() ? profile : definition.getLabel(),
                "type", definition.getType() == null ? "" : definition.getType(),
                "baseUrl", definition.getBaseUrl() == null ? "" : definition.getBaseUrl(),
                "defaultModel", definition.getDefaultModel() == null ? "" : definition.getDefaultModel(),
                "modelContextWindows", windows));
        });
        values.put("providerProfileDefaults", profileDefaults);
        values.put("router", JSON.convertValue(active.getRouter(), Object.class));
        values.put("summarizer", JSON.convertValue(active.getSummarizer(), Object.class));
        values.put("http", JSON.convertValue(active.getHttp(), Object.class));
        values.put("webSearch", JSON.convertValue(active.getWebSearch(), Object.class));
        values.put("workspace", Map.of("outsideWritePolicy", active.getWorkspace().getOutsideWritePolicy()));
        values.put("safety", JSON.convertValue(active.getSafety(), Object.class));
        values.put("execution", JSON.convertValue(active.getExecution(), Object.class));
        values.put("toolsets", JSON.convertValue(active.getToolsets(), Object.class));
        values.put("mcp", JSON.convertValue(active.getMcp(), Object.class));
        values.put("hooks", JSON.convertValue(active.getHooks(), Object.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)maskSecrets(values);
        return result;
    }

    private static void validateRouter(Object value, List<String> errors) {
        if (value == null) return;
        if (!(value instanceof Map<?, ?> router)) {
            errors.add("router must be an object");
            return;
        }
        if (router.containsKey("order")) {
            errors.add("router.order is not supported; remove it and use router.default-provider");
        }
        Object defaultProvider = router.containsKey("default-provider")
            ? router.get("default-provider") : router.get("defaultProvider");
        if (defaultProvider != null && !String.valueOf(defaultProvider).isBlank()
            && !ID.matcher(String.valueOf(defaultProvider).trim()).matches()) {
            errors.add("router.default-provider contains invalid provider id: " + defaultProvider);
        }
        validateIntegerRange(router, "selected-provider-max-attempts", "selectedProviderMaxAttempts", 1, 10,
            "router.selected-provider-max-attempts", errors);
        validateIntegerRange(router, "circuit-failure-threshold", "circuitFailureThreshold", 1, 100,
            "router.circuit-failure-threshold", errors);
        validateLongRange(router, "circuit-open-ms", "circuitOpenMs", 1_000L, 3_600_000L,
            "router.circuit-open-ms", errors);
    }

    private static void validateImageServices(Object value, List<String> errors) {
        if (value == null) return;
        if (!(value instanceof Map<?, ?> entries)) { errors.add("image-services must be an object"); return; }
        entries.forEach((rawId, rawService) -> {
            String id = String.valueOf(rawId);
            if (!ID.matcher(id).matches()) errors.add("invalid image service id: " + id);
            try {
                var service = convert(normalizeKeys(rawService), ImageServiceConfig.class);
                if (service == null) { errors.add("image service " + id + " must be an object"); return; }
                validateHttpUrl(service.getBaseUrl(), "image service " + id + " base-url", errors, true);
                if (service.getTimeoutMillis() < 1000 || service.getTimeoutMillis() > 1800000) errors.add("image service " + id + ": timeout-millis must be 1000..1800000");
                var caps = service.getCapabilities();
                if (caps != null && (caps.getMaxCount() < 1 || caps.getMaxCount() > 16 || caps.getMaxSources() < 0
                    || caps.getMaxSources() > 16 || caps.getMaxSourceBytes() < 1 || caps.getMaxSourceBytes() > 67108864)) errors.add("image service " + id + ": invalid capability limits");
                var pricing = service.getPricing();
                if (pricing != null) {
                    if (pricing.currency() == null || !pricing.currency().matches("[A-Z]{3}")) errors.add("image service " + id + ": pricing currency must be a three-letter uppercase code");
                    for (Double rate : new Double[] { pricing.perImage(), pricing.textInputPerMillion(), pricing.imageInputPerMillion(), pricing.imageOutputPerMillion() }) {
                        if (rate != null && (!Double.isFinite(rate) || rate < 0)) errors.add("image service " + id + ": prices must be finite and non-negative");
                    }
                    if (pricing.perImage() != null && (pricing.textInputPerMillion() != null || pricing.imageInputPerMillion() != null || pricing.imageOutputPerMillion() != null)) errors.add("image service " + id + ": choose either per-image or token pricing");
                }
                // Missing models and unimplemented profiles remain inspectable catalog states; they do not block text settings.
            } catch (RuntimeException invalid) { errors.add("image service " + id + ": invalid configuration fields"); }
        });
    }

    private static void validateIntegerRange(Map<?, ?> values, String kebab, String camel, int min, int max,
        String path, List<String> errors) {
        Object value = values.containsKey(kebab) ? values.get(kebab) : values.get(camel);
        if (value == null) return;
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            if (parsed < min || parsed > max) errors.add(path + " must be between " + min + " and " + max);
        } catch (NumberFormatException e) {
            errors.add(path + " must be an integer");
        }
    }

    private static void validateLongRange(Map<?, ?> values, String kebab, String camel, long min, long max,
        String path, List<String> errors) {
        Object value = values.containsKey(kebab) ? values.get(kebab) : values.get(camel);
        if (value == null) return;
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed < min || parsed > max) errors.add(path + " must be between " + min + " and " + max);
        } catch (NumberFormatException e) {
            errors.add(path + " must be an integer");
        }
    }

    private static void validateSummarizer(Object value, List<String> errors) {
        if (value == null) return;
        if (!(value instanceof Map<?, ?> summarizer)) {
            errors.add("summarizer must be an object");
            return;
        }
        validateIntegerRange(summarizer, "context-window", "contextWindow", 1_024, 2_000_000,
            "summarizer.context-window", errors);
        validateIntegerRange(summarizer, "output-reserve-tokens", "outputReserveTokens", 0, 2_000_000,
            "summarizer.output-reserve-tokens", errors);
        validateIntegerRange(summarizer, "safety-reserve-tokens", "safetyReserveTokens", 0, 2_000_000,
            "summarizer.safety-reserve-tokens", errors);
        validateIntegerRange(summarizer, "soft-threshold-percent", "softThresholdPercent", 1, 99,
            "summarizer.soft-threshold-percent", errors);
        validateIntegerRange(summarizer, "hard-threshold-percent", "hardThresholdPercent", 1, 99,
            "summarizer.hard-threshold-percent", errors);
        validateIntegerRange(summarizer, "target-percent", "targetPercent", 1, 99,
            "summarizer.target-percent", errors);
        validateIntegerRange(summarizer, "min-new-tokens", "minNewTokens", 0, 2_000_000,
            "summarizer.min-new-tokens", errors);
        validateIntegerRange(summarizer, "min-new-context-percent", "minNewContextPercent", 0, 100,
            "summarizer.min-new-context-percent", errors);
        validateIntegerRange(summarizer, "minimum-reduction-percent", "minimumReductionPercent", 0, 100,
            "summarizer.minimum-reduction-percent", errors);
        validateIntegerRange(summarizer, "max-ineffective-compressions", "maxIneffectiveCompressions", 1, 100,
            "summarizer.max-ineffective-compressions", errors);
        validateIntegerRange(summarizer, "keep-tail", "keepTail", 0, 10_000,
            "summarizer.keep-tail", errors);
        validateIntegerRange(summarizer, "keep-tail-chars", "keepTailChars", 0, 10_000_000,
            "summarizer.keep-tail-chars", errors);
        validateIntegerRange(summarizer, "keep-head", "keepHead", 1, 1_000,
            "summarizer.keep-head", errors);
        validateIntegerRange(summarizer, "summary-max-chars", "summaryMaxChars", ContinuationSummary.MIN_MAX_CHARS, 1_000_000,
            "summarizer.summary-max-chars", errors);
        validateIntegerRange(summarizer, "max-output-tokens", "maxOutputTokens", 1, 131_072,
            "summarizer.max-output-tokens", errors);
        validateIntegerRange(summarizer, "max-concurrent", "maxConcurrent", 1, 8,
            "summarizer.max-concurrent", errors);
        validateLongRange(summarizer, "timeout-ms", "timeoutMs", 1L, 600_000L,
            "summarizer.timeout-ms", errors);
        validateLongRange(summarizer, "attempt-timeout-ms", "attemptTimeoutMs", 1L, 600_000L,
            "summarizer.attempt-timeout-ms", errors);
        Long context = longValue(summarizer, "context-window", "contextWindow");
        Long outputReserve = longValue(summarizer, "output-reserve-tokens", "outputReserveTokens");
        Long safetyReserve = longValue(summarizer, "safety-reserve-tokens", "safetyReserveTokens");
        if (context != null && (outputReserve == null ? 0 : outputReserve) + (safetyReserve == null ? 0 : safetyReserve)
            >= context) {
            errors.add("summarizer output-reserve-tokens + safety-reserve-tokens must be < context-window");
        }
        Long target = longValue(summarizer, "target-percent", "targetPercent");
        Long soft = longValue(summarizer, "soft-threshold-percent", "softThresholdPercent");
        Long hard = longValue(summarizer, "hard-threshold-percent", "hardThresholdPercent");
        if (target != null && soft != null && hard != null && !(target < soft && soft < hard)) {
            errors.add("summarizer token watermarks must satisfy target-percent < soft-threshold-percent "
                + "< hard-threshold-percent");
        }
    }

    private static Long longValue(Map<?, ?> values, String kebab, String camel) {
        Object value = values.containsKey(kebab) ? values.get(kebab) : values.get(camel);
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void validateWorkspace(Object value, List<String> errors) {
        if (value == null) return;
        if (!(value instanceof Map<?, ?> workspace)) {
            errors.add("workspace must be an object");
            return;
        }
        Object policy = workspace.containsKey("outside-write-policy")
            ? workspace.get("outside-write-policy") : workspace.get("outsideWritePolicy");
        if (policy != null && !Set.of("deny", "ask", "allow")
            .contains(String.valueOf(policy).trim().toLowerCase(Locale.ROOT))) {
            errors.add("workspace.outside-write-policy must be deny, ask or allow");
        }
    }

    private void validateResolvedCandidate(Map<String, Object> values) {
        AgentConfig candidate = new AgentConfig();
        Map<String, AgentConfig.ProviderProfileDef> profiles = cfg.getProviderProfiles();
        if (profiles == null || profiles.isEmpty()) {
            profiles = convert(normalizeNamedMap(packagedHarnex.get("provider-profiles")),
                new TypeReference<Map<String, AgentConfig.ProviderProfileDef>>() {});
        }
        candidate.setProviderProfiles(profiles);
        candidate.setVision(cfg.getVision());
        candidate.setWebSearch(cfg.getWebSearch());
        candidate.setMoa(cfg.getMoa());
        Map<String, Object> persisted = harnex(readDocument(userFile));
        Object providerValues = values.containsKey("providers") ? values.get("providers")
            : persisted.containsKey("providers") ? persisted.get("providers") : packagedHarnex.get("providers");
        candidate.setProviders(convert(normalizeNamedMap(normalizeProviderCollection(providerValues)),
            new TypeReference<Map<String, AgentConfig.ProviderConfig>>() {}));
        candidate.setRouter(convert(normalizeKeys(values.getOrDefault("router", persisted.getOrDefault("router", cfg.getRouter()))),
            AgentConfig.Router.class));
        candidate.setSummarizer(convert(normalizeKeys(values.getOrDefault("summarizer", persisted.getOrDefault("summarizer", cfg.getSummarizer()))),
            AgentConfig.Summarizer.class));
        candidate.getSummarizer().validate(LoggerFactory.getLogger(ConfigControlService.class));
        providerResolver.resolve(candidate);
    }

    private static <T> T convert(Object value, Class<T> type) {
        return JSON.convertValue(value, type);
    }

    private static <T> T convert(Object value, TypeReference<T> type) {
        return JSON.convertValue(value, type);
    }

    private static Object normalizeKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(toCamelCase(String.valueOf(key)),
                WIRE_FIELDS.contains(toKebabCase(String.valueOf(key))) ? item : normalizeKeys(item)));
            return normalized;
        }
        if (value instanceof List<?> list) return list.stream().map(ConfigControlService::normalizeKeys).toList();
        return value;
    }

    /** Preserve user/config ids at a named-map boundary while normalising the fields inside each value DTO. */
    private static Object normalizeNamedMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return value;
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((key, item) -> normalized.put(String.valueOf(key), normalizeKeys(item)));
        return normalized;
    }

    private static String toCamelCase(String key) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char ch : key.toCharArray()) {
            if (ch == '-' || ch == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static String toKebabCase(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replace('_', '-').toLowerCase(Locale.ROOT);
    }

    /** Provider ids are user-owned; only the DTO fields inside each provider have canonical YAML spellings. */
    private static Object normalizeProviderCollection(Object value) {
        if (!(value instanceof Map<?, ?> map)) return value;
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((id, provider) -> normalized.put(String.valueOf(id), normalizeProviderFields(provider)));
        return normalized;
    }

    private static Object normalizeProviderFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String original = String.valueOf(key);
                String canonical = toKebabCase(original);
                // UI edits use kebab-case. A stale camelCase alias must never override an edit or an explicit clear.
                if (!original.equals(canonical) && map.containsKey(canonical)) return;
                if (item != null) {
                    normalized.put(canonical, WIRE_FIELDS.contains(canonical) ? item : normalizeProviderFields(item));
                }
            });
            return normalized;
        }
        if (value instanceof List<?> list) return list.stream().map(ConfigControlService::normalizeProviderFields).toList();
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>)map) : new LinkedHashMap<>();
    }

    /** Recursive configuration-layer merge used to present the editable target catalogue. */
    private static Map<String, Object> mergeMaps(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        overlay.forEach((key, value) -> {
            Object inherited = result.get(key);
            if (value instanceof Map<?, ?> && inherited instanceof Map<?, ?>) {
                result.put(key, mergeMaps(mapValue(inherited), mapValue(value)));
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    /** Normal Spring layering for every section except the concrete Provider collection. */
    private Map<String, Object> effectiveExisting(Map<String, Object> userHarnex) {
        Map<String, Object> normalized = new LinkedHashMap<>(userHarnex);
        if (normalized.containsKey("web-search")) normalized.put("web-search", normalizeProviderFields(normalized.get("web-search")));
        Map<String, Object> effective = mergeMaps(packagedHarnex, normalized);
        effective.put("providers", normalizeProviderCollection(userHarnex.containsKey("providers")
            ? userHarnex.get("providers") : packagedHarnex.get("providers")));
        effective.put("web-search", normalizeProviderFields(effective.get("web-search")));
        return effective;
    }

    private static Map<String, Object> packagedHarnex() {
        try (var input = ConfigControlService.class.getResourceAsStream("/agent-config.yml")) {
            if (input == null) return new LinkedHashMap<>();
            Map<String, Object> document = YAML.readValue(input, MAP);
            return harnex(document == null ? Map.of() : document);
        } catch (IOException e) {
            throw new IllegalStateException("packaged configuration is unreadable", e);
        }
    }

    private static String normalizeScope(String scope) {
        String normalized = scope == null ? "user" : scope.trim().toLowerCase(Locale.ROOT);
        if (!"user".equals(normalized)) {
            throw new IllegalArgumentException("configuration scope must be user; workspace state has dedicated APIs");
        }
        return normalized;
    }

    private static Map<String, Object> readDocument(Path file) {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> value = YAML.readValue(file.toFile(), MAP);
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        } catch (IOException e) {
            throw new IllegalStateException("configuration file is unreadable: " + file, e);
        }
    }

    private static void writeDocument(Path file, Map<String, Object> document) {
        try {
            SharedFileTransaction.writeAtomically(file, YAML.writeValueAsBytes(document), true, true);
        } catch (IOException e) {
            throw new IllegalStateException("configuration save failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> harnex(Map<String, Object> document) {
        Object block = document.get("harnex");
        return block instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>)map) : new LinkedHashMap<>();
    }

    private static Map<String, Object> selectEditable(Map<String, Object> source, Set<String> allowed) {
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String key : allowed) {
            if (source.containsKey(key)) {
                selected.put(key, switch (key) {
                    case "workspace" -> selectWorkspacePolicy(source.get(key));
                    case "providers", "image-services" -> normalizeProviderCollection(source.get(key));
                    case "web-search" -> normalizeProviderFields(source.get(key));
                    default -> source.get(key);
                });
            }
        }
        return selected;
    }

    /** The generic user control plane may change the policy, never the runtime-selected project root or RED globs. */
    private static Map<String, Object> selectWorkspacePolicy(Object value) {
        if (!(value instanceof Map<?, ?> workspace)) return new LinkedHashMap<>();
        Map<String, Object> selected = new LinkedHashMap<>();
        if (workspace.containsKey("outside-write-policy")) {
            selected.put("outside-write-policy", workspace.get("outside-write-policy"));
        } else if (workspace.containsKey("outsideWritePolicy")) {
            selected.put("outside-write-policy", workspace.get("outsideWritePolicy"));
        }
        return selected;
    }

    private static String revision(Path file) {
        try {
            byte[] bytes = Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("cannot compute config revision", e);
        }
    }

    private static Object maskSecrets(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key),
                isSecretKey(String.valueOf(key)) && item != null && !String.valueOf(item).isBlank()
                    ? SECRET_PLACEHOLDER : maskSecrets(item)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ConfigControlService::maskSecrets).toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> preserveSecretPlaceholders(Map<String, Object> submitted,
        Map<String, Object> existing) {
        return (Map<String, Object>)preserve(submitted, existing, "");
    }

    private static Object preserve(Object submitted, Object existing, String key) {
        if (SECRET_PLACEHOLDER.equals(submitted) && isSecretKey(key)) {
            return existing;
        }
        if (submitted instanceof Map<?, ?> map) {
            Map<?, ?> existingMap = existing instanceof Map<?, ?> e ? e : Map.of();
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((childKey, childValue) -> out.put(String.valueOf(childKey),
                preserve(childValue, existingMap.get(childKey), String.valueOf(childKey))));
            return out;
        }
        if (submitted instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return submitted;
    }

    private static boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (Set.of("maxtokens", "maxoutputtokens", "defaultmaxtokens", "outputreservetokens", "safetyreservetokens",
            "minnewtokens").contains(normalized)) return false;
        return normalized.contains("apikey") || normalized.contains("token") || normalized.contains("password")
            || normalized.contains("secret");
    }

    private static void validateHttpUrl(Object value, String label, List<String> errors, boolean allowBlank) {
        if (value == null || String.valueOf(value).isBlank()) {
            if (!allowBlank) {
                errors.add(label + " is required");
            }
            return;
        }
        String configured = String.valueOf(value).trim();
        if (configured.startsWith("${") && configured.endsWith("}")) {
            String expression = configured.substring(2, configured.length() - 1);
            int separator = expression.indexOf(':');
            configured = separator < 0 ? "" : expression.substring(separator + 1).trim();
            if (configured.isBlank() && allowBlank) return;
        }
        try {
            URI uri = URI.create(configured);
            if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT))
                || uri.getHost() == null) {
                errors.add(label + " must be an absolute http(s) URL");
            }
        } catch (RuntimeException e) {
            errors.add(label + " is not a valid URL");
        }
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    public static final class RevisionConflictException extends RuntimeException {
        private final String currentRevision;

        public RevisionConflictException(String currentRevision) {
            super("configuration changed since it was loaded");
            this.currentRevision = currentRevision;
        }

        public String currentRevision() {
            return currentRevision;
        }
    }
}
