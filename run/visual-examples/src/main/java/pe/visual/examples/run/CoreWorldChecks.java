package pe.visual.examples.run;

import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.structure.*;
import com.circulation.prototype_eng.api.structure.json.*;
import com.circulation.prototype_eng.integration.crafttweaker.structure.*;
import com.circulation.prototype_eng.structure.WorldStructureSource;
import com.circulation.prototype_eng.structure.json.StructureJsonLoader;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.api.world.IWorld;
import crafttweaker.api.world.IBlockPos;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Actual user-world capture diagnostics. Never invoked by the standalone no-bootstrap verifier.
 */
final class CoreWorldChecks {
    static ZenStructureQuery query;
    static ZenStructure shape;
    static ZenStructurePredicate predicate;
    private static long totalCaptures;
    private static Thread owner;
    private static World activeWorld;
    private static final Object2ObjectLinkedOpenHashMap<BlockPos, Sample> samples = new Object2ObjectLinkedOpenHashMap<>();
    private static final Object2LongOpenHashMap<BlockPos> counts = new Object2LongOpenHashMap<>();
    private static final ObjectArrayList<Validation> validations = new ObjectArrayList<>();
    private static String lastCommand = "world-query not executed";
    private static WeakReference<World> previousWorld = new WeakReference<>(null);
    private static BlockPos previousController;
    private static BlockOrientation previousOrientation;
    private static StructurePlan previousPlan;
    private static StructureSnapshot previousSnapshot;
    private static StructureEvaluation previousEvaluation;
    private static boolean previousMatched;
    private static int previousLight;

    private CoreWorldChecks() {
    }

    static void observe(IWorld scriptWorld, IBlockPos scriptPosition, int light) {
        var world = CraftTweakerMC.getWorld(scriptWorld);
        requireOwner(world);
        var position = CraftTweakerMC.getBlockPos(scriptPosition).toImmutable();
        if (!world.isBlockLoaded(position, false) || light != world.getLight(position))
            throw new IllegalStateException("SDK brightness differs from the actual loaded World.getLight at " + position);
        totalCaptures = Math.incrementExact(totalCaptures);
        if (activeWorld == world) {
            if (Thread.currentThread() != owner) throw new IllegalStateException("World query capture left its owner");
            samples.put(position, new Sample(position, light, Thread.currentThread().getName()));
            counts.addTo(position, 1);
        }
    }

    static void validation(ZenStructureFacts facts) {
        if (owner == null) return;
        if (Thread.currentThread() == owner)
            throw new IllegalStateException("Explicit world-query validator ran on capture owner");
        // Only frozen facts are read here; no World/TE/source is accessible to this worker observer.
        validations.add(new Validation(facts.x(), facts.y(), facts.z(), facts.offsetX(), facts.offsetY(), facts.offsetZ(),
            facts.read(Objects.requireNonNull(query)).asInt(), facts.dimension(), Thread.currentThread().getName()));
    }

    static String stats() {
        return "actual query captures=" + totalCaptures + "; " + lastCommand;
    }

