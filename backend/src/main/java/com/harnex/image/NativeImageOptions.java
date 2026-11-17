package com.harnex.image;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import com.harnex.execution.ImageSpec;
import com.harnex.net.ApiEndpoints;

/** Native wire limits. Operator capability declarations can narrow these, never enable an unimplemented feature. */
final class NativeImageOptions {
    static final String GEMINI = "gemini-images";
    static final String QWEN = "dashscope-images";
    static final String QWEN_PATH = "/services/aigc/multimodal-generation/generation";
    record GeminiSize(String aspectRatio, String resolution) {}
    private static final Map<String, GeminiSize> GEMINI_SIZES = sizes();

    static boolean nativeProfile(String profile) { return GEMINI.equals(profile) || QWEN.equals(profile); }
    static String defaultBase(String profile) {
        return switch (profile) {
            case GEMINI -> "https://generativelanguage.googleapis.com";
            case QWEN -> null; // Workspace and region are account-specific; require the actual DashScope endpoint.
            default -> "https://api.openai.com/v1";
        };
    }
    static String endpoint(String profile, String base, String endpoint) {
        if (!nativeProfile(profile)) return ApiEndpoints.resolveEndpoint(base, endpoint);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith(endpoint)) return base;
        if (GEMINI.equals(profile)) {
            if (base.contains(":generateContent")) throw new ImageServiceException("image_configuration_invalid", "The Gemini endpoint model does not match the configured model");
            return (base.matches(".*/v\\d+(?:beta\\d*)?$") ? base : base + "/v1") + endpoint;
        }
        if (URI.create(base).getPath().contains("/compatible-mode")) {
            throw new ImageServiceException("image_configuration_invalid", "Qwen native images need the DashScope /api/v1 endpoint, not compatible-mode");
        }
        return (base.endsWith("/api/v1") ? base : base + "/api/v1") + endpoint;
    }
    static String geminiModel(String model) {
        String id = model.startsWith("models/") ? model.substring(7) : model;
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new ImageServiceException("image_configuration_invalid", "Gemini images require a model ID, not a URL or resource path");
        }
        return id;
    }
    static List<String> geminiSizes() { return Stream.concat(Stream.of("auto"), GEMINI_SIZES.keySet().stream()).toList(); }
    static GeminiSize geminiSize(String size) { return GEMINI_SIZES.get(size); }
    static List<String> restrict(List<String> configured, List<String> supported) {
        return supported.stream().filter(configured::contains).toList();
    }
    static void validate(String profile, ImageSpec.Parameters parameters) {
        if (GEMINI.equals(profile) && !"auto".equals(parameters.size()) && geminiSize(parameters.size()) == null) {
            throw new ImageServiceException("image_parameters_unsupported", "Choose a supported Gemini image size");
        }
        if (QWEN.equals(profile) && !"auto".equals(parameters.size())) {
            String[] parts = parameters.size().split("x");
            long width = Long.parseLong(parts[0]), height = Long.parseLong(parts[1]);
            if (width * height < 512L * 512 || width * height > 2048L * 2048
                || width > height * 8 || height > width * 8) {
                throw new ImageServiceException("image_parameters_unsupported", "Qwen images require 512x512 to 2048x2048 pixels in total and an aspect ratio within 1:8 to 8:1");
            }
        }
    }
    private static Map<String, GeminiSize> sizes() {
        Map<String, GeminiSize> sizes = new LinkedHashMap<>();
        // Shared 1K/2K/4K presets in current Nano Banana and Pro image APIs. No arbitrary resizing promise.
        String[][] presets = {{"1:1", "1024x1024"}, {"2:3", "848x1264"}, {"3:2", "1264x848"},
            {"3:4", "896x1200"}, {"4:3", "1200x896"}, {"4:5", "928x1152"}, {"5:4", "1152x928"},
            {"9:16", "768x1376"}, {"16:9", "1376x768"}, {"21:9", "1584x672"}};
        for (String[] preset : presets) {
            String[] dimensions = preset[1].split("x");
            for (int scale : new int[] {1, 2, 4}) {
                sizes.put(Integer.parseInt(dimensions[0]) * scale + "x" + Integer.parseInt(dimensions[1]) * scale,
                    new GeminiSize(preset[0], scale + "K"));
            }
        }
        return Collections.unmodifiableMap(sizes);
    }
}
