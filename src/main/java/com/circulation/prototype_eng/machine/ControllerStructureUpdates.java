package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.structure.MachineStructureHost;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.structure.StructureRefresh;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.structure.ReloadableStructureHost;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.structure.reload.ServerStructureReloads;
import java.util.function.BooleanSupplier;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.structure.StructureAssembly;
import com.circulation.prototype_eng.api.structure.StructureCheckPolicy;
import com.circulation.prototype_eng.api.structure.StructureOperation;
import com.circulation.prototype_eng.api.structure.StructurePredicateResult;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;

/**
 * PM's controller cadence and concurrent matching, owned by one loaded controller generation.
 * Capture and completion use the scheduler's exclusive owner boundary; workers only evaluate
 * detached requests and enqueue completion. Ordinary processing keeps its existing bindings until
 * publication, while unstable publication explicitly pauses them before retrying on the next tick.
 */
public class ControllerStructureUpdates {
    private final BooleanSupplier attachmentCurrent;
    private final BooleanSupplier activeMachine;
    private final MachineLifecycle lifecycle;
    private final MachineStructureHost host;
    private final TaskScheduler scheduler;
    private final StructureRefresh refresh;
    private final Runnable changed;
    @Nullable private Attempt current;
    private boolean checked;
    private long nextCheckAt;
    private long lastTick;
    private boolean detached;
    private boolean firstCheckPending = true;
    private boolean advanced;
    private long advancedAt;
    private StructureCheckPolicy policy = StructureCheckPolicy.DEFAULT;
    private StructureCheckPolicy.Status status = StructureCheckPolicy.Status.IDLE;
    private long lastWorkUnits;
    private long totalWorkUnits;
    private long lastElapsedNanos;
    private long requiredAtomicUnits;
    @Nullable private StructureConfiguration budgetBlockedConfiguration;

    public ControllerStructureUpdates(MachineController controller, MachineStructureHost host,
                                      TaskScheduler scheduler, Runnable changed) {
        this(Objects.requireNonNull(Objects.requireNonNull(controller).machine(), "Controller has no loaded machine"), host,
            scheduler, () -> {
                var currentMachine = controller.machine();
                var currentLifecycle = currentMachine == null ? null : currentMachine.lifecycle();
                return currentLifecycle != null && !currentLifecycle.retired();
            },
            controller::active, changed);
        }

    /**
     * Independent external hosts need no built-in TE or controller subclass. During a live server
     * structure session they must use that session's scheduler to join its reload/automatic owner;
     * another scheduler is rejected before this constructor returns. Without a server session,
     * independently owned logical updates keep their own scheduler.
     */
    public ControllerStructureUpdates(MachineInstance machine, ReloadableStructureHost host, TaskScheduler scheduler,
                                      BooleanSupplier attachmentCurrent, Runnable changed) {
        this(machine, host, scheduler, attachmentCurrent, machine::active, changed);
    }

    private ControllerStructureUpdates(MachineInstance machine, MachineStructureHost host, TaskScheduler scheduler,
                                       BooleanSupplier attachmentCurrent, BooleanSupplier activeMachine, Runnable changed) {
        this.attachmentCurrent = Objects.requireNonNull(attachmentCurrent);
        this.activeMachine = Objects.requireNonNull(activeMachine);
        this.host = Objects.requireNonNull(host);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.changed = Objects.requireNonNull(changed);
        host.requireServerThread();
        lifecycle = Objects.requireNonNull(machine.lifecycle());
        if (lifecycle.state() != MachineLifecycle.State.LOADED || lifecycle.retired()) {
            throw new IllegalStateException("Structure updates require a loaded controller");
        }
        refresh = new StructureRefresh(lifecycle, host, this::loaded);
        ServerStructureReloads.attach(scheduler, this);
    }

    /** Called by the server tile tick; tick zero must perform the first check. */
    public void tick(long now) {
        host.requireServerThread();
        lastTick = now;
        if (!canRefresh() || current == null && checked && now < nextCheckAt) { return; }
        scheduler.runAtBoundary(() -> checkAtBoundary(now));
    }