    static String run(World world, BlockPos controller, BlockOrientation orientation, boolean expectedMatch) {
        requireOwner(world);
        var tile = world.getTileEntity(controller);
        if (!(tile instanceof MachineControllerHost actual) || !actual.type().id().equals(CoreCases.id("core_world")))
            throw new IllegalArgumentException("world-query requires the actual core_world controller");
        if (actual.orientation() != orientation)
            throw new IllegalArgumentException("Use the actual controller orientation: " + actual.orientation());
        var canonical = MachineTypeRegistry.INSTANCE.structure(actual.type());
        if (canonical != Objects.requireNonNull(shape, "21 core world script did not execute").definition().shape())
            throw new IllegalStateException("World shape is not the canonical registered definition");
        var source = new WorldStructureSource(new Host(world, actual));
        var factories = new StructurePredicateRegistry();
        factories.register(CoreCases.id("core_world_gate"), Set.of(), (_, _) -> Objects.requireNonNull(predicate).value());
        factories.freeze();
        var fixture = Path.of("scripts", "fixtures", "core_world.json");
        if (!Files.isRegularFile(fixture)) fixture = Path.of("run", "scripts", "fixtures", "core_world.json");
        owner = Thread.currentThread();
        activeWorld = world;
        samples.clear();
        counts.clear();
        validations.clear();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var catalog = StructureJsonLoader.readSources(Map.of("core_world.json", Files.readString(fixture)))
                                             .resolve(new StructureCatalog(List.of()), factories, new NoBlocks());
            var json = Objects.requireNonNull(catalog.get(CoreCases.id("core_world_json"))).shape();
            var center = controller.add(StructureTransform.of(orientation).apply(new BlockPos(6, 0, 0)));
            var neighbor = controller.add(StructureTransform.of(orientation).apply(new BlockPos(7, 0, 0)));
            boolean samePrevious = previousWorld.get() == world && controller.equals(previousController) && orientation == previousOrientation;
            int light = world.getLight(center);
            if (samePrevious && light != previousLight) {
                NativeChecks.check("core_world_previous_query_proof_stale", !previousEvaluation.isCurrent(source));
                var held = worker.submit(() -> previousPlan.evaluate(previousSnapshot)).get(5, TimeUnit.SECONDS);
                NativeChecks.check("core_world_previous_snapshot_frozen", held.status() == (previousMatched ? StructurePredicateResult.MATCHED : StructurePredicateResult.MISMATCH));
            }
            int beforeCurrent = validations.size();
            StructurePlan newJsonPlan = null;
            StructureSnapshot newJsonSnapshot = null;
            StructureEvaluation newJsonEvaluation = null;
            for (var definition : List.of(canonical, json)) {
                var plan = new StructurePlan(definition, controller, orientation);
                var snapshot = StructureSnapshot.capture(plan.reads(), source);
                var result = worker.submit(() -> plan.evaluate(snapshot)).get(5, TimeUnit.SECONDS);
                var expected = expectedMatch ? StructurePredicateResult.MATCHED : StructurePredicateResult.MISMATCH;
                NativeChecks.check("core_world_" + definition.id().getPath() + "_actual_status", result.status() == expected && result.isCurrent(source));
                if (definition == json) {
                    newJsonPlan = plan;
                    newJsonSnapshot = snapshot;
                    newJsonEvaluation = result;
                }
            }
            NativeChecks.check("core_world_actual_query_positions", samples.containsKey(center) && samples.containsKey(neighbor) && samples.containsKey(controller));
            NativeChecks.check("core_world_actual_light_condition", (samples.get(center).light() >= 8) == expectedMatch);
            NativeChecks.check("core_world_actual_worker_validations", validations.size() - beforeCurrent == 2);
            for (int index = beforeCurrent; index < validations.size(); index++) {
                var frozen = validations.get(index);
                NativeChecks.check("core_world_frozen_value_and_rotation_" + index,
                    new BlockPos(frozen.x(), frozen.y(), frozen.z()).equals(center)
                        && frozen.queryLight() == samples.get(center).light() && frozen.dimension() == world.provider.getDimension()
                        && frozen.offsetX() * frozen.offsetX() + frozen.offsetY() * frozen.offsetY() + frozen.offsetZ() * frozen.offsetZ() == 36);
            }
            previousWorld = new WeakReference<>(world);
            previousController = controller.toImmutable();
            previousOrientation = orientation;
            previousPlan = newJsonPlan;
            previousSnapshot = newJsonSnapshot;
            previousEvaluation = newJsonEvaluation;
            previousMatched = expectedMatch;
            previousLight = samples.get(center).light();
            lastCommand = "owner=" + owner.getName() + " controller=" + controller + " orientation=" + orientation
                + " status=" + (expectedMatch ? "MATCHED" : "MISMATCH") + " actual samples=" + samples.values()
                + " callsByPosition=" + counts + " frozen worker observations=" + validations;
            return stats();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("World query check interrupted", interrupted);
        } catch (java.io.IOException | java.util.concurrent.ExecutionException |
                 java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Actual world query check failed", failure);
        } finally {
            owner = null;
            activeWorld = null;
        }
    }

    /**
     * NORTH_UP local block table; the command rotates these offsets with the actual controller.
     */
    static BlockPos2ObjectMap<Block> placement() {
        var blocks = new BlockPos2ObjectMap<Block>();
        blocks.put(BlockPos.ORIGIN, Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(CoreCases.id("core_world_controller"))));
        blocks.put(new BlockPos(-1, 0, 0), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng", "input_hatch_4"))));
        blocks.put(new BlockPos(1, 0, 0), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng", "output_hatch_4"))));
        blocks.put(new BlockPos(6, 0, 0), Blocks.REDSTONE_LAMP);
        for (var position : List.of(new BlockPos(5, 0, 0), new BlockPos(7, 0, 0), new BlockPos(6, 1, 0), new BlockPos(6, 0, -1), new BlockPos(6, 0, 1)))
            blocks.put(position, Blocks.STONE);
        blocks.put(new BlockPos(6, -1, 0), Blocks.REDSTONE_BLOCK);
        return blocks;
    }

    private static void requireOwner(World world) {
        if (world.isRemote || world.getMinecraftServer() == null || !world.getMinecraftServer().isCallingFromMinecraftThread())
            throw new IllegalStateException("Actual World query checks require the server owner");
    }

    private record Sample(BlockPos position, int light, String ownerThread) {
    }

    private record Validation(int x, int y, int z, int offsetX, int offsetY, int offsetZ, int queryLight, int dimension,
                              String workerThread) {
    }

    private record Host(World world, MachineControllerHost actual) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(actual.type().id(), world.provider.getDimension(), actual.blockEntity().getPos());
        }

        @Override
        public void requireServerThread() {
            requireOwner(world);
            var tile = actual.blockEntity();
            if (tile.getWorld() != world || tile.isInvalid() || world.getTileEntity(tile.getPos()) != tile)
                throw new IllegalStateException("World query controller attachment is no longer current");
        }
    }

    private static final class NoBlocks implements StructureBlockResolver {
        private IllegalArgumentException unexpected() {
            return new IllegalArgumentException("World JSON fixture has no block resolver entries");
        }

        @Override
        public StructurePredicate block(ResourceLocation id) {
            throw unexpected();
        }

        @Override
        public StructurePredicate state(ResourceLocation id, int meta, Map<String, String> nbt) {
            throw unexpected();
        }

        @Override
        public StructurePredicate regex(String expression) {
            throw unexpected();
        }

        @Override
        public StructureDisplay.BlockOption displayState(ResourceLocation id, int meta, Map<String, String> nbt) {
            throw unexpected();
        }

        @Override
        public List<StructureDisplay.BlockOption> displayRegex(String expression) {
            throw unexpected();
        }
    }
}
