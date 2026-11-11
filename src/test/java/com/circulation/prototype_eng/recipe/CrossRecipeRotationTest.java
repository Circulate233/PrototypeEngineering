package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossRecipeRotationTest {
    private static final KeyType TYPE = new KeyType("cross_rotation");
    private static final Key INPUT = new Key(TYPE, "input");

    private static ResourceLocation id(String name) {
        return new ResourceLocation("cross_rotation_test", name);
    }

    private static MachineRecipe recipe(String name) {
        return new MachineRecipe(id(name), 1, List.of(RecipeRequirements.RESOURCES.requirement(id(name + "_io"),
            new ResourceRequirement(List.of(new GenericStack(INPUT, 1)), List.of()))));
    }

    @Test
    void changedInventoryRecapturesDoNotLetTheFirstRecipeMonopolizeEveryCompletedCycle() {
        var machine = new CrossRecipeController();
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        var input = new SlottedResourceBuffer(1, Reference2LongMaps.singleton(TYPE, 100L), () -> {
        });
        assertEquals(12, input.insert(INPUT, 12, Actionable.MODULATE));
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Port(input)), List.of(), List.of()));
        var first = recipe("first");
        var second = recipe("second");
        var third = recipe("third");
        var ordered = List.of(first, second, third);
        var catalog = new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "pool.title", ordered)));
        var host = machine.createRecipeHost(List.of(), Set.of());
        var scanner = machine.createRecipeScanner(host, catalog, () -> 1);
        for (int cycle = 0; cycle < 9; cycle++) {
            var result = scanner.scan(new ScanLimits(3, 3));
            assertEquals(RecipeScanner.Status.ADMITTED, result.status());
            assertSame(ordered.get(cycle % ordered.size()), Objects.requireNonNull(result.process()).recipe());
            host.tickProcesses();
            assertTrue(host.activeProcesses().isEmpty());
            assertEquals(11 - cycle, input.availableAmount(INPUT));
        }
    }

    private record Port(ResourceStorage input) implements ResourceHatch {
        @Override
        public @Nullable ResourceStorage output() {
            return null;
        }

        @Override
        public boolean isValid() {
            return true;
        }
    }
}
