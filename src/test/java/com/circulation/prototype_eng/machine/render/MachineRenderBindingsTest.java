package com.circulation.prototype_eng.machine.render;

import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.render.GeckoModelBinding;
import com.circulation.prototype_eng.api.client.render.GeckoStructureBinding;
import com.circulation.prototype_eng.api.client.render.MachineRenderBindings;
import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.client.render.StructureRenderAnchors;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("resource") // Logical registration tokens are scoped by the local test registry.
class MachineRenderBindingsTest {
    static GeckoModelBinding model(String name) {
        return new GeckoModelBinding(MachineRenderSnapshotTest.id("geo/" + name + ".json"), MachineRenderSnapshotTest.id("textures/" + name + ".png"),
            MachineRenderSnapshotTest.id("animations/" + name + ".json"), "idle", List.of("default_base", "default_overlay"),
            1, 2, 3, 1, MachineRenderPass.BLOOM_TRANSPARENT, true);
    }

    @Test
    void acceptedSliceStepsAndZeroParentChildOriginsAreNotRotatedOrOffsetAgain() {
        try (var fixture = new MachineRenderSnapshotTest.Fixture(MachineRenderSnapshotTest.nested(), BlockOrientation.EAST_UP)) {
            fixture.accept();
            var registry = new MachineRenderBindings();
            registry.bindStructure(MachineRenderSnapshotTest.MACHINE, MachineRenderSnapshotTest.id("repeated"), new GeckoStructureBinding(model("slices")));
            registry.bindStructure(MachineRenderSnapshotTest.MACHINE, MachineRenderSnapshotTest.id("zero"), new GeckoStructureBinding(model("zero")));
            registry.bindStructure(MachineRenderSnapshotTest.MACHINE, MachineRenderSnapshotTest.id("tail"), new GeckoStructureBinding(model("tail")));
            var anchors = StructureRenderAnchors.select(fixture.capture(), registry.view());
            assertEquals(List.of(new BlockPos(0, 4, 2), new BlockPos(2, 4, 2), new BlockPos(0, 7, 2), new BlockPos(-7, 4, 10)),
                anchors.stream().map(StructureRenderAnchors.Anchor::origin).toList());
            assertEquals(List.of(List.of(0), List.of(0), List.of(1), List.of(2, 0)), anchors.stream().map(a -> a.part().occurrencePath()).toList());
            assertEquals(List.of(0, 1, 0, -1), anchors.stream().map(a -> a.part().slice()).toList());
            for (var anchor : anchors) assertEquals(new Vec3d(-3, 3, 1), anchor.modelOffset());
            registry.bindStructure(MachineRenderSnapshotTest.MACHINE, MachineRenderSnapshotTest.id("zero"),
                new GeckoStructureBinding(model("zero"), GeckoStructureBinding.SliceMode.STRUCTURE_ONLY));
            var single = StructureRenderAnchors.select(fixture.capture(), registry.view());
            assertEquals(5, single.size());
            assertEquals(new BlockPos(0, 2, 9), single.get(3).origin());
            assertEquals(-1, single.get(3).part().slice());
        }
    }

    @Test
    void typedLayersOverrideDefaultsAndNoAnimationResourceProducesNoLayers() {
        var binding = model("animated");
        assertEquals(List.of("default_base", "default_overlay"), binding.effectiveLayers(new MachineAnimationState.Snapshot(1, List.of())));
        assertEquals(List.of("base", "overlay"), binding.effectiveLayers(new MachineAnimationState.Snapshot(2, List.of("base", "overlay"))));
        var single = new GeckoModelBinding(binding.geo(), binding.texture(), binding.animation(), "idle", List.of(), 0, 0, 0, 0, MachineRenderPass.DEFAULT, false);
        assertEquals(List.of("idle"), single.effectiveLayers(null));
        assertEquals(List.of(), new GeckoModelBinding(binding.geo(), binding.texture()).effectiveLayers(new MachineAnimationState.Snapshot(1, List.of("active"))));
    }

    @Test
    void unformedFallbackUsesActualComponentIdsAndFormedStructureDeclarationsKeepPriority() {
        var registry = new MachineRenderBindings();
        registry.bindMachine(MachineRenderSnapshotTest.MACHINE, model("machine"));
        registry.bindComponent(MachineAnimationState.TYPE.id(), model("animation"));
        registry.bindComponent(MachineRenderSnapshotTest.id("absent"), model("absent"));
        registry.bindStructure(MachineRenderSnapshotTest.MACHINE, MachineRenderSnapshotTest.id("absent_shape"), new GeckoStructureBinding(model("structure")));
        var snapshot = new MachineRenderSnapshot(new ControllerDisplayData(MachineRenderSnapshotTest.MACHINE, BlockOrientation.NORTH_UP, false, 1, 1),
            new MachineRenderSnapshot.CatalogToken(MachineRenderSnapshotTest.SESSION, 0), BlockPos.ORIGIN, null, List.of(), List.of(),
            List.of(MachineAnimationState.TYPE.id()), new MachineAnimationState.Snapshot(1, List.of("active")));
        var anchors = StructureRenderAnchors.select(snapshot, registry.view());
        assertEquals(List.of(StructureRenderAnchors.Kind.MACHINE, StructureRenderAnchors.Kind.COMPONENT), anchors.stream().map(a -> a.part().kind()).toList());
        for (var anchor : anchors) assertEquals(List.of("active"), anchor.layers());
        assertTrue(StructureRenderAnchors.select(ControllerRenderChunksTest.snapshot(1, 2, 0, 1), registry.view()).isEmpty());
    }

    @Test
    void oldRegistrationCloseCannotRemoveItsReplacementAndOldEpochIsRevokedBeforeCallbacks() {
        var registry = new MachineRenderBindings();
        var first = registry.bindMachine(MachineRenderSnapshotTest.MACHINE, model("first"));
        var captured = registry.view();
        var calls = new int[1];
        try (@SuppressWarnings("unused") var listener = registry.onInvalidated(() -> {
            assertFalse(registry.current(captured));
            calls[0]++;
        })) {
            var second = registry.bindMachine(MachineRenderSnapshotTest.MACHINE, model("second"));
            first.close();
            assertEquals(1, calls[0]);
            assertEquals(model("second"), registry.view().machines().get(MachineRenderSnapshotTest.MACHINE));
            second.close();
            assertEquals(2, calls[0]);
            assertTrue(registry.view().machines().isEmpty());
            second.close();
            assertEquals(2, calls[0]);
        }
    }

    @Test
    void bindingsAndAcceptedAnchorsRejectOverflowInsteadOfClamping() {
        assertThrows(IllegalArgumentException.class, () -> new GeckoModelBinding(MachineRenderSnapshotTest.id("geo/x.json"),
            MachineRenderSnapshotTest.id("textures/x.png"), null, null, List.of(), Double.NaN, 0, 0, 0, MachineRenderPass.DEFAULT, false));
        assertThrows(ArithmeticException.class, () -> new MachineRenderSnapshot.Occurrence(-1, -1, MachineRenderSnapshotTest.id("slice"),
            new BlockPos(Integer.MAX_VALUE, 0, 0), 2, StructurePreviewProvider.Kind.SLICE,
            new BlockPos(1, 0, 0), false, List.of()));
    }
}
