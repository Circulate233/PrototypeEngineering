package com.circulation.prototype_eng.api.recipe.importing;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.minecraft.FurnaceRecipePool;
import com.circulation.prototype_eng.localization.RecipePoolTitle;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportedPoolFreezeTest {
    @Test
    void freezesCanonicalDefinitionsAndIndependentMachineBindingsWithoutReimporting() {
        var reads = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var pool = new ImportedRecipePool<>(id("pool"), "import.freeze.title", () -> {
            reads.incrementAndGet();
            return List.of(new ImportedRecipe<>(new RecipeBuilder(id("recipe"), 1), "source"));
        }, (recipe, context) -> {
            callbacks.incrementAndGet();
            recipe.durationTicks(3);
            return context.equals("source");
        });
        assertTrue(pool.machines().isEmpty());
        assertEquals(0, reads.get());
        assertEquals(0, callbacks.get());

        var canonical = new MachineRecipe(id("recipe"), 7, List.of());
        var definitions = List.of(canonical);
        var firstMachine = machine("first");
        var firstBindings = List.of(firstMachine);
        var frozen = pool.freeze(definitions, firstBindings);
        assertNotSame(pool, frozen);
        assertEquals(pool.id(), frozen.id());
        assertEquals(pool.translationKey(), frozen.translationKey());
        assertSame(definitions, frozen.recipes());
        assertSame(firstBindings, frozen.machines());
        assertSame(canonical, frozen.match(new AEKeyLinkedOpenHashMap(0)));
        assertNotSame(canonical, pool.recipes().getFirst());
        assertEquals(3, pool.recipes().getFirst().durationTicks());
        assertTrue(pool.machines().isEmpty());

        var secondMachine = machine("second");
        var secondBindings = List.of(secondMachine);
        var second = frozen.freeze(definitions, secondBindings);
        assertSame(canonical, second.recipes().getFirst());
        assertSame(secondBindings, second.machines());
        assertSame(firstMachine, frozen.machines().getFirst());
        assertEquals(1, reads.get());
        assertEquals(1, callbacks.get());
    }

    @Test
    void furnaceCopiesRetainConcretePoolTypeAndDoNotRepeatCreationCallbacks() {
        Bootstrap.register();
        var callbacks = new AtomicInteger();
        var pool = new FurnaceRecipePool(id("furnace"), "import.furnace.title", (_, _) -> {
            callbacks.incrementAndGet();
            return true;
        });
        assertTrue(pool.machines().isEmpty());
        assertEquals(0, callbacks.get());
        var originals = pool.recipes();
        assertFalse(originals.isEmpty());
        int originalCalls = callbacks.get();
        var first = originals.getFirst();
        var canonical = new MachineRecipe(first.id(), first.durationTicks() + 1, first.orderedRequirements());
        var bindings = List.of(machine("furnace"));
        FurnaceRecipePool frozen = pool.freeze(List.of(canonical), bindings);
        assertNotSame(pool, frozen);
        assertSame(canonical, frozen.recipes().getFirst());
        assertSame(bindings, frozen.machines());
        FurnaceRecipePool copied = frozen.freeze(frozen.recipes(), bindings);
        assertSame(canonical, copied.recipes().getFirst());
        assertSame(bindings, copied.machines());
        assertSame(first, pool.recipes().getFirst());
        assertEquals(originalCalls, callbacks.get());
    }

    private static MachineType machine(String name) {
        return MachineType.builder(id("machine/" + name), new RecipePoolTitle("import.machine.title"), id("structure")).build();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("import_freeze_test", path);
    }
}
