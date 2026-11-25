package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.structure.MachineStructureHost;
import com.circulation.prototype_eng.api.structure.ReloadableStructureHost;
import com.circulation.prototype_eng.api.structure.StructureAssembly;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureCheckPolicy;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.api.structure.StructureOperation;
import com.circulation.prototype_eng.api.structure.StructurePredicateResult;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.structure.StructureRefresh;
import com.circulation.prototype_eng.structure.reload.ServerStructureReloads;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
    @Nullable
    private Attempt current;
    private boolean checked;
    private long nextCheckAt;
    private long lastTick;
    private boolean detached;
    private int dismantling;
    private boolean firstCheckPending = true;
    private boolean advanced;
    private long advancedAt;
    private StructureCheckPolicy policy = StructureCheckPolicy.DEFAULT;
    private StructureCheckPolicy.Status status = StructureCheckPolicy.Status.IDLE;
    private long lastWorkUnits;
    private long totalWorkUnits;
    private long lastElapsedNanos;
    private long requiredAtomicUnits;
    @Nullable
    private StructureConfiguration budgetBlockedConfiguration;

    @SuppressWarnings("DataFlowIssue")
    public ControllerStructureUpdates(MachineController controller, MachineStructureHost host,
                                      TaskScheduler scheduler, Runnable changed) {
        this(controller.machine(), host,
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

    @SuppressWarnings("DataFlowIssue")
    private ControllerStructureUpdates(MachineInstance machine, MachineStructureHost host, TaskScheduler scheduler,
                                       BooleanSupplier attachmentCurrent, BooleanSupplier activeMachine, Runnable changed) {
        this.attachmentCurrent = attachmentCurrent;
        this.activeMachine = activeMachine;
        this.host = host;
        this.scheduler = scheduler;
        this.changed = changed;
        host.requireServerThread();
        lifecycle = machine.lifecycle();
        if (lifecycle.state() != MachineLifecycle.State.LOADED || lifecycle.retired()) {
            throw new IllegalStateException("Structure updates require a loaded controller");
        }
        refresh = new StructureRefresh(lifecycle, host, this::loaded);
        ServerStructureReloads.attach(scheduler, this);
    }

    /**
     * Called by the server tile tick; tick zero must perform the first check.
     */
    public void tick(long now) {
        host.requireServerThread();
        lastTick = now;
        if (!canRefresh() || current == null && checked && now < nextCheckAt) {
            return;
        }
        scheduler.runAtBoundary(() -> checkAtBoundary(now));
    }

    /**
     * Native periodic ticks gather adjacent checks; the first real tick remains synchronous.
     */
    public void enqueueTick(long now) {
        enqueueTick(now, () -> {
        });
    }

    /**
     * The exact host's owner observation follows the completed slice, including a no-check tick.
     */
    public void enqueueTick(long now, Runnable observed) {
        host.requireServerThread();
        if (firstCheckPending) {
            tick(now);
            scheduler.runAtBoundary(observed);
            return;
        }
        lastTick = now;
        scheduler.enqueueStructureCheck(this, () -> {
            var check = prepareCheckAtBoundary(now);
            return check == null ? null : check.next();
        }, observed);
    }

    /**
     * The native load continuation belongs to the first real update and already holds the boundary.
     */
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
        if (!canRefresh()) {
            return;
        }
        refresh.invalidate();
        changed.run();
    }

    public void beginDismantling() {
        host.requireServerThread();
        scheduler.requireOwnerBoundary();
        if (!canRefresh()) throw new IllegalStateException("Dismantling requires an active structure owner");
        dismantling++;
        if (dismantling > 1) return;
        try {
            invalidate();
        } catch (RuntimeException | Error failure) {
            dismantling--;
            throw failure;
        }
    }

    public void endDismantling() {
        host.requireServerThread();
        scheduler.requireOwnerBoundary();
        if (dismantling == 0) throw new IllegalStateException("No dismantling operation owns this structure");
        if (--dismantling == 0) {
            checked = false;
            nextCheckAt = lastTick;
        }
    }

    private boolean canRefresh() {
        return loaded() && activeMachine.getAsBoolean();
    }

    public MachineLifecycle lifecycle() {
        return lifecycle;
    }

    public TaskScheduler scheduler() {
        return scheduler;
    }

    public boolean loaded() {
        host.requireServerThread();
        return !detached && lifecycle.state() == MachineLifecycle.State.LOADED && !lifecycle.retired() && attachmentCurrent.getAsBoolean()
            && (!(host instanceof ReloadableStructureHost configurable) || configurable.structureLifecycleCurrent(lifecycle));
    }

    /**
     * Exact external attachment must detach before replacing its host or scheduler registration.
     */
    public void detach() {
        host.requireServerThread();
        scheduler.runAtBoundary(() -> {
            detached = true;
            ServerStructureReloads.detach(scheduler, this);
        });
        // Admission must succeed before revocation; a rejected metadata callback cannot detach.
        detached = true;
    }

    public Reload prepareReload(StructureCatalog catalog) {
        host.requireServerThread();
        if (!loaded()) return null;
        if (!(host instanceof ReloadableStructureHost configurable))
            throw new IllegalStateException("Registered structure host cannot install a new configuration");
        var previous = host.structureConfiguration();
        if (previous == null) return null;
        lifecycle.machine().requirePersistenceBoundary();
        var elements = lifecycle.machine().components().assemblyBindings();
        for (int index = 0; index < elements.size(); index++) {
            var binding = elements.get(index);
            if (binding.component() instanceof MachineWorkSlots slots) slots.requireQuiescent();
        }
        var definition = catalog.get(previous.definition().id());
        if (definition == null)
            throw new IllegalArgumentException("Reload deletes a loaded host's required structure: " + previous.definition().id());
        return new Reload(this, configurable, previous, new StructureConfiguration(catalog, definition.shape(), previous.orientation()));
    }

    public void requireReloadable() {
        host.requireServerThread();
        if (!(host instanceof ReloadableStructureHost))
            throw new IllegalStateException("Registered structure host cannot install a new configuration");
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
        if (!reloadCurrent(reload))
            throw new IllegalStateException("Reload host configuration changed after preflight");
        current = null;
        checked = false;
        status = StructureCheckPolicy.Status.IDLE;
        budgetBlockedConfiguration = null;
        reload.host.installStructureConfiguration(null);
    }

    /**
     * Whole-batch configuration revocation precedes lifecycle/world notification callbacks.
     */
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

    /**
     * Local policy changes grant a new allowance, without restoring any candidate execution right.
     */
    public void checkPolicy(StructureCheckPolicy policy) {
        host.requireServerThread();
        this.policy = policy;
        current = null;
        checked = false;
        status = StructureCheckPolicy.Status.IDLE;
        budgetBlockedConfiguration = null;
    }

    @SuppressWarnings("unused") // Public host policy diagnostics.
    public StructureCheckPolicy checkPolicy() {
        return policy;
    }

    public StructureCheckPolicy.Status checkStatus() {
        return status;
    }

    public long lastWorkUnits() {
        return lastWorkUnits;
    }

    @SuppressWarnings("unused") // Public host work diagnostics include every actual slice and tail.
    public long totalWorkUnits() {
        return totalWorkUnits;
    }

    @SuppressWarnings("unused") // Public host elapsed diagnostics include actual worker joins and cleanup.
    public long lastElapsedNanos() {
        return lastElapsedNanos;
    }

    /**
     * Minimum indivisible live operation observed when the policy explicitly cannot progress.
     */
    public long requiredAtomicUnits() {
        return requiredAtomicUnits;
    }

    private void checkAtBoundary(long now) {
        var check = prepareCheckAtBoundary(now);
        if (check == null) return;
        var step = check.next();
        while (step != null) {
            PlannedTaskExecutor.Result<Boolean> result;
            try {
                result = check.first ? new PlannedTaskExecutor.Success<>(step.action().get())
                    : scheduler.runStructureWorker(step.action(), step.deadline());
            } catch (RuntimeException | Error failure) {
                result = new PlannedTaskExecutor.Failure<>(failure);
            }
            try {
                step.joined().accept(result);
            } catch (RuntimeException | Error failure) {
                result = new PlannedTaskExecutor.Failure<>(failure);
            }
            var continuation = step.completed().apply(result);
            step = continuation == null ? null : continuation.get();
        }
    }

    @Nullable
    private Check prepareCheckAtBoundary(long now) {
        host.requireServerThread();
        if (dismantling != 0 || !canRefresh() || advanced && advancedAt == now) return null;
        if (status == StructureCheckPolicy.Status.NEEDS_MORE_BUDGET) {
            if (budgetBlockedConfiguration == host.structureConfiguration()) return null;
            status = StructureCheckPolicy.Status.IDLE;
            checked = false;
            budgetBlockedConfiguration = null;
        }
        if (current == null && checked && now < nextCheckAt) return null;
        var configuration = host.structureConfiguration();
        if (configuration == null) return null;
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
            return null;
        }
        return new Check(attempt, first);
    }

    private StructureSnapshot.JoinedWorker worker(Attempt attempt) {
        return action -> {
            var result = scheduler.runStructureWorker(() -> {
                action.run();
                return Boolean.TRUE;
            }, attempt.deadline);
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

    public static final class Reload {
        private final ControllerStructureUpdates owner;
        private final ReloadableStructureHost host;
        private final StructureConfiguration previous;
        private final StructureConfiguration next;

        private Reload(ControllerStructureUpdates owner, ReloadableStructureHost host, StructureConfiguration previous, StructureConfiguration next) {
            this.owner = owner;
            this.host = host;
            this.previous = previous;
            this.next = next;
        }
    }

    private static final class Attempt {
        private final StructureConfiguration configuration;
        private final PlannedDeadline deadline;
        @Nullable
        private StructureOperation<StructureRefresh.@Nullable Request> preparation;
        @Nullable
        private StructureRefresh.ParallelPreparation parallelPreparation;
        @Nullable
        private StructureRefresh.Request request;
        @Nullable
        private StructureOperation<StructureRefresh.Result> evaluation;
        @Nullable
        private StructureOperation<@Nullable StructureAssembly> discovery;
        private boolean stopped;

        private Attempt(StructureConfiguration configuration, PlannedDeadline deadline) {
            this.configuration = configuration;
            this.deadline = deadline;
        }
    }

    /**
     * One owner's allowance and detached steps; no shared mutable work budget crosses hosts.
     */
    private final class Check {
        private final Attempt attempt;
        private final boolean first;
        private final long started = System.nanoTime();
        private final StructureCheckPolicy selectedPolicy = policy;
        private final StructureCheckPolicy.Work work;
        @Nullable
        private final StructureSnapshot.JoinedWorker worker;
        @Nullable
        private StructureCheckPolicy.Work tail;
        private boolean capturing;
        private boolean accounted;

        private Check(Attempt attempt, boolean first) {
            this.attempt = attempt;
            this.first = first;
            work = new StructureCheckPolicy.Work(selectedPolicy.workUnits(), attempt.deadline, selectedPolicy.sliceTime(),
                first && selectedPolicy.finalMode() == StructureCheckPolicy.FinalMode.COOPERATIVE);
            worker = first ? null : worker(attempt);
        }

        @Nullable
        private TaskScheduler.StructureWork next() {
            host.requireServerThread();
            try {
                if (current != attempt) {
                    finishAccounting();
                    return null;
                }
                if (attempt.request == null) {
                    boolean prepared;
                    if (first) {
                        if (attempt.preparation == null) attempt.preparation = refresh.beginPrepare(null);
                        prepared = attempt.preparation.advance(work);
                        if (prepared) attempt.request = attempt.preparation.result();
                    } else {
                        if (attempt.parallelPreparation == null)
                            attempt.parallelPreparation = refresh.beginParallelPrepare();
                        var action = attempt.parallelPreparation.prepareWorker(work);
                        if (action != null) {
                            capturing = true;
                            return new TaskScheduler.StructureWork(attempt.deadline, () -> {
                                action.run();
                                return true;
                            }, this::closeCapture, this::complete);
                        }
                        prepared = attempt.parallelPreparation.complete();
                        if (prepared) attempt.request = attempt.parallelPreparation.result();
                    }
                    if (current != attempt) {
                        finishAccounting();
                        return null;
                    }
                    if (!prepared) {
                        yieldWork();
                        finishAccounting();
                        return null;
                    }
                    if (attempt.request == null || attempt.stopped) {
                        stopAttempt(attempt);
                        finishAccounting();
                        return null;
                    }
                }
                if (attempt.evaluation == null) attempt.evaluation = attempt.request.beginEvaluate();
                var evaluation = attempt.evaluation;
                return new TaskScheduler.StructureWork(attempt.deadline,
                    () -> evaluation.advance(work), this::complete);
            } catch (RuntimeException | Error failure) {
                try {
                    fail(failure);
                } finally {
                    finishAccounting();
                }
                throw failure;
            }
        }

        @SuppressWarnings("DataFlowIssue")
        private void closeCapture(PlannedTaskExecutor.Result<Boolean> outcome) {
            host.requireServerThread();
            var failure = outcome instanceof PlannedTaskExecutor.Failure<Boolean>(var cause) ? cause : null;
            boolean accepted = outcome instanceof PlannedTaskExecutor.Success<Boolean>(var success) && success;
            // The scheduler runs all such closes before any completion or publication callback.
            attempt.parallelPreparation.finishWorker(accepted, failure);
        }

        @Nullable
        @SuppressWarnings("DataFlowIssue")
        private Supplier<TaskScheduler.StructureWork> complete(PlannedTaskExecutor.Result<Boolean> outcome) {
            host.requireServerThread();
            if (capturing) {
                capturing = false;
                try {
                    if (outcome instanceof PlannedTaskExecutor.Failure<Boolean>(var cause)) {
                        if (cause instanceof RuntimeException runtime) throw runtime;
                        if (cause instanceof Error error) throw error;
                        throw new IllegalStateException("Structure capture worker failed", cause);
                    }
                    boolean accepted = outcome instanceof PlannedTaskExecutor.Success<Boolean>(var success) && success;
                    if (current != attempt) {
                        finishAccounting();
                        return null;
                    }
                    if (!accepted || attempt.deadline.expired(System.nanoTime())) {
                        stopAttempt(attempt);
                        finishAccounting();
                        return null;
                    }
                    return this::next;
                } catch (RuntimeException | Error failure) {
                    try {
                        fail(failure);
                    } finally {
                        finishAccounting();
                    }
                    throw failure;
                }
            }
            try {
                if (outcome instanceof PlannedTaskExecutor.Failure<Boolean>(var cause)) {
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IllegalStateException("Structure worker failed", cause);
                }
                if (current != attempt) return null;
                if (outcome instanceof PlannedTaskExecutor.Stopped<Boolean> || attempt.deadline.expired(System.nanoTime())) {
                    stopAttempt(attempt);
                    return null;
                }
                if (!(outcome instanceof PlannedTaskExecutor.Success<Boolean>(var evaluated)) || !evaluated) {
                    yieldWork();
                    return null;
                }
                var result = attempt.evaluation.result();
                if (attempt.discovery == null) attempt.discovery = refresh.beginDiscovery(result);
                if (!attempt.discovery.advance(work)) {
                    yieldWork();
                    return null;
                }
                tail = work.tail(selectedPolicy.finalMode() == StructureCheckPolicy.FinalMode.COOPERATIVE);
                boolean published = refresh.publish(result, tail, worker);
                if (current != attempt) return null;
                current = null;
                if (published) {
                    firstCheckPending = false;
                    status = refresh.publicationStatus() == StructurePredicateResult.MATCHED
                        ? StructureCheckPolicy.Status.MATCHED : StructureCheckPolicy.Status.MISMATCH;
                    changed.run();
                } else {
                    status = tail.expired() || attempt.stopped ? StructureCheckPolicy.Status.EXPIRED
                        : selectedPolicy.finalMode() == StructureCheckPolicy.FinalMode.STRICT && tail.exhausted()
                        ? StructureCheckPolicy.Status.NEEDS_MORE_BUDGET : StructureCheckPolicy.Status.UNAVAILABLE;
                    lifecycle.structureReadiness(false);
                    checked = false;
                    if (status == StructureCheckPolicy.Status.UNAVAILABLE) firstCheckPending = false;
                    if (status == StructureCheckPolicy.Status.NEEDS_MORE_BUDGET)
                        budgetBlockedConfiguration = attempt.configuration;
                }
                return null;
            } catch (RuntimeException | Error failure) {
                fail(failure);
                throw failure;
            } finally {
                finishAccounting();
            }
        }

        private void yieldWork() {
            status = first || work.spent() == 0 || work.needsAtomicTime() || work.atomicMinimum() > selectedPolicy.workUnits()
                ? StructureCheckPolicy.Status.NEEDS_MORE_BUDGET : StructureCheckPolicy.Status.YIELD;
            lifecycle.structureReadiness(false);
        }

        private void fail(Throwable failure) {
            if (current == attempt) current = null;
            if (attempt.request != null) refresh.fail(attempt.request, failure);
        }

        private void finishAccounting() {
            if (accounted) return;
            accounted = true;
            lastWorkUnits = work.spent() + (tail == null ? 0 : tail.spent());
            totalWorkUnits = Math.addExact(totalWorkUnits, lastWorkUnits);
            lastElapsedNanos = System.nanoTime() - started;
            requiredAtomicUnits = Math.max(work.atomicMinimum(), tail == null ? 0 : tail.atomicMinimum());
            if (current == attempt && attempt.deadline.expired(System.nanoTime()) && status == StructureCheckPolicy.Status.YIELD)
                stopAttempt(attempt);
            if (current == attempt && status == StructureCheckPolicy.Status.NEEDS_MORE_BUDGET) {
                current = null;
                budgetBlockedConfiguration = attempt.configuration;
            }
        }
    }
}
