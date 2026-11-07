package com.circulation.prototype_eng.api.recipe.process;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.MachineRecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementContinuation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementAlternatives;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import com.circulation.prototype_eng.recipe.RecipeAbortCleanup;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.List;
import java.util.Collection;
import java.util.Comparator;
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
    @Nullable private final MachineHatches materialHatches;
    private final ComponentMap<RecipeProcess> components = new ComponentMap<>();
    private final MachineAttributes attributes;
    private State state = new State(Lifecycle.NEW, 0, 0, ProcessResult.SUCCESS);
    private boolean ticking;
    private int acquiredTransactions;
    private boolean persistenceFailure;
    private boolean probing;
    private long stageVersion;
    @Nullable
    private TickSession activeSession;
    @Nullable
    private MachineWorkSlots host;
    @Nullable
    private Tick activeTick;
    @Nullable
    private RequirementPath activeRequirement;

    public RecipeProcess(MachineInstance owner, MachineRecipe recipe, long seed) {
        this(owner, recipe, seed, ProbabilityTuning.defaultTuning());
    }

    public RecipeProcess(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning probabilityTuning) {
        this(owner, recipe, seed, probabilityTuning, null);
    }

    private RecipeProcess(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning probabilityTuning,
                          @Nullable MachineHatches materialHatches) {
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
        orderedRequirements = materialHatches == null ? captureRequirements(owner, recipe)
            : canonicalRequirements(recipe.orderedRequirements());
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

    /** Uses the canonical definition owned by a frozen material persistence binding. */
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
                if (supplied == null) { supplied = new ObjectArrayList<>(declared); }
                supplied.addAll(Objects.requireNonNull(provider.requirementsFor(recipe), "Machine recipe requirements"));
            }
        }
        var roots = canonicalRequirements(supplied == null ? declared : supplied);
        if (supplied == null) { return roots; }
        supplied.sort(Comparator.comparing(root -> root.type().id().toString()));
        return ObjectLists.unmodifiable(supplied);
    }

    private static List<RecipeRequirement<?>> canonicalRequirements(List<RecipeRequirement<?>> roots) {
        var types = new Object2ObjectOpenHashMap<ResourceLocation, RecipeRequirementType<?>>();
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        for (var root : roots) {
            Objects.requireNonNull(root, "Machine recipe requirement root");
            if (root != root.definition()) { throw new IllegalArgumentException("Machine recipe requirements contain an effective view"); }
            var previous = types.putIfAbsent(root.type().id(), root.type());
            if (previous != null && previous != root.type()) { throw new IllegalArgumentException("Different requirement types share id " + root.type().id()); }
            if (!ids.add(root.id())) { throw new IllegalArgumentException("Duplicate machine recipe requirement " + root.id()); }
        }
        return roots;
    }

    public MachineInstance owner() {
        return owner;
    }

    /** Transient attachment identity, never a persisted scheduler or world authority. */
    @Nullable
    public MachineWorkSlots host() { return host; }

    /** Host assembly boundary; a process may never occupy two work slots or hosts. */
    public void attachTo(MachineWorkSlots newHost) {
        requirePersistenceBoundary();
        if (Objects.requireNonNull(newHost).owner() != owner || host != null) {
            throw new IllegalStateException("Process already attached or belongs to a different machine");
        }
        host = newHost;
    }

    public void detachFrom(MachineWorkSlots previousHost) {
        requirePersistenceBoundary();
        if (host == null || host != previousHost) { throw new IllegalStateException("Process is not attached to the expected host"); }
        host = null;
    }

    public MachineRecipe recipe() {
        return recipe;
    }

    /** Ordinary processes observe current machine bindings; material cycles retain their capture. */
    public MachineHatches hatches() { return materialHatches == null ? MachineHatches.get(owner) : materialHatches; }

    /** Persistence hosts must restore material cycles through their corresponding factory. */
    @SuppressWarnings("unused") // External process codecs distinguish this explicitly restored clock.
    public boolean isMaterialCycle() { return materialHatches != null; }

    /** Frozen canonical roots from the recipe and its actual machine component providers. */
    public List<RecipeRequirement<?>> orderedRequirements() { return orderedRequirements; }

    /** Downstream mods can retain the seed when restoring deterministic process state. */
    @SuppressWarnings("unused")
    public long seed() {
        return seed;
    }

    public ComponentMap<RecipeProcess> components() {
        return components;
    }

    /** Process-local bases/modifiers overlay the live machine baseline without mutating it. */
    public MachineAttributes attributes() {
        return attributes;
    }

    /** Frozen per process so a global tuning change cannot reroll a blocked phase. */
    public ProbabilityTuning probabilityTuning() {
        return probabilityTuning;
    }

    public State state() {
        return state;
    }

    /** Only complete, quiescent stage boundaries may be serialized; no live transaction is resumable. */
    public void requirePersistenceBoundary() {
        requireQuiescent();
        if (persistenceFailure) {
            throw new IllegalStateException("Process is executing, has acquired transactions, or failed terminal cleanup");
        }
    }

    /** Rejects live work; unlike persistence this permits observing a faulted but inactive process. */
    public void requireQuiescent() {
        if (host != null) { host.requireProcessBoundary(this); }
        if (!quiescent()) {
            throw new IllegalStateException("Process is executing or has acquired transactions");
        }
    }

    /** Local inactivity proof; the host/scheduler may still own an enclosing batch lease. */
    public boolean quiescent() {
        return !ticking && !probing && activeSession == null && activeTick == null && activeRequirement == null && acquiredTransactions == 0;
    }

    /** True only for an explicit admission probe and its captured acquisition/cleanup callbacks. */
    public boolean probing() { return probing; }

    /** Independent START/TICK/END probes never grant authority to perform ordinary network work. */
    public <T> T withProbeContext(Supplier<T> action) {
        Objects.requireNonNull(action);
        requirePersistenceBoundary();
        if (host != null || state.lifecycle() != Lifecycle.NEW) { throw new IllegalArgumentException("Probe context requires an unattached NEW process"); }
        probing = true;
        try { return action.get(); }
        finally { probing = false; }
    }

    /** Local save eligibility, not authority to bypass an enclosing work-slot or machine lease. */
    public boolean persistenceReady() { return quiescent() && !persistenceFailure; }

    /** Framework failure marker for an exceptional acquisition/cleanup outside tick(). */
    public void invalidatePersistenceBoundary() { persistenceFailure = true; }

    public boolean cancel() { return cancel(RecipeText.Cancelled.failure()); }

    /** Explicit terminal cancellation at an exclusive, transaction-free boundary; no END or refund. */
    public boolean cancel(ProcessResult.Failure reason) {
        return host == null ? cancelFrom(null, reason) : host.cancel(this, reason);
    }

    /** Work-slot assembly boundary. Hosted callers use cancel(), which coordinates the slot result. */
    public boolean cancelFrom(@Nullable MachineWorkSlots expectedHost, ProcessResult.Failure reason) {
        Objects.requireNonNull(reason);
        if (host != expectedHost) { throw new IllegalArgumentException("Process belongs to a different cancellation host"); }
        requirePersistenceBoundary();
        if (state.lifecycle().isTerminal()) { return false; }
        ticking = true;
        try {
            state = new State(Lifecycle.CANCELLED, state.progress(), state.successfulTicks(), reason, state.started());
            RecipeAbortCleanup.run(this);
            return true;
        } catch (RuntimeException | Error failure) {
            persistenceFailure = true;
            throw failure;
        } finally {
            ticking = false;
        }
    }

    /** Constructs an unpublished restored process. Its components still require restoration and validation. */
    public static RecipeProcess restored(MachineInstance owner, MachineRecipe recipe, long seed,
                                          ProbabilityTuning tuning, State state) {
        Objects.requireNonNull(state);
        if (state.lifecycle() == Lifecycle.COMPLETED && state.progress() < recipe.durationTicks()) {
            throw new IllegalArgumentException("Completed process has insufficient work progress");
        }
        var process = new RecipeProcess(owner, recipe, seed, tuning);
        process.state = state;
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

    private boolean workComplete() {
        return materialHatches == null ? state.progress() >= recipe.durationTicks() : state.successfulTicks() >= 1;
    }

    /** Framework capture of the caller's optional timeline, without inventing a raw stage tick. */
    public @Nullable Tick captureTickContext() { return activeTick; }

    /** Frozen scaled/unscaled timeline for requirements executing the current TICK stage. */
    public Tick tickContext() {
        return Objects.requireNonNull(activeTick, "No TICK stage is executing");
    }

    /** The executing definition's stable path, including checkpoint/selection wrapper ancestry. */
    public RequirementPath requirementPath() {
        return Objects.requireNonNull(activeRequirement, "No requirement callback is executing");
    }

    /** Framework boundary used by RecipeRequirement.acquire; all returned callbacks retain this scope. */
    public RequirementTransaction acquireRequirement(RecipeRequirement<?> requirement, Supplier<RequirementTransaction> acquisition) {
        requireAcquisitionBoundary();
        if (state.lifecycle().isTerminal()) { throw new IllegalStateException("Cannot acquire requirements on a terminal process"); }
        var path = requirement.path(activeRequirement);
        var transaction = withRequirementPath(path, () -> Objects.requireNonNull(acquisition.get(), "requirement transaction"));
        if (transaction == RequirementTransaction.SUCCESS) { return transaction; }
        acquiredTransactions = Math.incrementExact(acquiredTransactions);
        return new ScopedRequirement(this, path, transaction);
    }

    /** Exact framework continuation while a synchronous current stage owns the process callback. */
    public void acquireContinuation(RecipeStageExecutor.Acquisition expected) {
        var session = activeSession;
        if (session == null) { expected.acquireNext(); return; }
        var current = Objects.requireNonNull(session.current, "No current stage owns this continuation");
        current.requireLive();
        if (current.acquisition != expected || !session.executing) {
            throw new IllegalStateException("Continuation does not belong to the executing stage");
        }
        boolean previous = current.acquiring;
        current.acquiring = true;
        try { expected.acquireNext(); }
        finally { current.acquiring = previous; }
    }

    /** Captured hatches plus explicit external domains, collected before a sequence claims its fence. */
    public List<StorageContext> declaredStorageContexts() {
        var contexts = new ObjectArrayList<StorageContext>();
        var bindings = hatches();
        for (var hatch : bindings.resources()) {
            var input = hatch.input();
            var output = hatch.output();
            if (input != null) { contexts.add(input.context()); }
            if (output != null) { contexts.add(output.context()); }
        }
        for (var input : bindings.energyInputs()) { contexts.add(input.context()); }
        for (var output : bindings.energyOutputs()) { contexts.add(output.context()); }
        for (var requirement : orderedRequirements()) { collectStorageContexts(requirement, contexts); }
        return contexts;
    }

    private <C> void collectStorageContexts(RecipeRequirement<C> requirement, List<StorageContext> contexts) {
        withRequirementPath(requirement.path(activeRequirement), () -> {
            contexts.addAll(Objects.requireNonNull(requirement.type().system().storageContexts(this, requirement)));
            for (var child : requirement.type().system().children(requirement.payload())) { collectStorageContexts(child, contexts); }
            return null;
        });
    }

    /** Raw framework acquisitions must not bypass the current stage of a captured work-slot batch. */
    public void requireAcquisitionBoundary() {
        if (host == null || host.pendingTickBatch() == null) { return; }
        host.requireProcessOperation(this);
        if (activeSession == null || activeSession.current == null || !activeSession.current.acquiring) {
            throw new IllegalStateException("Work-slot batch requires acquisition through its current stage");
        }
    }

    private <T> T withCallbackScope(Supplier<T> action) {
        return host == null ? action.get() : host.withProcessCallback(this, action);
    }

    /** Requirement cancellation uses the same parent/child scope without acquiring a new transaction. */
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

    private static final class ScopedRequirement implements RequirementTransaction {
        private final RecipeProcess process;
        private final RequirementPath path;
        private final RequirementTransaction delegate;
        @Nullable private final Tick tick;
        private final boolean probe;
        @Nullable private Resolution intendedTerminal;
        private Resolution terminal = Resolution.UNKNOWN;
        @Nullable private RequirementContinuation continuation;
        private boolean continuationRead;
        private boolean continuing;
        private boolean preparationAttempted;
        private boolean preparationAccepted;
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

        @Override public ProcessResult result() { return scoped(delegate::result); }
        @Override public List<? extends ResourceReservation> reservations() {
            return scoped(delegate::reservations);
        }
        @Override public @Nullable RequirementContinuation continuation() {
            if (!continuationRead) {
                var tail = scoped(delegate::continuation);
                continuationRead = true;
                if (tail instanceof RequirementAlternatives alternatives) {
                    continuation = new RequirementAlternatives() {
                        @Override public boolean startsAlternative() { return scoped(alternatives::startsAlternative); }
                        @Override public void rejectAlternative(ProcessResult rejected) {
                            scoped(() -> alternatives.rejectAlternative(rejected));
                        }
                        @Override public @Nullable RequirementTransaction acquireNext() { return advanceContinuation(tail); }
                    };
                } else if (tail != null) { continuation = () -> advanceContinuation(tail); }
            }
            return continuation;
        }
        private @Nullable RequirementTransaction advanceContinuation(RequirementContinuation tail) {
            if (!commitAttempted || closed || intendedTerminal != null || continuing) {
                throw new IllegalStateException("Requirement continuation is not at a staged acquisition boundary");
            }
            continuing = true;
            try { return scoped(tail::acquireNext); }
            finally { continuing = false; }
        }

        @Override public boolean prepareCommit() {
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
            } finally { preparing = false; }
        }
        @Override public void commit() {
            if (closed || intendedTerminal != null || commitAttempted || queryExecuting || preparing || validating
                || preparationAttempted && !preparationAccepted) {
                throw new IllegalStateException("Cannot replay or commit a resolving requirement transaction");
            }
            commitAttempted = true;
            try { scoped(delegate::commit); }
            catch (RuntimeException | Error failure) { process.persistenceFailure = true; throw failure; }
        }
        @Override public void complete() { close(Resolution.COMPLETED, delegate::complete); }
        @Override public void rollback() { close(Resolution.ROLLED_BACK, delegate::rollback); }

        @Override public boolean validateCommit() {
            if (closed || intendedTerminal != null || !commitAttempted || validationAttempted || queryExecuting || preparing) {
                throw new IllegalStateException("Cannot validate uncommitted or resolving requirement work");
            }
            validationAttempted = true;
            validating = true;
            try { return scoped(delegate::validateCommit); }
            catch (RuntimeException | Error failure) { process.persistenceFailure = true; throw failure; }
            finally { validating = false; }
        }

        @Override public Resolution resolution() {
            if (terminalExecuting || queryExecuting || preparing || validating) { throw new IllegalStateException("Cannot reenter a requirement recovery callback"); }
            if (closed) { return terminal; }
            queryExecuting = true;
            try {
                var actual = Objects.requireNonNull(scoped(delegate::resolution));
                if (uncertain && actual == intendedTerminal) { confirmTerminal(actual); }
                return actual;
            } finally { queryExecuting = false; }
        }

        private void close(Resolution expected, Runnable action) {
            if (terminalExecuting || queryExecuting || preparing || validating) { throw new IllegalStateException("Cannot reenter a requirement terminal callback"); }
            if (closed) {
                if (terminal != expected) { throw new IllegalStateException("Requirement already resolved in the opposite direction"); }
                return;
            }
            if (intendedTerminal != null && intendedTerminal != expected) {
                throw new IllegalStateException("Cannot change a requirement's terminal decision");
            }
            if (uncertain) {
                var actual = resolution();
                if (closed) { return; }
                if (actual != Resolution.REVERSIBLE) {
                    throw new IllegalStateException("Cannot retry a requirement without an exact recovery proof");
                }
            }
            intendedTerminal = expected;
            try {
                terminalExecuting = true;
                try { scoped(action); }
                finally { terminalExecuting = false; }
                confirmTerminal(expected);
            } catch (RuntimeException | Error failure) {
                uncertain = true;
                process.persistenceFailure = true;
                try { resolution(); }
                catch (RuntimeException | Error query) { if (query != failure) { failure.addSuppressed(query); } }
                throw failure;
            }
        }

        private void confirmTerminal(Resolution actual) {
            if (closed) { return; }
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
                try { return process.withRequirementPath(path, action); }
                finally { process.activeTick = previous; process.probing = previousProbe; }
            });
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
        if (session == null) { return state.result(); }
        try {
            Stage stage;
            while ((stage = session.nextStage()) != null) {
                try { stage.acquire(); }
                catch (RuntimeException | Error failure) {
                    try { stage.rollbackSynchronously(); }
                    catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
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
        if (state.lifecycle().isTerminal() || workPaused()) { return null; }
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
        } finally { session.executing = false; }
    }

    /** Recovery owner access after an ordinary tick threw with unconfirmed cleanup. */
    @Nullable
    public TickSession pendingTick() { return activeSession; }

    /** One owner-thread component dispatch and a cursor that permits at most one TICK attempt. */
    public static final class TickSession {
        private final RecipeProcess process;
        private final Thread ownerThread = Thread.currentThread();
        @Nullable private final MachineWorkSlots host;
        @Nullable private ComponentMap.TickSession<RecipeProcess> callbacks;
        @Nullable private Stage current;
        @Nullable private Throwable failure;
        private boolean executing;
        private boolean tickAttempted;
        private boolean stopped;
        private boolean postDone;

        private TickSession(RecipeProcess process) {
            this.process = process;
            host = process.host;
        }

        /** Creates the lease before acquisition, so even an empty stage prevents reentry/save. */
        @Nullable
        public Stage nextStage() {
            requireOwner();
            if (current != null) { throw new IllegalStateException("Current recipe stage is still leased"); }
            if (stopped || process.workPaused() || process.state.lifecycle().isTerminal()) { return null; }
            RecipeStage phase;
            if (process.state.lifecycle() == Lifecycle.NEW) { phase = RecipeStage.START; }
            else if (process.workComplete()) { phase = RecipeStage.END; }
            else if (!tickAttempted) { phase = RecipeStage.TICK; }
            else { return null; }
            process.stageVersion = Math.incrementExact(process.stageVersion);
            current = new Stage(this, phase, process.stageVersion);
            if (phase == RecipeStage.TICK) { tickAttempted = true; }
            return current;
        }

        /** The retained stage remains available for exact cleanup after acquisition/body failure. */
        @Nullable
        public Stage currentStage() { requireOwner(); return current; }

        /** Stops later rounds and retains the original fault, including faults from source lanes. */
        public void recordFailure(Throwable cause) {
            requireThread();
            Objects.requireNonNull(cause);
            if (failure == null) { failure = cause; }
            else { suppress(failure, cause); }
            stopped = true;
            process.persistenceFailure = true;
        }

        /** Finishes planned post only after the current lease was accurately settled. */
        public void finish() {
            requireOwner();
            if (process.host != null) { process.host.requireProcessBoundary(process); }
            if (current != null) { throw new IllegalStateException("Cannot finish an unsettled recipe tick"); }
            finishSynchronousBody();
        }

        /** Actual post entry proof, including a throwing post; rejection before entry remains false. */
        public boolean postStarted() { return postDone; }

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
                if (current == null) { process.activeSession = null; }
            }
        }

        private void requireThread() {
            if (Thread.currentThread() != ownerThread || process.activeSession != this || process.host != host) {
                throw new IllegalStateException("Stale recipe tick session or incorrect owner");
            }
            if (host != null) { host.requireProcessOperation(process); }
        }

        private void requireOwner() {
            requireThread();
            if (executing) { throw new IllegalStateException("Recipe tick callback is executing"); }
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
        private final State next;
        @Nullable private final Tick tick;
        private final RecipeStageExecutor.Acquisition acquisition;
        @Nullable private ResourceBatch batch;
        private boolean acquired;
        private boolean acquiring;
        private boolean closed;
        private boolean synchronous;
        private ReversibleTransaction.Resolution transition = ReversibleTransaction.Resolution.REVERSIBLE;

        private Stage(TickSession session, RecipeStage phase, long version) {
            this.session = session;
            this.phase = phase;
            this.version = version;
            var process = session.process;
            before = process.state;
            next = switch (phase) {
                case START -> new State(Lifecycle.RUNNING, before.progress(), before.successfulTicks(), ProcessResult.SUCCESS);
                case TICK -> new State(Lifecycle.RUNNING,
                    before.progress() + Math.max(0, process.attributes.value(StandardMachineAttributes.PROCESS_SPEED, 1)),
                    Math.incrementExact(before.successfulTicks()), ProcessResult.SUCCESS);
                case END -> new State(Lifecycle.COMPLETED, before.progress(), before.successfulTicks(), ProcessResult.SUCCESS);
            };
            tick = phase == RecipeStage.TICK ? new Tick(before.progress(), next.progress(),
                before.successfulTicks(), next.successfulTicks()) : null;
            acquisition = RecipeStageExecutor.prepare(process, phase, new RequirementTransaction() {
                @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                @Override public void commit() {
                    requireLive();
                    if (session.failure != null || acquisition.failure() != null || acquisition.result() != ProcessResult.SUCCESS
                        || transition != Resolution.REVERSIBLE || process.state != before) {
                        throw new IllegalStateException("Cannot commit a rejected or stale recipe stage");
                    }
                    process.state = next;
                }
                @Override public void complete() {
                    requireLive();
                    if (process.state != next || transition != Resolution.REVERSIBLE) {
                        throw new IllegalStateException("Recipe transition did not commit");
                    }
                    transition = Resolution.COMPLETED;
                }
                @Override public void rollback() {
                    requireLive();
                    if (transition != Resolution.REVERSIBLE) { throw new IllegalStateException("Recipe transition is terminal"); }
                    process.state = before;
                    transition = Resolution.ROLLED_BACK;
                }
                @Override public Resolution resolution() { requireLive(); return transition; }
            });
        }

        public RecipeStage phase() { return phase; }

        /** Exact local lease closure; the enclosing tick still owns its post obligation. */
        public boolean settled() { return closed; }

        /** Known complete inventory that can transfer to a source batch without guessing on failure. */
        public boolean transferable() {
            requireOwner();
            return acquired && !synchronous && batch == null && acquisition.inventoryComplete();
        }

        /** Stops before acquisition without invoking requirements, for cancellation/retirement. */
        public void discard() {
            requireOwner();
            if (acquired) { throw new IllegalStateException("Acquired recipe stage must settle its participants"); }
            session.stopped = true;
            session.current = null;
            closed = true;
        }

        /** A throwing acquire still leaves this handle owning its partial acquisition. */
        public void acquire() {
            requireOwner();
            if (acquired) { throw new IllegalStateException("Recipe stage was already acquired"); }
            acquired = true;
            acquiring = true;
            session.executing = true;
            var process = session.process;
            Tick previous = process.activeTick;
            process.activeTick = tick;
            try { process.withCallbackScope(() -> { acquisition.acquire(); return null; }); }
            catch (RuntimeException | Error failure) { session.recordFailure(failure); throw failure; }
            finally { process.activeTick = previous; acquiring = false; session.executing = false; }
        }

        public ProcessResult result() { requireAcquired(); return acquisition.result(); }
        public boolean hasContinuation() { requireAcquired(); return acquisition.hasContinuation(); }
        public boolean hasRejectedAlternative() { requireAcquired(); return acquisition.hasRejectedAlternative(); }
        public ResourceBatch.Savepoint rejectedSavepoint() { requireAcquired(); return acquisition.rejectedSavepoint(); }
        public boolean finishAlternativeRollback(ResourceBatch expected) {
            requireAcquired();
            if (batch != expected) { throw new IllegalStateException("Candidate belongs to another batch"); }
            return acquisition.finishAlternativeRollback(expected);
        }

        private void acquireNext() {
            requireAcquired();
            acquiring = true;
            session.executing = true;
            Tick previous = session.process.activeTick;
            session.process.activeTick = tick;
            try { session.process.withCallbackScope(() -> { acquisition.acquireNext(); return null; }); }
            catch (RuntimeException | Error failure) { session.recordFailure(failure); throw failure; }
            finally { session.process.activeTick = previous; acquiring = false; session.executing = false; }
        }

        /**
         * Continues a bound round under the same fence after the preceding business effects were
         * staged. Every entered acquisition is adopted even after a fault, if its inventory is known.
         * An unknown inventory leaves the batch's acquisition window open and prevents release.
         */
        public static boolean acquireNextBatch(List<Stage> stages, ResourceBatch batch) {
            var ready = new ObjectArrayList<Stage>();
            Stage exclusive = null;
            for (var stage : stages) {
                stage.requireAcquired();
                if (stage.batch != batch) { throw new IllegalStateException("Recipe stage belongs to another batch"); }
                if (stage.acquisition.activeAlternative()) {
                    if (exclusive != null) { throw new IllegalStateException("A batch cannot interleave unresolved candidates"); }
                    exclusive = stage;
                }
            }
            if (exclusive != null) {
                if (!exclusive.hasContinuation()) { throw new IllegalStateException("Candidate compensation must finish before more acquisition"); }
                ready.add(exclusive);
            } else {
                for (var stage : stages) {
                    if (!stage.hasContinuation()) { continue; }
                    if (stage.acquisition.startsAlternative()) {
                        if (ready.isEmpty()) { ready.add(stage); }
                        break;
                    }
                    ready.add(stage);
                }
            }
            if (ready.isEmpty()) { throw new IllegalStateException("No continued stage remains"); }
            // An unresolved candidate owns a contiguous suffix; ordinary continuations can still
            // share the same round and dispatch independent sources in parallel.
            for (var stage : ready) { stage.acquisition.prepareContinuation(batch); }
            var entered = new ObjectArrayList<Stage>();
            Throwable failure = null;
            try {
                batch.acquireNext(() -> {
                    for (var stage : ready) {
                        entered.add(stage);
                        stage.acquireNext();
                        if (stage.result() != ProcessResult.SUCCESS) { break; }
                    }
                });
            } catch (RuntimeException | Error acquisitionFailure) { failure = acquisitionFailure; }
            var tokens = new ObjectArrayList<ResourceReservation>();
            var business = new ObjectArrayList<ReversibleTransaction>();
            boolean known = true;
            for (var stage : entered) {
                known &= stage.acquisition.inventoryComplete();
                tokens.addAll(stage.acquisition.stepReservations());
                business.addAll(stage.acquisition.stepTransactions());
            }
            if (known) {
                try {
                    if (tokens.stream().allMatch(ResourceReservation::supportsSequentialUse)) { batch.append(tokens, business); }
                    else {
                        batch.appendForRollback(tokens, business);
                        throw new IllegalArgumentException("Continued storage reservations must support reversible sequential use");
                    }
                } catch (RuntimeException | Error adoptionFailure) {
                    if (failure == null) { failure = adoptionFailure; }
                    else { suppress(failure, adoptionFailure); }
                }
            }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
            return entered.stream().allMatch(stage -> stage.result() == ProcessResult.SUCCESS || stage.hasRejectedAlternative());
        }

        /** Preserves ResourceTransactions' all-domain lock and terminal notification semantics. */
        public ProcessResult settleSynchronously() {
            requireSynchronousStart();
            session.executing = true;
            try { return session.process.withCallbackScope(acquisition::settleSynchronously); }
            catch (RuntimeException | Error failure) { session.recordFailure(failure); throw failure; }
            finally { finishSynchronousAttempt(); }
        }

        /** Rolls back a rejected/abandoned or partially acquired stage without replaying commit. */
        public void rollbackSynchronously() {
            requireSynchronousStart();
            session.stopped = true;
            session.executing = true;
            try { session.process.withCallbackScope(() -> { acquisition.rollbackSynchronously(); return null; }); }
            catch (RuntimeException | Error failure) { session.recordFailure(failure); throw failure; }
            finally { finishSynchronousAttempt(); }
        }

        /** Retries only terminal cleanup proven safe by the exact synchronous participants. */
        public boolean resolveSynchronously() {
            requireAcquired();
            if (!synchronous || batch != null) { throw new IllegalStateException("No synchronous settlement to recover"); }
            session.executing = true;
            try { session.process.withCallbackScope(acquisition::resolveSynchronously); return acquisition.resolved(); }
            catch (RuntimeException | Error failure) { session.recordFailure(failure); throw failure; }
            finally { finishSynchronousAttempt(); }
        }

        private void finishSynchronousAttempt() {
            try {
                if (acquisition.resolved()) {
                    session.process.withCallbackScope(() -> { settled(transition == ReversibleTransaction.Resolution.COMPLETED); return null; });
                }
            } catch (RuntimeException | Error cleanup) {
                session.recordFailure(cleanup);
                throw cleanup;
            } finally { session.executing = false; }
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
            if (round.isEmpty()) { throw new IllegalArgumentException("Recipe round is empty"); }
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
                for (var stage : round) { contexts.addAll(stage.session.process.declaredStorageContexts()); }
            }
            for (var stage : round) { stage.session.executing = true; }
            try {
                var bound = sequential ? ResourceBatch.tryAcquireSequential(contexts, reservations, transactions)
                    : ResourceBatch.tryAcquire(reservations, transactions);
                if (bound != null) { for (var stage : round) { stage.batch = bound; } }
                return bound;
            } finally { for (var stage : round) { stage.session.executing = false; } }
        }

        /**
         * Acknowledges only this handle's bound batch, after all actions exited and isolation was
         * released. False leaves the stage/post obligation pinned. Pass any source/owner/finish fault
         * so a terminal notification failure cannot silently permit later stages or persistence.
         */
        public boolean settleBatch(@Nullable Throwable failure) {
            requireAcquired();
            var bound = Objects.requireNonNull(batch, "Recipe stage has no bound batch");
            if (failure != null) { session.recordFailure(failure); }
            if (!bound.finished()) { return false; }
            session.executing = true;
            try {
                session.process.withCallbackScope(() -> { settled(bound.decision() == ResourceBatch.Decision.COMPLETE); return null; });
                return true;
            }
            catch (RuntimeException | Error cleanup) { session.recordFailure(cleanup); throw cleanup; }
            finally { session.executing = false; }
        }

        private void settled(boolean committed) {
            var process = session.process;
            session.current = null;
            closed = true;
            if (!committed) { session.stopped = true; }
            if (session.failure != null) { return; }
            ProcessResult result = acquisition.result();
            if (!committed && result == ProcessResult.SUCCESS) { result = RecipeText.StorageChanged.blocked(); }
            if (result != ProcessResult.SUCCESS) {
                session.stopped = true;
                process.state = new State(result instanceof ProcessResult.Failure ? Lifecycle.FAILED : before.lifecycle(),
                    before.progress(), before.successfulTicks(), result, before.started());
                if (result instanceof ProcessResult.Failure) { RecipeAbortCleanup.run(process); }
            }
        }

        private void requireSynchronousStart() {
            requireAcquired();
            if (synchronous || batch != null) { throw new IllegalStateException("Recipe stage settlement already started"); }
            synchronous = true;
        }

        private void requireAcquired() {
            requireOwner();
            if (!acquired) { throw new IllegalStateException("Recipe stage has not acquired requirements"); }
        }

        private void requireOwner() { session.requireOwner(); requireLive(); }

        private void requireLive() {
            session.requireThread();
            var process = session.process;
            if (session.current != this || process.stageVersion != version || process.state != before && process.state != next) {
                throw new IllegalStateException("Stale recipe stage handle or state");
            }
        }
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary != secondary) { primary.addSuppressed(secondary); }
    }

    private boolean workPaused() {
        var lifecycle = owner.lifecycle();
        return lifecycle != null && lifecycle.retired() || !owner.recipeWorkAllowed();
    }

    public enum Lifecycle {
        NEW, RUNNING, COMPLETED, FAILED, CANCELLED;

        public boolean isTerminal() { return this == COMPLETED || this == FAILED || this == CANCELLED; }
    }

    /** Progress is absolute scaled work ticks; successfulTicks is the unscaled deterministic clock. */
    public record State(Lifecycle lifecycle, double progress, long successfulTicks, ProcessResult result, boolean started) {
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
     * Repeatable phase/context selection, matching PM's seed XOR salt convention.
     * Downstream requirement implementations use a salt to isolate their random choices.
     */
    @SuppressWarnings("unused")
    public Random random(String salt) {
        return new Random(seed ^ salt.hashCode());
    }
}
