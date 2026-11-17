package com.harnex.core;

/** Additional authority ceiling. Thread permissions and path/command checks still apply. */
public record PermissionContext(boolean allowMutations) {
    public static final PermissionContext UNRESTRICTED = new PermissionContext(true);
    public static final PermissionContext READ_ONLY = new PermissionContext(false);

    public PermissionContext intersect(PermissionContext other) {
        return new PermissionContext(allowMutations && other != null && other.allowMutations);
    }
}
