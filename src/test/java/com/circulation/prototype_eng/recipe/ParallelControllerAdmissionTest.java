package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.ParallelController;
import com.circulation.prototype_eng.api.machine.ParallelControllers;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.structure.StructureComponentProvider;
import com.circulation.prototype_eng.api.structure.StructureComponents;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelControllerAdmissionTest {
    @Test
    void structuralControllerChangesRevokeAdmissionProofAndOnlyAffectNewWork() {
        var machine = new MultithreadController();
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        machine.attributes().setBase(StandardMachineAttributes.MAX_PROCESS_PARALLELISM, 10);
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2);
        var first = new Setting(4, 4);
        var second = new Setting(16, 16);
        machine.components().put(StructureComponents.TYPE, new StructureComponents(List.of(
            binding(machine, first, 1), binding(machine, second, 2))));
        var host = new MachineWorkSlots(machine, List.of(new WorkSlotDefinition("first"), new WorkSlotDefinition("second")));
        var recipe = new MachineRecipe(id("recipe"), 20, List.of());
        var catalog = new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "parallel.test", List.of(recipe))));
        var scanner = RecipeScanner.multithreaded(host, catalog, () -> 1);
        var admitted = scanner.scan(new ScanLimits(10, 30));
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        assertEquals(10, RecipeParallelism.effective(process));
        var before = host.beginSharedUsage(recipe);
        assertTrue(before.advance(new RecipeWorkBudget(Long.MAX_VALUE)));
        assertEquals(0, before.result().available());
        first.select(1);
        second.select(2);
        assertFalse(before.current());
        assertEquals(10, RecipeParallelism.effective(process));
        assertEquals(4, host.sharedUsage(recipe).limit());
        assertEquals(0, host.sharedUsage(recipe).available());
        assertTrue(host.cancel(process));
        scanner.reset();
        var next = scanner.scan(new ScanLimits(10, 30));
        assertEquals(RecipeScanner.Status.ADMITTED, next.status());
        assertEquals(4, RecipeParallelism.effective(Objects.requireNonNull(next.process())));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("parallel_controller_test", path);
    }

    private static StructureComponents.Binding<ParallelController> binding(MultithreadController machine, ParallelController controller, int x) {
        return new StructureComponents.Binding<>(new StructureComponentProvider.Context(machine, new BlockPos(x, 0, 0), 1),
            new StructureComponentProvider.Entry<>(ParallelControllers.TYPE, "parallel", controller));
    }

    private static final class Setting implements ParallelController {
        private Snapshot state;

        private Setting(int selected, int maximum) {
            state = new Snapshot(selected, maximum, 0, true);
        }

        private void select(int selected) {
            state = new Snapshot(selected, state.maximum(), state.revision() + 1, true);
        }

        @Override
        public Snapshot snapshot() {
            return state;
        }
    }
}
