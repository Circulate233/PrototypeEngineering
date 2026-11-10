package com.circulation.prototype_eng.api.machine.lifecycle;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.structure.StructureAssembly;
import com.circulation.prototype_eng.api.structure.StructureCheckPolicy;
import com.circulation.prototype_eng.api.structure.StructureComponents;
import com.circulation.prototype_eng.api.structure.StructureMatch;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Server-side machine boundaries migrated from PM. Attach once, after world attachment and before
 * scheduling. Restore all component NBT before load(RESTORED); register scheduling only after load
 * succeeds. The scheduler must join its workers before changing structure, unloading or saving.
 * The shared gate rejects overlap and publishes ownership transfers; it does not join workers,
 * validate PN permissions, provide world snapshots or perform planned compensation.
 */
public final class MachineLifecycle {
    private static final int IDLE = 0;
    private static final int RUNNING = 1;
    private static final int TRANSITION = 2;
    private static final int ASSEMBLING = 3;
    private static final int PERSISTING = 4;
    private static final VarHandle GATE;

    static {
        try {
            GATE = MethodHandles.lookup().findVarHandle(MachineLifecycle.class, "gate", int.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final MachineInstance machine;
    private final MachineLifecycleHost host;
    private final MachineIdentity identity;
    private final MachineLifecycleRegistry registry;
    @SuppressWarnings("FieldMayBeFinal") // Mutated by the shared VarHandle at ownership boundaries.
    private volatile int gate = IDLE;
    private volatile State state = State.CREATED;
    private volatile boolean formed;
    private volatile boolean structureReady = true;
    private long formationGeneration;
    private volatile boolean broken;
    private volatile boolean faulted;
    private volatile boolean retired;
    @Nullable
    private volatile StructureMatch structureMatch;
    @Nullable
    private Captured lastCaptured;
    @Nullable
    private ScheduledExecution scheduledExecution;
    private boolean assemblyStarted;

    @SuppressWarnings("unused") // Public controller entry using the shared lifecycle registry.
    public MachineLifecycle(MachineInstance machine, MachineLifecycleHost host) {
        this(machine, host, MachineLifecycleRegistry.GLOBAL);
    }

    public MachineLifecycle(MachineInstance machine, MachineLifecycleHost host, MachineLifecycleRegistry registry) {
        this.machine = Objects.requireNonNull(machine);
        this.host = Objects.requireNonNull(host);
        this.registry = Objects.requireNonNull(registry);
        host.requireServerThread();
        identity = Objects.requireNonNull(host.identity());
        var type = machine.type();
        if (type != null && !type.id().equals(identity.machineType())) {
            throw new IllegalArgumentException("Machine declaration does not match its attached host identity");
        }
        machine.attachLifecycle(this);
    }

    private static void requireQuiescent(Captured captured) {
        for (var slots : captured.workSlots()) {
            slots.requireQuiescent();
        }
    }

    private static void publish(MachineLifecycleEvent event, List<MachineLifecycleListener> listeners) {
        for (var listener : listeners) {
            invoke(event, listener);
        }
    }

    @Nullable
    private static Throwable publishAll(MachineLifecycleEvent event, List<MachineLifecycleListener> listeners, @Nullable Throwable failure) {
        for (var listener : listeners) {
            try {
                invoke(event, listener);
            } catch (RuntimeException | Error cleanup) {
                failure = append(failure, cleanup);
            }
        }
        return failure;
    }

    private static void invoke(MachineLifecycleEvent event, MachineLifecycleListener listener) {
        switch (event) {
            case MachineLifecycleEvent.Loaded loaded -> listener.onLoaded(loaded);
            case MachineLifecycleEvent.Unloaded unloaded -> listener.onUnloaded(unloaded);
            case MachineLifecycleEvent.FormedChanged formed -> listener.onFormedChanged(formed);
            case MachineLifecycleEvent.ControllerBroken broken -> listener.onControllerBroken(broken);
        }
    }

    private static Throwable append(@Nullable Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (primary != additional) {
            primary.addSuppressed(additional);
        }
        return primary;
    }

    private static void throwFailure(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    public MachineInstance machine() {
        return machine;
    }

    public MachineLifecycleHost host() {
        return host;
    }

    public State state() {
        return state;
    }

    public boolean formed() {
        return formed;
    }

    /**
     * Persistent count of successful false-to-true formations; refresh within a formation keeps it.
     */
    public long formationGeneration() {
        return formationGeneration;
    }

    /**
     * Restore only during unpublished assembly; ports and formed authority are rebuilt afterward.
     */
    public void restoreFormationGeneration(long generation) {
        host.requireServerThread();
        if (state != State.CREATED || (int) GATE.getAcquire(this) != ASSEMBLING || generation < 0) {
            throw new IllegalStateException("Formation generation restore requires unpublished assembly and a nonnegative value");
        }
        formationGeneration = generation;
    }

    /**
     * Revokes new execution immediately; owner cleanup still waits for the scheduler's real join.
     */
    public void retire() {
        retired = true;
    }

    public boolean retired() {
        return retired;
    }

    @Nullable
    public StructureMatch structureMatch() {
        return structureMatch;
    }

    /**
     * Readiness for ordinary recipe systems, not a transferable worker permission.
     */
    public boolean processingAllowed() {
        return formed && structureReady && schedulingAllowed();
    }

    /** Temporary inconclusive checks preserve accepted formation, components and every work slot. */
    public void structureReadiness(boolean ready) {
        enterTransition();
        try {
            requireLoaded();
            requireQuiescent(capture());
            structureReady = ready;
        } finally {
            leave();
        }
    }

    /**
     * Ordinary components may run before formation, but never after unload, break or a fault.
     */
    public boolean schedulingAllowed() {
        return state == State.LOADED && !broken && !faulted && !retired && (gate == IDLE || gate == RUNNING);
    }

    /**
     * Creates/restores unpublished local components after host attachment. Factories and codecs may
     * read the attached host but must defer external registrations and world effects to onLoaded.
     * Failure retires the unpublished instance without sending cleanup to never-loaded listeners.
     */
    public void assemble(Runnable assembly) {
        Objects.requireNonNull(assembly);
        host.requireServerThread();
        enter(ASSEMBLING);
        try {
            requireHealthy();
            if (state != State.CREATED || assemblyStarted) {
                throw new IllegalStateException("Machine assembly can run only once before load");
            }
            assemblyStarted = true;
            try {
                assembly.run();
                requireQuiescent(capture());
            } catch (RuntimeException | Error failure) {
                faulted = true;
                state = State.UNLOADED;
                throw failure;
            }
        } finally {
            leave();
        }
    }

    /**
     * Holds the loaded/unloaded machine's owner boundary for a complete save. Nested component and
     * work-slot codecs may check this boundary, but cannot recursively save, tick or change lifecycle.
     * The caller must have joined all workers; this gate rejects overlap rather than joining them.
     */
    public <T> T withPersistence(Supplier<T> save) {
        Objects.requireNonNull(save);
        host.requireServerThread();
        enter(PERSISTING);
        try {
            requireHealthy();
            if (state == State.CREATED) {
                throw new IllegalStateException("Unloaded assembly cannot be saved before load succeeds");
            }
            try {
                requireQuiescent(capture());
                return save.get();
            } catch (RuntimeException | Error failure) {
                faulted = true;
                throw failure;
            }
        } finally {
            leave();
        }
    }

    public void load(MachineLifecycleEvent.LoadBoundary boundary) {
        Objects.requireNonNull(boundary);
        enterTransition();
        try {
            requireHealthy();
            if (state != State.CREATED) {
                throw new IllegalStateException("Machine can only load once");
            }
            var captured = capture();
            requireQuiescent(captured);
            state = State.LOADED;
            try {
                publish(new MachineLifecycleEvent.Loaded(context(), boundary), captured.listeners());
            } catch (RuntimeException | Error failure) {
                faulted = true;
                // A prior listener may already own external registrations. Clean the same captured
                // participants even if a callback changed the registry or machine component map.
                throwFailure(unloadCaptured(MachineLifecycleEvent.UnloadReason.LOAD_FAILED, captured, failure));
            }
        } finally {
            leave();
        }
    }

    /**
     * Replace discovered public hatch bindings before notifying an actual formed transition.
     */
    public void form(MachineHatches hatches) {
        changeStructure(Objects.requireNonNull(hatches));
    }

    /**
     * Clears structure-derived hatch bindings and pauses recipes, retaining all ordinary progress.
     */
    public void invalidateStructure() {
        changeStructure(null);
    }

    private void changeStructure(@Nullable MachineHatches hatches) {
        enterTransition();
        try {
            requireLoaded();
            if (retired && hatches != null) {
                throw new IllegalStateException("A retired machine cannot form");
            }
            var captured = capture();
            requireQuiescent(captured);
            if (!installStructure(hatches, null, StructureComponents.EMPTY, captured, new RecipeWorkBudget(Long.MAX_VALUE)))
                throw new IllegalStateException("Structure installation exceeds the synchronous work range");
        } finally {
            leave();
        }
    }

    /**
     * Publication after the scheduler has joined workers. Validates a request and prepares all
     * bindings under the same ownership gate as their installation. A revoked/obsolete request
     * does nothing; null preparation means retry without publishing a false formed transition.
     * A completed UNFORMED candidate clears formation. Current exceptions fault the machine.
     * Rechecks authority after discovery callbacks, before installing or notifying listeners.
     */
    public boolean refreshStructure(BooleanSupplier applicable, Supplier<StructureAssembly> prepare) {
        return refreshStructure(applicable, prepare, new RecipeWorkBudget(Long.MAX_VALUE));
    }

    /** All preparation is budgeted before the indivisible accepted-state and notification tail. */
    public boolean refreshStructure(BooleanSupplier applicable, Supplier<StructureAssembly> prepare, RecipeWorkBudget work) {
        Objects.requireNonNull(applicable);
        Objects.requireNonNull(prepare);
        enterTransition();
        try {
            if (state != State.LOADED || broken || retired) {
                return false;
            }
            requireHealthy();
            if (!work.take() || !applicable.getAsBoolean() || retired) {
                return false;
            }
            var captured = capture(work);
            if (captured == null) return false;
            for (var slots : captured.workSlots()) {
                if (!work.take()) return false;
                slots.requireQuiescent();
            }
            try {
                var assembly = prepare.get();
                if (assembly == null || !work.take() || !applicable.getAsBoolean() || retired) {
                    return false;
                }
                return installStructure(assembly.hatches(), assembly.match(), assembly.components(), captured, work);
            } catch (RuntimeException | Error failure) {
                // A failed obsolete attempt still reports its exception, but cannot poison the
                // replacement generation. Failure to determine authority is itself unrecoverable.
                try {
                    if (applicable.getAsBoolean()) {
                        faulted = true;
                    }
                } catch (RuntimeException | Error authorityFailure) {
                    faulted = true;
                    if (failure != authorityFailure) {
                        failure.addSuppressed(authorityFailure);
                    }
                }
                throw failure;
            }
        } finally {
            leave();
        }
    }

    private boolean installStructure(@Nullable MachineHatches hatches, @Nullable StructureMatch match,
                                     StructureComponents ports, Captured captured, RecipeWorkBudget work) {
        boolean previous = formed;
        long generation = hatches != null && !previous ? Math.incrementExact(formationGeneration) : formationGeneration;
        for (var binding : ports.bindings()) {
            if (!work.take()) return false;
            if (binding.context().machine() != machine || binding.context().formationGeneration() != generation) {
                throw new IllegalArgumentException("Structure ports belong to a different machine or formation generation");
            }
        }
        var prepared = new ObjectArrayList<ComponentMap<MachineInstance>.PreparedPut>();
        if (hatches != null) {
            var hatchPut = machine.components().preparePut(MachineHatches.TYPE, hatches, work);
            if (hatchPut == null) return false;
            var portsPut = machine.components().preparePut(StructureComponents.TYPE, ports, work);
            if (portsPut == null) return false;
            prepared.add(hatchPut);
            prepared.add(portsPut);
        }
        // Both component-map writes, accepted state and its listener sequence are indivisible.
        // Callbacks remain cooperative once admitted; a partial notification cannot yield a tick.
        boolean nextFormed = hatches != null;
        long installationCost = 3L + (previous != nextFormed ? captured.listeners().size() : 0);
        var accounting = work instanceof StructureCheckPolicy.Work actual ? actual : null;
        if (accounting == null ? !work.take(installationCost) : !accounting.reserveAtomic(installationCost))
            return false;
        if (hatches == null) {
            if (accounting != null) accounting.completeReserved();
            machine.components().remove(MachineHatches.TYPE);
            if (accounting != null) accounting.completeReserved();
            machine.components().remove(StructureComponents.TYPE);
        } else {
            machine.components().installPuts(prepared);
            if (accounting != null) {
                accounting.completeReserved();
                accounting.completeReserved();
            }
        }
        if (accounting != null) accounting.completeReserved();
        formationGeneration = generation;
        structureMatch = match;
        formed = hatches != null;
        structureReady = true;
        if (previous != formed) {
            try {
                var event = new MachineLifecycleEvent.FormedChanged(context(), previous);
                for (var listener : captured.listeners()) {
                    if (accounting != null) accounting.completeReserved();
                    invoke(event, listener);
                }
            } catch (RuntimeException | Error failure) {
                faulted = true;
                throw failure;
            }
        }
        return true;
    }

    /**
     * Ends the loaded instance exactly once. CHUNK_UNLOAD and INVALIDATED preserve work-slot state:
     * no END, cancellation or historical IO refund. A new instance restores NBT and revalidates its
     * structure before recipes resume. Cleanup remains available after a callback/execution fault.
     */
    public boolean unload(MachineLifecycleEvent.UnloadReason reason) {
        Objects.requireNonNull(reason);
        if (reason == MachineLifecycleEvent.UnloadReason.LOAD_FAILED) {
            throw new IllegalArgumentException("LOAD_FAILED is emitted only by failed load cleanup");
        }
        enterTransition();
        try {
            if (state == State.UNLOADED) {
                return false;
            }
            var attempt = captureForCleanup();
            requireQuiescent(attempt.captured());
            if (state == State.CREATED && attempt.failure() == null) {
                state = State.UNLOADED;
                return true;
            }
            throwFailure(unloadCaptured(reason, attempt.captured(), attempt.failure()));
            return true;
        } finally {
            leave();
        }
    }

    /**
     * Break event, owned ordinary-process cancellation, then unload, even if an earlier cleanup
     * fails. Gates execution before callbacks. No END or refund of earlier completed stages.
     */
    public boolean controllerBroken() {
        enterTransition();
        try {
            if (broken) {
                return false;
            }
            if (state != State.LOADED) {
                throw new IllegalStateException("Controller break requires a loaded machine");
            }
            var attempt = captureForCleanup();
            var captured = attempt.captured();
            requireQuiescent(captured);
            broken = true;
            Throwable failure = publishAll(new MachineLifecycleEvent.ControllerBroken(context()), captured.listeners(), attempt.failure());
            var reason = RecipeText.ControllerBroken.failure();
            for (var slots : captured.workSlots()) {
                try {
                    slots.cancelAll(reason);
                } catch (RuntimeException | Error cleanup) {
                    failure = append(failure, cleanup);
                }
            }
            throwFailure(unloadCaptured(MachineLifecycleEvent.UnloadReason.INVALIDATED, captured, failure));
            return true;
        } finally {
            leave();
        }
    }

    /**
     * MachineInstance dispatch boundary; worker ownership must come from the real scheduler.
     */
    public void runScheduled(Runnable execution) {
        Objects.requireNonNull(execution);
        enter(RUNNING);
        try {
            requireHealthy();
            if (state != State.LOADED || broken || retired) {
                return;
            }
            try {
                execution.run();
            } catch (RuntimeException | Error failure) {
                faulted = true;
                throw failure;
            }
        } finally {
            leave();
        }
    }

    /**
     * Captures deferred owner execution without running callbacks. The returned handle retains the
     * RUNNING gate until its original and current work slots are quiescent. Unloaded, broken or
     * retired machines decline new work; a runtime fault retains the ordinary fatal boundary.
     */
    @Nullable
    public ScheduledExecution beginScheduled() {
        host.requireServerThread();
        enter(RUNNING);
        boolean retained = false;
        try {
            requireHealthy();
            if (state != State.LOADED || broken || retired) {
                return null;
            }
            try {
                var execution = new ScheduledExecution(this, capture().workSlots());
                scheduledExecution = execution;
                retained = true;
                return execution;
            } catch (RuntimeException | Error failure) {
                faulted = true;
                throw failure;
            }
        } finally {
            if (!retained) {
                leave();
            }
        }
    }

    /**
     * Only the dedicated owner normal-callback scope may replace ordinary recipe ticking.
     */
    public boolean deferRecipeWork(MachineWorkSlots slots) {
        var execution = scheduledExecution;
        return execution != null && execution.defer(Objects.requireNonNull(slots));
    }

    /**
     * Captures the exact scanner callback before any acquisition; the scheduler owns its attempt.
     */
    public boolean deferRecipeScan(RecipeScanner scanner, ScanLimits limits) {
        var execution = scheduledExecution;
        return execution != null && execution.deferScan(Objects.requireNonNull(scanner), Objects.requireNonNull(limits));
    }

    /**
     * A check, not a lock spanning serialization; the caller must already hold exclusive ownership.
     */
    public void requirePersistenceBoundary() {
        requireHealthy();
        int phase = (int) GATE.getAcquire(this);
        if (phase == ASSEMBLING || phase == PERSISTING) {
            host.requireServerThread();
        } else if (phase != IDLE) {
            throw new IllegalStateException("Machine boundary is active");
        }
    }

    private void enterTransition() {
        host.requireServerThread();
        enter(TRANSITION);
    }

    private void enter(int phase) {
        if (!(boolean) GATE.compareAndSet(this, IDLE, phase)) {
            throw new IllegalStateException("Machine boundary overlaps execution or another lifecycle callback");
        }
    }

    private void leave() {
        GATE.setRelease(this, IDLE);
    }

    private void requireHealthy() {
        if (faulted) {
            throw new IllegalStateException("Machine has an unrecoverable runtime failure");
        }
    }

    private void requireLoaded() {
        requireHealthy();
        if (state != State.LOADED || broken) {
            throw new IllegalStateException("Machine is not loaded");
        }
    }

    private MachineLifecycleContext context() {
        return new MachineLifecycleContext(machine, identity, host, formed);
    }

    private Captured capture() {
        return Objects.requireNonNull(capture(new RecipeWorkBudget(Long.MAX_VALUE)));
    }

    @Nullable
    private Captured capture(RecipeWorkBudget work) {
        var listeners = new ObjectArrayList<MachineLifecycleListener>();
        var workSlots = new ObjectArrayList<MachineWorkSlots>();
        var seenSlots = new ReferenceOpenHashSet<MachineWorkSlots>();
        var ordered = machine.components().orderedBindings(work);
        if (ordered == null) return null;
        for (var binding : ordered) {
            if (!work.take()) return null;
            if (binding.component() instanceof MachineLifecycleListener listener) {
                listeners.add(listener);
            }
            if (binding.component() instanceof MachineWorkSlots slots && seenSlots.add(slots)) {
                if (slots.owner() != machine) {
                    throw new IllegalStateException("Foreign work slots installed on machine");
                }
                workSlots.add(slots);
            }
        }
        // Retain only owned components for corrupt-graph cleanup. Global subscriptions can be
        // cancelled independently and must not be kept alive or resurrected by this machine.
        var ownedListeners = new ObjectArrayList<MachineLifecycleListener>();
        for (var listener : listeners) {
            if (!work.take()) return null;
            ownedListeners.add(listener);
        }
        for (var listener : registry.snapshot()) {
            if (!work.take()) return null;
            listeners.add(listener);
        }
        lastCaptured = new Captured(ownedListeners, workSlots);
        return new Captured(listeners, workSlots);
    }

    private CleanupCapture captureForCleanup() {
        try {
            return new CleanupCapture(capture(), null);
        } catch (RuntimeException | Error failure) {
            faulted = true;
            // Graph corruption must remain fatal, but must not strand PN/global registrations.
            // Preserve the last ordered participants, then include newly assembled ones by identity.
            var listeners = new ObjectArrayList<MachineLifecycleListener>();
            var slots = new ObjectArrayList<MachineWorkSlots>();
            var seenListeners = new ReferenceOpenHashSet<MachineLifecycleListener>();
            var seenSlots = new ReferenceOpenHashSet<MachineWorkSlots>();
            if (lastCaptured != null) {
                for (var listener : lastCaptured.listeners()) {
                    if (seenListeners.add(listener)) {
                        listeners.add(listener);
                    }
                }
                for (var prior : lastCaptured.workSlots()) {
                    if (seenSlots.add(prior)) {
                        slots.add(prior);
                    }
                }
            }
            for (var binding : machine.components().assemblyBindings()) {
                if (binding.component() instanceof MachineLifecycleListener listener && seenListeners.add(listener)) {
                    listeners.add(listener);
                }
                if (binding.component() instanceof MachineWorkSlots candidate && seenSlots.add(candidate)) {
                    if (candidate.owner() == machine) {
                        slots.add(candidate);
                    } else {
                        failure.addSuppressed(new IllegalStateException("Foreign work slots excluded from machine cleanup"));
                    }
                }
            }
            for (var listener : registry.snapshot()) {
                if (seenListeners.add(listener)) {
                    listeners.add(listener);
                }
            }
            return new CleanupCapture(new Captured(listeners, slots), failure);
        }
    }

    @Nullable
    private Throwable unloadCaptured(MachineLifecycleEvent.UnloadReason reason, Captured captured, @Nullable Throwable failure) {
        state = State.UNLOADED;
        failure = publishAll(new MachineLifecycleEvent.Unloaded(context(), reason), captured.listeners(), failure);
        machine.components().remove(MachineHatches.TYPE);
        machine.components().remove(StructureComponents.TYPE);
        structureMatch = null;
        formed = false;
        if (failure != null) {
            faulted = true;
        }
        return failure;
    }

    public enum State {CREATED, LOADED, UNLOADED}

    /**
     * One deferred lifecycle ownership transfer, controlled only by its creating server thread.
     * This handle does not dispatch or join workers. Callers retain it through source exit and exact
     * compensation, using cleanup after retirement or faults before trying to release ownership.
     */
    public static final class ScheduledExecution {
        private final MachineLifecycle lifecycle;
        private final Thread owner = Thread.currentThread();
        private final ObjectArrayList<MachineWorkSlots> observedSlots = new ObjectArrayList<>();
        private final ReferenceOpenHashSet<MachineWorkSlots> observedSlotIdentities = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<MachineWorkSlots> completedDeferred = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<RecipeScanner> dispatchedScanners = new ReferenceOpenHashSet<>();
        private boolean executing;
        private boolean finished;
        private boolean componentScope;
        @Nullable
        private DeferredScan deferredScan;
        @Nullable
        private MachineWorkSlots deferred;
        @Nullable
        private Throwable failure;

        private ScheduledExecution(MachineLifecycle lifecycle, List<MachineWorkSlots> capturedSlots) {
            this.lifecycle = lifecycle;
            observe(capturedSlots);
        }

        private void observe(List<MachineWorkSlots> slots) {
            for (var candidate : slots) {
                if (observedSlotIdentities.add(candidate)) {
                    observedSlots.add(candidate);
                }
            }
        }

        /**
         * Returns false without calling the body once retirement or a fault revokes forward work.
         */
        public boolean run(Runnable execution) {
            Objects.requireNonNull(execution);
            requireOwner();
            if (!lifecycle.schedulingAllowed()) {
                return false;
            }
            invoke(execution);
            return true;
        }

        /**
         * Runs one consumed normal callback with the precise work-slot deferral hook enabled.
         */
        public boolean runComponent(Runnable execution) {
            Objects.requireNonNull(execution);
            requireOwner();
            if (deferred != null || deferredScan != null) {
                throw new IllegalStateException("Previous component work is still deferred");
            }
            if (!lifecycle.schedulingAllowed()) {
                return false;
            }
            componentScope = true;
            try {
                invoke(execution);
            } finally {
                componentScope = false;
            }
            return true;
        }

        private boolean defer(MachineWorkSlots slots) {
            if (Thread.currentThread() != owner || !executing || !componentScope || finished) {
                return false;
            }
            if (slots.owner() != lifecycle.machine) {
                throw new IllegalArgumentException("Deferred slots belong to another machine");
            }
            if (deferredScan != null)
                throw new IllegalStateException("A scanner attempt must settle before work slots");
            if (completedDeferred.contains(slots)) {
                return true;
            }
            if (deferred != null && deferred != slots) {
                throw new IllegalStateException("One normal callback deferred multiple work-slot hosts");
            }
            deferred = slots;
            observe(List.of(slots));
            return true;
        }

        private boolean deferScan(RecipeScanner scanner, ScanLimits limits) {
            if (Thread.currentThread() != owner || !executing || !componentScope || finished) return false;
            if (scanner.host().owner() != lifecycle.machine)
                throw new IllegalArgumentException("Deferred scanner belongs to another machine");
            if (dispatchedScanners.contains(scanner)) return true;
            if (deferredScan != null)
                throw new IllegalStateException("One normal callback deferred multiple recipe scanners");
            if (deferred != null)
                throw new IllegalStateException("Work slots must settle before a later scan callback");
            dispatchedScanners.add(scanner);
            deferredScan = new DeferredScan(scanner, limits);
            return true;
        }

        public @Nullable DeferredScan deferredRecipeScan() {
            requireOwner();
            return deferredScan;
        }

        public void finishDeferredRecipeScan(DeferredScan expected) {
            requireOwner();
            if (deferredScan != Objects.requireNonNull(expected))
                throw new IllegalArgumentException("Different deferred scanner");
            if (expected.scanner().pendingScan() != null)
                throw new IllegalStateException("Scanner still owns its attempt");
            deferredScan = null;
        }

        @Nullable
        public MachineWorkSlots deferredRecipeWork() {
            requireOwner();
            return deferred;
        }

        /**
         * Acknowledges exact finished work before any successor normal callback can run.
         */
        public void finishDeferredRecipeWork(MachineWorkSlots slots) {
            requireOwner();
            if (deferred != Objects.requireNonNull(slots)) {
                throw new IllegalArgumentException("Different deferred work-slot host");
            }
            slots.requireQuiescent();
            completedDeferred.add(slots);
            deferred = null;
        }

        /**
         * Mandatory confirmation/compensation remains callable after retirement or a runtime fault.
         */
        public void cleanup(Runnable cleanup) {
            Objects.requireNonNull(cleanup);
            requireOwner();
            invoke(cleanup);
        }

        private void invoke(Runnable execution) {
            executing = true;
            try {
                execution.run();
            } catch (RuntimeException | Error observed) {
                record(observed);
                throw observed;
            } finally {
                executing = false;
            }
        }

        /**
         * Records an actual worker or business failure without releasing its cleanup obligation.
         */
        public void recordFailure(Throwable observed) {
            requireOwner();
            record(Objects.requireNonNull(observed));
        }

        private void record(Throwable observed) {
            failure = append(failure, observed);
            lifecycle.faulted = true;
        }

        /**
         * Pending work is an ordinary false result and retains this exact handle and RUNNING gate.
         * Removed or replaced work slots cannot hide any obligations observed by this handle.
         */
        public boolean tryFinish() {
            requireOwner();
            executing = true;
            try {
                observe(lifecycle.capture().workSlots());
                if (deferred != null || deferredScan != null) {
                    return false;
                }
                boolean quiescent = true;
                for (var slots : observedSlots) {
                    quiescent &= slots.quiescent();
                }
                if (!quiescent) {
                    return false;
                }
                finished = true;
                lifecycle.scheduledExecution = null;
                lifecycle.leave();
                return true;
            } catch (RuntimeException | Error observed) {
                record(observed);
                throw observed;
            } finally {
                executing = false;
            }
        }

        public boolean finished() {
            return finished;
        }

        @Nullable
        public Throwable failure() {
            return failure;
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner || executing || finished || lifecycle.scheduledExecution != this
                || (int) GATE.getAcquire(lifecycle) != RUNNING) {
                throw new IllegalStateException("Scheduled execution requires its idle captured owner");
            }
            lifecycle.host.requireServerThread();
        }

        public record DeferredScan(RecipeScanner scanner, ScanLimits limits) {
        }
    }

    private record Captured(List<MachineLifecycleListener> listeners, List<MachineWorkSlots> workSlots) {
    }

    private record CleanupCapture(Captured captured, @Nullable Throwable failure) {
    }
}
