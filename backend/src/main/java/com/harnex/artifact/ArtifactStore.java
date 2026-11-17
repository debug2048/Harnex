package com.harnex.artifact;

import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Binary content ownership and lifetime are independent of conversation and provider storage. */
public interface ArtifactStore {
    ArtifactRecord save(String scopeId, SaveRequest metadata, InputStream content);
    Optional<ArtifactRecord> find(String scopeId, String artifactId);
    ContentHandle open(String scopeId, String artifactId);

    record SaveRequest(String artifactId, String name, String mime, ArtifactRecord.Creator creator,
        List<String> sourceRefs, boolean requireImage) {
        public SaveRequest { sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs); }
    }
    interface ContentHandle extends AutoCloseable {
        ArtifactRecord metadata();
        InputStream stream();
        @Override void close() throws IOException;
    }
}
