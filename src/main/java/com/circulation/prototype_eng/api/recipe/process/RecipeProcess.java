package com.circulation.prototype_eng.api.recipe.process;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.CrossRecipeBatch;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.requirement.MachineRecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementAlternatives;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementContinuation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.recipe.PreparedRequirement;
import com.circulation.prototype_eng.recipe.RecipeAbortCleanup;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Isolated requirement/extension state for one work slot. A scheduler may run different processes
 * concurrently but must never execute the same process concurrently. Its machine attribute baseline
 * must remain stable during that execution. Persistence requires a quiescent, exclusively owned
 * process; tick() is the synchronous single-process lifecycle, independent of its scheduler.
 */
public final class RecipeProcess {
    private final MachineInstance owner;
    private final MachineRecipe recipe;
    private final List<RecipeRequirement<?>> orderedRequirements;
    private final long seed;
    private final ProbabilityTuning probabilityTuning;
    @Nullable
    private final MachineHatches materialHatches;
    private final ComponentMap<RecipeProcess> components = new ComponentMap<>();
    private final MachineAttributes attributes;
    private final Object2ObjectOpenHashMap<PureFactPath, RecipePureComputation> pureFacts =
        new Object2ObjectOpenHashMap<>();
    private State state = new State(Lifecycle.NEW, 0, 0, ProcessResult.SUCCESS);
    private boolean ticking;
    private int acquiredTransactions;
    private boolean persistenceFailure;
    private boolean probing;
    private long stageVersion;
    @Nullable
    private TickSession activeSession;
    @Nullable
    private RecipeStageExecutor.Probe activeProbe;
    @Nullable
    private MachineWorkSlots host;
    @Nullable
    private Tick activeTick;
    @Nullable
    private RequirementPath activeRequirement;
    private boolean purePreparation;
    @Nullable
    private RecipeWorkBudget preparationWork;
    // Frozen requirement systems/structural children make this process-local capability cache stable.
    private byte ownerThreadRequirement;
    @Nullable
    private CrossRecipeBatch.Timing crossRecipeTiming;

    public @Nullable CrossRecipeBatch.Timing crossRecipeTiming() {
        return crossRecipeTiming;
    }

    public void crossRecipeTiming(CrossRecipeBatch.Timing timing) {
        requirePersistenceBoundary();
        if (crossRecipeTiming != null || state.lifecycle() != Lifecycle.NEW || isMaterialCycle())
            throw new IllegalStateException("Only new ordinary work may join a cross-recipe batch");
        crossRecipeTiming = Objects.requireNonNull(timing);
    }

    /** Framework-only rebase after every START committed, before any member's first TICK. */
    public void refreshCrossRecipeTiming(CrossRecipeBatch.Timing timing) {
        if (host != null) host.requireProcessOperation(this);
        if (crossRecipeTiming == null || !crossRecipeTiming.batch().equals(timing.batch()) || activeSession == null
            || activeSession.current != null || !state.started() || state.progress() != 0 || state.successfulTicks() != 0
            || acquiredTransactions != 0)
            throw new IllegalStateException("Cross-recipe timing can only refresh after START and before work");
        crossRecipeTiming = Objects.requireNonNull(timing);
    }

    /** Runtime batch duration never changes the canonical recipe definition or its codec identity. */
    public int durationTicks() {
        return crossRecipeTiming == null ? recipe.durationTicks() : crossRecipeTiming.duration();
    }

    public RecipeProcess(MachineInstance owner, MachineRecipe recipe, long seed) {
        this(owner, recipe, seed, ProbabilityTuning.defaultTuning());
    }

    public RecipeProcess(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning probabilityTuning) {
        this(owner, recipe, seed, probabilityTuning, null);
    }

    private RecipeProcess(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning probabilityTuning,
                          @Nullable MachineHatches materialHatches) {
        this(owner, recipe, seed, probabilityTuning, materialHatches, null);
    }

    private RecipeProcess(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning probabilityTuning,
                          @Nullable MachineHatches materialHatches, @Nullable List<RecipeRequirement<?>> captured) {
        this.owner = Objects.requireNonNull(owner);
        this.recipe = Objects.requireNonNull(recipe);
        this.seed = seed;
        this.probabilityTuning = Objects.requireNonNull(probabilityTuning);
        this.materialHatches = materialHatches;
        attributes = new MachineAttributes(owner.attributes(), 1);
        if (materialHatches != null) {
            // One segment pays one material execution; its own later modifiers remain effective.
            attributes.setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        }
        orderedRequirements = captured != null ? captured : materialHatches == null ? captureRequirements(owner, recipe)
            : canonicalRequirements(recipe.orderedRequirements());
    }

    public static Creation beginCreation(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
        return new Creation(owner, recipe, seed, tuning);
    }

    /**
     * One complete material lifecycle on a real machine, using only the supplied canonical roots
     * and captured hatch bindings. START and END follow normal transaction ordering; exactly one
     * successful TICK is required, even when its scaled progress is zero. Machine recipe providers
     * are deliberately excluded because this cycle is not ordinary recipe work.
     */
    public static RecipeProcess materialCycle(MachineInstance owner, ResourceLocation id,
                                              Collection<? extends RecipeRequirement<?>> requirements,
                                              MachineHatches hatches, long seed) {
        return materialCycle(owner, new MachineRecipe(id, 1, requirements), hatches, seed);
    }

    /**
     * Uses the canonical definition owned by a frozen material persistence binding.
     */
    public static RecipeProcess materialCycle(MachineInstance owner, MachineRecipe canonical, MachineHatches hatches, long seed) {
        if (Objects.requireNonNull(canonical).durationTicks() != 1) {
            throw new IllegalArgumentException("Material cycle definition must declare one work tick");
        }
        return new RecipeProcess(owner, canonical, seed,
            ProbabilityTuning.defaultTuning(), Objects.requireNonNull(hatches));
    }

    private static List<RecipeRequirement<?>> captureRequirements(MachineInstance owner, MachineRecipe recipe) {
        var declared = recipe.orderedRequirements();
        ObjectArrayList<RecipeRequirement<?>> supplied = null;
        for (var binding : owner.components().assemblyBindings()) {
            if (binding.component() instanceof MachineRecipeRequirements provider) {
                if (supplied == null) {
                    supplied = new ObjectArrayList<>(declared);
                }
                supplied.addAll(Objects.requireNonNull(provider.requirementsFor(recipe), "Machine recipe requirements"));
            }
        }
        var roots = canonicalRequirements(supplied == null ? declared : supplied);
        if (supplied == null) {
            return roots;
        }
        supplied.sort(Comparator.comparing(root -> root.type().id().toString()));
        return ObjectLists.unmodifiable(supplied);
    }

    private static List<RecipeRequirement<?>> canonicalRequirements(List<RecipeRequirement<?>> roots) {
        var types = new Object2ObjectOpenHashMap<ResourceLocation, RecipeRequirementType<?>>();
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        for (var root : roots) {
            Objects.requireNonNull(root, "Machine recipe requirement root");
            if (root != root.definition()) {
                throw new IllegalArgumentException("Machine recipe requirements contain an effective view");
            }
            var previous = types.putIfAbsent(root.type().id(), root.type());
            if (previous != null && previous != root.type()) {
                throw new IllegalArgumentException("Different requirement types share id " + root.type().id());
            }
            if (!ids.add(root.id())) {
                throw new IllegalArgumentException("Duplicate machine recipe requirement " + root.id());
            }
        }
        return roots;
    }

    /**
     * Constructs an unpublished restored process. Its components still require restoration and validation.
     */
    public static RecipeProcess restored(MachineInstance owner, MachineRecipe recipe, long seed,
                                         ProbabilityTuning tuning, State state) {
        return restored(owner, recipe, seed, tuning, state, null);
    }

    public static RecipeProcess restored(MachineInstance owner, MachineRecipe recipe, long seed,
                                         ProbabilityTuning tuning, State state, @Nullable CrossRecipeBatch.Timing timing) {
        Objects.requireNonNull(state);
        if (state.lifecycle() == Lifecycle.COMPLETED && state.progress() < (timing == null ? recipe.durationTicks() : timing.duration())) {
            throw new IllegalArgumentException("Completed process has insufficient work progress");
        }
        var process = new RecipeProcess(owner, recipe, seed, tuning);
        process.state = state;
        process.crossRecipeTiming = timing;
        return process;
    }

    /**
     * Recreates a material process at a confirmed stage boundary. Components/attributes must still
     * be restored and validated by the host's process codec; this does not restore live tokens,
     * hatch authority or a tick session. A one-cycle clock can contain only zero or one successful
     * TICK, and completion requires that TICK even when scaled progress remained zero.
     */
    public static RecipeProcess restoredMaterialCycle(MachineInstance owner, MachineRecipe recipe, long seed,
                                                      ProbabilityTuning tuning, State state, MachineHatches hatches) {
        Objects.requireNonNull(state);
        if (recipe.durationTicks() != 1 || state.successfulTicks() > 1
            || state.successfulTicks() == 0 && state.progress() != 0
            || state.lifecycle() == Lifecycle.COMPLETED && state.successfulTicks() != 1) {
            throw new IllegalArgumentException("Invalid material cycle definition or confirmed state");
        }
        var process = new RecipeProcess(owner, recipe, seed, tuning, Objects.requireNonNull(hatches));
        process.state = state;
        return process;
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
    }

    private static <C> List<RecipeRequirement<?>> structuralChildren(RecipeRequirement<C> requirement) {
        return requirement.type().system().children(requirement.payload());
    }

    private static <C> boolean declaresContinuation(RecipeRequirement<C> requirement) {
        return requirement.type().system().continuedAcquisition(requirement.payload());
    }

    /**
     * Process-local, unsaved pure facts. Callers must independently prove every frozen input equal.
     */
    public @Nullable RecipePureComputation pureFacts(RecipeStage stage) {
        return pureFacts.get(new PureFactPath(requirementPath(), stage));
    }

    /**
     * Transfers cached mutable facts out before a new input proof or worker can touch them.
     */
    public @Nullable RecipePureComputation takePureFacts(RecipeStage stage) {
        return pureFacts.remove(new PureFactPath(requirementPath(), stage));
    }

    /**
     * Only owner publication after actual worker exit; never stores a preparation binding or token.
     */
    public void retainPureFacts(RecipeStage stage, RecipePureComputation computation) {
        pureFacts.put(new PureFactPath(requirementPath(), stage), Objects.requireNonNull(computation));
    }

    public void clearPureFacts() {
        pureFacts.clear();
    }

    /**
     * Withdraw mutable facts before dispatch; only an accepted quiescent Success may republish.
     */
    public void withdrawPureFacts(RecipeStage stage, RecipePureComputation expected) {
        var path = new PureFactPath(requirementPath(), stage);
        if (pureFacts.get(path) == expected) pureFacts.remove(path);
    }

    public MachineInstance owner() {
        return owner;
    }

    /**
     * Transient attachment identity, never a persisted scheduler or world authority.
     */
    @Nullable
    public MachineWorkSlots host() {
        return host;
    }

    /**
     * Host assembly boundary; a process may never occupy two work slots or hosts.
     */
    public void attachTo(MachineWorkSlots newHost) {
        requirePersistenceBoundary();
        if (Objects.requireNonNull(newHost).owner() != owner || host != null) {
            throw new IllegalStateException("Process already attached or belongs to a different machine");
        }
        owner.recordRecipeOccupant(this);
        host = newHost;
    }

