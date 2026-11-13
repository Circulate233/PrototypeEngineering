package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeWork;
import com.circulation.prototype_eng.api.network.recipe.NetworkWorkRequirements;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkRecipeReplacementTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("network_replacement", name);
    }

    private static MachineRecipe recipe(int duration) {
        return new MachineRecipe(id("recipe"), duration, List.of());
    }

    private static RecipeRegistry registry(MachineRecipe recipe, String revision) {
        var registry = new RecipeRegistry();
        registry.register(new DefaultRecipePool(id("pool"), "pool.title", List.of(recipe)));
        registry.declareRevision(recipe, revision);
        return registry;
    }

    @Test
    void coldStartHistoryAndNewObjectsHaveExactGatesWithoutReconfiguringCapturedOldProcesses() {
        var old = recipe(2);
        var current = recipe(4);
        var next = recipe(8);
        var registry = registry(current, "v2");
        registry.declareHistoricalRevision(old, "v1");
        registry.freeze();
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var research = new ResearchRegistry();
        research.freeze(resources);
        var extensions = new NetworkRecipeExtensions();
        var calls = new ObjectArrayList<MachineRecipe>();
        extensions.register((recipe, builder) -> {
            assertFalse(Thread.holdsLock(extensions));
            calls.add(recipe);
            builder.resource(NetworkResources.COMPUTE, recipe.durationTicks());
        });
        extensions.publish(registry.snapshot(), research, resources);
        assertEquals(List.of(old, current), calls);
        var device = new ComponentType<MachineInstance, MachineNetworkDevice>(id("device"), _ -> {
            throw new AssertionError("Pure requirement capture cannot acquire a network device");
        }, List.of());
        var work = NetworkRecipeWork.component(id("work"), device, NetworkWorkRequirements.EMPTY, extensions, research, resources);
        var machine = new MachineInstance();
        machine.components().create(work, machine);
        var oldProcess = new RecipeProcess(machine, old, 1);
        var oldGate = assertInstanceOf(NetworkRecipeWork.Binding.class, oldProcess.orderedRequirements().getFirst().payload()).requirements();
        var expected = extensions.snapshot();
        var prepared = extensions.prepare(expected, List.of(old, next, next));
        assertThrows(IllegalArgumentException.class, () -> extensions.require(next));
        assertEquals(List.of(old, current, next), calls);
        var repeated = extensions.prepare(expected, List.of(next));
        assertEquals(List.of(old, current, next), calls);
        extensions.install(prepared);
        assertThrows(IllegalStateException.class, () -> extensions.install(repeated));
        var newProcess = new RecipeProcess(machine, next, 2);
        var newGate = assertInstanceOf(NetworkRecipeWork.Binding.class, newProcess.orderedRequirements().getFirst().payload()).requirements();
        assertEquals(2, oldGate.resources().getLong(NetworkResources.COMPUTE));
        assertEquals(8, newGate.resources().getLong(NetworkResources.COMPUTE));
        assertSame(oldGate, assertInstanceOf(NetworkRecipeWork.Binding.class, oldProcess.orderedRequirements().getFirst().payload()).requirements());
        assertEquals(4, extensions.require(current).resources().getLong(NetworkResources.COMPUTE));
    }

    @Test
    void failedConfigurationRemainsPrivateSealsItsBuilderAndNeverReplaysTheSameObject() {
        var old = recipe(2);
        var next = recipe(8);
        var registry = registry(old, "v1");
        registry.freeze();
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var research = new ResearchRegistry();
        research.freeze(resources);
        var failure = new IllegalArgumentException("configuration failed");
        var retained = new ObjectArrayList<NetworkWorkRequirements.Builder>();
        var calls = new int[1];
        var extensions = new NetworkRecipeExtensions();
        extensions.register((recipe, builder) -> {
            calls[0]++;
            retained.add(builder);
            if (recipe == next) throw failure;
        });
        extensions.publish(registry.snapshot(), research, resources);
        var expected = extensions.snapshot();
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> extensions.prepare(expected, List.of(next))));
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> extensions.prepare(expected, List.of(next))));
        assertEquals(2, calls[0]);
        assertSame(expected, extensions.snapshot());
        assertThrows(IllegalArgumentException.class, () -> extensions.require(next));
        for (var builder : retained) assertThrows(IllegalStateException.class, builder::build);
    }

}
