package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.circulation.prototype_eng.api.machine.component.AffinityKeyProvider;
import com.circulation.prototype_eng.api.structure.StructureComponents;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedMachineTask;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import java.util.List;

/**
 * Per-machine component execution state. The scheduler must give this instance exclusive execution
 * ownership; it may transfer that ownership between threads at a join boundary. A world host attaches
 * one MachineLifecycle before scheduling. Unbound instances support standalone library execution;
 * they carry no world/scheduler permission. Scheduler registration and world matching remain external.
 */
public class MachineInstance implements PlannedMachineTask {
    private final ComponentMap<MachineInstance> components = new ComponentMap<>();
    private final MachineAttributes attributes = new MachineAttributes();
    @Nullable private final MachineType type;
    @Nullable
    private MachineLifecycle lifecycle;
    @Nullable private ObjectArrayList<BooleanSupplier> recipeWorkGates;

    /** Standalone execution has no declared machine type or world authority. */
    public MachineInstance() { type = null; }

    /**
     * Retains the canonical declaration only. The controller must attach its host before creating
     * declared components; construction never invokes factories or reads an unattached world.
     */
    public MachineInstance(MachineType type) { this.type = Objects.requireNonNull(type); }

    @Nullable public MachineType type() { return type; }

    /**
     * The single runtime pool-selection override called by the recipe framework. Default declared
     * machines use only their static pool IDs; a standalone instance uses the supplied catalog.
     * Overrides may select any canonical pool from that catalog, including outside the static list.
     * Empty means no automatic candidates. The returned collection must stay immutable while the
     * logical scan pass captures it across budget yields. This cooperative callback runs on the execution owner and
     * must use thread-safe component/state facts, never an unavailable World or team backend.
     * A new pass after admission, exhaustion or an explicit safe reset samples again;
     * selection changes during the pass apply to the next pass. Existing processes and static JEI bindings are unaffected.
     */
    public List<RecipePool> getRecipePools(RecipeCatalog catalog) {
        return type == null ? catalog.pools() : catalog.poolsFor(type);
    }

    /** Host assembly boundary; an attached lifecycle cannot be replaced or shared across machines. */
    public void attachLifecycle(MachineLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle);
        if (this.lifecycle != null || lifecycle.machine() != this) {
            throw new IllegalStateException("Machine lifecycle is already attached or belongs to another machine");
        }
        this.lifecycle = lifecycle;
    }

    @Nullable
    public MachineLifecycle lifecycle() { return lifecycle; }

    public boolean canProcessRecipes() {
        if (!(lifecycle == null ? type == null : lifecycle.processingAllowed())) { return false; }
        return recipeWorkAllowed();
    }

    /** Additional permissions for low-level recipe APIs; formation remains the scheduler host's condition. */
    public boolean recipeWorkAllowed() {
        if (recipeWorkGates != null) {
            for (var permission : recipeWorkGates) { if (!permission.getAsBoolean()) { return false; } }
        }
        return true;
    }

    /**
     * Adds a permanent recipe-only condition during unpublished assembly. Conditions must read only
     * thread-safe runtime state; they may not read a World or team backend on recipe workers.
     * Gates are recreated by component factories, never persisted, removed, or used to retire ordinary components.
     */
    public void addRecipeWorkGate(BooleanSupplier permission) {
        Objects.requireNonNull(permission);
        if (lifecycle != null) {
            lifecycle.host().requireServerThread();
            if (lifecycle.state() != MachineLifecycle.State.CREATED) { throw new IllegalStateException("Recipe gates require unpublished assembly"); }
        }
        if (recipeWorkGates == null) { recipeWorkGates = new ObjectArrayList<>(); }
        recipeWorkGates.add(permission);
    }

    @Override
    public MachineInstance plannedMachine() {
        return this;
    }

    /** Unformed machines still run their ordinary non-recipe components. */
    @Override public boolean active() { return lifecycle == null ? type == null : lifecycle.schedulingAllowed(); }

    @Override public ExecutionMode executionMode() {
        var scheduling = components.get(MachineScheduling.TYPE);
        return scheduling != null ? scheduling.mode() : type != null ? type.executionMode() : ExecutionMode.CONCURRENT;
    }

    /**
     * Captured by the scheduler on the owner thread; actual balance aliases share affinity keys.
     */
    @Override
    public Set<?> affinityKeys() {
        var keys = new ObjectLinkedOpenHashSet<>();
        var hatches = MachineHatches.get(this);
        for (var hatch : hatches.resources()) {
            var input = hatch.inputStorageIdentity();
            var output = hatch.outputStorageIdentity();
            if (input != null) {
                keys.add(input);
            }
            if (output != null) {
                keys.add(output);
            }
        }
        for (var input : hatches.energyInputs()) {
            keys.add(Objects.requireNonNull(input.storageIdentity()));
        }
        for (var output : hatches.energyOutputs()) {
            keys.add(Objects.requireNonNull(output.storageIdentity()));
        }
        for (var binding : components.assemblyBindings()) {
            if (binding.component() instanceof AffinityKeyProvider provider) {
                for (var key : Objects.requireNonNull(provider.affinityKeys())) {
                    keys.add(Objects.requireNonNull(key));
                }
            }
        }
        return ObjectSets.unmodifiable(keys);
    }

    public void requirePersistenceBoundary() {
        if (type != null && lifecycle == null) { throw new IllegalStateException("Declared machine has no attached lifecycle"); }
        if (lifecycle != null) { lifecycle.requirePersistenceBoundary(); }
    }

    /** Baseline attributes; mutate only while this machine has exclusive execution ownership. */
    public MachineAttributes attributes() {
        return attributes;
    }

    public ComponentMap<MachineInstance> components() { return components; }

    /** Structure-derived ports are replaced together at each joined refresh and are never persisted. */
    public StructureComponents structureComponents() {
        var ports = components.get(StructureComponents.TYPE);
        return ports == null ? StructureComponents.EMPTY : ports;
    }

    /** Runs all pre callbacks, all normal callbacks, then all post callbacks. Never swallows errors. */
    @Override
    public void onSchedule() { if (lifecycle == null) { if (type != null) { throw new IllegalStateException("Declared machine has no attached lifecycle"); } components.tick(this); } else { lifecycle.runScheduled(() -> components.tick(this)); } }
}