    /** The native load continuation belongs to the first real update and already holds the boundary. */
    public void firstTickAtBoundary(long now) {
        host.requireServerThread();
        lastTick = now;
        if (firstCheckPending) checkAtBoundary(now);
    }

    /**
     * The caller holds the scheduler's exclusive owner boundary for this operation and subsequent
     * configuration/orientation changes. It must not enqueue those changes independently.
     */
    public void invalidate() {
        host.requireServerThread();
        current = null;
        checked = false;
        status = StructureCheckPolicy.Status.IDLE;
        budgetBlockedConfiguration = null;
        if (!canRefresh()) { return; }
        refresh.invalidate();
        changed.run();
    }

    private boolean canRefresh() {
        return loaded() && activeMachine.getAsBoolean();
    }

    public MachineLifecycle lifecycle() { return lifecycle; }
    public TaskScheduler scheduler() { return scheduler; }

    public boolean loaded() {
        host.requireServerThread();
        return !detached && lifecycle.state() == MachineLifecycle.State.LOADED && !lifecycle.retired() && attachmentCurrent.getAsBoolean()
            && (!(host instanceof ReloadableStructureHost configurable) || configurable.structureLifecycleCurrent(lifecycle));
    }

    /** Exact external attachment must detach before replacing its host or scheduler registration. */
    public void detach() {
        host.requireServerThread();
        scheduler.runAtBoundary(() -> { detached = true; ServerStructureReloads.detach(scheduler, this); });
        // Admission must succeed before revocation; a rejected metadata callback cannot detach.
        detached = true;
    }

    public Reload prepareReload(StructureCatalog catalog) {
        host.requireServerThread();
        if (!loaded()) return null;
        if (!(host instanceof ReloadableStructureHost configurable)) throw new IllegalStateException("Registered structure host cannot install a new configuration");
        var previous = host.structureConfiguration();
        if (previous == null) return null;
        lifecycle.machine().requirePersistenceBoundary();
        for (var binding : lifecycle.machine().components().assemblyBindings())
            if (binding.component() instanceof MachineWorkSlots slots) slots.requireQuiescent();
        var definition = catalog.get(previous.definition().id());
        if (definition == null) throw new IllegalArgumentException("Reload deletes a loaded host's required structure: " + previous.definition().id());
        return new Reload(this, configurable, previous, new StructureConfiguration(catalog, definition.shape(), previous.orientation()));
    }

    public void requireReloadable() {
        host.requireServerThread();
        if (!(host instanceof ReloadableStructureHost)) throw new IllegalStateException("Registered structure host cannot install a new configuration");
    }

    public boolean reloadCurrent(Reload reload) {
        return reload.owner == this && loaded() && host.structureConfiguration() == reload.previous;
    }

    public boolean catalogCurrent(StructureCatalog catalog) {
        host.requireServerThread();
        var configuration = host.structureConfiguration();
        return configuration == null || configuration.catalog() == catalog;
    }

    public void revokeReload(Reload reload) {
        host.requireServerThread();
        if (!reloadCurrent(reload)) throw new IllegalStateException("Reload host configuration changed after preflight");
        current = null; checked = false;
        status = StructureCheckPolicy.Status.IDLE;
        budgetBlockedConfiguration = null;
        reload.host.installStructureConfiguration(null);
    }

    /** Whole-batch configuration revocation precedes lifecycle/world notification callbacks. */
    public void pauseReload(Reload reload) {
        host.requireServerThread();
        if (reload.owner != this) throw new IllegalArgumentException("Foreign reload host");
        if (lifecycle.state() == MachineLifecycle.State.LOADED && !lifecycle.retired()) refresh.invalidate();
    }

    public void installReload(Reload reload) {
        host.requireServerThread();
        if (reload.owner != this || !loaded() || host.structureConfiguration() != null) return;
        reload.host.installStructureConfiguration(reload.next);
        changed.run();
        // Match only the new generation; submission still belongs to the original scheduler.
        checked = false;
        nextCheckAt = lastTick;
    }

