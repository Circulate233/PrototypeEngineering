package com.circulation.prototype_eng.api.research;

import java.util.Objects;

/**
 * Startup configuration for the existing authoritative research owner, shared by Java and scripts.
 */
public final class ResearchServerConfiguration {
    public static final ResearchServerConfiguration INSTANCE = new ResearchServerConfiguration();
    private ResearchPermissionPolicy permissions = ResearchPermissionPolicy.DEFAULT;
    private boolean configured;
    private boolean frozen;

    public synchronized void permissions(ResearchPermissionPolicy policy) {
        if (frozen) throw new IllegalStateException("Research server configuration has ended");
        if (configured) throw new IllegalArgumentException("Research server permissions are already configured");
        permissions = Objects.requireNonNull(policy);
        configured = true;
    }

    public synchronized void freeze() {
        if (frozen) throw new IllegalStateException("Research server configuration is already frozen");
        frozen = true;
    }

    public synchronized ResearchPermissionPolicy permissions() {
        if (!frozen) throw new IllegalStateException("Research server configuration is not ready");
        return permissions;
    }
}
