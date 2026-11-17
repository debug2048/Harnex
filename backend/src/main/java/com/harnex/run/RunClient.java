package com.harnex.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client attribution within an authenticated Runtime, independent of the calling thread or transport. */
public record RunClient(String id) {
    /** Trusted in-process callers have no interactive client owner. */
    public static final RunClient INTERNAL = new RunClient(null);

    public RunOrigin origin() { return RunOrigin.client(id); }

    public boolean canControl(Object origin) {
        if (id == null) return true;
        String kind, reference;
        if (origin instanceof RunOrigin value) {
            kind = value.kind().name(); reference = value.reference();
        } else if (origin instanceof Map<?, ?> value) {
            kind = String.valueOf(value.get("kind"));
            reference = value.get("reference") instanceof String text ? text : null;
        } else return true;
        return !"CLIENT".equals(kind) || id.equals(reference == null ? "anonymous" : reference);
    }

    public Map<String, Object> publicView(Map<String, Object> facts) {
        if (facts == null || canControl(facts.get("origin"))) return facts;
        var view = new LinkedHashMap<>(facts);
        view.put("allowedActions", hostCanCancel(facts) ? List.of("cancel") : List.of());
        view.put("controlState", "EXTERNAL");
        view.put("executing", false);
        return view;
    }

    /** The user's host may cancel an attached editor task, but continuing it belongs to that editor. */
    public boolean hostCanCancel(Map<String, Object> facts) {
        if (id == null || !id.startsWith("workbench-") || Boolean.TRUE.equals(facts.get("terminal"))
            || "EXTERNAL".equals(facts.get("controlState"))) return false;
        Object origin = facts.get("origin");
        String owner = null;
        if (origin instanceof RunOrigin value && value.kind() == RunOrigin.Kind.CLIENT) owner = value.reference();
        else if (origin instanceof Map<?, ?> value && "CLIENT".equals(value.get("kind"))
            && value.get("reference") instanceof String reference) owner = reference;
        return owner != null && owner.startsWith("vscode-")
            && facts.get("allowedActions") instanceof List<?> actions && actions.contains("cancel");
    }
}
