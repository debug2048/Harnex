package com.harnex.execution;

import com.harnex.contract.StrictRequest;

/** A missing target is resolved from server configuration before accepting a turn. */
public record TextTarget(String provider, String model, String reasoningEffort) implements StrictRequest {}
