package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Startup declarations close before controller generation; structures bind in a separate complete
 * publication after block resolution. Both stages retain the exact canonical MachineType objects.
 * Controller runtime publication additionally requires an assembly for every declaration; neither
 * publication grants PN permissions or planned readiness.
 */
public final class MachineTypeRegistry {
    public static final MachineTypeRegistry INSTANCE = new MachineTypeRegistry();

    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineType> declarations = new Object2ObjectLinkedOpenHashMap<>();
    private List<MachineType> types = ObjectLists.emptyList();
    @Nullable private Map<ResourceLocation, MachineStructure> structures;
    @Nullable private Map<ResourceLocation, MachineAssembly> assemblies;
    private boolean frozen;
    private boolean mutating;

    public synchronized void register(MachineType type) { registerAll(List.of(Objects.requireNonNull(type))); }

    /** Validate the entire batch before changing pending declarations. Rejected batches add nothing. */
    public synchronized void registerAll(Collection<? extends MachineType> batch) {
        beginMutation();
        try {
            if (frozen) { throw new IllegalStateException("Machine declarations are already frozen"); }
            Objects.requireNonNull(batch, "machine batch");
            var additions = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineType>();
            for (var type : batch) {
                Objects.requireNonNull(type, "machine type");
                if (declarations.containsKey(type.id()) || additions.putIfAbsent(type.id(), type) != null) {
                    throw new IllegalArgumentException("Duplicate machine type ID: " + type.id());
                }
            }
            declarations.putAll(additions);
        } finally { mutating = false; }
    }

    /** Close once, publishing the complete deterministic declaration order. */
    public synchronized void freeze() {
        beginMutation();
        try {
            if (frozen) { throw new IllegalStateException("Machine declarations are already frozen"); }
            types = ObjectLists.unmodifiable(new ObjectArrayList<>(declarations.values()));
            frozen = true;
        } finally { mutating = false; }
    }

    public synchronized List<MachineType> types() { requireFrozen(); return types; }
    @Nullable public synchronized MachineType get(ResourceLocation id) { requireFrozen(); return declarations.get(Objects.requireNonNull(id)); }

    /** Validate every supplied key/ID and every declared reference before publishing any binding. */
    public synchronized void resolveStructures(Map<ResourceLocation, ? extends MachineStructure> definitions) {
        beginMutation();
        try {
            requireFrozen();
            if (structures != null) { throw new IllegalStateException("Machine structures are already resolved"); }
            structures = resolveStructureReferences(validateStructures(definitions));
        } finally { mutating = false; }
    }

    /** Validate the complete controller runtime before publishing either structures or assemblies. */
    public synchronized void resolveRuntime(Map<ResourceLocation, ? extends MachineStructure> definitions,
                                            Collection<? extends MachineAssembly> assemblyDefinitions) {
        beginMutation();
        try {
            requireFrozen();
            if (structures != null) { throw new IllegalStateException("Machine structures are already resolved"); }
            var resolvedStructures = resolveStructureReferences(validateStructures(definitions));
            Objects.requireNonNull(assemblyDefinitions, "machine assemblies");
            var checked = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineAssembly>();
            for (var assembly : assemblyDefinitions) {
                Objects.requireNonNull(assembly, "machine assembly");
                var type = assembly.type();
                requireCanonical(type);
                if (checked.putIfAbsent(type.id(), assembly) != null) {
                    throw new IllegalArgumentException("Duplicate machine assembly ID: " + type.id());
                }
            }
            var resolvedAssemblies = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineAssembly>();
            for (var type : types) {
                var assembly = checked.get(type.id());
                if (assembly == null) { throw new IllegalArgumentException("Missing assembly for machine " + type.id()); }
                resolvedAssemblies.put(type.id(), assembly);
            }
            var publishedAssemblies = Object2ObjectMaps.unmodifiable(resolvedAssemblies);
            structures = resolvedStructures;
            assemblies = publishedAssemblies;
        } finally { mutating = false; }
    }

    private Map<ResourceLocation, MachineStructure> resolveStructureReferences(Map<ResourceLocation, MachineStructure> checked) {
        var resolved = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineStructure>();
        for (var type : types) {
            var definition = checked.get(type.structureId());
            if (definition == null) { throw new IllegalArgumentException("Unknown structure " + type.structureId() + " for machine " + type.id()); }
            resolved.put(type.id(), definition);
        }
        return Object2ObjectMaps.unmodifiable(resolved);
    }

    private static Map<ResourceLocation, MachineStructure> validateStructures(Map<ResourceLocation, ? extends MachineStructure> definitions) {
        Objects.requireNonNull(definitions, "structure definitions");
        var checked = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineStructure>();
        for (var entry : definitions.entrySet()) {
            var key = Objects.requireNonNull(entry.getKey(), "structure key");
            var definition = Objects.requireNonNull(entry.getValue(), "structure definition");
            if (!key.equals(Objects.requireNonNull(definition.id(), "structure ID"))) {
                throw new IllegalArgumentException("Structure key does not match its ID: " + key);
            }
            if (checked.putIfAbsent(key, definition) != null) { throw new IllegalArgumentException("Duplicate structure ID: " + key); }
        }
        return checked;
    }

    public synchronized MachineStructure structure(MachineType type) {
        requireFrozen();
        requireCanonical(type);
        if (structures == null) { throw new IllegalStateException("Machine structures have not been resolved"); }
        return Objects.requireNonNull(structures.get(type.id()));
    }

    public synchronized MachineAssembly assembly(MachineType type) {
        requireFrozen();
        requireCanonical(type);
        if (assemblies == null) { throw new IllegalStateException("Machine runtime has not been resolved"); }
        return Objects.requireNonNull(assemblies.get(type.id()));
    }

    private void requireCanonical(MachineType type) {
        Objects.requireNonNull(type, "machine type");
        if (declarations.get(type.id()) != type) { throw new IllegalArgumentException("Machine type is not the canonical declaration: " + type.id()); }
    }

    private void requireFrozen() {
        if (!frozen) { throw new IllegalStateException("Machine declarations have not been frozen"); }
    }

    private void beginMutation() {
        if (mutating) { throw new IllegalStateException("Machine registry mutation is reentrant"); }
        mutating = true;
    }
}
