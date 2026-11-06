package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Per-machine component execution state. The scheduler must give this instance exclusive execution
 * ownership; it may transfer that ownership between threads at a join boundary. A world host attaches
 * one MachineLifecycle before scheduling. Unbound instances support standalone library execution;
 * they carry no world/scheduler permission. Scheduler registration and world matching remain external.
 */
public final class MachineInstance {
    private final ComponentMap<MachineInstance> components = new ComponentMap<>();
    private final MachineAttributes attributes = new MachineAttributes();
    @Nullable
    private MachineLifecycle lifecycle;

    /** Host assembly boundary; an attached lifecycle cannot be replaced or shared across machines. */
    public void attachLifecycle(MachineLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle);
        if (this.lifecycle != null || lifecycle.machine() != this) {
            throw new IllegalStateException("Machine lifecycle is already attached or belongs to another machine");
        }
        this.lifecycle = lifecycle;
    }

    @Nullable
    public MachineLifecycle lifecycle() { return lifecycle; }

    public boolean canProcessRecipes() { return lifecycle == null || lifecycle.processingAllowed(); }

    public void requirePersistenceBoundary() {
        if (lifecycle != null) { lifecycle.requirePersistenceBoundary(); }
    }

    /** Baseline attributes; mutate only while this machine has exclusive execution ownership. */
    public MachineAttributes attributes() {
        return attributes;
    }

    public ComponentMap<MachineInstance> components() { return components; }

    /** Runs all pre callbacks, all normal callbacks, then all post callbacks. Never swallows errors. */
    public void onSchedule() { if (lifecycle == null) { components.tick(this); } else { lifecycle.runScheduled(() -> components.tick(this)); } }
}
