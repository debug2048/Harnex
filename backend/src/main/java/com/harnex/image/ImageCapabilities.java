package com.harnex.image;

import java.util.List;

/** Exact configured/implemented operation surface. Endpoint and model names alone never establish availability. */
public record ImageCapabilities(boolean generate, boolean edit, boolean mask, int maxCount, int maxSources,
    long maxSourceBytes, List<String> sizes, List<String> qualities, List<String> formats, List<String> backgrounds,
    boolean previews, boolean query, boolean upstreamCancel) {
    public ImageCapabilities {
        sizes = List.copyOf(sizes); qualities = List.copyOf(qualities); formats = List.copyOf(formats); backgrounds = List.copyOf(backgrounds);
    }
}
