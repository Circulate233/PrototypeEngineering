package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.component.AffinityKeyProvider;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedMachineTask;
import com.circulation.prototype_eng.api.structure.StructureComponents;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Per-machine component execution state. The scheduler must give this instance exclusive execution
 * ownership; it may transfer that ownership between threads at a join boundary. A world host attaches
 * one MachineLifecycle before scheduling. Unbound instances support standalone library execution;
 * they carry no world/scheduler permission. Scheduler registration and world matching remain external.
 */
public class MachineInstance implements PlannedMachineTask {
    private final ReferenceOpenHashSet<MachineWorkSlots> recipeHosts =
        new ReferenceOpenHashSet<>();
    private List<MachineWorkSlots> recipeHostView = ObjectLists.emptyList();
    @Nullable
    private List<RecipeScanner> recipeScannerSnapshot;
    private final ReferenceOpenHashSet<RecipeScanner> recipeScanners =
        new ReferenceOpenHashSet<>();
    private final ReferenceOpenHashSet<RecipeProcess> recipeOccupants =
        new ReferenceOpenHashSet<>();
    private final Object2IntOpenHashMap<ResourceLocation> recipeThreadCounts =
        new Object2IntOpenHashMap<>();
    private final ComponentMap<MachineInstance> components = new ComponentMap<>();
    private final MachineAttributes attributes = new MachineAttributes();
    @Nullable
    private final MachineType type;
    private long recipeRosterRevision;
    @Nullable
    private MachineLifecycle lifecycle;
    @Nullable
    private ObjectArrayList<BooleanSupplier> recipeWorkGates;

    /**
     * Standalone execution has no declared machine type or world authority.
     */
    public MachineInstance() {
        type = null;
    }

    /**
     * Retains the canonical declaration only. The controller must attach its host before creating
     * declared components; construction never invokes factories or reads an unattached world.
     */
    public MachineInstance(MachineType type) {
        this.type = Objects.requireNonNull(type);
    }

    /** All logical hosts share this machine's allowance, including explicitly constructed external hosts. */
    public void registerRecipeHost(MachineWorkSlots host) {
        if (Objects.requireNonNull(host).owner() != this) throw new IllegalArgumentException("Foreign recipe host");
        if (!recipeHosts.add(host)) return;
        var hosts = new ObjectArrayList<>(recipeHostView);
        hosts.add(host);
        recipeHostView = ObjectLists.unmodifiable(hosts);
        recipeRosterRevision = Math.incrementExact(recipeRosterRevision);
    }

    /** Immutable registration snapshot; only registering a new host publishes a replacement. */
    public List<MachineWorkSlots> recipeHosts() {
        return recipeHostView;
    }

    /** Layout replacements also revoke retained vacancy observations, including empty dynamic layouts. */
    public final void recordRecipeHostLayoutChange(MachineWorkSlots host) {
        if (!recipeHosts.contains(host)) throw new IllegalArgumentException("Unregistered recipe host");
        recipeRosterRevision = Math.incrementExact(recipeRosterRevision);
    }

    public MachineWorkSlots createRecipeHost(List<WorkSlotDefinition> definitions,
                                             Set<ResourceLocation> groups) {
        return new MachineWorkSlots(this, definitions, groups);
    }

    public RecipeScanner createRecipeScanner(MachineWorkSlots host,
                                             RecipeCatalog catalog, LongSupplier seeds) {
        if (host.owner() != this) throw new IllegalArgumentException("Foreign scanner host");
        return new RecipeScanner(host, catalog, seeds);
    }

    public final void registerRecipeScanner(RecipeScanner scanner) {
        if (scanner.host().owner() != this) throw new IllegalArgumentException("Foreign recipe scanner");
        if (recipeScanners.add(scanner)) recipeScannerSnapshot = null;
    }

    public final List<RecipeScanner> recipeScanners() {
        if (recipeScannerSnapshot == null)
            recipeScannerSnapshot = ObjectLists.unmodifiable(new ObjectArrayList<>(recipeScanners));
        return recipeScannerSnapshot;
    }

    public boolean supportsStableFixedInputLowerBounds() {
        return false;
    }

    public final int recipeThreadCount(ResourceLocation recipe) {
        return recipeThreadCounts.getInt(recipe);
    }

    public final long recipeRosterRevision() {
        return recipeRosterRevision;
    }

