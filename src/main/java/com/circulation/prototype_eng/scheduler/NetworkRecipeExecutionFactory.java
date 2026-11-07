package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeWork;
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

/** Server-owned bridge for real ordinary concurrent network machines; capture invokes no work. */
final class NetworkRecipeExecutionFactory implements TaskScheduler.PlannedFactory {
    private final Supplier<List<TaskScheduler.PlannedCandidate>> candidates;
    private final Runnable refresh;
    private final LongSupplier tick;
    private final BooleanSupplier tickOpen;

    NetworkRecipeExecutionFactory(Supplier<List<TaskScheduler.PlannedCandidate>> candidates, Runnable refresh,
                                  LongSupplier tick, BooleanSupplier tickOpen) {
        this.candidates = Objects.requireNonNull(candidates);
        this.refresh = Objects.requireNonNull(refresh);
        this.tick = Objects.requireNonNull(tick);
        this.tickOpen = Objects.requireNonNull(tickOpen);
    }

    @Override public boolean owns(ScheduledTask task) {
        var machine = machine(task);
        if (machine == null || machine.lifecycle() == null) { return false; }
        for (var binding : machine.components().assemblyBindings()) {
            if (binding.component() instanceof NetworkRecipeWork) { return true; }
        }
        return false;
    }

    @Override @Nullable public PlannedExecution capture(PlannedTaskExecutor tasks, PlannedDeadline forwardDeadline) {
        var captured = new ObjectArrayList<NetworkRecipeExecution.Candidate>();
        var unique = new ReferenceOpenHashSet<MachineInstance>();
        for (var candidate : candidates.get()) {
            var machine = machine(candidate.task());
            if (machine == null || !owns(candidate.task())) { continue; }
            var lifecycle = Objects.requireNonNull(machine.lifecycle());
            if (!unique.add(machine)) { throw new IllegalStateException("Machine registered through multiple scheduled hosts"); }
            captured.add(new NetworkRecipeExecution.Candidate(machine, lifecycle,
                () -> candidate.current().getAsBoolean() && machine(candidate.task()) == machine
                    && machine.lifecycle() == lifecycle));
        }
        if (captured.isEmpty()) { return null; }
        long capturedTick = tick.getAsLong();
        return new NetworkRecipeExecution(tasks, captured, forwardDeadline, refresh,
            () -> tickOpen.getAsBoolean() && tick.getAsLong() == capturedTick);
    }

    @Nullable private static MachineInstance machine(ScheduledTask task) {
        if (task instanceof MachineController controller) { return controller.machine(); }
        return task instanceof MachineInstance machine ? machine : null;
    }
}
