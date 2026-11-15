package com.circulation.prototype_eng.api.structure.json;

import com.circulation.prototype_eng.api.structure.StructurePredicate;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.util.ResourceLocation;

import java.util.Set;

/**
 * Startup factory registration only; shape publication remains owned by MachineTypeRegistry.
 */
public final class StructurePredicateRegistry {
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, Registration> factories = new Object2ObjectLinkedOpenHashMap<>();
    private boolean frozen;

    public synchronized void register(ResourceLocation id, Set<String> parameterFields, StructurePredicateFactory factory) {
        if (frozen) throw new IllegalStateException("Predicate registrations are closed");
        var registration = new Registration(parameterFields.toArray(String[]::new), factory);
        if (factories.putIfAbsent(id, registration) != null)
            throw new IllegalArgumentException("Duplicate structure predicate ID: " + id);
    }

    public synchronized void freeze() {
        if (frozen) throw new IllegalStateException("Predicate registrations are closed");
        frozen = true;
    }

    public synchronized StructurePredicate create(StructureJsonParameters identifier, StructureJsonParameters parameters, StructureBlockResolver blocks) {
        if (!frozen) throw new IllegalStateException("Predicate resolution is not available");
        var id = identifier.id();
        var factory = factories.get(id);
        if (factory == null) throw identifier.error("Unknown structure predicate/validator: " + id);
        try {
            parameters.fields(factory.fields);
            return factory.factory.create(parameters, blocks);
        } catch (StructureJsonException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw parameters.error("Cannot resolve predicate/validator " + id, failure);
        }
    }

    private record Registration(String[] fields, StructurePredicateFactory factory) {
    }
}