    public void detachFrom(MachineWorkSlots previousHost) {
        requirePersistenceBoundary();
        if (host == null || host != previousHost) {
            throw new IllegalStateException("Process is not attached to the expected host");
        }
        host = null;
        owner.releaseRecipeOccupant(this);
    }

    public MachineRecipe recipe() {
        return recipe;
    }

    /**
     * Ordinary processes observe current machine bindings; material cycles retain their capture.
     */
    public MachineHatches hatches() {
        return materialHatches == null ? MachineHatches.get(owner) : materialHatches;
    }

    /**
     * Persistence hosts must restore material cycles through their corresponding factory.
     */
    @SuppressWarnings("unused") // External process codecs distinguish this explicitly restored clock.
    public boolean isMaterialCycle() {
        return materialHatches != null;
    }

    /**
     * Frozen canonical roots from the recipe and its actual machine component providers.
     */
    public List<RecipeRequirement<?>> orderedRequirements() {
        return orderedRequirements;
    }

    /**
     * Read once at the quiescent owner handoff, including machine-supplied roots and every nested
     * structural child. This reads only immutable capability/shape metadata and invokes no stage.
     */
    public boolean requiresOwnerThread() {
        if (ownerThreadRequirement == 0) {
            var pending = new ObjectArrayList<>(orderedRequirements);
            var visited = new ReferenceOpenHashSet<RecipeRequirement<?>>();
            while (!pending.isEmpty()) {
                var requirement = pending.removeLast();
                if (!visited.add(requirement)) continue;
                if (requirement.type().system().requiresOwnerThread()) {
                    ownerThreadRequirement = 2;
                    return true;
                }
                pending.addAll(Objects.requireNonNull(structuralChildren(requirement), "Requirement structural children"));
            }
            ownerThreadRequirement = 1;
        }
        return ownerThreadRequirement == 2;
    }

    /**
     * Downstream mods can retain the seed when restoring deterministic process state.
     */
    @SuppressWarnings("unused")
    public long seed() {
        return seed;
    }

    public ComponentMap<RecipeProcess> components() {
        return components;
    }

    /**
     * Process-local bases/modifiers overlay the live machine baseline without mutating it.
     */
    public MachineAttributes attributes() {
        return attributes;
    }

    /**
     * Frozen per process so a global tuning change cannot reroll a blocked phase.
     */
    public ProbabilityTuning probabilityTuning() {
        return probabilityTuning;
    }

    public State state() {
        return state;
    }

    /**
     * Only complete, quiescent stage boundaries may be serialized; no live transaction is resumable.
     */
    public void requirePersistenceBoundary() {
        requireQuiescent();
        if (persistenceFailure) {
            throw new IllegalStateException("Process is executing, has acquired transactions, or failed terminal cleanup");
        }
    }

    /**
     * Rejects live work; unlike persistence this permits observing a faulted but inactive process.
     */
    public void requireQuiescent() {
        if (host != null) {
            host.requireProcessBoundary(this);
        }
        if (!quiescent()) {
            throw new IllegalStateException("Process is executing or has acquired transactions");
        }
    }

    /**
     * Local inactivity proof; the host/scheduler may still own an enclosing batch lease.
     */
    public boolean quiescent() {
        return !ticking && !probing && activeSession == null && activeProbe == null && activeTick == null && activeRequirement == null && acquiredTransactions == 0;
    }

    /**
     * True only for an explicit admission probe and its captured acquisition/cleanup callbacks.
     */
    public boolean probing() {
        return probing;
    }

    /**
     * Exact prepared acquisition owner; opaque synchronous child callbacks retain their contract.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public @Nullable RecipeStageExecutor.Acquisition.FlatCandidate capturePreparedCandidate(RequirementTransaction transaction) {
        requireAcquisitionBoundary();
        if (!purePreparation) return null;
        if (activeProbe != null) return activeProbe.captureFlatCandidate(transaction);
        var current = activeSession == null ? null : activeSession.current;
        return current == null || !current.preparedAcquisition ? null : current.acquisition.captureFlatCandidate(transaction);
    }

    @SuppressWarnings("unused") // Public compatibility entry point for external owner-driven consumers.
    public boolean preparedCleanupPending() {
        if (activeProbe != null) return activeProbe.flatCleanupPending();
        var current = activeSession == null ? null : activeSession.current;
        return current != null && current.preparedAcquisition && current.acquisition.flatCleanupPending();
    }

    public boolean incrementalAcquisition() {
        if (!purePreparation) return false;
        var current = activeSession == null ? null : activeSession.current;
        return activeProbe != null || current != null && current.preparedAcquisition;
    }

    /**
     * Private preflight inventory is retained by the existing acquisition before a pause returns.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public RecipeStageExecutor.Acquisition.FlatCandidate capturePreparedCleanup(RequirementTransaction transaction) {
        requireAcquisitionBoundary();
        if (!incrementalAcquisition())
            throw new IllegalStateException("Prepared cleanup requires an incremental acquisition");
        if (activeProbe != null) return activeProbe.captureCleanupCandidate(transaction);
        return Objects.requireNonNull(Objects.requireNonNull(activeSession).current).acquisition.captureCleanupCandidate(transaction);
    }

    /**
     * Independent START/TICK/END probes never grant authority to perform ordinary network work.
     */
    public <T> T withProbeContext(Supplier<T> action) {
        Objects.requireNonNull(action);
        requirePersistenceBoundary();
        if (host != null || state.lifecycle() != Lifecycle.NEW) {
            throw new IllegalArgumentException("Probe context requires an unattached NEW process");
        }
        probing = true;
        try {
            return action.get();
        } finally {
            probing = false;
        }
    }

    /**
     * Framework ownership is installed before any probe acquisition or callback can throw.
     */
    public void claimProbe(RecipeStageExecutor.Probe expected) {
        Objects.requireNonNull(expected);
        requirePersistenceBoundary();
        if (host != null || state.lifecycle() != Lifecycle.NEW || expected.process() != this) {
            throw new IllegalArgumentException("Probe requires its unattached NEW process");
        }
        activeProbe = expected;
    }

    /**
     * Resumes the exact probe without pretending its outstanding transactions are a save boundary.
     * Its scoped transactions independently retain path/probe/TICK flags during source cleanup.
     */
    public <T> T withProbeWork(RecipeStageExecutor.Probe expected, RecipeWorkBudget budget, @Nullable Tick tick,
                               Supplier<T> action) {
        if (activeProbe != expected || probing || activeSession != null || host != null) {
            throw new IllegalStateException("Different or already executing probe");
        }
        boolean previousPreparation = purePreparation;
        var previousBudget = preparationWork;
        var previousTick = activeTick;
        probing = true;
        purePreparation = true;
        preparationWork = Objects.requireNonNull(budget);
        activeTick = tick;
        try {
            return Objects.requireNonNull(action).get();
        } finally {
            activeTick = previousTick;
            preparationWork = previousBudget;
            purePreparation = previousPreparation;
            probing = false;
        }
    }

    /**
     * Releases only after the exact acquisition has confirmed every rollback obligation.
     */
    public void finishProbe(RecipeStageExecutor.Probe expected) {
        if (activeProbe != expected || probing || activeTick != null || activeRequirement != null || acquiredTransactions != 0) {
            throw new IllegalStateException("Probe still owns callbacks or transactions");
        }
        activeProbe = null;
    }

    /**
     * Local save eligibility, not authority to bypass an enclosing work-slot or machine lease.
     */
    public boolean persistenceReady() {
        return quiescent() && !persistenceFailure;
    }

    /**
     * Framework failure marker for an exceptional acquisition/cleanup outside tick().
     */
    public void invalidatePersistenceBoundary() {
        persistenceFailure = true;
    }

    public boolean cancel() {
        return cancel(RecipeText.Cancelled.failure());
    }

    /**
     * Explicit terminal cancellation at an exclusive, transaction-free boundary; no END or refund.
     */
    public boolean cancel(ProcessResult.Failure reason) {
        return host == null ? cancelFrom(null, reason) : host.cancel(this, reason);
    }

    /**
     * Work-slot assembly boundary. Hosted callers use cancel(), which coordinates the slot result.
     */
    public boolean cancelFrom(@Nullable MachineWorkSlots expectedHost, ProcessResult.Failure reason) {
        Objects.requireNonNull(reason);
        if (host != expectedHost) {
            throw new IllegalArgumentException("Process belongs to a different cancellation host");
        }
        requirePersistenceBoundary();
        if (state.lifecycle().isTerminal()) {
            return false;
        }
        ticking = true;
        try {
            state = new State(Lifecycle.CANCELLED, state.progress(), state.successfulTicks(), reason, state.started());
            clearPureFacts();
            RecipeAbortCleanup.run(this);
            return true;
        } catch (RuntimeException | Error failure) {
            persistenceFailure = true;
            throw failure;
        } finally {
            ticking = false;
        }
    }

    private boolean workComplete() {
        return materialHatches == null ? state.progress() >= durationTicks() : state.successfulTicks() >= 1;
    }

    /**
     * Framework capture of the caller's optional timeline, without inventing a raw stage tick.
     */
    public @Nullable Tick captureTickContext() {
        return activeTick;
    }

    /**
     * Frozen scaled/unscaled timeline for requirements executing the current TICK stage.
     */
    public Tick tickContext() {
        return Objects.requireNonNull(activeTick, "No TICK stage is executing");
    }

    /**
     * The executing definition's stable path, including checkpoint/selection wrapper ancestry.
     */
    public RequirementPath requirementPath() {
        return Objects.requireNonNull(activeRequirement, "No requirement callback is executing");
    }

    /**
     * Framework owner scope retained by an ordered built-in preparation wrapper.
     */
    public <T> T withPreparationContext(RequirementPath path, Supplier<T> action) {
        requireAcquisitionBoundary();
        return withRequirementPath(Objects.requireNonNull(path), action);
    }

    public <T> T withSynchronousRequirements(Supplier<T> action) {
        boolean previous = purePreparation;
        purePreparation = false;
        try {
            return action.get();
        } finally {
            purePreparation = previous;
        }
    }

    public RecipeWorkBudget preparationBudget() {
        requireAcquisitionBoundary();
        return Objects.requireNonNull(preparationWork, "No prepared owner work budget");
    }

    public boolean usesPurePreparation() {
        return purePreparation;
    }

    public RequirementTransaction acquireRequirement(RecipeRequirement<?> requirement, Supplier<RequirementTransaction> acquisition) {
        requireAcquisitionBoundary();
        if (state.lifecycle().isTerminal()) {
            throw new IllegalStateException("Cannot acquire requirements on a terminal process");
        }
        var path = requirement.path(activeRequirement);
        var transaction = withRequirementPath(path, () -> Objects.requireNonNull(acquisition.get(), "requirement transaction"));
        if (transaction == RequirementTransaction.SUCCESS) {
            return transaction;
        }
        acquiredTransactions = Math.incrementExact(acquiredTransactions);
        return new ScopedRequirement(this, path, transaction);
    }

