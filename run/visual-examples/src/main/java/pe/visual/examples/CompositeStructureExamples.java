package pe.visual.examples;

import it.unimi.dsi.fastutil.objects.Object2IntMaps;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.structure.*;
import com.circulation.prototype_eng.structure.WorldStructureSource;
import com.circulation.prototype_eng.structure.tool.StructureBuildPlan;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import pe.visual.examples.run.NativeChecks;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** Run-only adapters. Actual definitions live in 12/23 scripts; this class never mirrors their geometry. */
public final class CompositeStructureExamples {
    public static final String NESTED = "nested_structure", EXPANDABLE = "expandable_structure";
    private CompositeStructureExamples() {}
    public static ResourceLocation id(String path) { return new ResourceLocation("pe_run_tests", path); }
    public static String name(String value) { return value.startsWith("pe_run_tests:") ? value.substring("pe_run_tests:".length()) : value; }
    public static boolean known(String value) { return List.of(NESTED, EXPANDABLE).contains(name(value)); }

    /** Derives every placement from the canonical registered graph and its actual slice path. */
    public static @Nullable Map<BlockPos, Block> placement(String requested, int repeats) {
        String name = name(requested);
        if (!known(name)) return null;
        if (repeats < 1 || repeats > 4 || name.equals(NESTED) && repeats != 1)
            throw new IllegalArgumentException("Nested has one instance; expandable allows 1..4 slices");
        var type = Objects.requireNonNull(MachineTypeRegistry.INSTANCE.get(id(name)), "12 composite preinit script is not loaded");
        var shape = MachineTypeRegistry.INSTANCE.structure(type);
        var catalog = StructureCatalogView.capture(new StructureCatalog(List.of(new StructureDefinition(shape))), new UUID(0, 23), 0);
        var expanded = StructureBuildPlan.expand(catalog, type.structureId(), BlockOrientation.NORTH_UP, Object2IntMaps.singleton("0/0", repeats));
        var blocks = new Object2ObjectLinkedOpenHashMap<BlockPos, Block>();
        for (var cell : expanded.cells()) {
            var display = Objects.requireNonNull(cell.display(), "Composite example has an unknown material");
            if (!display.complete()) throw new IllegalStateException("Composite example has partial material facts");
            var block = ForgeRegistries.BLOCKS.getValue(display.options().getFirst().blockId());
            if (block == null || block == Blocks.AIR) throw new IllegalStateException("Composite material is not registered");
            blocks.put(cell.position(), block);
        }
        blocks.put(BlockPos.ORIGIN, Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(id(name + "_controller"))));
        return blocks;
    }

    /** Explicit player command: captures the real World on its owner, evaluates only the frozen snapshot on a worker. */
    public static void check(World world, BlockPos position, int expectedRepeats, boolean expectedMatch) {
        if (!world.isBlockLoaded(position, false)) throw new IllegalArgumentException("Composite controller is not loaded");
        if (!(world.getTileEntity(position) instanceof MachineControllerHost controller) || !known(controller.type().id().toString()))
            throw new IllegalArgumentException("Expected an actual composite-example controller");
        if (expectedMatch && controller.type().id().equals(id(EXPANDABLE)) && (expectedRepeats < 1 || expectedRepeats > 4))
            throw new IllegalArgumentException("A matched expandable example must have 1..4 slices");
        var source = new WorldStructureSource(new Host(world, controller));
        var shape = MachineTypeRegistry.INSTANCE.structure(controller.type());
        var plan = new StructurePlan(shape, position, controller.orientation());
        var snapshot = StructureSnapshot.capture(plan.reads(), source);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> plan.evaluate(snapshot)).get(5, TimeUnit.SECONDS);
            NativeChecks.check("composite_actual_world_status", result.status() == (expectedMatch ? StructurePredicateResult.MATCHED : StructurePredicateResult.MISMATCH)
                && result.isCurrent(source));
            if (!expectedMatch) return;
            var match = Objects.requireNonNull(result.match());
            boolean nested = controller.type().id().equals(id(NESTED));
            int expectedBlocks = nested ? 26 : 13 + 9 * expectedRepeats;
            NativeChecks.check("composite_actual_block_count", match.allBlocks().size() == expectedBlocks);
            var pending = new ObjectArrayList<StructureMatch>(); pending.add(match);
            int pods = 0, markers = 0, slices = 0, caps = 0;
            while (!pending.isEmpty()) {
                var node = pending.removeLast();
                if (node.structure().id().equals(id("nested_shared_pod"))) pods++;
                if (node.structure().id().equals(id("nested_marker"))) markers++;
                if (node.structure().id().equals(id("expandable_body"))) {
                    slices++;
                    NativeChecks.check("composite_actual_repetitions", node.repetitions() == expectedRepeats);
                }
                if (node.structure().id().equals(id("expandable_cap"))) {
                    caps++;
                    var rotation = StructureTransform.of(controller.orientation());
                    NativeChecks.check("composite_actual_cap_origin", node.origin().equals(position.add(rotation.apply(new BlockPos(0, expectedRepeats + 1, 3)))));
                }
                pending.addAll(node.children());
            }
            NativeChecks.check("composite_actual_occurrence_tree", nested ? pods == 2 && markers == 2 && slices == 0 && caps == 0
                : pods == 0 && markers == 0 && slices == 1 && caps == 1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Composite check interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Composite frozen evaluation failed", failure);
        }
    }

    private record Host(World world, MachineControllerHost controller) implements MachineLifecycleHost {
        @Override public MachineIdentity identity() { return new MachineIdentity(controller.type().id(), world.provider.getDimension(), controller.blockEntity().getPos()); }
        @Override public void requireServerThread() {
            if (world.isRemote || world.getMinecraftServer() == null || !world.getMinecraftServer().isCallingFromMinecraftThread())
                throw new IllegalStateException("Composite capture requires the actual server thread");
        }
    }
}
