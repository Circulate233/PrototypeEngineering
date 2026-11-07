package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
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
        if (!canRefresh() || checked && now < nextCheckAt) { return; }
        scheduler.runAtBoundary(() -> checkAtBoundary(now));
    }

    /**
     * The caller holds the scheduler's exclusive owner boundary for this operation and subsequent
     * configuration/orientation changes. It must not enqueue those changes independently.
     */
    public void invalidate() {
        host.requireServerThread();
        current = null;
        checked = false;
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
        checkAtBoundary(lastTick);
    }

    public static final class Reload {
        private final ControllerStructureUpdates owner;
        private final ReloadableStructureHost host;
        private final StructureConfiguration previous;
        private final StructureConfiguration next;
        private Reload(ControllerStructureUpdates owner, ReloadableStructureHost host, StructureConfiguration previous, StructureConfiguration next) {
            this.owner = owner; this.host = host; this.previous = previous; this.next = next;
        }
    }

    private void checkAtBoundary(long now) {
        host.requireServerThread();
        if (!canRefresh() || checked && now < nextCheckAt) { return; }
        checked = true;
        nextCheckAt = now + (lifecycle.formed() ? 20L : 5L);
        if (current != null) { return; }
        var request = refresh.prepare();
        if (request == null) { return; }
        var attempt = new Attempt(request);
        current = attempt;
        try {
            scheduler.submit(() -> evaluate(attempt), ExecutionMode.CONCURRENT);
        } catch (RuntimeException | Error failure) {
            failAtBoundary(attempt, failure);
        }
    }

    private void evaluate(Attempt attempt) {
        StructureRefresh.Result result;
        try { result = attempt.request.evaluate(); }
        catch (RuntimeException | Error failure) {
            try { scheduler.runAtBoundary(() -> failAtBoundary(attempt, failure)); }
            catch (RuntimeException | Error completionFailure) {
                if (failure != completionFailure) { failure.addSuppressed(completionFailure); }
            }
            throw failure;
        }
        scheduler.runAtBoundary(() -> publishAtBoundary(attempt, result));
    }

    private void failAtBoundary(Attempt attempt, Throwable failure) {
        host.requireServerThread();
        if (current == attempt) { current = null; }
        try { refresh.fail(attempt.request, failure); }
        catch (RuntimeException | Error completionFailure) {
            if (failure != completionFailure) { failure.addSuppressed(completionFailure); }
        }
        // Even an obsolete failure must remain visible to its owner. A current failure additionally
        // faults the lifecycle through StructureRefresh's request/configuration authority check.
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        throw new IllegalStateException("Structure worker failed", failure);
    }

    private void publishAtBoundary(Attempt attempt, StructureRefresh.Result result) {
        host.requireServerThread();
        if (current != attempt) { return; }
        if (!canRefresh()) { current = null; return; }
        boolean published = refresh.publish(result);
        // External publication callbacks may revoke this controller or enqueue configuration work.
        if (current != attempt) { return; }
        current = null;
        if (published) { changed.run(); }
        else if (canRefresh()) {
            checked = false;
            refresh.invalidate();
            changed.run();
        }
    }

    private record Attempt(StructureRefresh.Request request) {}
}