    /**
     * Exact framework continuation while a synchronous current stage owns the process callback.
     */
    public void acquireContinuation(RecipeStageExecutor.Acquisition expected) {
        var session = activeSession;
        if (session == null) {
            if (purePreparation && preparationWork != null) expected.acquireNextWithinBudget(preparationWork);
            else expected.acquireNext();
            return;
        }
        var current = Objects.requireNonNull(session.current, "No current stage owns this continuation");
        current.requireLive();
        if (current.acquisition != expected || !session.executing) {
            throw new IllegalStateException("Continuation does not belong to the executing stage");
        }
        boolean previous = current.acquiring;
        current.acquiring = true;
        try {
            if (purePreparation && preparationWork != null) expected.acquireNextWithinBudget(preparationWork);
            else expected.acquireNext();
        } finally {
            current.acquiring = previous;
        }
    }

    /**
     * Captured hatches plus explicit external domains, collected before a sequence claims its fence.
     */
    public List<StorageContext> declaredStorageContexts() {
        var contexts = new ObjectArrayList<StorageContext>();
        var bindings = hatches();
        for (var hatch : bindings.resources()) {
            var input = hatch.input();
            var output = hatch.output();
            if (input != null) {
                contexts.add(input.context());
            }
            if (output != null) {
                contexts.add(output.context());
            }
        }
        for (var input : bindings.energyInputs()) {
            contexts.add(input.context());
        }
        for (var output : bindings.energyOutputs()) {
            contexts.add(output.context());
        }
        for (var requirement : orderedRequirements()) {
            collectStorageContexts(requirement, contexts);
        }
        return contexts;
    }

    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public ContextCapture beginStorageContextCapture() {
        return new ContextCapture();
    }

    private <C> List<StorageContext> storageDeclarations(RecipeRequirement<C> requirement) {
        return requirement.type().system().storageContexts(this, requirement);
    }

    /**
     * Captures the original recursive continuation declaration at an actual effective selector.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public ContinuationShape beginContinuationShape(List<RecipeRequirement<?>> candidates, RequirementPath parent) {
        return new ContinuationShape(candidates, parent);
    }

    private <C> void collectStorageContexts(RecipeRequirement<C> requirement, List<StorageContext> contexts) {
        withRequirementPath(requirement.path(activeRequirement), () -> {
            contexts.addAll(Objects.requireNonNull(requirement.type().system().storageContexts(this, requirement)));
            for (var child : requirement.type().system().children(requirement.payload())) {
                collectStorageContexts(child, contexts);
            }
            return null;
        });
    }

    /**
     * Raw framework acquisitions must not bypass the current stage of a captured work-slot batch.
     */
    public void requireAcquisitionBoundary() {
        if (host != null) host.requireProcessOperation(this);
        if (host == null || host.pendingTickBatch() == null) {
            return;
        }
        if (activeSession == null || activeSession.current == null || !activeSession.current.acquiring) {
            throw new IllegalStateException("Work-slot batch requires acquisition through its current stage");
        }
    }

    private <T> T withCallbackScope(Supplier<T> action) {
        return host == null ? action.get() : host.withProcessCallback(this, action);
    }

    /**
     * Requirement cancellation uses the same parent/child scope without acquiring a new transaction.
     */
    public void cancelRequirement(RecipeRequirement<?> requirement, Runnable cleanup) {
        withRequirementPath(requirement.path(activeRequirement), () -> {
            cleanup.run();
            return null;
        });
    }

    private <T> T withRequirementPath(RequirementPath path, Supplier<T> action) {
        RequirementPath previous = activeRequirement;
        activeRequirement = path;
        try {
            return action.get();
        } finally {
            activeRequirement = previous;
        }
    }

