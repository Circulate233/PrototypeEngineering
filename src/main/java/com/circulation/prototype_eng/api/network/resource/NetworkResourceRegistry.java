package com.circulation.prototype_eng.api.network.resource;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Canonical computing metadata, registered on the mod loading thread and frozen before definitions
 * are loaded. Runtime readers see one immutable publication; registration never continues in a world.
 */
public final class NetworkResourceRegistry {
    public static final NetworkResourceRegistry INSTANCE = new NetworkResourceRegistry();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, NetworkResourceType> registrations = new Object2ObjectLinkedOpenHashMap<>();
    @Nullable
    private volatile Frozen frozen;

    public NetworkResourceRegistry() {
        register(NetworkResources.THREAD);
        register(NetworkResources.COMPUTE);
        register(NetworkResources.QUBIT);
    }

    /**
     * Returns the immutable declaration itself; equal metadata never requires a second identity object.
     */
    public NetworkResourceType register(NetworkResourceType type) {
        if (frozen != null) {
            throw new IllegalStateException("Computing resource registration has ended");
        }
        Objects.requireNonNull(type);
        if (registrations.putIfAbsent(type.id(), type) != null) {
            throw new IllegalArgumentException("Duplicate computing resource: " + type.id());
        }
        return type;
    }

    public void freeze() {
        if (frozen != null) {
            throw new IllegalStateException("Computing resource registry already frozen");
        }
        var types = new Object2ObjectLinkedOpenHashMap<>(registrations);
        frozen = new Frozen(types, ObjectLists.unmodifiable(new ObjectArrayList<>(types.values())));
        registrations.clear();
    }

    public boolean isFrozen() {
        return frozen != null;
    }

    /**
     * Resolve ids while loading definitions or stored numeric vectors; unknown ids are errors.
     */
    public NetworkResourceType require(ResourceLocation id) {
        var type = requireFrozen().byId.get(Objects.requireNonNull(id));
        if (type == null) {
            throw new IllegalArgumentException("Unknown computing resource: " + id);
        }
        return type;
    }

    /**
     * All metadata fields must match; an equal record from a loader is accepted and canonicalized.
     */
    public NetworkResourceType canonical(NetworkResourceType type) {
        Objects.requireNonNull(type);
        var registered = require(type.id());
        if (!registered.equals(type)) {
            throw new IllegalArgumentException("Computing resource metadata differs from registration: " + type.id());
        }
        return registered;
    }

    /**
     * Immutable declarations in registration order, including logical concurrency resources.
     */
    public ObjectList<NetworkResourceType> types() {
        return requireFrozen().ordered;
    }

    private Frozen requireFrozen() {
        var current = frozen;
        if (current == null) {
            throw new IllegalStateException("Computing resource registry is not ready");
        }
        return current;
    }

    private record Frozen(Object2ObjectLinkedOpenHashMap<ResourceLocation, NetworkResourceType> byId,
                          ObjectList<NetworkResourceType> ordered) {
    }
}
