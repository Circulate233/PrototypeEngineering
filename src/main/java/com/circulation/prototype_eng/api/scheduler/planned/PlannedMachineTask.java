package com.circulation.prototype_eng.api.scheduler.planned;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import org.jetbrains.annotations.Nullable;

/**
 * Explicit machine binding for owner-driven planned recipe execution. Selecting PLANNED_PARALLEL
 * requires this contract and a bound MachineLifecycle. Ordinary scheduling keeps its original path.
 * Binding reads require the scheduler owner and must run no callback, register no second owner, and
 * acquire no lifecycle or resource obligation. External task wrappers can expose the same machine;
 * its binding and mode must remain stable for one captured execution, or forward work is revoked.
 */
public interface PlannedMachineTask extends ScheduledTask {
    /** Current real machine, or null when the host has no assembled machine. This grants no authority. */
    @Nullable MachineInstance plannedMachine();
}
