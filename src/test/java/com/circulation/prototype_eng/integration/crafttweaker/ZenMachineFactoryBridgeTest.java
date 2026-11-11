package com.circulation.prototype_eng.integration.crafttweaker;

import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenParallelismCallback;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenParallelismSelection;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipe;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBindings;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import crafttweaker.api.data.IData;
import crafttweaker.zenscript.expand.ExpandAnyDict;
import crafttweaker.zenscript.expand.ExpandBool;
import crafttweaker.zenscript.expand.ExpandDataStringDict;
import crafttweaker.zenscript.expand.ExpandInt;
import crafttweaker.zenscript.expand.ExpandString;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import stanhebben.zenscript.ZenModule;
import stanhebben.zenscript.ZenParsedFile;
import stanhebben.zenscript.ZenTokener;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import stanhebben.zenscript.impl.GenericCompileEnvironment;
import stanhebben.zenscript.impl.GenericErrorLogger;
import stanhebben.zenscript.impl.GenericRegistry;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ZenMachineFactoryBridgeTest {
    @Test
    void compiledFactoriesKeepOwnerScanningCooperativeCreationSelectionAndExactRestoration() throws Exception {
        Harness.machines.clear();
        Harness.external = new ZenRecipe(RecipeProcessNbt.builder(new MachineRecipe(new ResourceLocation("ct_factory", "external"), 10, List.of()), "v1").build());
        var module = compile("""
            import mods.prototype_eng.MachineType;
            import mods.prototype_eng.MachineFactory;
            import mods.prototype_eng.recipe.Bindings;
            import mods.prototype_eng.recipe.Recipe;
            import mods.prototype_eng.recipe.Pool;
            import mods.prototype_eng.recipe.ParallelismSelection;
            import tests.prototype_eng.FactoryHarness;
            var names = Bindings.create();
            var recipe = Recipe.withSelection("ct_factory:recipe", "v1", 10, [], [], 4,
                ParallelismSelection.create(function(suggested as int) { return suggested + 1; }));
            FactoryHarness.capture(names, recipe,
                Pool.create("ct_factory:pool", "pool.title", [recipe]),
                Pool.create("ct_factory:external_pool", "pool.title", [FactoryHarness.external()]));
            FactoryHarness.machine(MachineType.create("ct_factory:thread", "machine.title", "ct_factory:shape")
                .recipePool("ct_factory:pool").attribute("prototype_eng:process_parallelism", 4)
                .processing(names, 8, 8));
            FactoryHarness.machine(MachineType.create("ct_factory:cross", "machine.title", "ct_factory:shape")
                .factory(MachineFactory.crossRecipe()).recipePool("ct_factory:pool")
                .attribute("prototype_eng:process_parallelism", 4).processing(names, 8, 8));
            FactoryHarness.machine(MachineType.create("ct_factory:addon", "machine.title", "ct_factory:shape")
                .factory(FactoryHarness.factory()).recipePool("ct_factory:external_pool")
                .processing(names, 8, 8).executionMode("MAIN_THREAD"));
            """);
        Objects.requireNonNull(module.getMain()).run();
        var declarations = Harness.machines.stream().map(value -> value.buildAssembly("v1")).toList();
        var source = RecipeRegistry.INSTANCE;
        source.register(Harness.pool.value());
        source.register(Harness.externalPool.value());
        source.declareRevision(Harness.recipe.recipe(), "v1");
        source.declareRevision(Harness.external.recipe(), "v1");
        var resolver = Harness.names.resolver();
        resolver.install(resolver.prepare(resolver.snapshot(), List.of(Harness.recipe.codec(), Harness.external.codec())));
        source.bindProcessCodecs(resolver);
        source.freeze(declarations.stream().map(MachineAssembly::type).toList());

        for (int i = 0; i < declarations.size(); i++) {
            var assembly = declarations.get(i);
            var owner = assembly.create(new Host(assembly.type()), new MachineLifecycleRegistry());
            Objects.requireNonNull(owner.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
            if (i == 0) assertInstanceOf(MultithreadController.class, owner);
            if (i == 1) assertInstanceOf(CrossRecipeController.class, owner);
            if (i == 2) assertInstanceOf(AddonOwner.class, owner);
            var scanner = owner.recipeScanners().getFirst();
            var attempt = scanner.beginScan(new ScanLimits(8, 8));
            int yields = 0;
            while (!attempt.finished() && yields++ < 1000) attempt.advance(new RecipeWorkBudget(1), () -> true);
            assertTrue(attempt.finished(), "Actual scanner must progress across owner budget yields");
            assertTrue(yields > 1);
            var work = Objects.requireNonNull(owner.components().get(MachineWorkSlots.TYPE));
            assertFalse(work.activeProcesses().isEmpty());
            if (i < 2) {
                var process = work.activeProcesses().getFirst();
                assertEquals(5, RecipeParallelism.effective(process), "Pure script selection runs before admission and may exceed baseline");
                assertNotNull(process.components().get(DataComponents.PROCESS));
                assertEquals(ExecutionMode.PLANNED_PARALLEL, Objects.requireNonNull(owner.components().get(MachineScheduling.TYPE)).mode());
                assertEquals(i == 1, work.dynamicRoster());
            } else {
                assertNull(work.activeProcesses().getFirst().components().get(DataComponents.PROCESS), "Addon codec does not declare script data");
                assertEquals(ExecutionMode.MAIN_THREAD, Objects.requireNonNull(owner.components().get(MachineScheduling.TYPE)).mode());
                assertTrue(((AddonOwner) owner).creations > 0);
            }
            assertThrows(IllegalStateException.class, () -> scanner.decorateProcessFactory(value -> value, true));
            var saved = assembly.encode(owner);
            var restored = assembly.restore(new Host(assembly.type()), saved, new MachineLifecycleRegistry());
            assertEquals(owner.getClass(), restored.getClass());
            var original = work.activeProcesses().getFirst();
            var retained = Objects.requireNonNull(restored.components().get(MachineWorkSlots.TYPE)).activeProcesses().getFirst();
            assertNotSame(original, retained);
            assertSame(original.recipe(), retained.recipe());
            assertEquals(RecipeParallelism.effective(original), RecipeParallelism.effective(retained));
        }
    }

    @SuppressWarnings("SameParameterValue") // A reusable real compiler fixture has one focused script here.
    private ZenModule compile(String script) throws Exception {
        var output = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment, new GenericErrorLogger(new PrintStream(output, true, StandardCharsets.UTF_8)));
        for (var type : List.of(ZenMachineType.class, ZenMachineFactory.class, ZenMachineComponent.class,
            ZenRecipeBindings.class, ZenRecipe.class, ZenRecipePool.class, ZenParallelismSelection.class, ZenParallelismCallback.class, IData.class, Harness.class))
            registry.registerNativeClass(type);
        for (var expansion : List.of(ExpandAnyDict.class, ExpandDataStringDict.class, ExpandInt.class, ExpandBool.class, ExpandString.class))
            registry.registerExpansion(expansion);
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(classes);
        var parsed = new ZenParsedFile("factory.zs", "FactoryScript", new ZenTokener(new StringReader(script), environment, "factory.zs", false), global);
        ZenModule.compileScripts("factory.zs", List.of(parsed), global, false);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        ZenModule.classes.clear();
        ZenModule.loadedClasses.clear();
        return new ZenModule(classes, getClass().getClassLoader());
    }

    @ZenClass("tests.prototype_eng.FactoryHarness")
    @SuppressWarnings("unused") // Real compiled script fixture.
    public static final class Harness {
        private static final ObjectArrayList<ZenMachineType> machines = new ObjectArrayList<>();
        private static ZenRecipeBindings names;
        private static ZenRecipe recipe;
        private static ZenRecipe external;
        private static ZenRecipePool pool;
        private static ZenRecipePool externalPool;
        @ZenMethod public static void capture(ZenRecipeBindings value, ZenRecipe declared, ZenRecipePool first, ZenRecipePool second) {
            names = value; recipe = declared; pool = first; externalPool = second;
        }
        @ZenMethod public static void machine(ZenMachineType value) { machines.add(value); }
        @ZenMethod public static ZenRecipe external() { return external; }
        @ZenMethod public static ZenMachineFactory factory() { return new ZenMachineFactory(AddonOwner::new); }
    }

    private static final class AddonOwner extends MultithreadController {
        private int creations;
        private AddonOwner(MachineType type) { super(type); }

        @Override public RecipeScanner createRecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, LongSupplier seeds) {
            return super.createRecipeScanner(host, catalog, seeds).decorateProcessFactory(delegate -> new RecipeProcessFactory() {
                @Override public RecipeProcess create(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
                    throw new AssertionError("Cooperative scanner must keep the owner's beginCreation override");
                }
                @Override public Creation beginCreation(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
                    creations++;
                    return delegate.beginCreation(owner, recipe, seed, tuning);
                }
            }, false);
        }
    }

    private record Host(MachineType type) implements MachineLifecycleHost {
        @Override public MachineIdentity identity() { return new MachineIdentity(type.id(), 0, BlockPos.ORIGIN); }
        @Override public World world() { throw new AssertionError("No Minecraft/Forge bootstrap"); }
        @Override public void requireServerThread() {}
    }
}
