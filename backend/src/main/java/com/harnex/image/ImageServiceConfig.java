package com.harnex.image;

import java.util.List;
import lombok.Data;
import com.harnex.execution.ImageSpec;

/** Image services have their own deployment configuration and never enter the text-model picker. */
@Data
public class ImageServiceConfig {
    private boolean enabled = true;
    private String label;
    private String profile = "openai-images";
    private String baseUrl;
    private String apiKey;
    /** Accepted for older configuration files; image requests now use the global network policy. */
    private String proxy;
    private String model;
    private String mainModel;
    private ImageSpec.Parameters defaults = new ImageSpec.Parameters(1, "auto", "auto", "png", "auto");
    private Capabilities capabilities = new Capabilities();
    private long timeoutMillis = 300_000;
    private Pricing pricing;
    @Data public static class Capabilities {
        private boolean generate = true;
        private boolean edit = true;
        private boolean mask = true;
        private int maxCount = 10;
        private int maxSources = 16;
        private long maxSourceBytes = 32L * 1024 * 1024;
        /** Empty means protocol-valid dimensions; an explicit list remains an operator restriction. */
        private List<String> sizes = List.of();
        private List<String> qualities = List.of("auto", "low", "medium", "high", "xhigh", "max");
        private List<String> formats = List.of("png", "jpeg", "webp");
        private List<String> backgrounds = List.of("auto", "opaque", "transparent");
    }
    public record Pricing(String version, String currency, Double perImage, Double textInputPerMillion,
        Double imageInputPerMillion, Double imageOutputPerMillion) {}
}
