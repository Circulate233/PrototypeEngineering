package com.circulation.prototype_eng.api.structure;

import org.jetbrains.annotations.Nullable;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;

/**
 * Actual mutable configuration responsibility for built-in and independent external hosts.
 * The owner installs the already prepared immutable object at its scheduler's joined boundary.
 * Installation must only assign the configuration, must not invoke world/scheduler callbacks,
 * and must never recover an old object as permission. Null revokes the previous generation.
 */
public interface ReloadableStructureHost extends MachineStructureHost {
    void installStructureConfiguration(@Nullable StructureConfiguration configuration);

    /** Exact active owner, including host-object reuse; old lifecycle ownership cannot become true again. */
    boolean structureLifecycleCurrent(MachineLifecycle lifecycle);
}
