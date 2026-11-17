package com.harnex.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Small persisted stamp used to avoid returning an unchanged file slice repeatedly across context compactions. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileReadCacheEntry {
    private long contextGeneration;
    private String fingerprint;
}
