package com.harnex.image;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.execution.ImageSpec;
import com.harnex.net.OutboundProxyResolver;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;

/** Missing or invalid image configuration is a catalog state, never a Runtime startup failure. */
@Component
public final class ImageServiceRegistry {
    public enum State { UNCONFIGURED, UNSUPPORTED, CONFIGURED_UNVERIFIED, AVAILABLE, TEMPORARILY_UNAVAILABLE }
    private final AgentConfig config;
    private final Path snapshots;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Health> health = new ConcurrentHashMap<>();
    public ImageServiceRegistry(AgentConfig config) {
        this.config = config;
        snapshots = Path.of(config.getDataDir()).resolve("images/configurations");
    }
    public Catalog catalog() {
        List<ServiceView> services = new ArrayList<>();
        for (var entry : configured().entrySet()) services.add(inspect(entry.getKey(), entry.getValue()));
        String selected = defaultId();
        State state = services.isEmpty() || services.stream().allMatch(service -> service.state() == State.UNCONFIGURED)
            ? State.UNCONFIGURED : services.stream().anyMatch(service -> service.state() == State.AVAILABLE) ? State.AVAILABLE
            : services.stream().anyMatch(ServiceView::canSubmit) ? State.CONFIGURED_UNVERIFIED
            : services.stream().allMatch(service -> service.state() == State.UNSUPPORTED) ? State.UNSUPPORTED : State.TEMPORARILY_UNAVAILABLE;
        return new Catalog(state, selected, List.copyOf(services));
    }
    public ImageServiceSnapshot freeze(String requestedId) {
        String id = requestedId == null || requestedId.isBlank() ? defaultId() : requestedId;
        if (id == null) throw new ImageServiceException("image_model_not_configured", "No image generation model is configured. Text conversation remains available.");
        ImageServiceConfig entry = configured().get(id);
        if (entry == null) throw new ImageServiceException("image_service_not_found", "The selected image service no longer exists");
        ServiceView view = inspect(id, entry);
        if (!view.canSubmit()) throw new ImageServiceException(view.code(), view.message());
        ImageServiceSnapshot snapshot = resolve(id, entry).snapshot();
        try {
            Path file = snapshots.resolve(snapshot.snapshotId() + ".json");
            if (!Files.isRegularFile(file)) AtomicJsonFiles.write(json, file, snapshot);
        } catch (IOException failure) { throw new ImageServiceException("image_storage_unavailable", "Could not freeze the image service configuration"); }
        return snapshot;
    }
    public ImageServiceSnapshot snapshot(String id) {
        if (!Ids.isSafeResourceId(id)) throw new ImageServiceException("image_configuration_unavailable", "Invalid image configuration reference");
        try { return json.readValue(Files.readAllBytes(snapshots.resolve(id + ".json")), ImageServiceSnapshot.class); }
        catch (IOException failure) { throw new ImageServiceException("image_configuration_unavailable", "The frozen image service configuration is unavailable"); }
    }
    public Connection connection(ImageServiceSnapshot snapshot) {
        ImageServiceConfig entry = configured().get(snapshot.serviceId());
        if (entry == null || !entry.isEnabled()) throw new ImageServiceException("image_service_unavailable", "The image service has been disabled or removed");
        Resolved current = resolve(snapshot.serviceId(), entry);
        if (!snapshot.connectionFingerprint().equals(current.snapshot().connectionFingerprint())) {
            throw new ImageServiceException("image_configuration_changed", "The image service connection changed after submission; review the settings and submit again");
        }
        return current.connection();
    }
    public void succeeded(ImageServiceSnapshot snapshot) { health.put(snapshot.snapshotId(), new Health(true, 0, null, null)); }
    public void failed(ImageServiceSnapshot snapshot, ImageServiceException failure) {
        long cooldown = Math.max(30_000, Math.min(24 * 3600_000L, failure.retryAfterMillis()));
        health.put(snapshot.snapshotId(), new Health(false, System.currentTimeMillis() + cooldown, failure.code(), failure.getMessage()));
    }
    private ServiceView inspect(String id, ImageServiceConfig entry) {
        try {
            if (entry == null || !entry.isEnabled()) return unavailable(id, entry, State.UNCONFIGURED, "image_service_disabled", "The image service is disabled");
            Resolved resolved = resolve(id, entry);
            ImageServiceSnapshot snapshot = resolved.snapshot();
            Health observed = health.get(snapshot.snapshotId());
            if (observed != null && !observed.success() && observed.until() > System.currentTimeMillis()) {
                return new ServiceView(id, label(id, entry), snapshot.profile(), snapshot.model(), snapshot.mainModel(),
                    State.TEMPORARILY_UNAVAILABLE, false, observed.code(), observed.message(), observed.until(), snapshot.capabilities(), snapshot.defaults());
            }
            return new ServiceView(id, label(id, entry), snapshot.profile(), snapshot.model(), snapshot.mainModel(),
                observed != null && observed.success() ? State.AVAILABLE : State.CONFIGURED_UNVERIFIED, true, null, null, 0,
                snapshot.capabilities(), snapshot.defaults());
        } catch (ImageServiceException failure) {
            State state = "image_profile_unsupported".equals(failure.code()) ? State.UNSUPPORTED
                : "image_model_not_configured".equals(failure.code()) ? State.UNCONFIGURED : State.TEMPORARILY_UNAVAILABLE;
            return unavailable(id, entry, state, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) { return unavailable(id, entry, State.UNSUPPORTED, "image_configuration_invalid", "The image service configuration is invalid"); }
    }
    private Resolved resolve(String id, ImageServiceConfig entry) {
        if (!Ids.isSafe(id) || entry == null) throw new ImageServiceException("image_configuration_invalid", "Invalid image service identifier");
        String profile = entry.getProfile() == null ? "" : entry.getProfile().trim().toLowerCase(Locale.ROOT);
        if (!List.of("openai-images", "openai-responses-image", NativeImageOptions.GEMINI, NativeImageOptions.QWEN).contains(profile)) {
            throw new ImageServiceException("image_profile_unsupported", "This Runtime does not implement the configured image profile");
        }
        String model = resolveEnv(entry.getModel());
        String main = resolveEnv(entry.getMainModel());
        if (blank(model) || "openai-responses-image".equals(profile) && blank(main)) {
            throw new ImageServiceException("image_model_not_configured", "Configure an image model and, for Responses image services, a main model");
        }
        if (NativeImageOptions.GEMINI.equals(profile)) NativeImageOptions.geminiModel(model);
        String base = entry.getBaseUrl(), key = entry.getApiKey();
        String resolvedKey = resolveEnv(key);
        if (key != null && key.startsWith("$") && (!key.startsWith("${") || blank(resolvedKey))) throw new ImageServiceException("image_credentials_unavailable", "The image service credential reference is unavailable");
        String resolvedBase = resolveEnv(base);
        if (base != null && base.startsWith("$") && (!base.startsWith("${") || blank(resolvedBase))) throw new ImageServiceException("image_connection_unavailable", "The image service endpoint reference is unavailable");
        base = resolvedBase;
        if (blank(base)) base = NativeImageOptions.defaultBase(profile);
        if (blank(base)) throw new ImageServiceException("image_configuration_invalid", "Configure the DashScope image endpoint for your workspace and region");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URI uri;
        try { uri = URI.create(base); }
        catch (IllegalArgumentException invalid) { throw new ImageServiceException("image_configuration_invalid", "Invalid image service URL"); }
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null) throw new ImageServiceException("image_configuration_invalid", "Image service URL must be an HTTP endpoint without embedded credentials");
        if ("api.openai.com".equalsIgnoreCase(uri.getHost()) && blank(resolvedKey)) throw new ImageServiceException("image_credentials_unavailable", "The image service needs an API credential");
        if (NativeImageOptions.QWEN.equals(profile)) NativeImageOptions.endpoint(profile, base, NativeImageOptions.QWEN_PATH);
        if (NativeImageOptions.GEMINI.equals(profile)) NativeImageOptions.endpoint(profile, base,
            "/models/" + NativeImageOptions.geminiModel(model) + ":generateContent");
        if (NativeImageOptions.nativeProfile(profile) && blank(resolvedKey)) throw new ImageServiceException("image_credentials_unavailable", "The image service needs an API credential");
        var declared = entry.getCapabilities() == null ? new ImageServiceConfig.Capabilities() : entry.getCapabilities();
        int maxCount = Math.min("openai-responses-image".equals(profile) ? 1 : 10, declared.getMaxCount());
        if (maxCount < 1 || declared.getMaxSources() < 0 || declared.getMaxSources() > 16 || declared.getMaxSourceBytes() < 1) {
            throw new ImageServiceException("image_configuration_invalid", "Invalid image service limits");
        }
        boolean nativeProfile = NativeImageOptions.nativeProfile(profile);
        boolean google = NativeImageOptions.GEMINI.equals(profile);
        int maxSources = declared.getMaxSources();
        if (nativeProfile) {
            maxCount = Math.min(maxCount, google ? 1 : 6);
            maxSources = Math.min(maxSources, google ? 14 : List.of("qwen-image-2.1-pro", "qwen-image-2.1-turbo").contains(model) ? 10 : 3);
        }
        List<String> sizes = google ? NativeImageOptions.geminiSizes() : declared.getSizes();
        if (google && !declared.getSizes().isEmpty()) sizes = NativeImageOptions.restrict(declared.getSizes(), sizes);
        if (google && sizes.isEmpty()) throw new ImageServiceException("image_configuration_invalid", "No configured Gemini image size is supported");
        ImageCapabilities caps = new ImageCapabilities(declared.isGenerate(), declared.isEdit(), !nativeProfile && declared.isMask(), maxCount,
            maxSources, Math.min(nativeProfile ? google ? 20L * 1024 * 1024 : 10L * 1024 * 1024 : 64L * 1024 * 1024, declared.getMaxSourceBytes()), sizes,
            nativeProfile ? NativeImageOptions.restrict(declared.getQualities(), List.of("auto")) : declared.getQualities(),
            nativeProfile ? NativeImageOptions.restrict(declared.getFormats(), List.of("png")) : declared.getFormats(),
            nativeProfile ? NativeImageOptions.restrict(declared.getBackgrounds(), List.of("auto")) : declared.getBackgrounds(), false, false, false);
        if (caps.qualities().isEmpty() || caps.formats().isEmpty() || caps.backgrounds().isEmpty()) {
            throw new ImageServiceException("image_configuration_invalid", "The declared image parameters have no values supported by this protocol");
        }
        ImageSpec.Parameters defaults = entry.getDefaults() == null ? new ImageSpec.Parameters(1, "auto", "auto", "png", "auto") : entry.getDefaults();
        String fingerprint = IdempotencyReceiptStore.requestHash(Map.of("base", base, "keyVersion", resolvedKey == null ? "" : resolvedKey,
            "proxy", ""));
        Map<String, Object> hashInput = new LinkedHashMap<>();
        hashInput.put("serviceId", id); hashInput.put("profile", profile); hashInput.put("model", model); hashInput.put("main", main);
        hashInput.put("connection", fingerprint); hashInput.put("defaults", defaults); hashInput.put("capabilities", caps); hashInput.put("pricing", entry.getPricing());
        hashInput.put("timeoutMillis", entry.getTimeoutMillis()); hashInput.put("label", label(id, entry));
        String snapshotId = "imagecfg_" + IdempotencyReceiptStore.requestHash(hashInput).substring(0, 32);
        return new Resolved(new ImageServiceSnapshot(snapshotId, id, label(id, entry), profile, model, main, fingerprint, defaults, caps,
            Math.max(1000, Math.min(30 * 60_000, entry.getTimeoutMillis())), entry.getPricing()), new Connection(base, resolvedKey, null));
    }
    private Map<String, ImageServiceConfig> configured() { return config.getImageServices() == null ? Map.of() : config.getImageServices(); }
    private String defaultId() {
        if (!blank(config.getDefaultImageService())) return config.getDefaultImageService();
        return configured().entrySet().stream().filter(entry -> entry.getValue() != null && entry.getValue().isEnabled()).map(Map.Entry::getKey).findFirst().orElse(null);
    }
    private static ServiceView unavailable(String id, ImageServiceConfig entry, State state, String code, String message) {
        return new ServiceView(id, label(id, entry), entry == null ? null : entry.getProfile(), entry == null ? null : entry.getModel(),
            entry == null ? null : entry.getMainModel(), state, false, code, message, 0, null, entry == null ? null : entry.getDefaults());
    }
    private static String label(String id, ImageServiceConfig entry) {
        return entry == null ? id : !blank(entry.getLabel()) ? entry.getLabel() : !blank(entry.getModel()) ? entry.getModel() : "Image model";
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static String resolveEnv(String value) { String resolved = OutboundProxyResolver.resolveEnv(value); return resolved == null ? null : resolved.trim(); }
    private record Resolved(ImageServiceSnapshot snapshot, Connection connection) {}
    private record Health(boolean success, long until, String code, String message) {}
    public record Catalog(State state, String defaultServiceId, List<ServiceView> services) {}
    public record ServiceView(String serviceId, String label, String profile, String model, String mainModel, State state,
        boolean canSubmit, String code, String message, long retryAt, ImageCapabilities capabilities, ImageSpec.Parameters defaults) {}
    public record Connection(String baseUrl, @JsonIgnore String apiKey, String proxy) {
        @Override public String toString() { return "ImageConnection[redacted]"; }
    }
}
