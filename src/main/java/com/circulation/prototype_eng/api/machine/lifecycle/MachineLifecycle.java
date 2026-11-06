package com.circulation.prototype_eng.api.machine.lifecycle;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.structure.StructureAssembly;
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
    private volatile boolean broken;
    private volatile boolean faulted;
    @Nullable private volatile StructureMatch structureMatch;
    @Nullable private Captured lastCaptured;
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

    public MachineInstance machine() { return machine; }
    public MachineLifecycleHost host() { return host; }
    public State state() { return state; }
    public boolean formed() { return formed; }
    @Nullable public StructureMatch structureMatch() { return structureMatch; }

    /** Readiness for ordinary recipe systems, not a transferable worker permission. */
    public boolean processingAllowed() {
        return formed && schedulingAllowed();
    }

    /** Ordinary components may run before formation, but never after unload, break or a fault. */
    public boolean schedulingAllowed() { return state == State.LOADED && !broken && !faulted && (gate == IDLE || gate == RUNNING); }

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
            if (state != State.CREATED || assemblyStarted) { throw new IllegalStateException("Machine assembly can run only once before load"); }
            assemblyStarted = true;
            try {
                assembly.run();
                requireQuiescent(capture());
            } catch (RuntimeException | Error failure) {
                faulted = true;
                state = State.UNLOADED;
                throw failure;
            }
        } finally { leave(); }
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
            if (state == State.CREATED) { throw new IllegalStateException("Unloaded assembly cannot be saved before load succeeds"); }
            try {
                requireQuiescent(capture());
                return save.get();
            } catch (RuntimeException | Error failure) {
                faulted = true;
                throw failure;
            }
        } finally { leave(); }
    }

    public void load(MachineLifecycleEvent.LoadBoundary boundary) {
        Objects.requireNonNull(boundary);
        enterTransition();
        try {
            requireHealthy();
            if (state != State.CREATED) { throw new IllegalStateException("Machine can only load once"); }
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

    /** Replace discovered public hatch bindings before notifying an actual formed transition. */
    public void form(MachineHatches hatches) { changeStructure(Objects.requireNonNull(hatches)); }

    /** Clears structure-derived hatch bindings and pauses recipes, retaining all ordinary progress. */
    public void invalidateStructure() { changeStructure(null); }

    private void changeStructure(@Nullable MachineHatches hatches) {
        enterTransition();
        try {
            requireLoaded();
            var captured = capture();
            requireQuiescent(captured);
            installStructure(hatches, null, captured);
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
        Objects.requireNonNull(applicable);
        Objects.requireNonNull(prepare);
        enterTransition();
        try {
            if (state != State.LOADED || broken) { return false; }
            requireHealthy();
            if (!applicable.getAsBoolean()) { return false; }
            var captured = capture();
            requireQuiescent(captured);
            try {
                var assembly = prepare.get();
                if (assembly == null || !applicable.getAsBoolean()) { return false; }
                installStructure(assembly.hatches(), assembly.match(), captured);
                return true;
            } catch (RuntimeException | Error failure) {
                // A failed obsolete attempt still reports its exception, but cannot poison the
                // replacement generation. Failure to determine authority is itself unrecoverable.
                try { if (applicable.getAsBoolean()) { faulted = true; } }
                catch (RuntimeException | Error authorityFailure) {
                    faulted = true;
                    if (failure != authorityFailure) { failure.addSuppressed(authorityFailure); }
                }
                throw failure;
            }
        } finally {
            leave();
        }
    }

    private void installStructure(@Nullable MachineHatches hatches, @Nullable StructureMatch match, Captured captured) {
        boolean previous = formed;
        if (hatches == null) { machine.components().remove(MachineHatches.TYPE); }
        else { machine.components().put(MachineHatches.TYPE, hatches); }
        structureMatch = match;
        formed = hatches != null;
        if (previous != formed) {
            try {
                publish(new MachineLifecycleEvent.FormedChanged(context(), previous), captured.listeners());
            } catch (RuntimeException | Error failure) {
                faulted = true;
                throw failure;
            }
        }
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
            if (state == State.UNLOADED) { return false; }
            var attempt = captureForCleanup();
            requireQuiescent(attempt.captured());
            if (state == State.CREATED && attempt.failure() == null) { state = State.UNLOADED; return true; }
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
            if (broken) { return false; }
            if (state != State.LOADED) { throw new IllegalStateException("Controller break requires a loaded machine"); }
            var attempt = captureForCleanup();
            var captured = attempt.captured();
            requireQuiescent(captured);
            broken = true;
            Throwable failure = publishAll(new MachineLifecycleEvent.ControllerBroken(context()), captured.listeners(), attempt.failure());
            var reason = RecipeText.ControllerBroken.failure();
            for (var slots : captured.workSlots()) {
                try { slots.cancelAll(reason); }
                catch (RuntimeException | Error cleanup) { failure = append(failure, cleanup); }
            }
            throwFailure(unloadCaptured(MachineLifecycleEvent.UnloadReason.INVALIDATED, captured, failure));
            return true;
        } finally {
            leave();
        }
    }

    /** MachineInstance dispatch boundary; worker ownership must come from the real scheduler. */
    public void runScheduled(Runnable execution) {
        Objects.requireNonNull(execution);
        enter(RUNNING);
        try {
            requireHealthy();
            if (state != State.LOADED || broken) { return; }
            try { execution.run(); }
            catch (RuntimeException | Error failure) { faulted = true; throw failure; }
        } finally {
            leave();
        }
    }

    /** A check, not a lock spanning serialization; the caller must already hold exclusive ownership. */
    public void requirePersistenceBoundary() {
        requireHealthy();
        int phase = (int) GATE.getAcquire(this);
        if (phase == ASSEMBLING || phase == PERSISTING) {
            host.requireServerThread();
        } else if (phase != IDLE) { throw new IllegalStateException("Machine boundary is active"); }
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

    private void leave() { GATE.setRelease(this, IDLE); }

    private void requireHealthy() {
        if (faulted) { throw new IllegalStateException("Machine has an unrecoverable runtime failure"); }
    }

    private void requireLoaded() {
        requireHealthy();
        if (state != State.LOADED || broken) { throw new IllegalStateException("Machine is not loaded"); }
    }

    private MachineLifecycleContext context() { return new MachineLifecycleContext(machine, identity, host, formed); }

    private Captured capture() {
        var listeners = new ObjectArrayList<MachineLifecycleListener>();
        var workSlots = new ObjectArrayList<MachineWorkSlots>();
        var seenSlots = new ReferenceOpenHashSet<MachineWorkSlots>();
        for (var binding : machine.components().orderedBindings()) {
            if (binding.component() instanceof MachineLifecycleListener listener) { listeners.add(listener); }
            if (binding.component() instanceof MachineWorkSlots slots && seenSlots.add(slots)) {
                if (slots.owner() != machine) { throw new IllegalStateException("Foreign work slots installed on machine"); }
                workSlots.add(slots);
            }
        }
        // Retain only owned components for corrupt-graph cleanup. Global subscriptions can be
        // cancelled independently and must not be kept alive or resurrected by this machine.
        lastCaptured = new Captured(new ObjectArrayList<>(listeners), workSlots);
        listeners.addAll(registry.snapshot());
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
                    if (seenListeners.add(listener)) { listeners.add(listener); }
                }
                for (var prior : lastCaptured.workSlots()) {
                    if (seenSlots.add(prior)) { slots.add(prior); }
                }
            }
            for (var binding : machine.components().assemblyBindings()) {
                if (binding.component() instanceof MachineLifecycleListener listener && seenListeners.add(listener)) { listeners.add(listener); }
                if (binding.component() instanceof MachineWorkSlots candidate && seenSlots.add(candidate)) {
                    if (candidate.owner() == machine) { slots.add(candidate); }
                    else { failure.addSuppressed(new IllegalStateException("Foreign work slots excluded from machine cleanup")); }
                }
            }
            for (var listener : registry.snapshot()) {
                if (seenListeners.add(listener)) { listeners.add(listener); }
            }
            return new CleanupCapture(new Captured(listeners, slots), failure);
        }
    }

    private static void requireQuiescent(Captured captured) {
        for (var slots : captured.workSlots()) { slots.requireQuiescent(); }
    }

    @Nullable
    private Throwable unloadCaptured(MachineLifecycleEvent.UnloadReason reason, Captured captured, @Nullable Throwable failure) {
        state = State.UNLOADED;
        failure = publishAll(new MachineLifecycleEvent.Unloaded(context(), reason), captured.listeners(), failure);
        if (failure != null) { faulted = true; }
        return failure;
    }

    private static void publish(MachineLifecycleEvent event, List<MachineLifecycleListener> listeners) {
        for (var listener : listeners) { invoke(event, listener); }
    }

    @Nullable
    private static Throwable publishAll(MachineLifecycleEvent event, List<MachineLifecycleListener> listeners, @Nullable Throwable failure) {
        for (var listener : listeners) {
            try { invoke(event, listener); }
            catch (RuntimeException | Error cleanup) { failure = append(failure, cleanup); }
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
        if (primary == null) { return additional; }
        if (primary != additional) { primary.addSuppressed(additional); }
        return primary;
    }

    private static void throwFailure(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }

    private record Captured(List<MachineLifecycleListener> listeners, List<MachineWorkSlots> workSlots) {}
    private record CleanupCapture(Captured captured, @Nullable Throwable failure) {}
    public enum State { CREATED, LOADED, UNLOADED }
}
