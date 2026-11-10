package com.circulation.prototype_eng.api.structure.json;

import com.circulation.prototype_eng.api.structure.StructurePredicate;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.Set;

/**
 * Startup factory registration only; shape publication remains owned by MachineTypeRegistry.
 */
public final class StructurePredicateRegistry {
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, Registration> factories = new Object2ObjectLinkedOpenHashMap<>();
    private boolean frozen;
    private boolean mutating;

    public synchronized void register(ResourceLocation id, Set<String> parameterFields, StructurePredicateFactory factory) {
        if (frozen || mutating) throw new IllegalStateException("Predicate registrations are closed or reentrant");
        mutating = true;
        try {
            var registration = new Registration(Set.copyOf(parameterFields), Objects.requireNonNull(factory));
            if (factories.putIfAbsent(Objects.requireNonNull(id), registration) != null)
                throw new IllegalArgumentException("Duplicate structure predicate ID: " + id);
        } finally {
            mutating = false;
        }
    }

    public synchronized void freeze() {
        if (frozen || mutating) throw new IllegalStateException("Predicate registrations are closed or reentrant");
        frozen = true;
    }

    public synchronized StructurePredicate create(StructureJsonParameters identifier, StructureJsonParameters parameters, StructureBlockResolver blocks) {
        if (!frozen || mutating) throw new IllegalStateException("Predicate resolution is not available or reentrant");
        var id = Objects.requireNonNull(identifier).id();
        var factory = factories.get(id);
        if (factory == null) throw identifier.error("Unknown structure predicate/validator: " + id);
        mutating = true;
        try {
            parameters.fields(factory.fields.toArray(String[]::new));
            return Objects.requireNonNull(factory.factory.create(parameters, Objects.requireNonNull(blocks)), "Predicate factory returned null");
        } catch (StructureJsonException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw parameters.error("Cannot resolve predicate/validator " + id, failure);
        } finally {
            mutating = false;
        }
    }

    private record Registration(Set<String> fields, StructurePredicateFactory factory) {
    }
}
