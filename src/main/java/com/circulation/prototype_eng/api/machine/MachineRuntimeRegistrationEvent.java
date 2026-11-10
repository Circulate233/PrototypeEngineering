package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects post-init structure and assembly definitions without publishing them. The caller resolves
 * both snapshots together after event delivery; every machine must use its frozen canonical type.
 */
public final class MachineRuntimeRegistrationEvent extends Event {
    private final MachineTypeRegistry registry;
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, StructureDefinition> structures = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineAssembly> assemblies = new Object2ObjectLinkedOpenHashMap<>();
    private boolean mutating;

    public MachineRuntimeRegistrationEvent(MachineTypeRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        registry.types();
    }

    public MachineTypeRegistry registry() {
        return registry;
    }

    public synchronized void structure(MachineStructure definition) {
        beginMutation();
        try {
            addDefinition(new StructureDefinition(Objects.requireNonNull(definition, "structure definition")));
        } finally {
            mutating = false;
        }
    }

    /**
     * Java definitions may supply the same independent presentation data as JSON definitions.
     */
    public synchronized void definition(StructureDefinition definition) {
        beginMutation();
        try {
            addDefinition(Objects.requireNonNull(definition));
        } finally {
            mutating = false;
        }
    }

    private void addDefinition(StructureDefinition definition) {
        var id = Objects.requireNonNull(definition.id(), "structure ID");
        if (structures.putIfAbsent(id, definition) != null)
            throw new IllegalArgumentException("Duplicate structure ID: " + id);
    }

    public synchronized void assembly(MachineAssembly definition) {
        beginMutation();
        try {
            Objects.requireNonNull(definition, "machine assembly");
            var type = definition.type();
            if (registry.get(type.id()) != type) {
                throw new IllegalArgumentException("Machine type is not the canonical declaration: " + type.id());
            }
            if (assemblies.putIfAbsent(type.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate machine assembly ID: " + type.id());
            }
        } finally {
            mutating = false;
        }
    }

    /**
     * Stable, immutable snapshot; later registrations cannot change an already returned map.
     */
    public synchronized Map<ResourceLocation, MachineStructure> structures() {
        var shapes = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineStructure>();
        structures.forEach((id, definition) -> shapes.put(id, definition.shape()));
        return Object2ObjectMaps.unmodifiable(shapes);
    }

    public synchronized StructureCatalog structureDefinitions() {
        beginMutation();
        try {
            return new StructureCatalog(structures.values());
        } finally {
            mutating = false;
        }
    }

    /**
     * Stable, immutable snapshot in registration order.
     */
    public synchronized List<MachineAssembly> assemblies() {
        return ObjectLists.unmodifiable(new ObjectArrayList<>(assemblies.values()));
    }

    private void beginMutation() {
        if (mutating) {
            throw new IllegalStateException("Machine runtime registration is reentrant");
        }
        mutating = true;
    }
}
