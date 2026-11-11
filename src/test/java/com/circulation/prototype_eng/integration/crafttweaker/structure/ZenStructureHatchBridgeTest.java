package com.circulation.prototype_eng.integration.crafttweaker.structure;

import com.circulation.prototype_eng.api.storage.HatchConfigurationUpdates;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureQueries;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.integration.crafttweaker.hatch.ZenEnergyHatchSide;
import com.circulation.prototype_eng.integration.crafttweaker.hatch.ZenHatchConfiguration;
import com.circulation.prototype_eng.integration.crafttweaker.hatch.ZenHatchReport;
import com.circulation.prototype_eng.integration.crafttweaker.hatch.ZenHatchUpdate;
import com.circulation.prototype_eng.integration.crafttweaker.hatch.ZenResourceHatchSide;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenResource;
import crafttweaker.api.data.IData;
import crafttweaker.zenscript.expand.ExpandAnyDict;
import crafttweaker.zenscript.expand.ExpandBool;
import crafttweaker.zenscript.expand.ExpandDataStringDict;
import crafttweaker.zenscript.expand.ExpandInt;
import crafttweaker.zenscript.expand.ExpandString;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ZenStructureHatchBridgeTest {
    @Test
    void realScriptBuildsExistingShapesAndObservesDeferredConfigurationResults() throws Exception {
        Harness.shape = null;
        Harness.future = new CompletableFuture<>();
        String script = """
            import mods.prototype_eng.structure.Predicate;
            import mods.prototype_eng.structure.Pattern;
            import mods.prototype_eng.structure.Structure;
            import mods.prototype_eng.hatch.ResourceSide;
            import mods.prototype_eng.hatch.EnergySide;
            import mods.prototype_eng.hatch.Configuration;
            import tests.prototype_eng.StructureHatchHarness;

            var pattern = Pattern.create().put(1, 0, 0, Predicate.dimension(12));
            var slice = Structure.slice("bridge:slice", "NORTH_UP", 0, 0, 0, false,
                pattern, 1, 2, 1, 0, 0, [], []);
            StructureHatchHarness.shape(Structure.template("bridge:root", "NORTH_UP", 0, 0, 0, false,
                Pattern.create(), [], [slice]).translated("bridge.structure"));
            var resource = ResourceSide.itemEquivalent(5, 64);
            var energy = EnergySide.create(1000, 10);
            StructureHatchHarness.targets([Configuration.resources(2, resource, null), Configuration.energy(2, null, energy)]);
            var update = StructureHatchHarness.pending();
            if (update.done) {
                StructureHatchHarness.fail("Update completed before the original future");
            } else {
                StructureHatchHarness.before(update.result);
            }
            StructureHatchHarness.complete();
            if (update.done) {
                StructureHatchHarness.after(update.result.status, update.result.hostStatuses);
            } else {
                StructureHatchHarness.fail("Update remained pending after the original future completed");
            }
            """;
        var output = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment, new GenericErrorLogger(new PrintStream(output, true, StandardCharsets.UTF_8)));
        for (var type : List.of(ZenStructure.class, ZenStructurePattern.class, ZenStructurePredicate.class,
            ZenResource.class, ZenResourceHatchSide.class, ZenEnergyHatchSide.class, ZenHatchConfiguration.class,
            ZenHatchUpdate.class, ZenHatchReport.class, IData.class, Harness.class)) registry.registerNativeClass(type);
        for (var expansion : List.of(ExpandAnyDict.class, ExpandDataStringDict.class, ExpandInt.class, ExpandBool.class, ExpandString.class))
            registry.registerExpansion(expansion);
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(classes);
        var parsed = new ZenParsedFile("structure_hatch.zs", "StructureHatchScript",
            new ZenTokener(new StringReader(script), environment, "structure_hatch.zs", false), global);
        ZenModule.compileScripts("structure_hatch.zs", List.of(parsed), global, false);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        ZenModule.classes.clear();
        ZenModule.loadedClasses.clear();
        var module = new ZenModule(classes, getClass().getClassLoader());
        Objects.requireNonNull(module.getMain()).run();
        var shape = Objects.requireNonNull(Harness.shape).definition().shape();
        var plan = new StructureReadPlan.Builder();
        shape.collectReads(plan, BlockPos.ORIGIN, BlockPos.ORIGIN);
        var reads = plan.build();
        assertNotNull(shape.match(StructureSnapshot.capture(reads, new DimensionSource(12)), BlockPos.ORIGIN, BlockPos.ORIGIN));
        assertNull(shape.match(StructureSnapshot.capture(reads, new DimensionSource(13)), BlockPos.ORIGIN, BlockPos.ORIGIN));
    }

    private record DimensionSource(int dimension) implements StructureReadSource {
        @Override
        public void requireServerThread() {
            // Detached in-memory facts; this fixture owns all capture and matching calls.
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            return true;
        }

        @Override
        @SuppressWarnings("unchecked") // The fixture accepts exactly the typed dimension query used by the script.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            if (query != StructureQueries.DIMENSION) throw new AssertionError("Undeclared live read in script fixture");
            return (T) Integer.valueOf(dimension);
        }
    }

    @ZenClass("tests.prototype_eng.StructureHatchHarness")
    @SuppressWarnings("unused") // Called only by the compiled ZenScript fixture.
    public static final class Harness {
        private static ZenStructure shape;
        private static CompletableFuture<HatchConfigurationUpdates.Report> future;

        @ZenMethod
        public static void shape(ZenStructure value) {
            shape = value;
        }

        @ZenMethod
        public static void targets(ZenHatchConfiguration[] values) {
            for (var value : values) Objects.requireNonNull(value.value());
        }

        @ZenMethod
        public static ZenHatchUpdate pending() {
            return new ZenHatchUpdate(future);
        }

        @ZenMethod
        public static void before(@Nullable ZenHatchReport result) {
            assertNull(result);
        }

        @ZenMethod
        public static void complete() {
            future.complete(new HatchConfigurationUpdates.Report(17, HatchReconfigurationStatus.STALE_TARGET, List.of()));
        }

        @ZenMethod
        public static void after(String status, String[] hostStatuses) {
            assertEquals("STALE_TARGET", status);
            assertEquals(0, hostStatuses.length);
        }

        @ZenMethod
        public static void fail(String message) {
            throw new AssertionError(message);
        }
    }
}
