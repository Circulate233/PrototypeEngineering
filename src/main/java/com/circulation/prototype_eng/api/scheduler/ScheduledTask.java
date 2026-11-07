package com.circulation.prototype_eng.api.scheduler;

import java.util.Set;

/**
 * Scheduled work. Metadata is sampled on the scheduler owner thread before dispatch.
 * PLANNED_PARALLEL requires PlannedMachineTask and a real lifecycle-bound machine; the scheduler
 * then retains its full owner component/recipe execution instead of invoking ordinary onSchedule.
 * Concurrent callbacks must use worker-safe state and never access a live World or TileEntity.
 * Shared resource keys must have stable equals/hashCode; changing keys requires a joined boundary.
 */
public interface ScheduledTask {
    void onSchedule();
    ExecutionMode executionMode();
    boolean active();
    default Set<?> affinityKeys() { return Set.of(); }
}
