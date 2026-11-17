package com.harnex.image;

import com.harnex.execution.ImageSpec;

/** Frozen deployment/parameter contract. Contains neither credentials nor temporary provider URLs. */
public record ImageServiceSnapshot(String snapshotId, String serviceId, String label, String profile, String model,
    String mainModel, String connectionFingerprint, ImageSpec.Parameters defaults, ImageCapabilities capabilities,
    long timeoutMillis, ImageServiceConfig.Pricing pricing) {}
