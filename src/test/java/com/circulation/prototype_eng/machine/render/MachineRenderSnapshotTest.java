package com.circulation.prototype_eng.machine.render;

import java.util.Arrays;
import java.util.Objects;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.api.structure.*;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import com.circulation.prototype_eng.structure.SliceStructure;
import com.circulation.prototype_eng.structure.TemplateStructure;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MachineRenderSnapshotTest {
    static final ResourceLocation MACHINE = id("machine");
    static final UUID SESSION = new UUID(17, 19);

    static ResourceLocation id(String path) {
        return new ResourceLocation("render_test", path);
    }

    static final StructurePredicate YES = StructurePredicates.all(List.of());

    static MachineStructure nested() {
        var tail = new TemplateStructure(id("tail"), BlockOrientation.NORTH_UP, new BlockPos(1, 2, 3), true,
            new StructurePattern(Map.of(BlockPos.ORIGIN, YES)), List.of(), List.of());
        var zero = new SliceStructure(id("zero"), BlockOrientation.NORTH_UP, new BlockPos(7, 1, 0), true,
            new StructurePattern(Map.of()), 0, 0, new BlockPos(0, 0, -4), List.of(), List.of(tail));
        var first = new SliceStructure(id("repeated"), BlockOrientation.NORTH_UP, new BlockPos(0, 3, 0), true,
            new StructurePattern(Map.of(BlockPos.ORIGIN, YES)), 2, 2, new BlockPos(0, 0, -2), List.of(), List.of());
        var second = new SliceStructure(id("repeated"), BlockOrientation.NORTH_UP, new BlockPos(0, 6, 0), true,
            new StructurePattern(Map.of(BlockPos.ORIGIN, YES)), 1, 1, new BlockPos(0, 0, -3), List.of(), List.of());
        return new TemplateStructure(id("root"), BlockOrientation.NORTH_UP, new BlockPos(2, 1, 0), true,
            new StructurePattern(Map.of(new BlockPos(3, 0, 0), YES)), List.of(), List.of(first, second, zero));
    }

    @Test
    void captureConsumesTheInstalledMatchAndKeepsRepeatedAndZeroSliceOccurrences() {
        try (var fixture = new Fixture(nested(), BlockOrientation.EAST_UP)) {
            assertNull(fixture.capture()); // not formed until the actual lifecycle accepts the result
            fixture.accept();
            var snapshot = fixture.capture();
            assertNotNull(snapshot);
            assertEquals(5, snapshot.occurrences().size());
            assertEquals(List.of(0), snapshot.path(1));
            assertEquals(List.of(1), snapshot.path(2));
            assertEquals(List.of(2, 0), snapshot.path(4));
            assertEquals(2, snapshot.occurrences().get(1).repetitions());
            assertEquals(1, snapshot.occurrences().get(2).repetitions());
            assertEquals(0, snapshot.occurrences().get(3).repetitions());
            assertEquals(fixture.accepted.children().get(2).children().getFirst().origin(), snapshot.occurrences().get(4).origin());
            assertEquals(fixture.accepted.allBlocks(), snapshot.positions());
            assertTrue(snapshot.hidePositions().contains(BlockPos.ORIGIN));
            fixture.host.configuration = null;
            assertNull(ControllerRenderSnapshots.capture(fixture.machine, fixture.configuration, fixture.view,
                snapshot.display(), BlockPos.ORIGIN, () -> true));
        }
    }

    @Test
    void sameFormedRefreshChangesActualCountsAndClearsAcceptedPositionsOnMismatch() {
        try (var fixture = new Fixture(nested(), BlockOrientation.NORTH_UP)) {
            fixture.accept();
            var before = fixture.capture();
            var child = fixture.accepted.children().getFirst();
            var changed = new StructureMatch(fixture.accepted.structure(), fixture.accepted.origin(), 1, fixture.accepted.blocks(),
                List.of(new StructureMatch(child.structure(), child.origin(), 1, child.blocks().subList(0, 1), List.of()),
                    fixture.accepted.children().get(1), fixture.accepted.children().get(2)));
            fixture.install(changed);
            var after = fixture.capture();
            assertEquals(1, after.occurrences().get(1).repetitions());
            assertNotEquals(before.positions(), after.positions());
            fixture.install(null);
            var unformed = ControllerRenderSnapshots.capture(fixture.machine, fixture.configuration, fixture.view,
                new ControllerDisplayData(MACHINE, fixture.configuration.orientation(), false, 1, 3), BlockPos.ORIGIN, () -> true);
            assertNotNull(unformed);
            assertTrue(unformed.occurrences().isEmpty());
            assertTrue(unformed.hidePositions().isEmpty());
            assertEquals(StructureBounds.at(BlockPos.ORIGIN), unformed.bounds());
        }
    }

    @Test
    void opaqueCoveredPositionsReachBoundsAndCallbackRevocationCannotPublish() {
        var shape = new Shape();
        try (var fixture = new Fixture(shape, BlockOrientation.NORTH_UP)) {
            fixture.accept();
            var snapshot = fixture.capture();
            assertEquals(StructurePreviewProvider.Kind.OPAQUE, snapshot.occurrences().getFirst().kind());
            assertEquals(new BlockPos(-100, 0, 0), snapshot.bounds().min());
            assertEquals(new BlockPos(0, 2, 80), snapshot.bounds().max());
            assertTrue(snapshot.hidePositions().contains(new BlockPos(-100, 2, 80)));
            shape.geometryCallback = () -> fixture.host.configuration = null;
            assertNull(fixture.capture());
        }
    }

    @Test
    void compactCoordinatesKeepSignedExtremesAndRejectTrailingOrIncompletePayload() {
        var node = new MachineRenderSnapshot.Occurrence(-1, -1, id("root"), new BlockPos(Integer.MIN_VALUE, 0, Integer.MAX_VALUE), 1,
            StructurePreviewProvider.Kind.OPAQUE, BlockPos.ORIGIN, true, List.of(new BlockPos(Integer.MAX_VALUE, -2000000, Integer.MIN_VALUE)));
        var snapshot = new MachineRenderSnapshot(new ControllerDisplayData(MACHINE, BlockOrientation.NORTH_UP, true, 1, 1),
            new MachineRenderSnapshot.CatalogToken(SESSION, 0), BlockPos.ORIGIN, id("root"), List.of(node), node.blocks(), List.of(), null);
        byte[] bytes = ControllerRenderSnapshotCodec.encode(snapshot);
        var decoded = ControllerRenderSnapshotCodec.decode(bytes);
        assertEquals(snapshot, decoded);
        assertThrows(IllegalArgumentException.class, () -> ControllerRenderSnapshotCodec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> ControllerRenderSnapshotCodec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
    }

    @Test
    void replacedHostRejectsTheOldAcceptedMachineEvenAfterItsAnimationChanges() {
        try (var fixture = new Fixture(nested(), BlockOrientation.NORTH_UP)) {
            fixture.accept();
            var replacement = fixture.assembly.create(fixture.host, new MachineLifecycleRegistry());
            fixture.host.machine = replacement;
            try {
                assertTrue(Objects.requireNonNull(replacement.lifecycle()).refreshStructure(() -> true,
                    () -> new StructureAssembly(fixture.accepted, new MachineHatches(List.of(), List.of(), List.of()))));
                Objects.requireNonNull(fixture.machine.components().get(MachineAnimationState.TYPE)).setAnimation("late_old_state");
                assertNull(fixture.capture());
                var current = ControllerRenderSnapshots.capture(replacement, fixture.configuration, fixture.view,
                    new ControllerDisplayData(MACHINE, BlockOrientation.NORTH_UP, true, 2, 1), BlockPos.ORIGIN, () -> true);
                assertNotNull(current);
                assertTrue(Objects.requireNonNull(current.animation()).layers().isEmpty());
            } finally {
                Objects.requireNonNull(replacement.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    static final class Fixture implements AutoCloseable {
        final Host host = new Host();
        final StructureConfiguration configuration;
        final StructureCatalogView view;
        final MachineAssembly assembly;
        final MachineInstance machine;
        StructureMatch accepted;

        Fixture(MachineStructure shape, BlockOrientation orientation) {
            var catalog = StructureCatalog.fromShapes(Map.of(shape.id(), shape));
            configuration = new StructureConfiguration(catalog, shape, orientation);
            host.configuration = configuration;
            view = StructureCatalogView.capture(catalog, SESSION, 0);
            var type = MachineType.builder(MACHINE, RecipeText.Cancelled, shape.id()).component(MachineAnimationState.TYPE).build();
            assembly = MachineAssembly.builder(type, "render-test").component(MachineAnimationState.TYPE, MachineAnimationState.CODEC).build();
            machine = assembly.create(host, new MachineLifecycleRegistry());
            host.machine = machine;
        }

        void accept() {
            var plan = new StructurePlan(configuration.definition(), BlockPos.ORIGIN, configuration.orientation());
            accepted = plan.match(StructureSnapshot.capture(plan.reads(), host));
            assertNotNull(accepted);
            install(accepted);
        }

        void install(StructureMatch match) {
            assertTrue(Objects.requireNonNull(machine.lifecycle()).refreshStructure(() -> true,
                () -> match == null ? StructureAssembly.UNFORMED : new StructureAssembly(match, new MachineHatches(List.of(), List.of(), List.of()))));
        }

        MachineRenderSnapshot capture() {
            return ControllerRenderSnapshots.capture(machine, configuration, view,
                new ControllerDisplayData(MACHINE, configuration.orientation(), true, 1, 1), BlockPos.ORIGIN, () -> true);
        }

        @Override
        public void close() {
            Objects.requireNonNull(machine.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    static final class Host implements ReloadableStructureHost {
        StructureConfiguration configuration;
        MachineInstance machine;
        private final Thread owner = Thread.currentThread();
        private final Object world = new Object();

        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(MACHINE, 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("No native world initialization");
        }

        @Override
        public Object worldIdentity() {
            return world;
        }

        @Override
        public void requireServerThread() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Foreign owner");
        }

        @Override
        public StructureConfiguration structureConfiguration() {
            requireServerThread();
            return configuration;
        }

        @Override
        public void installStructureConfiguration(StructureConfiguration value) {
            requireServerThread();
            configuration = value;
        }

        @Override
        public boolean structureLifecycleCurrent(MachineLifecycle lifecycle) {
            return machine != null && machine.lifecycle() == lifecycle;
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            requireServerThread();
            return true;
        }

        @Override
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            throw new AssertionError("No undeclared reads");
        }

        @Override
        public StructureHatchBinding hatchesAt(BlockPos position) {
            return StructureHatchBinding.EMPTY;
        }
    }

    static final class Shape implements MachineStructure, StructurePreviewProvider {
        Runnable geometryCallback = () -> {
        };

        @Override
        public ResourceLocation id() {
            return MachineRenderSnapshotTest.id("opaque");
        }

        @Override
        public BlockOrientation orientation() {
            return BlockOrientation.NORTH_UP;
        }

        @Override
        public boolean hideWorldBlocks() {
            return true;
        }

        @Override
        public MachineStructure transform(StructureTransform rotation) {
            return this;
        }

        @Override
        public void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
        }

        @Override
        public StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
            return new StructureMatch(this, anchor, 1, List.of(new BlockPos(-100, 2, 80)), List.of());
        }

        @Override
        public Geometry previewGeometry() {
            geometryCallback.run();
            return new Geometry(Kind.OPAQUE, BlockPos.ORIGIN, 0, 0, BlockPos.ORIGIN, List.of(), List.of());
        }
    }
}
