package com.circulation.prototype_eng.integration.crafttweaker.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePlan;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicateResult;
import com.circulation.prototype_eng.api.structure.StructureQueries;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.api.structure.json.StructureBlockResolver;
import com.circulation.prototype_eng.api.structure.json.StructurePredicateRegistrationEvent;
import com.circulation.prototype_eng.api.structure.json.StructurePredicateRegistry;
import com.circulation.prototype_eng.integration.crafttweaker.ScriptRegistrationEvents;
import com.circulation.prototype_eng.structure.json.StructureJsonLoader;
import crafttweaker.api.data.IData;
import crafttweaker.api.world.IBlockPos;
import crafttweaker.api.world.IWorld;
import crafttweaker.zenscript.expand.ExpandAnyDict;
import crafttweaker.zenscript.expand.ExpandBool;
import crafttweaker.zenscript.expand.ExpandDataStringDict;
import crafttweaker.zenscript.expand.ExpandInt;
import crafttweaker.zenscript.expand.ExpandString;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ZenCustomStructureBridgeTest {
    @Test
    void compiledWorldSamplerAndDetachedValidatorRegisterBeforeJsonFreezeAndMatchRotatedNestedBases() throws Exception {
        Harness.owner = Thread.currentThread();
        Harness.visits.set(0);
        var module = compile("""
            import mods.prototype_eng.structure.Predicate;
            import mods.prototype_eng.structure.Query;
            import mods.prototype_eng.structure.Facts;
            import mods.prototype_eng.structure.Pattern;
            import mods.prototype_eng.structure.Structure;
            import crafttweaker.world.IWorld;
            import crafttweaker.world.IBlockPos;
            import crafttweaker.data.IData;
            import tests.prototype_eng.CustomStructureHarness;

            var query = Query.create("script_structure:brightness", function(world as IWorld, pos as IBlockPos) {
                return world.getBrightness(pos) as IData;
            });
            CustomStructureHarness.query(query);
            var callback = Predicate.custom(["biomeName", "biomeId", "dimension", "light", "worldTime"],
                [query], [[0,0,0], [1,0,0]], [[0,0,0]], function(ctx as Facts) {
                    CustomStructureHarness.visited();
                    return ctx.x == 10 && ctx.y == 64 && ctx.offsetZ == 6
                        && ctx.biomeName == "Birch Forest" && ctx.biomeId == "other:plains"
                        && ctx.worldTime == 6000 && ctx.read(query).asInt() == 17
                        && ctx.at(1,0,0).light == 9 && ctx.controller.dimension == 12;
                });
            var validator = Predicate.all([Predicate.anyOf([callback.at(1,0,0)])]);
            validator.register("script_structure:custom");
            var child = Structure.slice("script_structure:child", "NORTH_UP", 3, 0, 0, false,
                Pattern.create(), 1, 1, 0, 1, 0, [validator], []);
            CustomStructureHarness.shape(Structure.template("script_structure:root", "NORTH_UP", 2, 0, 0,
                false, Pattern.create(), [], [child]));
            """);
        Objects.requireNonNull(module.getMain()).run();
        assertEquals(0, Harness.visits.get(), "Registration must not execute the matcher");
        var factories = new StructurePredicateRegistry();
        ScriptRegistrationEvents.structurePredicates(new StructurePredicateRegistrationEvent(factories));
        factories.freeze();
        var catalog = StructureJsonLoader.readSources(Map.of("custom.json", """
            {"id":"script_structure:json","type":"template","offset":{"x":5,"y":0,"z":0},
             "pattern":[],"validators":[{"id":"script_structure:custom"}]}
            """)).resolve(new StructureCatalog(List.of()), factories, new NoBlocks());
        var javaShape = Objects.requireNonNull(Harness.shape).definition().shape();
        var jsonShape = Objects.requireNonNull(catalog.get(new ResourceLocation("script_structure", "json"))).shape();
        assertEquals(0, Harness.visits.get(), "JSON resolution must not infer callback results");
        var controller = new BlockPos(10, 64, 10);
        var source = new Source(Harness.owner, Objects.requireNonNull(Harness.query));
        try (var worker = Executors.newSingleThreadExecutor()) {
            for (var shape : List.of(javaShape, jsonShape)) {
                var plan = new StructurePlan(shape, controller, BlockOrientation.EAST_UP);
                var snapshot = StructureSnapshot.capture(plan.reads(), source);
                var evaluation = worker.submit(() -> plan.evaluate(snapshot)).get(5, TimeUnit.SECONDS);
                assertEquals(StructurePredicateResult.MATCHED, evaluation.status());
                assertTrue(evaluation.isCurrent(source));
                source.brightness = 18;
                assertFalse(evaluation.isCurrent(source), "Custom IData participates in the existing publication proof");
                var changed = StructureSnapshot.capture(plan.reads(), source);
                assertEquals(StructurePredicateResult.MISMATCH,
                    worker.submit(() -> plan.evaluate(changed)).get(5, TimeUnit.SECONDS).status());
                source.brightness = 17;
            }
        }
        assertEquals(4, Harness.visits.get());
    }

    @SuppressWarnings("SameParameterValue") // The single fixture compiles the complete callback/registration program.
    private ZenModule compile(String script) throws Exception {
        var output = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment, new GenericErrorLogger(new PrintStream(output, true, StandardCharsets.UTF_8)));
        for (var type : List.of(ZenStructure.class, ZenStructurePattern.class, ZenStructurePredicate.class,
            ZenStructureValidator.class, ZenStructureFacts.class, ZenStructureCapture.class, ZenStructureQuery.class,
            IData.class, IWorld.class, IBlockPos.class, Harness.class))
            registry.registerNativeClass(type);
        for (var expansion : List.of(ExpandAnyDict.class, ExpandDataStringDict.class, ExpandInt.class, ExpandBool.class, ExpandString.class))
            registry.registerExpansion(expansion);
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(classes);
        var parsed = new ZenParsedFile("custom_structure.zs", "CustomStructureScript",
            new ZenTokener(new StringReader(script), environment, "custom_structure.zs", false), global);
        ZenModule.compileScripts("custom_structure.zs", List.of(parsed), global, false);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        ZenModule.classes.clear();
        ZenModule.loadedClasses.clear();
        return new ZenModule(classes, getClass().getClassLoader());
    }

    @ZenClass("tests.prototype_eng.CustomStructureHarness")
    @SuppressWarnings("unused") // Compiled script fixture.
    public static final class Harness {
        private static final AtomicInteger visits = new AtomicInteger();
        private static Thread owner;
        private static ZenStructure shape;
        private static ZenStructureQuery query;

        @ZenMethod
        public static void query(ZenStructureQuery value) {
            query = value;
        }

        @ZenMethod
        public static void shape(ZenStructure value) {
            shape = value;
        }

        @ZenMethod
        public static void visited() {
            assertNotSame(owner, Thread.currentThread());
            visits.incrementAndGet();
        }
    }

    private static final class Source implements StructureReadSource {
        private final Thread owner;
        private final ZenStructureQuery custom;
        private int brightness = 17;

        private Source(Thread owner, ZenStructureQuery custom) {
            this.owner = owner;
            this.custom = custom;
        }

        @Override
        public void requireServerThread() {
            assertSame(owner, Thread.currentThread());
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            requireServerThread();
            return true;
        }

        @Override
        @SuppressWarnings("unchecked") // Exact query descriptor branches supply their declared type.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            Object fact;
            if (query == custom.value()) fact = new NBTTagInt(brightness);
            else if (query == StructureQueries.BIOME_NAME) fact = "Birch Forest";
            else if (query == StructureQueries.BIOME) fact = new ResourceLocation("other", "plains");
            else if (query == StructureQueries.DIMENSION) fact = 12;
            else if (query == StructureQueries.LIGHT) fact = 9;
            else if (query == StructureQueries.WORLD_TIME) fact = 6000L;
            else return fail("Unexpected query: " + query.id());
            return (T) fact;
        }
    }

    private static final class NoBlocks implements StructureBlockResolver {
        @Override
        public StructurePredicate block(ResourceLocation id) {
            return fail("No block resolution expected");
        }

        @Override
        public StructurePredicate state(ResourceLocation id, int meta, Map<String, String> nbt) {
            return fail("No block resolution expected");
        }

        @Override
        public StructurePredicate regex(String expression) {
            return fail("No block resolution expected");
        }

        @Override
        public StructureDisplay.BlockOption displayState(ResourceLocation id, int meta, Map<String, String> nbt) {
            return fail("No display resolution expected");
        }

        @Override
        public List<StructureDisplay.BlockOption> displayRegex(String expression) {
            return fail("No display resolution expected");
        }
    }
}