    /** Local policy changes grant a new allowance, without restoring any candidate execution right. */
    public void checkPolicy(StructureCheckPolicy policy) {
        host.requireServerThread();
        this.policy = Objects.requireNonNull(policy);
        current = null;
        checked = false;
        status = StructureCheckPolicy.Status.IDLE;
        budgetBlockedConfiguration = null;
    }

    @SuppressWarnings("unused") // Public host policy diagnostics.
    public StructureCheckPolicy checkPolicy() { return policy; }
    public StructureCheckPolicy.Status checkStatus() { return status; }
    public long lastWorkUnits() { return lastWorkUnits; }
    @SuppressWarnings("unused") // Public host work diagnostics include every actual slice and tail.
    public long totalWorkUnits() { return totalWorkUnits; }
    @SuppressWarnings("unused") // Public host elapsed diagnostics include actual worker joins and cleanup.
    public long lastElapsedNanos() { return lastElapsedNanos; }
    /** Minimum indivisible live operation observed when the policy explicitly cannot progress. */
    public long requiredAtomicUnits() { return requiredAtomicUnits; }

    public static final class Reload {
        private final ControllerStructureUpdates owner;
        private final ReloadableStructureHost host;
        private final StructureConfiguration previous;
        private final StructureConfiguration next;
        private Reload(ControllerStructureUpdates owner, ReloadableStructureHost host, StructureConfiguration previous, StructureConfiguration next) {
            this.owner = owner; this.host = host; this.previous = previous; this.next = next;
        }
    }

    private static final class Attempt {
        private final StructureConfiguration configuration;
        private final PlannedDeadline deadline;
        @Nullable private StructureOperation<StructureRefresh.Request> preparation;
        @Nullable private StructureRefresh.Request request;
        @Nullable private StructureOperation<StructureRefresh.Result> evaluation;
        @Nullable private StructureOperation<StructureAssembly> discovery;
        private boolean stopped;
        private Attempt(StructureConfiguration configuration, PlannedDeadline deadline) {
            this.configuration = configuration;
            this.deadline = deadline;
        }
    }