    /** Same owner publication as attach/detach; reservations and attached processes count once. */
    public final void recordRecipeOccupant(RecipeProcess process) {
        if (process.owner() != this) throw new IllegalArgumentException("Foreign recipe occupant");
        if (recipeOccupants.contains(process)) return;
        var id = process.recipe().id();
        if (recipeThreadCounts.getInt(id) == Integer.MAX_VALUE)
            throw new ArithmeticException("Recipe thread count overflow");
        recipeOccupants.add(process);
        recipeThreadCounts.addTo(id, 1);
        recipeRosterRevision = Math.incrementExact(recipeRosterRevision);
    }

    public final void releaseRecipeOccupant(RecipeProcess process) {
        if (!recipeOccupants.remove(process)) return;
        var id = process.recipe().id();
        if (recipeThreadCounts.addTo(id, -1) == 1) recipeThreadCounts.removeInt(id);
        recipeRosterRevision = Math.incrementExact(recipeRosterRevision);
    }

    @Nullable
    public MachineType type() {
        return type;
    }

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

    /**
     * Host assembly boundary; an attached lifecycle cannot be replaced or shared across machines.
     */
    public void attachLifecycle(MachineLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle);
        if (this.lifecycle != null || lifecycle.machine() != this) {
            throw new IllegalStateException("Machine lifecycle is already attached or belongs to another machine");
        }
        this.lifecycle = lifecycle;
    }

    @Nullable
    public MachineLifecycle lifecycle() {
        return lifecycle;
    }

    public boolean canProcessRecipes() {
        if (!(lifecycle == null ? type == null : lifecycle.processingAllowed())) {
            return false;
        }
        return recipeWorkAllowed();
    }

    /**
     * Additional permissions for low-level recipe APIs; formation remains the scheduler host's condition.
     */
    public boolean recipeWorkAllowed() {
        if (recipeWorkGates != null) {
            for (var permission : recipeWorkGates) {
                if (!permission.getAsBoolean()) {
                    return false;
                }
            }
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
            if (lifecycle.state() != MachineLifecycle.State.CREATED) {
                throw new IllegalStateException("Recipe gates require unpublished assembly");
            }
        }
        if (recipeWorkGates == null) {
            recipeWorkGates = new ObjectArrayList<>();
        }
        recipeWorkGates.add(permission);
    }

    @Override
    public MachineInstance plannedMachine() {
        return this;
    }

    /**
     * Unformed machines still run their ordinary non-recipe components.
     */
    @Override
    public boolean active() {
        return lifecycle == null ? type == null : lifecycle.schedulingAllowed();
    }

    @Override
    public ExecutionMode executionMode() {
        if (executionPolicy() != null) return ExecutionMode.PLANNED_PARALLEL;
        var scheduling = components.get(MachineScheduling.TYPE);
        return scheduling != null ? scheduling.mode() : type != null && type.hasExplicitExecutionMode()
            ? type.executionMode() : defaultExecutionMode();
    }

    /** Concrete runtimes select their normal scheduler path; explicit declarations remain authoritative. */
    protected ExecutionMode defaultExecutionMode() {
        return ExecutionMode.CONCURRENT;
    }

    /** A restored or explicitly replaced scheduling component is authoritative, including legacy null. */
    @Nullable
    public ControllerExecutionPolicy executionPolicy() {
        var scheduling = components.get(MachineScheduling.TYPE);
        if (scheduling != null) return scheduling.policy();
        if (type != null) {
            if (type.executionPolicy() != null) return type.executionPolicy();
            if (type.hasExplicitExecutionMode()) return null;
        }
        return defaultExecutionPolicy();
    }

    /** Ordinary machines retain their execution mode unless a controller policy is explicitly selected. */
    @Nullable
    protected ControllerExecutionPolicy defaultExecutionPolicy() {
        return null;
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
        if (type != null && lifecycle == null) {
            throw new IllegalStateException("Declared machine has no attached lifecycle");
        }
        if (lifecycle != null) {
            lifecycle.requirePersistenceBoundary();
        }
    }

    /**
     * Baseline attributes; mutate only while this machine has exclusive execution ownership.
     */
    public MachineAttributes attributes() {
        return attributes;
    }

    public ComponentMap<MachineInstance> components() {
        return components;
    }

    /**
     * Structure-derived ports are replaced together at each joined refresh and are never persisted.
     */
    public StructureComponents structureComponents() {
        var ports = components.get(StructureComponents.TYPE);
        return ports == null ? StructureComponents.EMPTY : ports;
    }

    /**
     * Runs all pre callbacks, all normal callbacks, then all post callbacks. Never swallows errors.
     */
    @Override
    public void onSchedule() {
        if (lifecycle == null) {
            if (type != null) {
                throw new IllegalStateException("Declared machine has no attached lifecycle");
            }
            components.tick(this);
        } else {
            lifecycle.runScheduled(() -> components.tick(this));
        }
    }
}
