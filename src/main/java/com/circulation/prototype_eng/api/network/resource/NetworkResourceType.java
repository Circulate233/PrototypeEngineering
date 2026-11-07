package com.circulation.prototype_eng.api.network.resource;

import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * Metadata for a game-level computing budget, independent of item/fluid identity and JVM threads.
 * maxPerTickValue limits a fixed computer component's per-item supply and a research stage's grant limit;
 * zero records no intrinsic cap. It does not limit a stack, device or aggregated team vector.
 */
public record NetworkResourceType(ResourceLocation id, boolean logicalConcurrency, long maxPerTickValue) {
    public NetworkResourceType {
        Objects.requireNonNull(id, "Computing resource id");
        if (id.getNamespace().isBlank() || id.getPath().isBlank()) {
            throw new IllegalArgumentException("Computing resource id must not be blank");
        }
        if (maxPerTickValue < 0L) {
            throw new IllegalArgumentException("Computing resource contribution limit must be non-negative");
        }
    }
}
