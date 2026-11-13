package com.circulation.prototype_eng.api.network.resource;

import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * Metadata for a game-level computing budget, independent of item/fluid identity and JVM threads.
 * Supply quantities belong to providers; resource types impose no per-item or per-device limit.
 */
public record NetworkResourceType(ResourceLocation id, boolean logicalConcurrency, boolean internal) {
    public NetworkResourceType(ResourceLocation id, boolean logicalConcurrency) {
        this(id, logicalConcurrency, false);
    }

    public NetworkResourceType {
        Objects.requireNonNull(id, "Computing resource id");
        if (id.getNamespace().isBlank() || id.getPath().isBlank()) {
            throw new IllegalArgumentException("Computing resource id must not be blank");
        }
    }
}