    /**
     * Temporarily supplies a nested requirement's timeline without advancing this process.
     * Checkpoints use a zero-width context for a full child lifecycle executed at time zero.
     * This scope has the same exclusive-owner requirement as tick().
     */
    public <T> T withTickContext(Tick context, Supplier<T> action) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(action);
        Tick previous = activeTick;
        activeTick = context;
        try {
            return action.get();
        } finally {
            activeTick = previous;
        }
    }

    /**
     * PM's START -> TICK -> END loop, including the completion check before and after TICK.
     * Blocked retries the same stage; Failure is terminal. Call once per scheduled machine tick.
     * Component callbacks run pre/tick before processing and post afterwards, including blockage.
     */
    public ProcessResult tick() {
        var session = beginTick();
        if (session == null) {
            return state.result();
        }
        try {
            Stage stage;
            while ((stage = session.nextStage()) != null) {
                try {
                    stage.acquire();
                } catch (RuntimeException | Error failure) {
                    try {
                        stage.rollbackSynchronously();
                    } catch (RuntimeException | Error cleanup) {
                        suppress(failure, cleanup);
                    }
                    throw failure;
                }
                stage.settleSynchronously();
            }
            return state.result();
        } catch (RuntimeException | Error failure) {
            session.recordFailure(failure);
            throw failure;
        } finally {
            // PM's ordinary body always exits through post, even with uncertain cleanup.
            // The live session still pins persistence until its remaining stage is confirmed.
            session.finishSynchronousBody();
        }
    }

    /**
     * Begins one scheduled tick. Null means terminal or paused; no component callback ran.
     * The owner must retain the session through all stage rounds and finish it after settlement.
     * This is deliberately not AutoCloseable: unresolved resources must defer planned post callbacks.
     */
    @Nullable
    public TickSession beginTick() {
        requirePersistenceBoundary();
        if (state.lifecycle().isTerminal() || workPaused()) {
            return null;
        }
        var session = new TickSession(this);
        activeSession = session;
        session.executing = true;
        try {
            session.callbacks = components.beginTick(this);
            return session;
        } catch (RuntimeException | Error failure) {
            persistenceFailure = true;
            activeSession = null;
            throw failure;
        } finally {
            session.executing = false;
        }
    }

    /**
     * Recovery owner access after an ordinary tick threw with unconfirmed cleanup.
     */
    @Nullable
    public TickSession pendingTick() {
        return activeSession;
    }

    private boolean workPaused() {
        var lifecycle = owner.lifecycle();
        return lifecycle != null && lifecycle.retired() || !owner.recipeWorkAllowed();
    }

    /**
     * Repeatable phase/context selection, matching PM's seed XOR salt convention.
     * Downstream requirement implementations use a salt to isolate their random choices.
     */
    @SuppressWarnings("unused")
    public Random random(String salt) {
        return new Random(seed ^ salt.hashCode());
    }

    public enum Lifecycle {
        NEW, RUNNING, COMPLETED, FAILED, CANCELLED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    private record PureFactPath(RequirementPath path, RecipeStage stage) {
    }

    /**
     * Standard factory's private pure capture: provider additions, canonical checks and stable type order.
     */
    public static final class Creation implements RecipeProcessFactory.Creation {
        private final MachineInstance owner;
        private final MachineRecipe recipe;
        private final long seed;
        private final ProbabilityTuning tuning;
        private final ComponentMap<MachineInstance>.BindingCapture bindings;
        private final ObjectArrayList<RecipeRequirement<?>> roots = new ObjectArrayList<>();
        private final Object2ObjectOpenHashMap<ResourceLocation, RecipeRequirementType<?>> types = new Object2ObjectOpenHashMap<>();
        private final ObjectOpenHashSet<ResourceLocation> ids = new ObjectOpenHashSet<>();
        private final Object2ObjectRBTreeMap<String, ObjectArrayList<RecipeRequirement<?>>> orderedTypes = new Object2ObjectRBTreeMap<>();
        private final ObjectArrayList<RecipeRequirement<?>> sorted = new ObjectArrayList<>();
        private int declaredCursor;
        private List<ComponentMap.SavedBinding<MachineInstance, ?>> assembly;
        private int bindingCursor;
        private Iterator<RecipeRequirement<?>> supplied;
        private boolean provider;
        private int validationCursor;
        private Iterator<ObjectArrayList<RecipeRequirement<?>>> buckets;
        private ObjectArrayList<RecipeRequirement<?>> bucket;
        private int bucketCursor;
        private int phase;
        @Nullable
        private RecipeProcess process;

        private Creation(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
            this.owner = Objects.requireNonNull(owner);
            this.recipe = Objects.requireNonNull(recipe);
            this.seed = seed;
            this.tuning = Objects.requireNonNull(tuning);
            bindings = owner.components().beginAssemblyCapture();
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (!current()) return false;
            if (phase == 4) return true;
            if (phase == 0) {
                while (declaredCursor < recipe.orderedRequirements().size()) {
                    if (!budget.take()) return false;
                    roots.add(recipe.orderedRequirements().get(declaredCursor++));
                }
                if (!bindings.advance(budget)) return false;
                assembly = bindings.result();
                phase = 1;
            }
            if (phase == 1) {
                while (supplied != null || bindingCursor < assembly.size()) {
                    if (supplied == null) {
                        if (!budget.take()) return false;
                        var binding = assembly.get(bindingCursor++);
                        if (!(binding.component() instanceof MachineRecipeRequirements requirements)) continue;
                        provider = true;
                        supplied = Objects.requireNonNull(requirements.requirementsFor(recipe), "Machine recipe requirements").iterator();
                    }
                    while (supplied.hasNext()) {
                        if (!budget.take()) return false;
                        roots.add(Objects.requireNonNull(supplied.next(), "Machine recipe requirement root"));
                    }
                    supplied = null;
                }
                phase = 2;
            }
            if (phase == 2) {
                while (validationCursor < roots.size()) {
                    if (!budget.take()) return false;
                    var root = roots.get(validationCursor++);
                    if (root != root.definition())
                        throw new IllegalArgumentException("Machine recipe requirements contain an effective view");
                    var previous = types.putIfAbsent(root.type().id(), root.type());
                    if (previous != null && previous != root.type())
                        throw new IllegalArgumentException("Different requirement types share id " + root.type().id());
                    if (!ids.add(root.id()))
                        throw new IllegalArgumentException("Duplicate machine recipe requirement " + root.id());
                    if (provider)
                        orderedTypes.computeIfAbsent(root.type().id().toString(), _ -> new ObjectArrayList<>()).add(root);
                }
                buckets = orderedTypes.values().iterator();
                phase = 3;
            }
            if (phase == 3) {
                while (bucket != null || buckets.hasNext()) {
                    if (bucket == null) {
                        if (!budget.take()) return false;
                        bucket = buckets.next();
                        bucketCursor = 0;
                    }
                    while (bucketCursor < bucket.size()) {
                        if (!budget.take()) return false;
                        sorted.add(bucket.get(bucketCursor++));
                    }
                    bucket = null;
                }
                if (!budget.take()) return false;
                process = new RecipeProcess(owner, recipe, seed, tuning, null,
                    provider ? ObjectLists.unmodifiable(sorted) : recipe.orderedRequirements());
                phase = 4;
            }
            return true;
        }

        @Override
        public boolean current() {
            return bindings.current();
        }

        @Override
        public RecipeProcess result() {
            if (phase != 4 || !current())
                throw new IllegalStateException("Default factory capture is incomplete or stale");
            return Objects.requireNonNull(process);
        }
    }

    private static final class ContextFrame {
        private final RecipeRequirement<?> requirement;
        private final RequirementPath path;
        private Iterator<StorageContext> declared;
        private Iterator<RecipeRequirement<?>> children;

        private ContextFrame(RecipeRequirement<?> requirement, RequirementPath path) {
            this.requirement = requirement;
            this.path = path;
        }
    }

    private static final class ScopedRequirement implements RequirementTransaction {
        private final RecipeProcess process;
        private final RequirementPath path;
        private final RequirementTransaction delegate;
        @Nullable
        private final Tick tick;
        private final boolean probe;
        @Nullable
        private Resolution intendedTerminal;
        private Resolution terminal = Resolution.UNKNOWN;
        @Nullable
        private RequirementContinuation continuation;
        private boolean continuationRead;
        private boolean continuing;
        private boolean preparationAttempted;
        private boolean preparationAccepted;
        private boolean budgetPreparation;
        private boolean budgetValidation;
        private boolean commitPending;
        private boolean preparing;
        private boolean commitAttempted;
        private boolean validationAttempted;
        private boolean validating;
        private boolean terminalExecuting;
        private boolean queryExecuting;
        private boolean uncertain;
        private boolean closed;

        private ScopedRequirement(RecipeProcess process, RequirementPath path, RequirementTransaction delegate) {
            this.process = process;
            this.path = path;
            this.delegate = delegate;
            tick = process.activeTick;
            probe = process.probing;
        }

        @Override
        public ProcessResult result() {
            return scoped(delegate::result);
        }

        @Override
        public List<? extends ResourceReservation> reservations() {
            return scoped(delegate::reservations);
        }

        @Override
        public @Nullable RequirementContinuation continuation() {
            if (!continuationRead) {
                var tail = scoped(delegate::continuation);
                continuationRead = true;
                if (tail instanceof RequirementAlternatives alternatives) {
                    continuation = new RequirementAlternatives() {
                        @Override
                        public boolean ownerWorkMetered() {
                            return scoped(tail::ownerWorkMetered);
                        }

                        @Override
                        public boolean captureOnlyPreparation() {
                            return scoped(tail::captureOnlyPreparation);
                        }

                        @Override
                        public void retainPureFacts() {
                            scoped(tail::retainPureFacts);
                        }

                        @Override
                        public void withdrawPureFacts(RecipePureComputation expected) {
                            scoped(() -> tail.withdrawPureFacts(expected));
                        }

                        @Override
                        public boolean preparationPending() {
                            return scoped(tail::preparationPending);
                        }

                        @Override
                        public @Nullable RecipePureComputation computation() {
                            return scoped(tail::computation);
                        }

                        @Override
                        public boolean startsAlternative() {
                            return scoped(alternatives::startsAlternative);
                        }

                        @Override
                        public void rejectAlternative(ProcessResult rejected) {
                            scoped(() -> alternatives.rejectAlternative(rejected));
                        }

                        @Override
                        public @Nullable RequirementTransaction acquireNext() {
                            return advanceContinuation(tail);
                        }
                    };
                } else if (tail != null) {
                    continuation = new RequirementContinuation() {
                        @Override
                        public boolean ownerWorkMetered() {
                            return scoped(tail::ownerWorkMetered);
                        }

                        @Override
                        public boolean captureOnlyPreparation() {
                            return scoped(tail::captureOnlyPreparation);
                        }

                        @Override
                        public void retainPureFacts() {
                            scoped(tail::retainPureFacts);
                        }

                        @Override
                        public void withdrawPureFacts(RecipePureComputation expected) {
                            scoped(() -> tail.withdrawPureFacts(expected));
                        }

                        @Override
                        public boolean preparationPending() {
                            return scoped(tail::preparationPending);
                        }

                        @Override
                        public @Nullable RecipePureComputation computation() {
                            return scoped(tail::computation);
                        }

                        @Override
                        public @Nullable RequirementTransaction acquireNext() {
                            return advanceContinuation(tail);
                        }
                    };
                }
            }
            return continuation;
        }

        private @Nullable RequirementTransaction advanceContinuation(RequirementContinuation tail) {
            if ((!commitAttempted || commitPending) && !tail.preparationPending() || closed || intendedTerminal != null || continuing) {
                throw new IllegalStateException("Requirement continuation is not at a staged acquisition boundary");
            }
            continuing = true;
            try {
                return scoped(() -> {
                    var next = tail.acquireNext();
                    if (tail.preparationPending() && next != null && next != RequirementTransaction.SUCCESS && !(next instanceof ScopedRequirement)) {
                        process.acquiredTransactions = Math.incrementExact(process.acquiredTransactions);
                        return new ScopedRequirement(process, path, next);
                    }
                    return next;
                });
            } finally {
                continuing = false;
            }
        }

        @Override
        public boolean prepareCommit() {
            if (closed || intendedTerminal != null || preparationAttempted || commitAttempted || queryExecuting || validating) {
                throw new IllegalStateException("Cannot replay or prepare a resolving requirement transaction");
            }
            preparationAttempted = true;
            preparing = true;
            try {
                preparationAccepted = scoped(delegate::prepareCommit);
                return preparationAccepted;
            } catch (RuntimeException | Error failure) {
                process.persistenceFailure = true;
                throw failure;
            } finally {
                preparing = false;
            }
        }

        @Override
        public void commit() {
            if (closed || intendedTerminal != null || commitAttempted || queryExecuting || preparing || validating
                || preparationAttempted && !preparationAccepted) {
                throw new IllegalStateException("Cannot replay or commit a resolving requirement transaction");
            }
            commitAttempted = true;
            try {
                scoped(delegate::commit);
            } catch (RuntimeException | Error failure) {
                process.persistenceFailure = true;
                throw failure;
            }
        }

        @Override
        public void complete() {
            close(Resolution.COMPLETED, delegate::complete);
        }

        @Override
        public void rollback() {
            close(Resolution.ROLLED_BACK, delegate::rollback);
        }

        @Override
        public @Nullable Boolean prepareCommitWithinBudget(RecipeWorkBudget budget) {
            if (closed || intendedTerminal != null || commitAttempted || queryExecuting || validating || preparationAttempted && !budgetPreparation) {
                throw new IllegalStateException("Cannot replay prepared requirement work");
            }
            preparationAttempted = true;
            budgetPreparation = true;
            preparing = true;
            try {
                var result = scopedBudget(budget, () -> delegate.prepareCommitWithinBudget(budget));
                if (result != null) {
                    preparationAccepted = result;
                    budgetPreparation = false;
                }
                return result;
            } catch (RuntimeException | Error failure) {
                budgetPreparation = false;
                process.persistenceFailure = true;
                throw failure;
            } finally {
                preparing = false;
            }
        }

        @Override
        public boolean commitWithinBudget(RecipeWorkBudget budget) {
            if (closed || intendedTerminal != null || queryExecuting || preparing || validating || commitAttempted && !commitPending
                || preparationAttempted && !preparationAccepted)
                throw new IllegalStateException("Cannot replay committed requirement work");
            commitAttempted = true;
            commitPending = true;
            try {
                boolean done = scopedBudget(budget, () -> delegate.commitWithinBudget(budget));
                if (done) commitPending = false;
                return done;
            } catch (RuntimeException | Error failure) {
                commitPending = false;
                process.persistenceFailure = true;
                throw failure;
            }
        }

        @Override
        public @Nullable Boolean validateCommitWithinBudget(RecipeWorkBudget budget) {
            if (closed || intendedTerminal != null || !commitAttempted || commitPending || queryExecuting || preparing
                || validationAttempted && !budgetValidation)
                throw new IllegalStateException("Cannot replay validated requirement work");
            validationAttempted = true;
            budgetValidation = true;
            validating = true;
            try {
                var result = scopedBudget(budget, () -> delegate.validateCommitWithinBudget(budget));
                if (result != null) budgetValidation = false;
                return result;
            } catch (RuntimeException | Error failure) {
                budgetValidation = false;
                process.persistenceFailure = true;
                throw failure;
            } finally {
                validating = false;
            }
        }

        @Override
        public boolean completeWithinBudget(RecipeWorkBudget budget) {
            return closeWithinBudget(Resolution.COMPLETED, budget);
        }

        @Override
        public boolean rollbackWithinBudget(RecipeWorkBudget budget) {
            return closeWithinBudget(Resolution.ROLLED_BACK, budget);
        }

        @Override
        public @Nullable Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
            if (terminalExecuting || queryExecuting || preparing || validating)
                throw new IllegalStateException("Cannot reenter requirement recovery");
            if (closed) return terminal;
            queryExecuting = true;
            try {
                var actual = scopedBudget(budget, () -> delegate.resolutionWithinBudget(budget));
                if (actual != null && uncertain && actual == intendedTerminal) confirmTerminal(actual);
                return actual;
            } finally {
                queryExecuting = false;
            }
        }

        private boolean closeWithinBudget(Resolution expected, RecipeWorkBudget budget) {
            if (terminalExecuting || queryExecuting || preparing || validating)
                throw new IllegalStateException("Cannot reenter requirement cleanup");
            if (closed) {
                if (terminal != expected)
                    throw new IllegalStateException("Requirement resolved in the opposite direction");
                return true;
            }
            if (intendedTerminal != null && intendedTerminal != expected)
                throw new IllegalStateException("Cannot change requirement terminal decision");
            if (uncertain) {
                var actual = resolutionWithinBudget(budget);
                if (closed) return true;
                if (actual == null || actual == Resolution.UNKNOWN) return false;
                if (actual != Resolution.REVERSIBLE)
                    throw new IllegalStateException("Requirement cleanup needs its original recovery proof");
            }
            intendedTerminal = expected;
            terminalExecuting = true;
            try {
                boolean done = scopedBudget(budget, () -> expected == Resolution.COMPLETED
                    ? delegate.completeWithinBudget(budget) : delegate.rollbackWithinBudget(budget));
                if (done) confirmTerminal(expected);
                return done;
            } catch (RuntimeException | Error failure) {
                uncertain = true;
                process.persistenceFailure = true;
                throw failure;
            } finally {
                terminalExecuting = false;
            }
        }

        private <T> T scopedBudget(RecipeWorkBudget budget, Supplier<T> action) {
            return scoped(() -> {
                var previous = process.preparationWork;
                process.preparationWork = Objects.requireNonNull(budget);
                try {
                    return action.get();
                } finally {
                    process.preparationWork = previous;
                }
            });
        }

        @Override
        public boolean validateCommit() {
            if (closed || intendedTerminal != null || !commitAttempted || validationAttempted || queryExecuting || preparing) {
                throw new IllegalStateException("Cannot validate uncommitted or resolving requirement work");
            }
            validationAttempted = true;
            validating = true;
            try {
                return scoped(delegate::validateCommit);
            } catch (RuntimeException | Error failure) {
                process.persistenceFailure = true;
                throw failure;
            } finally {
                validating = false;
            }
        }

        @Override
        public Resolution resolution() {
            if (terminalExecuting || queryExecuting || preparing || validating) {
                throw new IllegalStateException("Cannot reenter a requirement recovery callback");
            }
            if (closed) {
                return terminal;
            }
            queryExecuting = true;
            try {
                var actual = Objects.requireNonNull(scoped(delegate::resolution));
                if (uncertain && actual == intendedTerminal) {
                    confirmTerminal(actual);
                }
                return actual;
            } finally {
                queryExecuting = false;
            }
        }

        private void close(Resolution expected, Runnable action) {
            if (terminalExecuting || queryExecuting || preparing || validating) {
                throw new IllegalStateException("Cannot reenter a requirement terminal callback");
            }
            if (closed) {
                if (terminal != expected) {
                    throw new IllegalStateException("Requirement already resolved in the opposite direction");
                }
                return;
            }
            if (intendedTerminal != null && intendedTerminal != expected) {
                throw new IllegalStateException("Cannot change a requirement's terminal decision");
            }
            if (uncertain) {
                var actual = resolution();
                if (closed) {
                    return;
                }
                if (actual != Resolution.REVERSIBLE) {
                    throw new IllegalStateException("Cannot retry a requirement without an exact recovery proof");
                }
            }
            intendedTerminal = expected;
            try {
                terminalExecuting = true;
                try {
                    scoped(action);
                } finally {
                    terminalExecuting = false;
                }
                confirmTerminal(expected);
            } catch (RuntimeException | Error failure) {
                uncertain = true;
                process.persistenceFailure = true;
                try {
                    resolution();
                } catch (RuntimeException | Error query) {
                    if (query != failure) {
                        failure.addSuppressed(query);
                    }
                }
                throw failure;
            }
        }

        private void confirmTerminal(Resolution actual) {
            if (closed) {
                return;
            }
            terminal = actual;
            closed = true;
            uncertain = false;
            process.acquiredTransactions--;
        }

        private void scoped(Runnable action) {
            scoped(() -> {
                action.run();
                return null;
            });
        }

        private <T> T scoped(Supplier<T> action) {
            return process.withCallbackScope(() -> {
                Tick previous = process.activeTick;
                boolean previousProbe = process.probing;
                process.activeTick = tick;
                process.probing = probe;
                try {
                    return process.withRequirementPath(path, action);
                } finally {
                    process.activeTick = previous;
                    process.probing = previousProbe;
                }
            });
        }
    }

    /**
     * One owner-thread component dispatch and a cursor that permits at most one TICK attempt.
     */
    public static final class TickSession {
        private final RecipeProcess process;
        private final Thread ownerThread = Thread.currentThread();
        @Nullable
        private final MachineWorkSlots host;
        @Nullable
        private ComponentMap.TickSession<RecipeProcess> callbacks;
        @Nullable
        private Stage current;
        @Nullable
        private Throwable failure;
        private boolean executing;
        private boolean tickAttempted;
        private boolean stopped;
        private boolean postDone;

        private TickSession(RecipeProcess process) {
            this.process = process;
            host = process.host;
        }

        /**
         * Creates the lease before acquisition, so even an empty stage prevents reentry/save.
         */
        @Nullable
        public Stage nextStage() {
            requireOwner();
            if (current != null) {
                throw new IllegalStateException("Current recipe stage is still leased");
            }
            if (stopped || process.workPaused() || process.state.lifecycle().isTerminal()) {
                return null;
            }
            RecipeStage phase;
            if (process.state.lifecycle() == Lifecycle.NEW) {
                phase = RecipeStage.START;
            } else if (process.workComplete()) {
                if (!CrossRecipeBatch.readyToEnd(process)) return null;
                phase = RecipeStage.END;
            } else if (!tickAttempted) {
                phase = RecipeStage.TICK;
            } else {
                return null;
            }
            process.stageVersion = Math.incrementExact(process.stageVersion);
            current = new Stage(this, phase, process.stageVersion);
            if (phase == RecipeStage.TICK) {
                tickAttempted = true;
            }
            return current;
        }

        /**
         * The retained stage remains available for exact cleanup after acquisition/body failure.
         */
        @Nullable
        public Stage currentStage() {
            requireOwner();
            return current;
        }

        /**
         * Stops later rounds and retains the original fault, including faults from source lanes.
         */
        public void recordFailure(Throwable cause) {
            requireThread();
            Objects.requireNonNull(cause);
            if (failure == null) {
                failure = cause;
            } else {
                suppress(failure, cause);
            }
            stopped = true;
            process.persistenceFailure = true;
        }

        /**
         * Finishes planned post only after the current lease was accurately settled.
         */
        public void finish() {
            requireOwner();
            if (process.host != null) {
                process.host.requireProcessBoundary(process);
            }
            if (current != null) {
                throw new IllegalStateException("Cannot finish an unsettled recipe tick");
            }
            finishSynchronousBody();
        }

        /**
         * Actual post entry proof, including a throwing post; rejection before entry remains false.
         */
        public boolean postStarted() {
            return postDone;
        }

        private void finishSynchronousBody() {
            requireOwner();
            stopped = true;
            executing = true;
            try {
                if (!postDone) {
                    postDone = true;
                    Objects.requireNonNull(callbacks).finish(failure);
                }
            } catch (RuntimeException | Error post) {
                recordFailure(post);
                throw post;
            } finally {
                executing = false;
                if (current == null) {
                    process.activeSession = null;
                }
            }
        }

        private void requireThread() {
            if (Thread.currentThread() != ownerThread || process.activeSession != this || process.host != host) {
                throw new IllegalStateException("Stale recipe tick session or incorrect owner");
            }
            if (host != null) {
                host.requireProcessOperation(process);
            }
        }

        private void requireOwner() {
            requireThread();
            if (executing) {
                throw new IllegalStateException("Recipe tick callback is executing");
            }
        }
    }

    /**
     * Exact current-stage acquisition. Use synchronous settlement or transfer a whole round to one
     * ResourceBatch; never both. The handle cannot advance its process until that boundary is proven.
     */
    public static final class Stage {
        private final TickSession session;
        private final RecipeStage phase;
        private final long version;
        private final State before;
        private final long nextSuccessfulTicks;
        private final RecipeStageExecutor.Acquisition acquisition;
        private State next;
        @Nullable
        private Tick tick;
        @Nullable
        private MachineAttributes.ValueCapture speedCapture;
        @Nullable
        private ResourceBatch batch;
        private boolean acquired;
        private boolean preparedAcquisition;
        private boolean acquiring;

        @Nullable
        private NextBatchCapture nextBatchCapture;
        private boolean closed;
        private boolean synchronous;
        private ReversibleTransaction.Resolution transition = ReversibleTransaction.Resolution.REVERSIBLE;

        private Stage(TickSession session, RecipeStage phase, long version) {
            this.session = session;
            this.phase = phase;
            this.version = version;
            var process = session.process;
            before = process.state;
            nextSuccessfulTicks = phase == RecipeStage.TICK ? Math.incrementExact(before.successfulTicks()) : before.successfulTicks();
            next = switch (phase) {
                case START ->
                    new State(Lifecycle.RUNNING, before.progress(), before.successfulTicks(), ProcessResult.SUCCESS);
                case TICK -> before;
                case END ->
                    new State(Lifecycle.COMPLETED, before.progress(), before.successfulTicks(), ProcessResult.SUCCESS);
            };
            tick = null;
            if (phase == RecipeStage.TICK) {
                speedCapture = process.attributes.beginValueCapture(StandardMachineAttributes.PROCESS_SPEED, 1);
            }
            acquisition = RecipeStageExecutor.prepare(process, phase, new RequirementTransaction() {
                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void commit() {
                    requireLive();
                    if (session.failure != null || acquisition.failure() != null || acquisition.result() != ProcessResult.SUCCESS
                        || transition != Resolution.REVERSIBLE || process.state != before) {
                        throw new IllegalStateException("Cannot commit a rejected or stale recipe stage");
                    }
                    process.state = next;
                }

                @Override
                public void complete() {
                    requireLive();
                    if (process.state != next || transition != Resolution.REVERSIBLE) {
                        throw new IllegalStateException("Recipe transition did not commit");
                    }
                    transition = Resolution.COMPLETED;
                }

                @Override
                public void rollback() {
                    requireLive();
                    if (transition != Resolution.REVERSIBLE) {
                        throw new IllegalStateException("Recipe transition is terminal");
                    }
                    process.state = before;
                    transition = Resolution.ROLLED_BACK;
                }

                @Override
                public Resolution resolution() {
                    requireLive();
                    return transition;
                }
            });
        }

        /**
         * Continues a bound round under the same fence after the preceding business effects were
         * staged. Every entered acquisition is adopted even after a fault, if its inventory is known.
         * An unknown inventory leaves the batch's acquisition window open and prevents release.
         */
        public static boolean acquireNextBatch(List<Stage> stages, ResourceBatch batch) {
            return acquireNextBatchWithinBudget(stages, batch, new RecipeWorkBudget(Long.MAX_VALUE));
        }

        public static boolean acquireNextBatchWithinBudget(List<Stage> stages, ResourceBatch batch, RecipeWorkBudget budget) {
            if (stages.isEmpty()) throw new IllegalArgumentException("Recipe round is empty");
            var first = stages.getFirst();
            var capture = first.nextBatchCapture;
            if (capture == null) {
                capture = new NextBatchCapture(stages, batch);
                first.nextBatchCapture = capture;
            }
            if (capture.batch != batch) throw new IllegalStateException("Continued capture belongs to another batch");
            if (!capture.advance(budget)) return true;
            first.nextBatchCapture = null;
            return capture.result();
        }

        /**
         * Adopts every known participant before cancellation/cleanup closes a paused original window.
         */
        @SuppressWarnings("unused") // Public compatibility entry point for external owner-driven consumers.
        public static boolean adoptNextBatch(List<Stage> stages, ResourceBatch batch, boolean rollback) {
            return adoptNextBatchWithinBudget(stages, batch, rollback, new RecipeWorkBudget(Long.MAX_VALUE));
        }

        public static boolean adoptNextBatchWithinBudget(List<Stage> stages, ResourceBatch batch, boolean rollback, RecipeWorkBudget budget) {
            if (stages.isEmpty()) throw new IllegalArgumentException("Recipe round is empty");
            var first = stages.getFirst();
            var capture = first.nextBatchCapture;
            if (capture == null) {
                capture = new NextBatchCapture(stages, batch);
                first.nextBatchCapture = capture;
            }
            if (capture.batch != batch) throw new IllegalStateException("Continued capture belongs to another batch");
            if (rollback) capture.cancel();
            if (!capture.advance(budget)) return false;
            first.nextBatchCapture = null;
            capture.result();
            return true;
        }

        public static boolean nextBatchPending(List<Stage> stages) {
            return !stages.isEmpty() && stages.getFirst().nextBatchCapture != null;
        }

        /**
         * Transfers a round's exact participants together. Busy domains return null and leave every
         * handle unbound. Rejected stages may only roll back; transition guards reject illegal commit.
         * Source actions still run on their assigned lanes; the owner performs business and finish.
         */
        @Nullable
        public static ResourceBatch tryAcquireBatch(List<Stage> stages) {
            return tryAcquireBatch(stages, List.of());
        }

        /**
         * Adds exact owner business acquisitions to this round, for example a material cycle's END
         * grant. A busy fence returns null without taking any participant; the caller still owns
         * their cleanup. On success all participants share the existing source/terminal boundary.
         */
        @Nullable
        public static ResourceBatch tryAcquireBatch(List<Stage> stages,
                                                    List<? extends ReversibleTransaction> participants) {
            var round = new ObjectArrayList<>(stages);
            if (round.isEmpty()) {
                throw new IllegalArgumentException("Recipe round is empty");
            }
            var unique = new ReferenceOpenHashSet<Stage>();
            var reservations = new ObjectArrayList<ResourceReservation>();
            var transactions = new ObjectArrayList<ReversibleTransaction>();
            for (var stage : round) {
                stage.requireAcquired();
                if (!unique.add(stage) || stage.synchronous || stage.batch != null || !stage.acquisition.inventoryComplete()) {
                    throw new IllegalStateException("Recipe stage cannot transfer its acquisition");
                }
                reservations.addAll(stage.acquisition.reservations());
                transactions.addAll(stage.acquisition.transactions());
            }
            transactions.addAll(Objects.requireNonNull(participants));
            boolean sequential = round.stream().anyMatch(stage -> stage.acquisition.sequential());
            var contexts = new ObjectArrayList<StorageContext>();
            if (sequential) {
                for (var stage : round) {
                    contexts.addAll(stage.session.process.declaredStorageContexts());
                }
            }
            for (var stage : round) {
                stage.session.executing = true;
            }
            try {
                var bound = sequential ? ResourceBatch.tryAcquireSequential(contexts, reservations, transactions)
                    : ResourceBatch.tryAcquire(reservations, transactions);
                if (bound != null) {
                    for (var stage : round) {
                        stage.batch = bound;
                        stage.acquisition.attachFlatParent(bound);
                    }
                }
                return bound;
            } finally {
                for (var stage : round) {
                    stage.session.executing = false;
                }
            }
        }

        public static BatchCapture beginBatchCapture(List<Stage> stages, List<? extends ReversibleTransaction> participants) {
            return new BatchCapture(stages, participants);
        }

        public RecipeStage phase() {
            return phase;
        }

        public RecipeProcess process() {
            return session.process;
        }

        /** A peer rejected this round; rollback must not retry this member in the same tick. */
        public void deferBatchRound() {
            requireOwner();
            session.stopped = true;
        }

        /**
         * Exact local lease closure; the enclosing tick still owns its post obligation.
         */
        public boolean settled() {
            return closed;
        }

        /**
         * Known complete inventory that can transfer to a source batch without guessing on failure.
         */
        public boolean transferable() {
            requireOwner();
            return acquired && !synchronous && batch == null && acquisition.inventoryComplete();
        }

        /**
         * Stops before acquisition without invoking requirements, for cancellation/retirement.
         */
        public void discard() {
            requireOwner();
            if (acquired) {
                throw new IllegalStateException("Acquired recipe stage must settle its participants");
            }
            session.stopped = true;
            session.current = null;
            closed = true;
        }

        /**
         * A throwing acquire still leaves this handle owning its partial acquisition.
         */
        public void acquire() {
            acquire(false);
        }

        /**
         * Owner acquisition with pure calculations retained in the original continuation cursor.
         */
        @SuppressWarnings("unused") // Public unbounded entry point for external owner-driven consumers.
        public void acquirePrepared() {
            acquirePreparedWithinBudget(new RecipeWorkBudget(Long.MAX_VALUE));
        }

        public void acquirePreparedWithinBudget(RecipeWorkBudget budget) {
            var process = session.process;
            var previous = process.preparationWork;
            process.preparationWork = Objects.requireNonNull(budget);
            try {
                acquire(true);
            } finally {
                process.preparationWork = previous;
            }
        }

        private void acquire(boolean preparePure) {
            requireOwner();
            if (acquired) {
                throw new IllegalStateException("Recipe stage was already acquired");
            }
            acquired = true;
            preparedAcquisition = preparePure;
            acquiring = true;
            session.executing = true;
            var process = session.process;
            Tick previous = process.activeTick;
            boolean previousPreparation = process.purePreparation;
            process.purePreparation = preparePure;
            process.activeTick = tick;
            try {
                process.withCallbackScope(() -> {
                    initializeTimeline(preparePure);
                    if (preparePure) acquisition.acquireWithinBudget(process.preparationBudget());
                    else acquisition.acquire();
                    return null;
                });
            } catch (RuntimeException | Error failure) {
                session.recordFailure(failure);
                throw failure;
            } finally {
                process.activeTick = previous;
                process.purePreparation = previousPreparation;
                acquiring = false;
                session.executing = false;
            }
        }

        @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
        private void initializeTimeline(boolean preparePure) {
            var process = session.process;
            if (phase == RecipeStage.TICK) {
                if (preparePure) acquisition.initializePreparation(new RequirementPreparation() {
                    @Override
                    public boolean ownerWorkMetered() {
                        return true;
                    }

                    @Override
                    public boolean captureOnlyPreparation() {
                        return true;
                    }

                    @Override
                    public @Nullable RecipePureComputation computation() {
                        return null;
                    }

                    @Override
                    public RequirementTransaction acquire() {
                        var capture = Objects.requireNonNull(speedCapture);
                        if (!capture.current()) return new RequirementTransaction() {
                            @Override
                            public ProcessResult result() {
                                return RecipeText.StorageChanged.blocked();
                            }

                            @Override
                            public void commit() {
                            }

                            @Override
                            public void rollback() {
                            }
                        };
                        if (!capture.advance(process.preparationBudget())) return new PreparedRequirement(this);
                        captureTick(capture.result());
                        process.activeTick = tick;
                        acquisition.captureStageTick();
                        speedCapture = null;
                        return RequirementTransaction.SUCCESS;
                    }
                });
                else {
                    captureTick(process.attributes.value(StandardMachineAttributes.PROCESS_SPEED, 1));
                    process.activeTick = tick;
                    speedCapture = null;
                }
            }
        }

        private void captureTick(double speed) {
            if (session.process.crossRecipeTiming != null && session.process.owner instanceof CrossRecipeController cross)
                speed = cross.batchSpeed();
            next = new State(Lifecycle.RUNNING, before.progress() + Math.max(0, speed), nextSuccessfulTicks, ProcessResult.SUCCESS);
            tick = new Tick(before.progress(), next.progress(), before.successfulTicks(), next.successfulTicks());
        }

        public ProcessResult result() {
            requireAcquired();
            return acquisition.result();
        }

        public void withdrawPreparedFacts(RecipePureComputation expected) {
            requireAcquired();
            if (acquisition.computation() != expected) {
                throw new IllegalStateException("Pure facts belong to another cursor");
            }
            acquiring = true;
            session.executing = true;
            try {
                session.process.withCallbackScope(() -> {
                    acquisition.withdrawPureFacts(expected);
                    return null;
                });
            } finally {
                acquiring = false;
                session.executing = false;
            }
        }

        public void retainPreparedFacts(RecipePureComputation expected) {
            requireAcquired();
            if (acquisition.computation() != expected) {
                throw new IllegalStateException("Pure facts belong to another cursor");
            }
            acquiring = true;
            session.executing = true;
            try {
                session.process.withCallbackScope(() -> {
                    acquisition.retainPureFacts();
                    return null;
                });
            } finally {
                acquiring = false;
                session.executing = false;
            }
        }

        public boolean ownerWorkMetered() {
            requireAcquired();
            return acquisition.ownerWorkMetered();
        }

        public boolean captureOnlyPreparation() {
            requireAcquired();
            return acquisition.captureOnlyPreparation();
        }

        public boolean preparationPending() {
            requireAcquired();
            return acquisition.preparationPending();
        }

        /**
         * Completes the current pure/opaque pause without exposing a new segment's resource effects.
         */
        @SuppressWarnings("unused") // Public unbounded entry point for external owner-driven consumers.
        public void resumePrepared() {
            resumePreparedWithinBudget(new RecipeWorkBudget(Long.MAX_VALUE));
        }

        public void resumePreparedWithinBudget(RecipeWorkBudget budget) {
            requireAcquired();
            acquiring = true;
            session.executing = true;
            var process = session.process;
            Tick previous = process.activeTick;
            boolean previousPreparation = process.purePreparation;
            process.purePreparation = true;
            process.activeTick = tick;
            var previousWork = process.preparationWork;
            process.preparationWork = Objects.requireNonNull(budget);
            try {
                process.withCallbackScope(() -> {
                    acquisition.resumePreparedWithinBudget(budget);
                    return null;
                });
            } catch (RuntimeException | Error failure) {
                session.recordFailure(failure);
                throw failure;
            } finally {
                process.activeTick = previous;
                process.purePreparation = previousPreparation;
                process.preparationWork = previousWork;
                acquiring = false;
                session.executing = false;
            }
        }

        public @Nullable RecipePureComputation computation() {
            requireAcquired();
            return acquisition.flatCleanupPending() ? null : acquisition.computation();
        }

        @SuppressWarnings("unused") // Public compatibility entry point for external owner-driven consumers.
        public boolean preparedCleanupPending() {
            requireAcquired();
            return acquisition.flatCleanupPending();
        }

        public boolean finishCapturedInventoryWithinBudget(RecipeWorkBudget budget) {
            requireAcquired();
            return acquisition.finishCapturedInventoryWithinBudget(budget);
        }

        public List<ResourceBatch.Source> preparedCleanupSources() {
            requireAcquired();
            var candidate = acquisition.cleanupCandidate();
            return candidate == null ? List.of() : candidate.sources();
        }

        public void finishPreparedCleanupSource(ResourceBatch.Source source, boolean resolved) {
            requireAcquired();
            Objects.requireNonNull(acquisition.cleanupCandidate()).finishSourceResolution(source, resolved);
        }

        public boolean advancePreparedCleanup(RecipeWorkBudget budget, boolean cancel) {
            requireAcquired();
            acquiring = true;
            session.executing = true;
            try {
                return session.process.withCallbackScope(() -> {
                    if (!cancel && acquisition.result() != ProcessResult.SUCCESS && !acquisition.rejectCleanupCandidatesWithinBudget(budget))
                        return false;
                    return acquisition.advanceCleanupCandidates(budget, cancel);
                });
            } finally {
                acquiring = false;
                session.executing = false;
            }
        }

        public boolean hasContinuation() {
            requireAcquired();
            return acquisition.hasContinuation();
        }

        public boolean hasRejectedAlternative() {
            requireAcquired();
            return acquisition.hasRejectedAlternative();
        }

        public ResourceBatch.Savepoint rejectedSavepoint() {
            requireAcquired();
            return acquisition.rejectedSavepoint();
        }

        @SuppressWarnings("unused") // Public compatibility entry point for external owner-driven consumers.
        public boolean finishAlternativeRollback(ResourceBatch expected) {
            requireAcquired();
            if (batch != expected) {
                throw new IllegalStateException("Candidate belongs to another batch");
            }
            return acquisition.finishAlternativeRollback(expected);
        }

        public boolean finishAlternativeRollbackWithinBudget(ResourceBatch expected, RecipeWorkBudget budget) {
            requireAcquired();
            if (batch != expected) throw new IllegalStateException("Candidate belongs to another batch");
            return acquisition.finishAlternativeRollbackWithinBudget(expected, budget);
        }

        private void acquireNextWithinBudget(RecipeWorkBudget budget) {
            var process = session.process;
            var previous = process.preparationWork;
            process.preparationWork = Objects.requireNonNull(budget);
            try {
                acquireNext();
            } finally {
                process.preparationWork = previous;
            }
        }

        private void acquireNext() {
            requireAcquired();
            acquiring = true;
            session.executing = true;
            Tick previous = session.process.activeTick;
            boolean previousPreparation = session.process.purePreparation;
            session.process.purePreparation = preparedAcquisition;
            session.process.activeTick = tick;
            try {
                session.process.withCallbackScope(() -> {
                    if (preparedAcquisition) acquisition.acquireNextWithinBudget(session.process.preparationBudget());
                    else acquisition.acquireNext();
                    return null;
                });
            } catch (RuntimeException | Error failure) {
                session.recordFailure(failure);
                throw failure;
            } finally {
                session.process.activeTick = previous;
                session.process.purePreparation = previousPreparation;
                acquiring = false;
                session.executing = false;
            }
        }

        /**
         * Preserves ResourceTransactions' all-domain lock and terminal notification semantics.
         */
        public ProcessResult settleSynchronously() {
            requireSynchronousStart();
            session.executing = true;
            try {
                return session.process.withCallbackScope(acquisition::settleSynchronously);
            } catch (RuntimeException | Error failure) {
                session.recordFailure(failure);
                throw failure;
            } finally {
                finishSynchronousAttempt();
            }
        }

        /**
         * Rolls back a rejected/abandoned or partially acquired stage without replaying commit.
         */
        public void rollbackSynchronously() {
            requireSynchronousStart();
            session.stopped = true;
            session.executing = true;
            try {
                session.process.withCallbackScope(() -> {
                    acquisition.rollbackSynchronously();
                    return null;
                });
            } catch (RuntimeException | Error failure) {
                session.recordFailure(failure);
                throw failure;
            } finally {
                finishSynchronousAttempt();
            }
        }

        /**
         * Retries only terminal cleanup proven safe by the exact synchronous participants.
         */
        public boolean resolveSynchronously() {
            requireAcquired();
            if (!synchronous || batch != null) {
                throw new IllegalStateException("No synchronous settlement to recover");
            }
            session.executing = true;
            try {
                session.process.withCallbackScope(acquisition::resolveSynchronously);
                return acquisition.resolved();
            } catch (RuntimeException | Error failure) {
                session.recordFailure(failure);
                throw failure;
            } finally {
                finishSynchronousAttempt();
            }
        }

        private void finishSynchronousAttempt() {
            try {
                if (acquisition.resolved()) {
                    session.process.withCallbackScope(() -> {
                        settled(transition == ReversibleTransaction.Resolution.COMPLETED);
                        return null;
                    });
                }
            } catch (RuntimeException | Error cleanup) {
                session.recordFailure(cleanup);
                throw cleanup;
            } finally {
                session.executing = false;
            }
        }

        /**
         * Acknowledges only this handle's bound batch, after all actions exited and isolation was
         * released. False leaves the stage/post obligation pinned. Pass any source/owner/finish fault
         * so a terminal notification failure cannot silently permit later stages or persistence.
         */
        public boolean settleBatch(@Nullable Throwable failure) {
            requireAcquired();
            var bound = Objects.requireNonNull(batch, "Recipe stage has no bound batch");
            if (failure != null) {
                session.recordFailure(failure);
            }
            if (!bound.finished()) {
                return false;
            }
            session.executing = true;
            try {
                session.process.withCallbackScope(() -> {
                    settled(bound.decision() == ResourceBatch.Decision.COMPLETE);
                    return null;
                });
                return true;
            } catch (RuntimeException | Error cleanup) {
                session.recordFailure(cleanup);
                throw cleanup;
            } finally {
                session.executing = false;
            }
        }

        private void settled(boolean committed) {
            var process = session.process;
            session.current = null;
            closed = true;
            if (!committed) {
                session.stopped = true;
            }
            if (session.failure != null) {
                process.clearPureFacts();
                return;
            }
            ProcessResult result = acquisition.result();
            if (!committed && result == ProcessResult.SUCCESS) {
                result = RecipeText.StorageChanged.blocked();
            }
            if (process.state.lifecycle().isTerminal()) {
                process.clearPureFacts();
            }
            if (result != ProcessResult.SUCCESS) {
                session.stopped = true;
                process.state = new State(result instanceof ProcessResult.Failure ? Lifecycle.FAILED : before.lifecycle(),
                    before.progress(), before.successfulTicks(), result, before.started());
                if (result instanceof ProcessResult.Failure) {
                    process.clearPureFacts();
                    RecipeAbortCleanup.run(process);
                }
            }
        }

        private void requireSynchronousStart() {
            requireAcquired();
            if (synchronous || batch != null) {
                throw new IllegalStateException("Recipe stage settlement already started");
            }
            synchronous = true;
        }

        private void requireAcquired() {
            requireOwner();
            if (!acquired) {
                throw new IllegalStateException("Recipe stage has not acquired requirements");
            }
        }

        private void requireOwner() {
            session.requireOwner();
            requireLive();
        }

        private void requireLive() {
            session.requireThread();
            var process = session.process;
            if (session.current != this || process.stageVersion != version || process.state != before && process.state != next) {
                throw new IllegalStateException("Stale recipe stage handle or state");
            }
        }

        /**
         * Original continued round's ready/entered/append cursors; source execution remains external.
         */
        private static final class NextBatchCapture {
            private final List<Stage> input;
            private final ResourceBatch batch;
            private final ObjectArrayList<Stage> ready = new ObjectArrayList<>();
            private final ObjectArrayList<Stage> entered = new ObjectArrayList<>();
            private final ObjectArrayList<ResourceReservation> tokens = new ObjectArrayList<>();
            private final ObjectArrayList<ReversibleTransaction> business = new ObjectArrayList<>();
            @Nullable
            private Stage exclusive;
            @Nullable
            private Throwable failure;
            private int phase;
            private int cursor;
            private int readyCursor;
            private int token;
            private int transaction;
            private int confirm;
            private boolean selected;
            private boolean enteredCurrent;
            private boolean supportsSequential = true;
            private boolean cancelled;
            private boolean complete;
            private boolean accepted = true;

            private NextBatchCapture(List<Stage> input, ResourceBatch batch) {
                this.input = input;
                this.batch = batch;
            }

            private void cancel() {
                cancelled = true;
            }

            private boolean advance(RecipeWorkBudget budget) {
                if (complete) return true;
                while (phase == 0 && cursor < input.size()) {
                    if (!budget.take()) return false;
                    var stage = input.get(cursor++);
                    stage.requireAcquired();
                    if (stage.batch != batch) throw new IllegalStateException("Recipe stage belongs to another batch");
                    stage.acquisition.attachFlatParent(batch);
                    if (stage.acquisition.activeAlternative()) {
                        if (exclusive != null)
                            throw new IllegalStateException("A batch cannot interleave unresolved candidates");
                        exclusive = stage;
                    }
                }
                if (phase == 0) {
                    cursor = 0;
                    phase = 1;
                }
                if (phase == 1) {
                    if (exclusive != null) {
                        if (!budget.take()) return false;
                        if (!exclusive.hasContinuation())
                            throw new IllegalStateException("Candidate compensation must finish before more acquisition");
                        ready.add(exclusive);
                    } else {
                        while (cursor < input.size()) {
                            if (!budget.take()) return false;
                            var stage = input.get(cursor++);
                            if (!stage.hasContinuation()) continue;
                            if (stage.acquisition.startsAlternative()) {
                                if (ready.isEmpty()) ready.add(stage);
                                break;
                            }
                            ready.add(stage);
                        }
                    }
                    if (ready.isEmpty()) throw new IllegalStateException("No continued stage remains");
                    cursor = 0;
                    phase = 2;
                }
                if (phase == 2 && !cancelled && !batch.acquiringNext()) {
                    while (cursor < ready.size()) {
                        if (!selected) {
                            if (!budget.take()) return false;
                            selected = true;
                        }
                        if (!ready.get(cursor).acquisition.prepareContinuationWithinBudget(batch, budget)) return false;
                        selected = false;
                        cursor++;
                    }
                }
                if (phase == 2) {
                    cursor = 0;
                    phase = 3;
                }
                if (phase == 3) {
                    if (cancelled) {
                        phase = 5;
                    } else {
                        try {
                            boolean scoped = batch.acquiringNext() ? batch.accessWithinBudget(() -> runReady(budget))
                                : batch.acquireNextWithinBudget(() -> runReady(budget));
                            if (!scoped || phase == 3) return false;
                            if (phase == 4) return false;
                        } catch (RuntimeException | Error observed) {
                            failure = observed;
                            phase = 5;
                            if (observed instanceof VirtualMachineError fatal) throw fatal;
                        }
                    }
                }
                if (phase == 4) {
                    if (cancelled) phase = 5;
                    else {
                        try {
                            if (!batch.accessWithinBudget(() -> runReady(budget)) || phase == 4) return false;
                        } catch (RuntimeException | Error observed) {
                            failure = observed;
                            phase = 5;
                            if (observed instanceof VirtualMachineError fatal) throw fatal;
                        }
                    }
                }
                while (phase == 5 && cursor < entered.size()) {
                    var stage = entered.get(cursor);
                    if ((cancelled || failure != null) && !stage.acquisition.finishCapturedInventoryWithinBudget(budget))
                        return false;
                    if (!stage.acquisition.inventoryComplete()) return false;
                    var reservations = stage.acquisition.stableStepReservations();
                    while (token < reservations.size()) {
                        if (!budget.take()) return false;
                        var reservation = reservations.get(token++);
                        tokens.add(reservation);
                        try {
                            supportsSequential &= reservation.supportsSequentialUse();
                        } catch (RuntimeException | Error observed) {
                            supportsSequential = false;
                            if (failure == null) failure = observed;
                            else suppress(failure, observed);
                            if (observed instanceof VirtualMachineError fatal) throw fatal;
                        }
                    }
                    var transactions = stage.acquisition.stableStepTransactions();
                    while (transaction < transactions.size()) {
                        if (!budget.take()) return false;
                        business.add(transactions.get(transaction++));
                    }
                    cursor++;
                    token = transaction = 0;
                }
                if (phase == 5) phase = 6;
                if (phase == 6) {
                    if (batch.acquiringNext() && !batch.appendWithinBudget(tokens, business, cancelled || failure != null || !supportsSequential, budget))
                        return false;
                    if (!supportsSequential && failure == null)
                        failure = new IllegalArgumentException("Continued storage reservations must support reversible sequential use");
                    phase = 7;
                }
                while (confirm < entered.size()) {
                    if (!budget.take()) return false;
                    var stage = entered.get(confirm++);

                    accepted &= stage.result() == ProcessResult.SUCCESS || stage.hasRejectedAlternative();
                }
                complete = true;
                return true;
            }

            private void runReady(RecipeWorkBudget budget) {
                phase = 4;
                while (readyCursor < ready.size()) {
                    var stage = ready.get(readyCursor);
                    if (!selected) {
                        if (!budget.take()) return;
                        selected = true;
                    }
                    if (!enteredCurrent) {
                        entered.add(stage);
                        enteredCurrent = true;
                        stage.acquireNextWithinBudget(budget);
                    } else if (stage.preparationPending()) stage.resumePreparedWithinBudget(budget);
                    if (stage.preparationPending()) return;
                    enteredCurrent = false;
                    selected = false;
                    readyCursor++;
                    if (stage.result() != ProcessResult.SUCCESS) break;
                }
                cursor = 0;
                phase = 5;
            }

            private boolean result() {
                if (!complete) throw new IllegalStateException("Continued round still owns inventory");
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                return accepted;
            }
        }

        /**
         * Only this original round's inventory, context proofs and partial claim are retained.
         */
        public static final class BatchCapture {
            private final List<Stage> input;
            private final List<? extends ReversibleTransaction> participants;
            private final ObjectArrayList<Stage> round = new ObjectArrayList<>();
            private final ReferenceOpenHashSet<Stage> unique = new ReferenceOpenHashSet<>();
            private final ObjectArrayList<ResourceReservation> tokens = new ObjectArrayList<>();
            private final ObjectArrayList<ReversibleTransaction> business = new ObjectArrayList<>();
            private final ObjectArrayList<StorageContext> contexts = new ObjectArrayList<>();
            @Nullable
            private ContextCapture contextCapture;
            @Nullable
            private ResourceBatch.Acquire acquire;
            @Nullable
            private ResourceBatch result;
            @Nullable
            private Stage selected;
            private int phase;
            private int stage;
            private int token;
            private int transaction;
            private int participant;
            private int context;
            private int publish;
            private boolean sequential;
            private boolean cancelled;
            private boolean cancellingClaim;
            private boolean complete;

            private BatchCapture(List<Stage> stages, List<? extends ReversibleTransaction> participants) {
                input = Objects.requireNonNull(stages);
                this.participants = Objects.requireNonNull(participants);
                if (input.isEmpty()) throw new IllegalArgumentException("Recipe round is empty");
            }

            public void cancel() {
                if (cancelled) return;
                cancelled = true;
                if (acquire != null && !cancellingClaim) {
                    acquire.cancel();
                    cancellingClaim = true;
                }
            }

            public boolean releaseCancelledClaimWithinBudget(RecipeWorkBudget budget) {
                cancel();
                if (acquire == null) return true;
                if (!acquire.advance(budget)) return false;
                acquire.result();
                acquire = null;
                cancellingClaim = false;
                return true;
            }

            public boolean advance(RecipeWorkBudget budget) {
                if (complete) return true;
                while (phase == 0 && stage < input.size()) {
                    if (selected == null) {
                        if (!budget.take()) return false;
                        selected = input.get(stage);
                        selected.requireAcquired();
                        if (!unique.add(selected) || selected.synchronous || selected.batch != null || !selected.acquisition.inventoryComplete())
                            throw new IllegalStateException("Recipe stage cannot transfer its acquisition");
                        round.add(selected);
                        sequential |= selected.acquisition.sequential();
                    }
                    var reservations = selected.acquisition.reservations();
                    while (token < reservations.size()) {
                        if (!budget.take()) return false;
                        tokens.add(reservations.get(token++));
                    }
                    var transactions = selected.acquisition.transactions();
                    while (transaction < transactions.size()) {
                        if (!budget.take()) return false;
                        business.add(transactions.get(transaction++));
                    }
                    selected = null;
                    token = transaction = 0;
                    stage++;
                }
                if (phase == 0) phase = 1;
                while (phase == 1 && participant < participants.size()) {
                    if (!budget.take()) return false;
                    business.add(Objects.requireNonNull(participants.get(participant++)));
                }
                if (phase == 1) {
                    phase = 2;
                    stage = 0;
                }
                if (phase == 2 && sequential && !cancelled) {
                    while (stage < round.size()) {
                        if (contextCapture == null) {
                            if (!budget.take()) return false;
                            contextCapture = round.get(stage).session.process.beginStorageContextCapture();
                        }
                        if (!contextCapture.advance(budget)) {
                            if (!contextCapture.current())
                                throw new IllegalStateException("Prepared storage bindings changed before batch claim");
                            return false;
                        }
                        var captured = contextCapture.result();
                        while (context < captured.size()) {
                            if (!budget.take()) return false;
                            contexts.add(captured.get(context++));
                        }
                        context = 0;
                        contextCapture = null;
                        stage++;
                    }
                }
                if (phase == 2) phase = 3;
                while (phase == 3) {
                    if (acquire == null)
                        acquire = sequential && !cancelled ? ResourceBatch.beginAcquireSequential(contexts, tokens, business) : ResourceBatch.beginAcquire(tokens, business);
                    if (!acquire.advance(budget)) return false;
                    result = acquire.result();
                    acquire = null;
                    if (cancellingClaim) {
                        cancellingClaim = false;
                        continue;
                    }
                    if (result == null) {
                        complete = true;
                        return true;
                    }
                    phase = 4;
                }
                while (publish < round.size()) {
                    if (!budget.take()) return false;
                    var target = round.get(publish++);
                    target.requireAcquired();
                    target.batch = result;
                    target.acquisition.attachFlatParent(Objects.requireNonNull(result));
                }
                complete = true;
                return true;
            }

            public @Nullable ResourceBatch result() {
                if (!complete) throw new IllegalStateException("Batch capture still owns a partial claim");
                return result;
            }
        }
    }

    /**
     * Progress is absolute scaled work ticks; successfulTicks is the unscaled deterministic clock.
     */
    public record State(Lifecycle lifecycle, double progress, long successfulTicks, ProcessResult result,
                        boolean started) {
        public State(Lifecycle lifecycle, double progress, long successfulTicks, ProcessResult result) {
            this(lifecycle, progress, successfulTicks, result, lifecycle == Lifecycle.RUNNING || lifecycle == Lifecycle.COMPLETED);
        }

        public State {
            Objects.requireNonNull(lifecycle);
            Objects.requireNonNull(result);
            if (!Double.isFinite(progress) || progress < 0 || successfulTicks < 0) {
                throw new IllegalArgumentException("Invalid process progress");
            }
            if (!started && (progress != 0 || successfulTicks != 0) || lifecycle == Lifecycle.NEW && started
                || (lifecycle == Lifecycle.RUNNING || lifecycle == Lifecycle.COMPLETED) && !started
                || (lifecycle == Lifecycle.FAILED || lifecycle == Lifecycle.CANCELLED) != (result instanceof ProcessResult.Failure)
                || lifecycle == Lifecycle.COMPLETED && result != ProcessResult.SUCCESS) {
                throw new IllegalArgumentException("Inconsistent process lifecycle and committed START state");
            }
        }
    }

    public record Tick(double progressBefore, double progressAfter, long indexBefore, long indexAfter) {
        public Tick {
            if (!Double.isFinite(progressBefore) || !Double.isFinite(progressAfter) || progressBefore < 0
                || progressAfter < progressBefore || indexBefore < 0 || indexAfter < indexBefore) {
                throw new IllegalArgumentException("Invalid frozen tick timeline");
            }
        }
    }

    /**
     * Same hatch/domain/structural-child superset, preserving every nested RequirementPath.
     */
    public final class ContextCapture {
        private final MachineHatches bindings = hatches();
        private final RequirementPath parent = activeRequirement;
        private final ObjectArrayList<StorageContext> contexts = new ObjectArrayList<>();
        private final ObjectArrayList<ContextFrame> frames = new ObjectArrayList<>();
        private int hatchCursor;
        private int side;
        private int energyCursor;
        private int rootCursor;
        private int phase;
        private boolean resourceEntered;

        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (!current()) return false;
            if (phase == 4) return true;
            if (phase == 0) {
                while (hatchCursor < bindings.resources().size()) {
                    if (!resourceEntered) {
                        if (!budget.take()) return false;
                        resourceEntered = true;
                        side = 0;
                    }
                    while (side < 2) {
                        if (!budget.take()) return false;
                        var hatch = bindings.resources().get(hatchCursor);
                        var storage = side++ == 0 ? hatch.input() : hatch.output();
                        if (storage != null) contexts.add(Objects.requireNonNull(storage.context()));
                    }
                    hatchCursor++;
                    resourceEntered = false;
                }
                phase = 1;
            }
            if (phase == 1) {
                while (energyCursor < bindings.energyInputs().size()) {
                    if (!budget.take()) return false;
                    contexts.add(Objects.requireNonNull(bindings.energyInputs().get(energyCursor++).context()));
                }
                energyCursor = 0;
                phase = 2;
            }
            if (phase == 2) {
                while (energyCursor < bindings.energyOutputs().size()) {
                    if (!budget.take()) return false;
                    contexts.add(Objects.requireNonNull(bindings.energyOutputs().get(energyCursor++).context()));
                }
                phase = 3;
            }
            while (!frames.isEmpty() || rootCursor < orderedRequirements.size()) {
                if (frames.isEmpty()) {
                    if (!budget.take()) return false;
                    var root = orderedRequirements.get(rootCursor++);
                    frames.add(new ContextFrame(root, root.path(parent)));
                }
                var frame = frames.getLast();
                if (frame.declared == null) {
                    if (!budget.take()) return false;
                    frame.declared = withRequirementPath(frame.path,
                        () -> Objects.requireNonNull(storageDeclarations(frame.requirement)).iterator());
                }
                while (frame.declared.hasNext()) {
                    if (!budget.take()) return false;
                    contexts.add(Objects.requireNonNull(frame.declared.next()));
                }
                if (frame.children == null) {
                    if (!budget.take()) return false;
                    frame.children = withRequirementPath(frame.path,
                        () -> Objects.requireNonNull(structuralChildren(frame.requirement)).iterator());
                }
                if (frame.children.hasNext()) {
                    if (!budget.take()) return false;
                    var child = Objects.requireNonNull(frame.children.next());
                    frames.add(new ContextFrame(child, child.path(frame.path)));
                    continue;
                }
                if (!budget.take()) return false;
                frames.removeLast();
            }
            phase = 4;
            return true;
        }

        public boolean current() {
            return hatches() == bindings;
        }

        public List<StorageContext> result() {
            if (phase != 4 || !current())
                throw new IllegalStateException("Storage context capture is incomplete or stale");
            return ObjectLists.unmodifiable(contexts);
        }
    }

    public final class ContinuationShape {
        private final List<RecipeRequirement<?>> candidates;
        private final RequirementPath parent;
        private final ObjectArrayList<ContextFrame> frames = new ObjectArrayList<>();
        private int root;
        private boolean checked;
        private boolean continued;
        private boolean done;

        private ContinuationShape(List<RecipeRequirement<?>> candidates, RequirementPath parent) {
            this.candidates = Objects.requireNonNull(candidates);
            this.parent = Objects.requireNonNull(parent);
        }

        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (done) return true;
            while (!frames.isEmpty() || root < candidates.size()) {
                if (frames.isEmpty()) {
                    if (!budget.take()) return false;
                    var candidate = Objects.requireNonNull(candidates.get(root++));
                    frames.add(new ContextFrame(candidate, candidate.path(parent)));
                    checked = false;
                }
                var frame = frames.getLast();
                if (!checked) {
                    if (!budget.take()) return false;
                    checked = true;
                    if (withPreparationContext(frame.path, () -> declaresContinuation(frame.requirement))) {
                        continued = true;
                        done = true;
                        return true;
                    }
                }
                if (frame.children == null) {
                    if (!budget.take()) return false;
                    frame.children = withPreparationContext(frame.path,
                        () -> Objects.requireNonNull(structuralChildren(frame.requirement)).iterator());
                }
                if (frame.children.hasNext()) {
                    if (!budget.take()) return false;
                    var child = Objects.requireNonNull(frame.children.next());
                    frames.add(new ContextFrame(child, child.path(frame.path)));
                    checked = false;
                } else {
                    if (!budget.take()) return false;
                    frames.removeLast();
                    checked = true; // A resumed parent already passed its declaration callback.
                }
            }
            done = true;
            return true;
        }

        public boolean usesContinuations() {
            if (!done) throw new IllegalStateException("Continuation shape has not completed");
            return continued;
        }
    }
}
