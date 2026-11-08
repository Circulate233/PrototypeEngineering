package com.circulation.prototype_eng.client.preview;

import java.util.function.Function;
import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.*;
import com.circulation.prototype_eng.api.structure.*;
import com.circulation.prototype_eng.structure.reload.StructureCatalogCodec;
import com.circulation.prototype_eng.structure.reload.StructureCatalogPacket;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StructurePreviewInteractionTest {
    private static final ResourceLocation ROOT = new ResourceLocation("preview_ui_test", "root");
    private static final ResourceLocation CHILD = new ResourceLocation("preview_ui_test", "shared");
    private static final StructureDisplay A = display("a"), B = display("b");
    private static StructureDisplay display(String key) {
        return new StructureDisplay(key, List.of(new StructureDisplay.BlockOption(new ResourceLocation("preview_ui_test", key), 0, Map.of()),
            new StructureDisplay.BlockOption(new ResourceLocation("preview_ui_test", key + "_alternative"), 0, Map.of())), null);
    }
    private static StructureCatalogView view(UUID session, long sequence) {
        var root = new StructureCatalogView.Node(ROOT, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.TEMPLATE,
            BlockPos.ORIGIN, 1, 1, BlockPos.ORIGIN, List.of(new BlockPos(1, 0, 0), new BlockPos(1, 1, 0)), List.of(1, 1));
        var child = new StructureCatalogView.Node(CHILD, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.SLICE,
            new BlockPos(3, 0, 0), 0, 3, new BlockPos(0, 1, 0), List.of(new BlockPos(1, 0, 0)), List.of(2));
        var leafId = new ResourceLocation("preview_ui_test", "leaf");
        var leaf = new StructureCatalogView.Node(leafId, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.TEMPLATE,
            new BlockPos(0, 0, 1), 1, 1, BlockPos.ORIGIN, List.of(new BlockPos(1, 0, 0)), List.of());
        return new StructureCatalogView(session, sequence, List.of(root, child, leaf), Map.of(
            ROOT, new StructureCatalogView.Entry(0, null, Map.of(new BlockPos(1, 0, 0), A, new BlockPos(1, 1, 0), B)),
            CHILD, new StructureCatalogView.Entry(1, null, Map.of(new BlockPos(1, 0, 0), A)),
            leafId, new StructureCatalogView.Entry(2, null, Map.of(new BlockPos(1, 0, 0), A))));
    }
    private static void deliver(ClientStructureCatalog catalog, Object connection, StructureCatalogView view) {
        var bytes = StructureCatalogCodec.encode(view);
        for (int offset = 0, index = 0; offset < bytes.length; offset += StructureCatalogPacket.CHUNK_BYTES, index++)
            catalog.receive(connection, new StructureCatalogPacket(view.session(), view.sequence(), bytes.length, index,
                Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + StructureCatalogPacket.CHUNK_BYTES))));
    }
    record Fixture(ClientStructureCatalog catalog, Object connection, UUID session, StructurePreviews service,
                           StructurePreviewView first, StructurePreviewView second) implements AutoCloseable {
        @Override public void close() { service.close(); }
    }
    static Fixture fixture() {
        var catalog = new ClientStructureCatalog(); var connection = new Object(); var session = UUID.randomUUID();
        catalog.connected(connection); deliver(catalog, connection, view(session, 0));
        var service = new StructurePreviews(catalog); var settings = new StructurePreviewSettings(BlockOrientation.NORTH_UP);
        var first = new StructurePreviewView(service, ROOT, settings); var second = new StructurePreviewView(service, ROOT, settings);
        first.advance(1000); second.advance(1000);
        return new Fixture(catalog, connection, session, service, first, second);
    }
    @Test void longCountsIssuesSearchAndFiltersKeepExactOrdering() {
        try (var f = fixture()) {
            var first = new StructurePreviewView.Material(A, "Zinc", 3_000_000_000L, 1, 0, 0);
            var second = new StructurePreviewView.Material(B, "Copper", 4_000_000_000L, 0, 1, 0);
            var ready = new StructurePreviewView.Material(null, "Other", 1, 0, 0, 0);
            var rows = List.of(second, ready, first);
            assertEquals(List.of(first, second, ready), f.first.filterAndSort(rows));
            f.first.sort = StructurePreviewView.Sort.COUNT;
            assertEquals(List.of(second, first, ready), f.first.filterAndSort(rows));
            f.first.search = "zInC";
            assertEquals(List.of(first), f.first.filterAndSort(rows));
            f.first.search = ""; f.first.showMismatch = false; f.first.showUnloaded = false;
            assertEquals(List.of(second, first, ready), f.first.filterAndSort(rows), "Mixed rows still contain matching cells");
            f.first.showMatch = false;
            assertTrue(f.first.filterAndSort(rows).isEmpty());
        }
    }
    @Test void layerKeepsWholeBomAndScanVisitsOnlyItsBudget() {
        try (var f = fixture()) {
            var ui = f.first; var whole = ui.materials(StructureDisplay::key, "unknown");
            ui.threeDimensional = false; ui.layer = 0;
            assertEquals(whole, ui.materials(StructureDisplay::key, "unknown"));
            assertTrue(ui.sceneSamples(100).stream().allMatch(cell -> cell.position().getY() == 0));
            ui.scan = true; var visits = new AtomicInteger(); var world = new Object();
            ui.scan(world, (_, _) -> { visits.incrementAndGet(); return StructureProjectionState.Status.MISSING; }, 1);
            assertEquals(1, visits.get());
            assertEquals(1L, ui.materials(StructureDisplay::key, "unknown").stream().mapToLong(StructurePreviewView.Material::mismatch).sum());
            assertEquals(whole.stream().mapToLong(StructurePreviewView.Material::count).sum(), ui.materials(StructureDisplay::key, "unknown").stream().mapToLong(StructurePreviewView.Material::count).sum());
        }
    }
    @Test void occurrenceChoicesAndZeroSliceAnchorsStaySeparate() {
        try (var f = fixture()) {
            var left = StructurePreviewPath.ROOT.child(0); var right = StructurePreviewPath.ROOT.child(1);
            assertTrue(f.first.slices(f.first.token(), left, 0)); f.first.advance(1000);
            assertTrue(f.first.slices(f.first.token(), right, 3)); f.first.advance(1000);
            var occurrences = Objects.requireNonNull(f.first.model()).occurrences();
            assertEquals(0, occurrences.stream().filter(o -> o.path().equals(left)).findFirst().orElseThrow().selectedSlices());
            assertEquals(3, occurrences.stream().filter(o -> o.path().equals(right)).findFirst().orElseThrow().selectedSlices());
            assertEquals(new BlockPos(3, 0, 0), occurrences.stream().filter(o -> o.path().equals(left)).findFirst().orElseThrow().origin());
            assertEquals(new BlockPos(3, -1, 1), occurrences.stream().filter(o -> o.path().equals(left.child(0))).findFirst().orElseThrow().origin());
            assertEquals("/0", StructurePreviewPanel.path(left)); assertEquals("/1", StructurePreviewPanel.path(right));
        }
    }
    @Test void alternativeInvalidatesDrawnClickAndDoesNotMutateOtherWrapperOrWorldIntent() {
        try (var f = fixture()) {
            var options = new StructureProjectionOptions();
            f.service.project(ROOT, f.first.settings(), new BlockPos(8, 9, 10), options);
            var projection = f.service.projection();
            var old = new StructurePreviewView.Click(f.first.token(), Objects.requireNonNull(f.first.model()).cells().getFirst());
            assertTrue(f.first.alternative(f.first.token(), A, 1)); f.first.advance(1000);
            assertFalse(f.first.choose(old));
            assertSame(projection, f.service.projection());
            assertTrue(Objects.requireNonNull(f.first.model()).settings().displayOptions().containsKey(A));
            assertTrue(Objects.requireNonNull(f.second.model()).settings().displayOptions().isEmpty());
        }
    }
    @Test void catalogReplacementRejectsOldClicksBeforeAdvance() {
        try (var f = fixture()) {
            var click = new StructurePreviewView.Click(f.first.token(), Objects.requireNonNull(f.first.model()).cells().getFirst());
            deliver(f.catalog, f.connection, view(f.session, 1));
            assertFalse(f.first.inputCurrent(), "Embedded/native input must reject the replaced catalog before the next draw");
            assertFalse(f.first.choose(click)); f.first.advance(1000); assertFalse(f.first.choose(click));
        }
    }
    @Test void orbitRayPickingScissorAndOutsideInputUseSameViewport() {
        try (var f = fixture()) {
            var camera = new StructurePreviewCamera();
            var bounds = Objects.requireNonNull(f.first.model()).bounds(); var viewport = new StructurePreviewCamera.Viewport(50, 30, 180, 150);
            assertArrayEquals(new int[] {100, 440, 360, 300}, viewport.scissor(2, 800));
            assertNull(camera.ray(viewport, 49, 60, bounds)); assertFalse(camera.press(viewport, 49, 60, 0));
            assertFalse(camera.wheel(viewport, 49, 60, 120));
            assertTrue(camera.press(viewport, 80, 60, 0)); assertTrue(camera.drag(90, 62, bounds, viewport)); assertFalse(camera.release(0));
            var ray = new StructurePreviewCamera.Ray(new Vec3d(1.5, 0.5, 4), new Vec3d(0, 0, -1));
            assertEquals(3, StructurePreviewCamera.intersect(ray, 1, 0, 0));
            assertEquals(Double.POSITIVE_INFINITY, StructurePreviewCamera.intersect(ray, 5, 0, 0));
        }
    }
    @Test void ddaStartsAtNegativeIntegerAndChargesEmptyVoxels() {
        var visits = new AtomicInteger();
        assertEquals(new BlockPos(-3, 0, 0), ProjectionRayMarcher.pick(BlockPos.ORIGIN, new Vec3d(-1, .5, .5), new Vec3d(-1, 0, 0), 8, 10,
            pos -> { visits.incrementAndGet(); return pos.getX() == -3; }));
        assertEquals(3, visits.get());
        assertNull(ProjectionRayMarcher.pick(BlockPos.ORIGIN, new Vec3d(-1, .5, .5), new Vec3d(-1, 0, 0), 8, 2, pos -> pos.getX() == -3));
    }
    @Test void all24OrientationsReturnAfterFourQuarterTurnsAndInverse() {
        for (var original : BlockOrientation.values()) for (var axis : EnumFacing.values()) {
            var next = original;
            for (int i = 0; i < 4; i++) next = ProjectionOrientations.rotate(next, axis);
            assertSame(original, next);
            assertSame(original, ProjectionOrientations.rotate(ProjectionOrientations.rotate(original, axis), axis.getOpposite()));
        }
    }
    @Test void clockwiseFirstQuarterUsesNativeBlockInteractionConvention() {
        assertSame(BlockOrientation.EAST_UP, ProjectionOrientations.rotate(BlockOrientation.NORTH_UP, EnumFacing.UP));
        assertSame(BlockOrientation.WEST_UP, ProjectionOrientations.rotate(BlockOrientation.NORTH_UP, EnumFacing.DOWN));
    }
    @Test void embeddedDragKeepsDeliveringAcrossDrawsPastItsOriginalExpiry() {
        try (var f = fixture()) {
            var camera = new StructurePreviewCamera();
            var bounds = Objects.requireNonNull(f.first.model()).bounds();
            var viewport = new StructurePreviewCamera.Viewport(4, 22, 180, 150);
            var drag = new PreviewEmbeddedInput.Drag();
            var host = new Object(); var control = new Object();
            assertTrue(camera.press(viewport, 30, 40, 0));
            drag.capture(host, control, 100, 50, 0, 0);
            for (int frame = 1; frame <= 5; frame++) {
                long now = frame * 100L;
                drag.mark(host, control, 100 + frame, 50, now);
                assertTrue(drag.fresh(host, now));
                assertTrue(drag.move(130 + frame * 3, 90, (x, y) -> camera.drag(x, y, bounds, viewport)));
            }
            var releases = new AtomicInteger();
            drag.release(160, 90, (_, _) -> { releases.incrementAndGet(); return camera.release(0); });
            assertEquals(1, releases.get());
            assertFalse(drag.fresh(host, 500));
            assertFalse(camera.drag(60, 40, bounds, viewport));
            drag.capture(host, control, 0, 0, 0, 500);
            drag.mark(new Object(), control, 0, 0, 800);
            assertFalse(drag.fresh(host, 800), "A foreign draw cannot refresh the original drag");
        }
    }
    @Test void lowBudgetIndexesContinueWithoutPublishingPartialCellsOrBom() {
        try (var f = fixture()) {
            var complete = Objects.requireNonNull(f.first.model());
            var ui = new StructurePreviewView(f.service, ROOT, f.first.settings());
            int work = complete.cells().size() + complete.bom().size();
            ui.advance(0);
            for (int step = 1; step < work; step++) {
                ui.advance(1);
                assertNull(ui.model()); assertEquals(StructurePreviews.State.BUILDING, ui.state());
                assertNull(ui.at(complete.cells().getFirst().position()));
                assertTrue(ui.materials(StructureDisplay::key, "unknown").isEmpty());
            }
            ui.advance(1);
            assertSame(complete, ui.model());
            for (var cell : complete.cells()) assertTrue(ui.choose(new StructurePreviewView.Click(ui.token(), cell)));
            assertEquals(complete.bom().stream().mapToLong(StructurePreviewModel.BomLine::count).sum(),
                ui.materials(StructureDisplay::key, "unknown").stream().mapToLong(StructurePreviewView.Material::count).sum());
        }
    }
    @Test void consumedEmbeddedPressOwnsExactlyOneReleaseAfterClosingOrLeavingHost() {
        var drag = new PreviewEmbeddedInput.Drag(); var host = new Object(); var panel = new Object();
        drag.capture(host, panel, 0, 0, 0, 100);
        drag.clear(); // HEI Esc closes/removes the panel before the mouse button comes up.
        assertFalse(drag.fresh(host, 110));
        assertFalse(drag.consumeRelease(1, false), "An unrelated button release still reaches the parent");
        assertTrue(drag.consumeRelease(0, false), "The parent cannot receive up for a down owned by the closed preview");
        assertFalse(drag.consumeRelease(0, false), "Release ownership is exactly once");
        drag.capture(host, panel, 0, 0, 0, 200); drag.clear();
        assertFalse(drag.consumeRelease(0, true), "A new same-button down retires an old drained-release token");
        assertFalse(drag.consumeRelease(0, false), "The new parent press owns its own release");
        drag.capture(host, panel, 0, 0, 1, 300);
        assertFalse(drag.fresh(new Object(), 310));
        assertTrue(drag.consumeRelease(1, false), "Host changes preserve the consumed press's release ownership");
    }
    @Test void stableMaterialsReuseRowsAndNativeNamesAcrossLayerQueryAndStatusFrames() {
        try (var f = fixture()) {
            var ui = f.first; var calls = new AtomicInteger();
            Function<StructureDisplay, String> names = display -> { calls.incrementAndGet(); return display.key(); };
            var initial = ui.materials(names, "unknown");
            assertEquals(2, calls.get()); assertSame(initial, ui.materials(names, "unknown"));
            ui.threeDimensional = false; ui.layer = 1;
            assertSame(initial, ui.materials(names, "unknown"));
            ui.search = "a";
            assertEquals(1, ui.materials(names, "unknown").size()); assertEquals(2, calls.get());
            ui.search = ""; ui.scan = true;
            var world = new Object();
            ui.scan(world, (_, _) -> StructureProjectionState.Status.MISSING, 100);
            var observed = ui.materials(names, "unknown");
            assertTrue(observed.stream().allMatch(row -> row.mismatch() == row.count() && row.unknown() == 0));
            ui.scan(world, (_, _) -> StructureProjectionState.Status.MISSING, 100);
            assertSame(observed, ui.materials(names, "unknown")); assertEquals(2, calls.get());
            ui.showMismatch = false;
            assertTrue(ui.materials(names, "unknown").isEmpty());
            ui.showMismatch = true; ui.sort = StructurePreviewView.Sort.NAME;
            assertEquals(2, ui.materials(names, "unknown").size()); assertEquals(2, calls.get());
        }
    }
    @Test void materialNamesRefreshForRepresentativeResourceLanguageAndGenerationChanges() {
        try (var f = fixture()) {
            var ui = f.first; var calls = new AtomicInteger(); var language = new String[] {"en"};
            Function<StructureDisplay, String> names = display -> {
                calls.incrementAndGet(); return language[0] + display.key() + ui.alternative(display);
            };
            var first = ui.materials(names, "unknown");
            ui.invalidateMaterials();
            assertNotSame(first, ui.materials(names, "unknown")); assertEquals(4, calls.get());
            language[0] = "zh"; ui.invalidateMaterials();
            assertTrue(ui.materials(names, "unknown").stream().allMatch(row -> row.name().startsWith("zh")));
            assertEquals(6, calls.get());
            assertTrue(ui.alternative(ui.token(), A, 1)); ui.advance(1000);
            assertTrue(ui.materials(names, "unknown").stream().anyMatch(row -> row.name().equals("zha1")));
            assertEquals(8, calls.get());
            deliver(f.catalog, f.connection, view(f.session, 1));
            assertTrue(ui.materials(names, "unknown").isEmpty());
            ui.advance(1000); ui.materials(names, "unknown"); assertEquals(10, calls.get());
        }
    }
    @Test void meshAndOverlaySamplesAdvanceIndependently() {
        try (var f = fixture()) {
            var cells = Objects.requireNonNull(f.first.model()).cells();
            assertEquals(cells.subList(0, 1), f.first.sceneSamples(1));
            assertEquals(cells.subList(0, 1), f.first.overlaySamples(1));
            assertEquals(cells.subList(1, 2), f.first.sceneSamples(1));
            assertEquals(cells.subList(1, 2), f.first.overlaySamples(1));
        }
    }
    @Test void automaticRotationUsesElapsedTimeAndPausesWhileDragging() {
        var camera = new StructurePreviewCamera();
        camera.frame(0, true); camera.frame(100, true);
        assertTrue(camera.yaw() > -45);
        var viewport = new StructurePreviewCamera.Viewport(0, 0, 180, 150);
        camera.press(viewport, 30, 30, 0);
        camera.frame(200, true);
        var paused = camera.yaw();
        camera.frame(1000, true);
        assertTrue(camera.yaw() - paused < 4, "Held drag stops adding auto rotation targets");
    }
}
