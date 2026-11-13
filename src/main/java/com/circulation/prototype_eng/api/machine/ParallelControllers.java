package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.structure.StructureComponents;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * Structural parallelism is sampled with the machine allowance and revalidated at admission.
 */
public final class ParallelControllers {
    public static final ComponentType<MachineInstance, ParallelController> TYPE = new ComponentType<>(
        new ResourceLocation(Tags.MOD_ID, "parallel_controller"), _ -> {
        throw new IllegalStateException("Parallel controller ports are discovered from structures");
    }, List.of());

    private ParallelControllers() {
    }

    public static int limit(MachineInstance machine) {
        var capture = beginLimitCapture(machine);
        if (!capture.advance(new RecipeWorkBudget(Long.MAX_VALUE)))
            throw new IllegalStateException("Parallel controller state changed during capture");
        return capture.result();
    }

    public static LimitCapture beginLimitCapture(MachineInstance machine) {
        return new LimitCapture(Objects.requireNonNull(machine));
    }

    public static final class LimitCapture {
        private final MachineInstance machine;
        private final StructureComponents structure;
        private final List<StructureComponents.Binding<ParallelController>> bindings;
        private final MachineAttributes.ValueCapture base;
        private final MachineAttributes.ValueCapture maximum;
        private final ObjectArrayList<ParallelController.Snapshot> snapshots = new ObjectArrayList<>();
        private long contribution;
        private boolean complete;
        private int result;

        private LimitCapture(MachineInstance machine) {
            this.machine = machine;
            structure = machine.structureComponents();
            bindings = structure.get(TYPE);
            base = machine.attributes().beginValueCapture(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
            maximum = machine.attributes().beginValueCapture(StandardMachineAttributes.MAX_PROCESS_PARALLELISM, Integer.MAX_VALUE);
        }

        public boolean advance(RecipeWorkBudget work) {
            if (!current()) return false;
            if (complete) return true;
            if (!base.advance(work) || !maximum.advance(work)) return false;
            while (snapshots.size() < bindings.size()) {
                if (!work.take()) return false;
                var state = Objects.requireNonNull(bindings.get(snapshots.size()).entry().component().snapshot());
                snapshots.add(state);
                if (state.valid()) contribution = Math.min(Integer.MAX_VALUE, contribution + state.selected());
            }
            if (!work.take() || !current()) return false;
            long baseline = Math.clamp((long) Math.floor(base.result()), 0, Integer.MAX_VALUE);
            long cap = Math.clamp((long) Math.floor(maximum.result()), 0, Integer.MAX_VALUE);
            result = (int) Math.min(cap, Math.min(Integer.MAX_VALUE, baseline + contribution));
            complete = true;
            return true;
        }

        public boolean current() {
            if (machine.structureComponents() != structure || !base.current() || !maximum.current()) return false;
            for (int index = 0; index < snapshots.size(); index++)
                if (!snapshots.get(index).equals(bindings.get(index).entry().component().snapshot())) return false;
            return true;
        }

        public int result() {
            if (!complete || !current())
                throw new IllegalStateException("Parallel limit capture is incomplete or stale");
            return result;
        }
    }
}
