package com.circulation.prototype_eng.api.machine.lifecycle;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;

import java.util.Objects;

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
        public UnassembledControllerBroken {
            Objects.requireNonNull(host);
            Objects.requireNonNull(identity);
            Objects.requireNonNull(ownership);
        }
    }

    record Loaded(MachineLifecycleContext context, LoadBoundary boundary) implements MachineLifecycleEvent {
        public Loaded {
            Objects.requireNonNull(context);
            Objects.requireNonNull(boundary);
        }
    }

    record Unloaded(MachineLifecycleContext context, UnloadReason reason) implements MachineLifecycleEvent {
        public Unloaded {
            Objects.requireNonNull(context);
            Objects.requireNonNull(reason);
        }
    }

    record FormedChanged(MachineLifecycleContext context, boolean previousFormed) implements MachineLifecycleEvent {
        public FormedChanged {
            Objects.requireNonNull(context);
        }
    }

    record ControllerBroken(MachineLifecycleContext context) implements MachineLifecycleEvent {
        public ControllerBroken {
            Objects.requireNonNull(context);
        }
    }
}
