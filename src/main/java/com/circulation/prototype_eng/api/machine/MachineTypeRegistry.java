package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Published collections are read-only by contract; consumers must not modify them.
 * Mutations must not be reentered from supplied collections or structure-definition callbacks.
 * Startup declarations close before controller generation; structures bind in a separate complete
 * publication after block resolution. Both stages retain the exact canonical MachineType objects.
 * Controller runtime publication additionally requires an assembly for every declaration; neither
 * publication grants PN permissions or planned readiness.
 */
public final class MachineTypeRegistry {
    public static final MachineTypeRegistry INSTANCE = new MachineTypeRegistry();

    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineType> declarations = new Object2ObjectLinkedOpenHashMap<>();
    private List<MachineType> types = ObjectLists.emptyList();
    @Nullable
    private ResolvedRuntime runtime;
    private boolean frozen;

    private static Map<ResourceLocation, MachineStructure> validateStructures(Map<ResourceLocation, ? extends MachineStructure> definitions) {
        Objects.requireNonNull(definitions, "structure definitions");
        var checked = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineStructure>(definitions.size());
        for (var entry : definitions.entrySet()) {
            var key = Objects.requireNonNull(entry.getKey(), "structure key");
            var definition = Objects.requireNonNull(entry.getValue(), "structure definition");
            if (!key.equals(Objects.requireNonNull(definition.id(), "structure ID"))) {
                throw new IllegalArgumentException("Structure key does not match its ID: " + key);
            }
            if (checked.putIfAbsent(key, definition) != null) {
                throw new IllegalArgumentException("Duplicate structure ID: " + key);
            }
        }
        return checked;
    }

    public synchronized void register(MachineType type) {
        registerAll(List.of(Objects.requireNonNull(type)));
    }

    /**
     * Validate the entire batch before changing pending declarations. Rejected batches add nothing.
     */
    public synchronized void registerAll(Collection<? extends MachineType> batch) {
        if (frozen) {
            throw new IllegalStateException("Machine declarations are already frozen");
        }
        Objects.requireNonNull(batch, "machine batch");
        var additions = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineType>(batch.size());
        for (var type : batch) {
            Objects.requireNonNull(type, "machine type");
            if (declarations.containsKey(type.id()) || additions.putIfAbsent(type.id(), type) != null) {
                throw new IllegalArgumentException("Duplicate machine type ID: " + type.id());
            }
        }
        declarations.putAll(additions);
    }

    /**
     * Close once, publishing the complete deterministic declaration order.
     */
    public synchronized void freeze() {
        if (frozen) {
            throw new IllegalStateException("Machine declarations are already frozen");
        }
        types = new ObjectArrayList<>(declarations.values());
        frozen = true;
    }

    public synchronized List<MachineType> types() {
        requireFrozen();
        return types;
    }

    @Nullable
    public synchronized MachineType get(ResourceLocation id) {
        requireFrozen();
        return declarations.get(Objects.requireNonNull(id));
    }

    /**
     * Validate every supplied key/ID and every declared reference before publishing any binding.
     */
    public synchronized void resolveStructures(Map<ResourceLocation, ? extends MachineStructure> definitions) {
        requireFrozen();
        if (runtime != null) {
            throw new IllegalStateException("Machine structures are already resolved");
        }
        var catalog = StructureCatalog.fromShapes(validateStructures(definitions));
        runtime = new ResolvedRuntime(catalog, resolveStructureReferences(catalog), null);
    }

    /**
     * Validate the complete controller runtime before publishing either structures or assemblies.
     */
    public synchronized void resolveRuntime(Map<ResourceLocation, ? extends MachineStructure> definitions,
                                            Collection<? extends MachineAssembly> assemblyDefinitions) {
        requireFrozen();
        if (runtime != null) throw new IllegalStateException("Machine structures are already resolved");
        resolveRuntimeCandidate(StructureCatalog.fromShapes(validateStructures(definitions)), assemblyDefinitions);
    }

    /**
     * The full JSON/Java catalog and all runtime bindings become visible through one snapshot.
     */
    public synchronized void resolveRuntime(StructureCatalog catalog,
                                            Collection<? extends MachineAssembly> assemblyDefinitions) {
        resolveRuntimeCandidate(Objects.requireNonNull(catalog), assemblyDefinitions);
    }

