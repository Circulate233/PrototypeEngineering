package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;

/**
 * Per-machine component execution state. The scheduler must give this instance exclusive execution
 * ownership; it may transfer that ownership between threads at a join boundary. A world host attaches
 * one MachineLifecycle before scheduling. Unbound instances support standalone library execution;
 * they carry no world/scheduler permission. Scheduler registration and world matching remain external.
 */
public final class MachineInstance implements ScheduledTask {
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

    /** Unformed machines still run their ordinary non-recipe components. */
    @Override public boolean active() { return lifecycle == null || lifecycle.schedulingAllowed(); }

    @Override public ExecutionMode executionMode() {
        var scheduling = components.get(MachineScheduling.TYPE);
        return scheduling == null ? ExecutionMode.CONCURRENT : scheduling.mode();
    }

    /** Captured by the scheduler on the owner thread; actual balance aliases share affinity keys. */
    @Override public Set<?> affinityKeys() {
        var keys = new ObjectLinkedOpenHashSet<>();
        var hatches = MachineHatches.get(this);
        for (var hatch : hatches.resources()) {
            var input = hatch.inputStorageIdentity();
            var output = hatch.outputStorageIdentity();
            if (input != null) { keys.add(input); }
            if (output != null) { keys.add(output); }
        }
        for (var input : hatches.energyInputs()) { keys.add(Objects.requireNonNull(input.storageIdentity())); }
        for (var output : hatches.energyOutputs()) { keys.add(Objects.requireNonNull(output.storageIdentity())); }
        return ObjectSets.unmodifiable(keys);
    }

    public void requirePersistenceBoundary() {
        if (lifecycle != null) { lifecycle.requirePersistenceBoundary(); }
    }

    /** Baseline attributes; mutate only while this machine has exclusive execution ownership. */
    public MachineAttributes attributes() {
        return attributes;
    }

    public ComponentMap<MachineInstance> components() { return components; }

    /** Runs all pre callbacks, all normal callbacks, then all post callbacks. Never swallows errors. */
    @Override
    public void onSchedule() { if (lifecycle == null) { components.tick(this); } else { lifecycle.runScheduled(() -> components.tick(this)); } }
}
