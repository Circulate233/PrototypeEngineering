package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.structure.MachineStructureHost;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.structure.StructureRefresh;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * PM's controller cadence and concurrent matching, owned by one loaded controller generation.
 * Capture and completion use the scheduler's exclusive owner boundary; workers only evaluate
 * detached requests and enqueue completion. Ordinary processing keeps its existing bindings until
 * publication, while unstable publication explicitly pauses them before retrying on the next tick.
 */
public final class ControllerStructureUpdates {
    private final MachineController controller;
    private final MachineLifecycle lifecycle;
    private final MachineStructureHost host;
    private final TaskScheduler scheduler;
    private final StructureRefresh refresh;
    private final Runnable changed;
    @Nullable private Attempt current;
    private boolean checked;
    private long nextCheckAt;

    public ControllerStructureUpdates(MachineController controller, MachineStructureHost host,
                                      TaskScheduler scheduler, Runnable changed) {
        this.controller = Objects.requireNonNull(controller);
        this.host = Objects.requireNonNull(host);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.changed = Objects.requireNonNull(changed);
        host.requireServerThread();
        lifecycle = Objects.requireNonNull(Objects.requireNonNull(controller.machine(), "Controller has no loaded machine").lifecycle());
        if (lifecycle.state() != MachineLifecycle.State.LOADED || lifecycle.retired()) {
            throw new IllegalStateException("Structure updates require a loaded controller");
        }
        refresh = new StructureRefresh(lifecycle, host);
    }

    /** Called by the server tile tick; tick zero must perform the first check. */
    public void tick(long now) {
        host.requireServerThread();
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
        return lifecycle.state() == MachineLifecycle.State.LOADED && !lifecycle.retired() && controller.active();
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