    private void resolveRuntimeCandidate(StructureCatalog catalog, Collection<? extends MachineAssembly> assemblyDefinitions) {
        requireFrozen();
        if (runtime != null) throw new IllegalStateException("Machine structures are already resolved");
        var resolvedStructures = resolveStructureReferences(catalog);
        Objects.requireNonNull(assemblyDefinitions, "machine assemblies");
        var checked = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineAssembly>(assemblyDefinitions.size());
        for (var assembly : assemblyDefinitions) {
            Objects.requireNonNull(assembly, "machine assembly");
            var type = assembly.type();
            requireCanonical(type);
            if (checked.putIfAbsent(type.id(), assembly) != null) {
                throw new IllegalArgumentException("Duplicate machine assembly ID: " + type.id());
            }
        }
        var resolvedAssemblies = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineAssembly>(types.size());
        for (var type : types) {
            var assembly = checked.get(type.id());
            if (assembly == null) {
                throw new IllegalArgumentException("Missing assembly for machine " + type.id());
            }
            resolvedAssemblies.put(type.id(), assembly);
        }
        runtime = new ResolvedRuntime(catalog, resolvedStructures, resolvedAssemblies);
    }

    private Map<ResourceLocation, MachineStructure> resolveStructureReferences(StructureCatalog catalog) {
        for (var entry : catalog.definitions().entrySet()) {
            if (!entry.getKey().equals(Objects.requireNonNull(entry.getValue().id())))
                throw new IllegalArgumentException("Structure key does not match its ID: " + entry.getKey());
        }
        var resolved = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineStructure>(types.size());
        for (var type : types) {
            var definition = catalog.get(type.structureId());
            if (definition == null) {
                throw new IllegalArgumentException("Unknown structure " + type.structureId() + " for machine " + type.id());
            }
            resolved.put(type.id(), definition.shape());
        }
        return resolved;
    }

    public synchronized MachineStructure structure(MachineType type) {
        requireFrozen();
        requireCanonical(type);
        if (runtime == null) {
            throw new IllegalStateException("Machine structures have not been resolved");
        }
        return Objects.requireNonNull(runtime.structures.get(type.id()));
    }

    public synchronized MachineAssembly assembly(MachineType type) {
        requireFrozen();
        requireCanonical(type);
        if (runtime == null || runtime.assemblies == null) {
            throw new IllegalStateException("Machine runtime has not been resolved");
        }
        return Objects.requireNonNull(runtime.assemblies.get(type.id()));
    }

    /**
     * A held catalog is a stable generation identity; future runtime reload must replace the whole snapshot.
     */
    public synchronized StructureCatalog structureCatalog() {
        requireFrozen();
        if (runtime == null) throw new IllegalStateException("Machine structures have not been resolved");
        return runtime.catalog;
    }

    /**
     * Preflight without changing either the complete catalog or canonical assembly bindings.
     */
    public synchronized Replacement prepareReplacement(StructureCatalog catalog) {
        requireFrozen();
        var previous = Objects.requireNonNull(runtime, "Machine runtime has not been resolved");
        if (previous.assemblies == null) throw new IllegalStateException("Machine runtime has no assemblies");
        return new Replacement(this, previous, new ResolvedRuntime(Objects.requireNonNull(catalog),
            resolveStructureReferences(catalog), previous.assemblies));
    }

    /**
     * Called once at the owning scheduler's joined boundary after exact host preflight.
     */
    public synchronized void publishReplacement(Replacement replacement) {
        if (!replacementCurrent(replacement)) throw new IllegalStateException("Structure replacement is obsolete");
        runtime = replacement.next;
    }

    public synchronized boolean replacementCurrent(Replacement replacement) {
        return replacement != null && replacement.owner == this && runtime == replacement.previous;
    }

    private void requireCanonical(MachineType type) {
        Objects.requireNonNull(type, "machine type");
        if (declarations.get(type.id()) != type) {
            throw new IllegalArgumentException("Machine type is not the canonical declaration: " + type.id());
        }
    }

    private void requireFrozen() {
        if (!frozen) {
            throw new IllegalStateException("Machine declarations have not been frozen");
        }
    }


    /**
     * A held replacement has no publication authority beyond its exact previous runtime.
     */
    public static final class Replacement {
        private final MachineTypeRegistry owner;
        private final ResolvedRuntime previous;
        private final ResolvedRuntime next;

        private Replacement(MachineTypeRegistry owner, ResolvedRuntime previous, ResolvedRuntime next) {
            this.owner = owner;
            this.previous = previous;
            this.next = next;
        }

        public StructureCatalog catalog() {
            return next.catalog;
        }
    }

    private record ResolvedRuntime(StructureCatalog catalog, Map<ResourceLocation, MachineStructure> structures,
                                   @Nullable Map<ResourceLocation, MachineAssembly> assemblies) {
    }
}