    private void checkAtBoundary(long now) {
        host.requireServerThread();
        if (!canRefresh() || advanced && advancedAt == now) return;
        if (status == StructureCheckPolicy.Status.NEEDS_MORE_BUDGET) {
            if (budgetBlockedConfiguration == host.structureConfiguration()) return;
            status = StructureCheckPolicy.Status.IDLE; checked = false; budgetBlockedConfiguration = null;
        }
        if (current == null && checked && now < nextCheckAt) return;
        var configuration = host.structureConfiguration();
        if (configuration == null) return;
        advanced = true;
        advancedAt = now;
        boolean first = firstCheckPending;
        if (current == null) {
            checked = true;
        nextCheckAt = now + (lifecycle.formed() ? 20L : 5L);
            current = new Attempt(configuration, PlannedDeadline.after(policy.timeout()));
        }
        var attempt = current;
        if (attempt.configuration != configuration || attempt.deadline.expired(System.nanoTime())) {
            current = null;
            status = StructureCheckPolicy.Status.EXPIRED;
            lifecycle.structureReadiness(false);
            return; }
        long started = System.nanoTime();
        var work = new StructureCheckPolicy.Work(policy.workUnits(), attempt.deadline, policy.sliceTime(),
            first && policy.finalMode() == StructureCheckPolicy.FinalMode.COOPERATIVE);
        StructureCheckPolicy.Work tail = null;
        try {
            var worker = first ? null : worker(attempt);
            if (attempt.preparation == null) attempt.preparation = refresh.beginPrepare(worker);
            if (!attempt.preparation.advance(work)) {
                status = first || work.spent() == 0 || work.needsAtomicTime() || work.atomicMinimum() > policy.workUnits()
                    ? StructureCheckPolicy.Status.NEEDS_MORE_BUDGET : StructureCheckPolicy.Status.YIELD;
                lifecycle.structureReadiness(false);
                return;
            }
            if (attempt.request == null) attempt.request = attempt.preparation.result();
        if (attempt.request == null || attempt.stopped) { stopAttempt(attempt); return; }
            if (attempt.evaluation == null) attempt.evaluation = attempt.request.beginEvaluate();
            boolean[] evaluated = { false };
            Runnable action = () -> evaluated[0] = Objects.requireNonNull(attempt.evaluation).advance(work);
            if (worker == null) action.run();
            else if (!worker.run(action)) { stopAttempt(attempt); return; }
            if (!evaluated[0]) {
                status = first || work.spent() == 0 || work.atomicMinimum() > policy.workUnits()
                    ? StructureCheckPolicy.Status.NEEDS_MORE_BUDGET : StructureCheckPolicy.Status.YIELD;
                lifecycle.structureReadiness(false);
                return; }
        var result = Objects.requireNonNull(attempt.evaluation.result());
            if (attempt.discovery == null) attempt.discovery = refresh.beginDiscovery(result);
            if (!attempt.discovery.advance(work)) {
                status = first || work.spent() == 0 || work.atomicMinimum() > policy.workUnits()
                    ? StructureCheckPolicy.Status.NEEDS_MORE_BUDGET : StructureCheckPolicy.Status.YIELD;
                lifecycle.structureReadiness(false);
                return;
            }
            tail = work.tail(policy.finalMode() == StructureCheckPolicy.FinalMode.COOPERATIVE);
            boolean published = refresh.publish(result, tail, worker);
            if (current != attempt) return;
        current = null;
            if (published) {
                firstCheckPending = false;
                status = refresh.publicationStatus() == StructurePredicateResult.MATCHED
                    ? StructureCheckPolicy.Status.MATCHED : StructureCheckPolicy.Status.MISMATCH;
                changed.run();
            } else {
                status = tail.expired() || attempt.stopped ? StructureCheckPolicy.Status.EXPIRED
                    : policy.finalMode() == StructureCheckPolicy.FinalMode.STRICT && tail.exhausted()
                    ? StructureCheckPolicy.Status.NEEDS_MORE_BUDGET : StructureCheckPolicy.Status.UNAVAILABLE;
                lifecycle.structureReadiness(false);
                checked = false;
                if (status == StructureCheckPolicy.Status.UNAVAILABLE) firstCheckPending = false;
            }
        } catch (RuntimeException | Error failure) {
            current = null;
            if (attempt.request != null) refresh.fail(attempt.request, failure);
            throw failure;
        } finally {
            lastWorkUnits = work.spent() + (tail == null ? 0 : tail.spent());
            totalWorkUnits = Math.addExact(totalWorkUnits, lastWorkUnits);
            lastElapsedNanos = System.nanoTime() - started;
            requiredAtomicUnits = Math.max(work.atomicMinimum(), tail == null ? 0 : tail.atomicMinimum());
            if (attempt.deadline.expired(System.nanoTime()) && status == StructureCheckPolicy.Status.YIELD) stopAttempt(attempt);
            if (status == StructureCheckPolicy.Status.NEEDS_MORE_BUDGET) {
                current = null; budgetBlockedConfiguration = attempt.configuration;
            }
    }
    }

    private StructureSnapshot.JoinedWorker worker(Attempt attempt) {
        return action -> {
            var result = scheduler.runStructureWorker(() -> { action.run(); return Boolean.TRUE; }, attempt.deadline);
            if (result instanceof PlannedTaskExecutor.Success<Boolean>) return true;
            if (result instanceof PlannedTaskExecutor.Failure<Boolean>(var cause)) {
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("Structure worker failed", cause);
            }
            attempt.stopped = true;
            return false;
        };
    }

    private void stopAttempt(Attempt attempt) {
        if (current == attempt) current = null;
        status = StructureCheckPolicy.Status.EXPIRED;
        checked = false;
        lifecycle.structureReadiness(false);
    }
}
