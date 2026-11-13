package com.circulation.prototype_eng.client.preview;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.ClientStructureCatalog;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewCache;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.network.clientbound.StructureCatalogPacket;
import com.circulation.prototype_eng.structure.reload.StructureCatalogCodec;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StructureProjectionStateTest {
    private static final ResourceLocation ID = new ResourceLocation("preview_test", "root");
    private static final StructurePreviewSettings SETTINGS = new StructurePreviewSettings(BlockOrientation.NORTH_UP);

    private static StructureCatalogView view(UUID session, long sequence, List<BlockPos> cells) {
        var node = new StructureCatalogView.Node(ID, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.TEMPLATE, BlockPos.ORIGIN, 1, 1, BlockPos.ORIGIN, cells, IntList.of());
        return new StructureCatalogView(session, sequence, List.of(node), Map.of(ID, new StructureCatalogView.Entry(0, null, BlockPos2ObjectMap.emptyMap())));
    }

    private static void deliver(ClientStructureCatalog catalog, Object connection, StructureCatalogView view) {
        byte[] bytes = StructureCatalogCodec.encode(view);
        for (int offset = 0, index = 0; offset < bytes.length; offset += StructureCatalogPacket.CHUNK_BYTES, index++)
            catalog.receive(connection, new StructureCatalogPacket(view.session(), view.sequence(), bytes.length, index,
                Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + StructureCatalogPacket.CHUNK_BYTES))));
    }

    private static StructureProjectionOptions options(boolean hide, StructurePreviewModel.Layer layer, int scan, int render) {
        return new StructureProjectionOptions(StructureProjectionOptions.Style.BOX_AND_OUTLINE, hide, layer, 1000, scan, render, 64);
    }

    private static ClientStructureCatalog catalog(Object connection, UUID session) {
        var catalog = new ClientStructureCatalog();
        catalog.connected(connection);
        deliver(catalog, connection, view(session, 0, List.of(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 1, 0), new BlockPos(4, 1, 0), new BlockPos(5, 2, 0))));
        return catalog;
    }

    @Test
    void scanAndRenderVisitBudgetsAdvanceAcrossFilteredCells() {
        var connection = new Object();
        var catalog = catalog(connection, UUID.randomUUID());
        var world = new Object();
        try (var previews = new StructurePreviews(catalog)) {
            assertTrue(previews.project(ID, SETTINGS, BlockPos.ORIGIN, options(true, null, 1, 2)));
            var state = new StructureProjectionState(previews);
            var scanned = new AtomicInteger();
            state.tick(world, (_, _, _) -> {
                scanned.incrementAndGet();
                return StructureProjectionState.Status.DISPLAY_MATCH;
            });
            assertEquals(1, scanned.get());
            assertEquals(List.of(new BlockPos(2, 0, 0)), state.frame(world).stream().map(StructureProjectionState.Sample::position).toList());
            assertEquals(List.of(new BlockPos(3, 1, 0), new BlockPos(4, 1, 0)), state.frame(world).stream().map(StructureProjectionState.Sample::position).toList());
            assertEquals(List.of(new BlockPos(5, 2, 0)), state.frame(world).stream().map(StructureProjectionState.Sample::position).toList());
        }
    }

    @Test
    void layerFilteringPreservesWholeBomAndSparsePlanesStillConsumeVisitBudget() {
        var connection = new Object();
        var catalog = catalog(connection, UUID.randomUUID());
        var world = new Object();
        try (var previews = new StructurePreviews(catalog)) {
            previews.project(ID, SETTINGS, BlockPos.ORIGIN, options(false, new StructurePreviewModel.Layer(1), 0, 2));
            var state = new StructureProjectionState(previews);
            state.tick(world, (_, _, _) -> fail("Disabled world observations must not execute"));
            assertTrue(state.frame(world).isEmpty());
            assertEquals(2, state.frame(world).size());
            var model = Objects.requireNonNull(Objects.requireNonNull(previews.request(ID, SETTINGS)).model());
            assertEquals(5, model.bom().getFirst().count());
        }
    }

    @Test
    void movingIntentImmediatelyHidesOldFrameAndResetsObservations() {
        var connection = new Object();
        var catalog = catalog(connection, UUID.randomUUID());
        var world = new Object();
        try (var previews = new StructurePreviews(catalog)) {
            previews.project(ID, SETTINGS, BlockPos.ORIGIN, options(false, null, 1, 2));
            var state = new StructureProjectionState(previews);
            state.tick(world, (_, _, _) -> StructureProjectionState.Status.DIFFERENT);
            previews.moveProjection(new BlockPos(10, 0, 0));
            assertTrue(state.frame(world).isEmpty());
            state.tick(world, (_, _, _) -> StructureProjectionState.Status.UNLOADED);
            var frame = state.frame(world);
            assertEquals(new BlockPos(11, 0, 0), frame.getFirst().position());
            assertEquals(StructureProjectionState.Status.UNLOADED, frame.getFirst().status());
            assertEquals(StructureProjectionState.Status.UNKNOWN, frame.getLast().status());
        }
    }

    @Test
    void completeReloadRevokesHeldRequestAndFrameBeforeNextTickThenRebuildsIntent() {
        var connection = new Object();
        var session = UUID.randomUUID();
        var catalog = catalog(connection, session);
        var world = new Object();
        try (var previews = new StructurePreviews(catalog)) {
            previews.project(ID, SETTINGS, BlockPos.ORIGIN, options(false, null, 0, 20));
            var state = new StructureProjectionState(previews);
            state.tick(world, (_, _, _) -> fail("Disabled"));
            var held = Objects.requireNonNull(previews.request(ID, SETTINGS));
            var oldModel = Objects.requireNonNull(held.model());
            deliver(catalog, connection, view(session, 1, List.of(new BlockPos(9, 0, 0))));
            assertEquals(StructurePreviews.State.STALE, held.state());
            assertNull(held.model());
            assertEquals(0, held.advance(10));
            assertFalse(previews.current(oldModel));
            assertTrue(state.frame(world).isEmpty());
            state.tick(world, (_, _, _) -> fail("Disabled"));
            assertEquals(List.of(new BlockPos(9, 0, 0)), state.frame(world).stream().map(StructureProjectionState.Sample::position).toList());
        }
    }

    @Test
    void disconnectAndWorldReplacementCannotReusePlacementOrOldObservations() {
        var connection = new Object();
        var catalog = catalog(connection, UUID.randomUUID());
        var world = new Object();
        try (var previews = new StructurePreviews(catalog)) {
            previews.project(ID, SETTINGS, BlockPos.ORIGIN, options(false, null, 0, 20));
            var state = new StructureProjectionState(previews);
            state.tick(world, (_, _, _) -> fail("Disabled"));
            var replacement = new Object();
            assertTrue(state.frame(replacement).isEmpty());
            state.tick(replacement, (_, _, _) -> fail("Wrong world must stop the projection"));
            assertNull(previews.projection());
            assertTrue(state.frame(replacement).isEmpty());
            previews.project(ID, SETTINGS, BlockPos.ORIGIN, options(false, null, 0, 20));
            state.tick(replacement, (_, _, _) -> fail("Disabled"));
            catalog.disconnected(connection);
            assertNull(previews.projection());
            assertTrue(state.frame(replacement).isEmpty());
        }
    }

    @Test
    void failedLimitedRequestKeepsFailureWithoutPublishingOrRetrying() {
        var connection = new Object();
        var catalog = catalog(connection, UUID.randomUUID());
        try (var previews = new StructurePreviews(catalog)) {
            var settings = new StructurePreviewSettings(BlockOrientation.NORTH_UP, Object2IntMaps.emptyMap(), 10, 1);
            var request = Objects.requireNonNull(previews.request(ID, settings));
            var failure = assertThrows(IllegalArgumentException.class, () -> request.advance(1000));
            assertEquals(StructurePreviews.State.FAILED, request.state());
            assertSame(failure, request.failure());
            assertNull(request.model());
            assertEquals(0, request.advance(1000));
        }
    }

    @Test
    void unrepresentableWorldPlacementFailsWithoutWrappedPositions() {
        var connection = new Object();
        var catalog = catalog(connection, UUID.randomUUID());
        var world = new Object();
        try (var previews = new StructurePreviews(catalog)) {
            previews.project(ID, SETTINGS, new BlockPos(Integer.MAX_VALUE, 0, 0), options(false, null, 0, 20));
            var state = new StructureProjectionState(previews);
            state.tick(world, (_, _, _) -> fail("Disabled"));
            assertThrows(ArithmeticException.class, () -> state.frame(world));
        }
    }

    @Test
    void boundedCacheEvictsLeastRecentlyAccessedPreviewAndRetainsCompletedGenerationChecks() {
        var connection = new Object();
        var catalog = catalog(connection, UUID.randomUUID());
        var builds = new AtomicInteger();
        try (var cache = new StructurePreviewCache<Integer>(catalog, 2)) {
            assertEquals(1, cache.get(ID, "a", (_, _) -> builds.incrementAndGet()));
            assertEquals(2, cache.get(ID, "b", (_, _) -> builds.incrementAndGet()));
            assertEquals(1, cache.get(ID, "a", (_, _) -> fail("Access must retain a")));
            assertEquals(3, cache.get(ID, "c", (_, _) -> builds.incrementAndGet()));
            assertEquals(1, cache.get(ID, "a", (_, _) -> fail("a was most recently accessed")));
            assertEquals(4, cache.get(ID, "b", (_, _) -> builds.incrementAndGet()));
            catalog.disconnected(connection);
            assertNull(cache.get(ID, "a", (_, _) -> fail("Disconnect must not rebuild")));
        }
    }

    @Test
    void observationFailureCancelsTheCurrentProjectionAndReportsOriginalCause() {
        var connection = new Object();
        var catalog = catalog(connection, UUID.randomUUID());
        var world = new Object();
        try (var previews = new StructurePreviews(catalog)) {
            previews.project(ID, SETTINGS, BlockPos.ORIGIN, options(false, null, 2, 20));
            var state = new StructureProjectionState(previews);
            var cause = new IllegalStateException("observer failure");
            var reported = assertThrows(IllegalStateException.class, () -> state.tick(world, (_, _, _) -> {
                throw cause;
            }));
            assertSame(cause, reported);
            assertNull(previews.projection());
            assertTrue(state.frame(world).isEmpty());
        }
    }

}
