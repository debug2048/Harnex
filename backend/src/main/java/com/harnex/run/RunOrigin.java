package com.harnex.run;

import java.util.LinkedHashMap;
import java.util.Map;

/** Entry attribution. Client references distinguish interactive owners; tool and filesystem permissions remain separate. */
public record RunOrigin(Kind kind, String reference) {
    public enum Kind { CLIENT, GATEWAY, AUTOMATION }

    public RunOrigin {
        if (kind == null) throw new IllegalArgumentException("origin kind required");
    }

    public static RunOrigin client(String reference) { return new RunOrigin(Kind.CLIENT, reference); }

    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", kind.name());
        if (reference != null) view.put("reference", reference);
        return view;
    }
}
