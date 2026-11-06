package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;

/**
 * Per-machine component execution state. The scheduler must give this instance exclusive execution
 * ownership; it may transfer that ownership between threads at a join boundary. World/structure,
 * persistence and scheduler registration are separate migration steps, not implied by onSchedule.
 */
public final class MachineInstance {
    private final ComponentMap<MachineInstance> components = new ComponentMap<>();
    private final MachineAttributes attributes = new MachineAttributes();

    /** Baseline attributes; mutate only while this machine has exclusive execution ownership. */
    public MachineAttributes attributes() {
        return attributes;
    }

    public ComponentMap<MachineInstance> components() { return components; }

    /** Runs all pre callbacks, all normal callbacks, then all post callbacks. Never swallows errors. */
    public void onSchedule() { components.tick(this); }
}
