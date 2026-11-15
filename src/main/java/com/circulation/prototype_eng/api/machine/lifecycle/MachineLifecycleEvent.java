package com.circulation.prototype_eng.api.machine.lifecycle;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;


/**
 * PM's four public boundaries, with one shared context and no duplicate marker-component API.
 */
public sealed interface MachineLifecycleEvent {
    MachineLifecycleContext context();

    enum LoadBoundary {FRESH, RESTORED}

    enum UnloadReason {CHUNK_UNLOAD, INVALIDATED, LOAD_FAILED}

    /**
     * Detached destruction evidence; no MachineInstance or loaded authority is manufactured.
     */
    record UnassembledControllerBroken(MachineLifecycleHost host, MachineIdentity identity,
                                       PersistentMachineIdentity ownership) {
    }

    record Loaded(MachineLifecycleContext context, LoadBoundary boundary) implements MachineLifecycleEvent {
    }

    record Unloaded(MachineLifecycleContext context, UnloadReason reason) implements MachineLifecycleEvent {
    }

    record FormedChanged(MachineLifecycleContext context, boolean previousFormed) implements MachineLifecycleEvent {
    }

    record ControllerBroken(MachineLifecycleContext context) implements MachineLifecycleEvent {
    }
}
