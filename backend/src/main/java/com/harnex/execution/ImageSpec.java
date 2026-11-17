package com.harnex.execution;

import java.util.List;
import com.harnex.contract.StrictRequest;

/** Portable, bounded image parameters; vendor payloads are assembled only by an adapter. */
public record ImageSpec(String serviceId, Parameters parameters, List<String> sourceArtifactIds,
    String maskArtifactId) implements StrictRequest {
    public ImageSpec {
        parameters = parameters == null ? new Parameters(null, null, null, null, null) : parameters;
        sourceArtifactIds = sourceArtifactIds == null ? List.of() : List.copyOf(sourceArtifactIds);
        if (sourceArtifactIds.size() > 16) throw new IllegalArgumentException("too many image sources");
        if (maskArtifactId != null && sourceArtifactIds.isEmpty()) {
            throw new IllegalArgumentException("a mask requires a source image");
        }
    }

    public record Parameters(Integer count, String size, String quality, String format, String background)
        implements StrictRequest {
        public static final String SIZE_PATTERN = "^(auto|[1-9][0-9]{0,4}x[1-9][0-9]{0,4})$";
        public Parameters {
            if (count != null && (count < 1 || count > 16)) {
                throw new IllegalArgumentException("image count must be 1..16");
            }
            for (String value : new String[] { size, quality, format, background }) {
                if (value != null && (value.isBlank() || value.length() > 64)) {
                    throw new IllegalArgumentException("invalid image parameter");
                }
            }
        }
    }
}
