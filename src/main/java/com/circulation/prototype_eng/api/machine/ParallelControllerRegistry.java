package com.circulation.prototype_eng.api.machine;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * Startup-only definitions. Register from preinit scripts or ParallelControllerRegistrationEvent.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class ParallelControllerRegistry {
    public static final ParallelControllerRegistry INSTANCE = new ParallelControllerRegistry();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, ParallelControllerType> types = new Object2ObjectLinkedOpenHashMap<>();
    private boolean frozen;

    private ParallelControllerRegistry() {
    }

    public synchronized void register(ParallelControllerType type) {
        if (frozen) throw new IllegalStateException("Parallel controller registration is closed");
        if (types.putIfAbsent(type.id(), type) != null)
            throw new IllegalArgumentException("Duplicate parallel controller: " + type.id());
    }

    public synchronized List<ParallelControllerType> freeze() {
        if (frozen) throw new IllegalStateException("Parallel controller registration already closed");
        frozen = true;
        return new ObjectArrayList<>(types.values());
    }

    public synchronized ParallelControllerType require(ResourceLocation id) {
        var type = types.get(id);
        if (type == null) throw new IllegalArgumentException("Unknown parallel controller: " + id);
        return type;
    }
}
