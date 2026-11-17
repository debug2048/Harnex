package com.harnex.contract.runtime;

import java.util.Map;

/** Profile-owned metadata appended to the common runtime catalogue. */
public interface RuntimeMetaExtension {
    Map<String, Object> diagnostics();
    Map<String, Object> statusline();
}
