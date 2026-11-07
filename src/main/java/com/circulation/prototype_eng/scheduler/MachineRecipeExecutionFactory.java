package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedMachineTask;
import java.util.function.Predicate;

/**
 * Owner bridge for real planned machines; ordinary concurrent admission is provided by the caller.
 */
final class MachineRecipeExecutionFactory implements TaskScheduler.PlannedFactory {
    private final Supplier<List<TaskScheduler.PlannedCandidate>> candidates;
    private final Supplier<Runnable> preparation;
    private final LongSupplier tick;
    private final BooleanSupplier tickOpen;
    private final Predicate<MachineInstance> concurrentAdmission;

    MachineRecipeExecutionFactory(Supplier<List<TaskScheduler.PlannedCandidate>> candidates, Supplier<Runnable> preparation,
                                  LongSupplier tick, BooleanSupplier tickOpen, Predicate<MachineInstance> concurrentAdmission) {
        this.candidates = Objects.requireNonNull(candidates);
        this.preparation = Objects.requireNonNull(preparation);
        this.tick = Objects.requireNonNull(tick);
        this.tickOpen = Objects.requireNonNull(tickOpen);
        this.concurrentAdmission = Objects.requireNonNull(concurrentAdmission);
    }

    @Override public boolean owns(ScheduledTask task) {
        var machine = machine(task);
        if (machine == null || machine.lifecycle() == null) { return false; }
        var mode = task.executionMode();
        return mode == ExecutionMode.PLANNED_PARALLEL
            || mode == ExecutionMode.CONCURRENT && concurrentAdmission.test(machine); }

    @Override @Nullable public PlannedExecution capture(PlannedTaskExecutor tasks, PlannedDeadline forwardDeadline) {
        var captured = new ObjectArrayList<MachineRecipeExecution.Candidate>();
        var unique = new ReferenceOpenHashSet<MachineInstance>();
        for (var candidate : candidates.get()) {
            if (!candidate.current().getAsBoolean()) {
                continue;
            }
            var machine = machine(candidate.task());
            if (machine == null || !owns(candidate.task())) { continue; }
            var lifecycle = Objects.requireNonNull(machine.lifecycle());
            var machineMode = machine.executionMode();
            if (!unique.add(machine)) { throw new IllegalStateException("Machine registered through multiple scheduled hosts"); }
            captured.add(new MachineRecipeExecution.Candidate(machine, lifecycle,
                () -> candidate.current().getAsBoolean() && candidate.task().executionMode() == candidate.mode() && machine(candidate.task()) == machine
                    && machine.lifecycle() == lifecycle && machine.executionMode() == machineMode));
        }
        if (captured.isEmpty()) { return null; }
        long capturedTick = tick.getAsLong();
        return new MachineRecipeExecution(tasks, captured, forwardDeadline, Objects.requireNonNull(preparation.get()),
            () -> tickOpen.getAsBoolean() && tick.getAsLong() == capturedTick);
    }

    @Nullable private static MachineInstance machine(ScheduledTask task) {
        return task instanceof PlannedMachineTask host ? host.plannedMachine() : null;
    }
}
