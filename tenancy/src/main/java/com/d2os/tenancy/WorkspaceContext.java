package com.d2os.tenancy;

import java.util.UUID;

/**
 * Per-request holder of the active workspace id (Principle IV).
 *
 * <p>The full servlet filter that populates this from the authenticated principal and also issues
 * {@code SET app.workspace_id} on the JDBC session (so Postgres RLS engages) is task T010. This
 * holder is the minimal enabler so bounded contexts can stamp {@code workspace_id} on writes now.
 */
public final class WorkspaceContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private WorkspaceContext() {}

    public static void set(UUID workspaceId) {
        CURRENT.set(workspaceId);
    }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("No workspace bound to the current request (T010 not engaged?)");
        }
        return id;
    }

    /** @return the bound workspace id, or null if none is bound (e.g. a startup-time job). */
    public static UUID currentOrNull() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
