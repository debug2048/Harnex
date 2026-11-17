package com.harnex.artifact;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Public asset metadata has no local path, upstream URL or embedded binary. */
@Schema(requiredProperties = {"schemaVersion", "artifactId", "scopeId", "name", "mime", "bytes", "checksum", "creator", "sourceRefs", "createdAt", "availability"})
public record ArtifactRecord(int schemaVersion, String artifactId, String scopeId, String name, String mime,
    long bytes, Integer width, Integer height, String checksum, Creator creator, List<String> sourceRefs,
    long createdAt, Availability availability) {
    public ArtifactRecord { sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs); }
    public record Creator(String kind, String runId, String operationId) {}
    public enum Availability { LOCAL, UNAVAILABLE }
}
