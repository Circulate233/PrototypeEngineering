package com.circulation.prototype_eng.api.machine.lifecycle;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import net.minecraft.nbt.NBTTagCompound;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;

/**
 * One controller generation's owned assembly and scheduler registration. The tile owns this object
 * before asking it to load, so load callbacks may request its retirement. Boundary admission always
 * precedes retirement: rejected metadata callbacks cannot change a machine's lifetime. Stop requests
 * may come from workers, but world access, registration and cleanup run only at the scheduler owner.
 * The actual tile still owns pending NBT before constructing this controller, orientation and display.
 */
public final class MachineController implements ScheduledTask {
    private final MachineAssembly assembly;
    private final MachineLifecycleHost host;
    private final MachineLifecycleRegistry listeners;
    private final TaskScheduler scheduler;
    @Nullable private volatile MachineInstance machine;
    @Nullable private volatile Stop requestedStop;
    private volatile Phase phase = Phase.NEW;
    private boolean loadRequested;
    @Nullable private NBTTagCompound pendingSave;

    @SuppressWarnings("unused") // Public API uses the global lifecycle registry by default.
    public MachineController(MachineAssembly assembly, MachineLifecycleHost host, TaskScheduler scheduler) {
        this(assembly, host, scheduler, MachineLifecycleRegistry.GLOBAL);
    }

    public MachineController(MachineAssembly assembly, MachineLifecycleHost host, TaskScheduler scheduler, MachineLifecycleRegistry listeners) {
        this.assembly = Objects.requireNonNull(assembly);
        this.host = Objects.requireNonNull(host);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.listeners = Objects.requireNonNull(listeners);
        host.requireServerThread();
    }

    /** Null requests fresh creation. A queued restored load retains its original data for saving. */
    public void load(@Nullable NBTTagCompound saved) {
        host.requireServerThread();
        if (loadRequested || phase != Phase.NEW || requestedStop != null) {
            throw new IllegalStateException("Controller can request loading only once before retirement");
        }
        var copy = saved == null ? null : saved.copy();
        scheduler.runAtBoundary(() -> {
            loadRequested = true;
            pendingSave = copy;
            loadAtBoundary(copy);
        });
        // An IDLE caller already completed the action. A MAIN caller has only enqueued it.
        loadRequested = true;
        if (phase == Phase.NEW) { pendingSave = copy; }
    }

    /**
     * Server save after the caller's worker join. If a restored load is still queued or failed before
     * publishing an instance, preserve its raw data. Null denotes a fresh, never-assembled controller.
     * An assembled machine always uses the checked codec; faults never fall back to stale raw data.
     */
    @Nullable
    public NBTTagCompound save() {
        host.requireServerThread();
        var current = machine;
        if (current == null) { return pendingSave == null ? null : pendingSave.copy(); }
        return assembly.encode(current);
    }

    /** A runtime view, not permission to bypass this controller's scheduler/lifecycle ownership. */
    @Nullable public MachineInstance machine() { return machine; }

    public void unload(MachineLifecycleEvent.UnloadReason reason) {
        Objects.requireNonNull(reason);
        if (reason == MachineLifecycleEvent.UnloadReason.LOAD_FAILED) {
            throw new IllegalArgumentException("LOAD_FAILED is owned by lifecycle load cleanup");
        }
        requestStop(reason == MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD ? Stop.CHUNK_UNLOAD : Stop.INVALIDATED);
    }

    public void controllerBroken() { requestStop(Stop.BROKEN); }

    private void requestStop(Stop reason) {
        if (phase == Phase.STOPPED || phase == Phase.FAILED) { return; }
        scheduler.runAtBoundary(() -> stopAtBoundary(reason));
        // This happens only after successful admission; IDLE cleanup may already have completed.
        synchronized (this) {
            if (phase == Phase.STOPPING || phase == Phase.STOPPED || phase == Phase.FAILED) { return; }
            requestedStop = stronger(requestedStop, reason);
            var current = machine;
            if (current != null) { Objects.requireNonNull(current.lifecycle()).retire(); }
        }
    }

    private void loadAtBoundary(@Nullable NBTTagCompound saved) {
        host.requireServerThread();
        synchronized (this) {
            if (phase == Phase.STOPPED || requestedStop != null) { phase = Phase.STOPPED; return; }
            if (phase != Phase.NEW) { throw new IllegalStateException("Controller assembly has already started"); }
            phase = Phase.LOADING;
        }
        try {
            var created = saved == null ? assembly.create(host, listeners) : assembly.restore(host, saved, listeners);
            machine = created;
            if (requestedStop != null) {
                Objects.requireNonNull(created.lifecycle()).retire();
                phase = Phase.LOADED;
                return;
            }
            scheduler.register(this);
            pendingSave = null;
            phase = Phase.LOADED;
        } catch (RuntimeException | Error failure) {
            var current = machine;
            if (current != null) {
                var lifecycle = Objects.requireNonNull(current.lifecycle());
                lifecycle.retire();
                try { lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED); }
                catch (RuntimeException | Error cleanup) { if (failure != cleanup) { failure.addSuppressed(cleanup); } }
            }
            try { scheduler.unregister(this); }
            catch (RuntimeException | Error cleanup) { if (failure != cleanup) { failure.addSuppressed(cleanup); } }
            phase = Phase.FAILED;
            throw failure;
        }
    }

    private void stopAtBoundary(Stop reason) {
        host.requireServerThread();
        Stop stop;
        synchronized (this) {
            if (phase == Phase.STOPPING || phase == Phase.STOPPED || phase == Phase.FAILED) { return; }
            stop = stronger(requestedStop, reason);
            requestedStop = stop;
            phase = Phase.STOPPING;
        }
        Throwable failure = null;
        try {
            var current = machine;
            if (current != null) {
                var lifecycle = Objects.requireNonNull(current.lifecycle());
                lifecycle.retire();
                if (stop == Stop.BROKEN && lifecycle.state() == MachineLifecycle.State.LOADED) {
                    lifecycle.controllerBroken();
                } else {
                    lifecycle.unload(stop == Stop.CHUNK_UNLOAD ? MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD
                        : MachineLifecycleEvent.UnloadReason.INVALIDATED);
                }
            }
        } catch (RuntimeException | Error cleanup) { failure = cleanup; }
        finally {
            try { scheduler.unregister(this); }
            catch (RuntimeException | Error cleanup) { failure = append(failure, cleanup); }
            phase = failure == null ? Phase.STOPPED : Phase.FAILED;
        }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }

    private static Stop stronger(@Nullable Stop current, Stop requested) {
        return current == null || requested.ordinal() > current.ordinal() ? requested : current;
    }

    private static Throwable append(@Nullable Throwable primary, Throwable additional) {
        if (primary == null) { return additional; }
        if (primary != additional) { primary.addSuppressed(additional); }
        return primary;
    }

    @Override public boolean active() {
        var current = machine;
        return phase == Phase.LOADED && requestedStop == null && current != null && current.active();
    }

    @Override public ExecutionMode executionMode() { return Objects.requireNonNull(machine, "Controller has no loaded machine").executionMode(); }
    @Override public Set<?> affinityKeys() { return Objects.requireNonNull(machine, "Controller has no loaded machine").affinityKeys(); }
    @Override public void onSchedule() {
        var current = machine;
        if (phase == Phase.LOADED && requestedStop == null && current != null) { current.onSchedule(); }
    }

    private enum Phase { NEW, LOADING, LOADED, STOPPING, STOPPED, FAILED }
    private enum Stop { CHUNK_UNLOAD, INVALIDATED, BROKEN }
}
