package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.List;
import java.util.Map;

/**
 * Published collections are read-only by contract; consumers must not modify them.
 * Collects post-init structure and assembly definitions without publishing them. The caller resolves
 * both snapshots together after event delivery; every machine must use its frozen canonical type.
 * Definition callbacks must not reenter this event's registration methods.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class MachineRuntimeRegistrationEvent extends Event {
    private final MachineTypeRegistry registry;
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, StructureDefinition> structures = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineAssembly> assemblies = new Object2ObjectLinkedOpenHashMap<>();
    private Map<ResourceLocation, MachineStructure> shapeView;
    private StructureCatalog structureView;
    private List<MachineAssembly> assemblyView;

    public MachineRuntimeRegistrationEvent(MachineTypeRegistry registry) {
        this.registry = registry;
        registry.types();
    }

    public MachineTypeRegistry registry() {
        return registry;
    }

    public synchronized void structure(MachineStructure definition) {
        addDefinition(new StructureDefinition(definition));
    }

    /**
     * Java definitions may supply the same independent presentation data as JSON definitions.
     */
    public synchronized void definition(StructureDefinition definition) {
        addDefinition(definition);
    }

    private void addDefinition(StructureDefinition definition) {
        var id = definition.id();
        if (structures.putIfAbsent(id, definition) != null)
            throw new IllegalArgumentException("Duplicate structure ID: " + id);
        shapeView = null;
        structureView = null;
    }

    public synchronized void assembly(MachineAssembly definition) {
        var type = definition.type();
        if (registry.get(type.id()) != type) {
            throw new IllegalArgumentException("Machine type is not the canonical declaration: " + type.id());
        }
        if (assemblies.putIfAbsent(type.id(), definition) != null) {
            throw new IllegalArgumentException("Duplicate machine assembly ID: " + type.id());
        }
        assemblyView = null;
    }

    /**
     * Read-only snapshot; later registrations publish a replacement.
     */
    public synchronized Map<ResourceLocation, MachineStructure> structures() {
        if (shapeView == null) {
            var shapes = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineStructure>(structures.size());
            structures.forEach((id, definition) -> shapes.put(id, definition.shape()));
            shapeView = shapes;
        }
        return shapeView;
    }

    public synchronized StructureCatalog structureDefinitions() {
        if (structureView == null) structureView = new StructureCatalog(structures.values());
        return structureView;
    }

    /**
     * Read-only snapshot in registration order.
     */
    public synchronized List<MachineAssembly> assemblies() {
        if (assemblyView == null) assemblyView = new ObjectArrayList<>(assemblies.values());
        return assemblyView;
    }

}
