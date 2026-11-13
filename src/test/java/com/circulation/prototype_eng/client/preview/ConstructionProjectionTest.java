package com.circulation.prototype_eng.client.preview;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.ClientStructureCatalog;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.network.clientbound.StructureCatalogPacket;
import com.circulation.prototype_eng.structure.reload.StructureCatalogCodec;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionProjectionTest {
    private static final ResourceLocation ID = new ResourceLocation("builder_test", "projection");
    private static final List<BlockPos> CELLS = List.of(new BlockPos(1, 0, 0), new BlockPos(2, 1, 0), new BlockPos(3, 2, 0));

    private static StructurePreviews previews(List<BlockPos> cells, boolean hide) {
        var connection = new Object();
        var catalog = new ClientStructureCatalog();
        catalog.connected(connection);
        var node = new StructureCatalogView.Node(ID, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.TEMPLATE,
            BlockPos.ORIGIN, 1, 1, BlockPos.ORIGIN, cells, IntList.of());
        var view = new StructureCatalogView(UUID.randomUUID(), 0, List.of(node), Map.of(ID, new StructureCatalogView.Entry(0, null, Map.of())));
        var bytes = StructureCatalogCodec.encode(view);
        catalog.receive(connection, new StructureCatalogPacket(view.session(), view.sequence(), bytes.length, 0, bytes));
        var previews = new StructurePreviews(catalog);
        previews.project(ID, new StructurePreviewSettings(BlockOrientation.NORTH_UP), BlockPos.ORIGIN,
            new StructureProjectionOptions(StructureProjectionOptions.Style.BLOCK_MODEL, hide, new StructurePreviewModel.Layer(0), 1000, 1, 10, 64));
        return previews;
    }

    @Test
    void cancelsOnlyAfterEveryPlannedLayerMatchesAndHidesVisitedMatches() {
        var world = new Object();
        try (var previews = previews(CELLS, true)) {
            var state = new StructureProjectionState(previews);
            for (int index = 0; index < 2; index++) {
                state.tick(world, (_, _) -> StructureProjectionState.Status.DISPLAY_MATCH);
                assertNotNull(previews.projection());
                assertTrue(state.frame(world).isEmpty());
            }
            state.tick(world, (_, _) -> StructureProjectionState.Status.DISPLAY_MATCH);
            assertNull(previews.projection());
            assertTrue(state.frame(world).isEmpty());
        }
    }

    @Test
    void unknownUnloadedOrMissingCellsOnHiddenLayersPreventCancellation() {
        for (var status : List.of(StructureProjectionState.Status.UNKNOWN, StructureProjectionState.Status.UNLOADED,
            StructureProjectionState.Status.MISSING, StructureProjectionState.Status.DIFFERENT)) {
            try (var previews = previews(CELLS, true)) {
                var state = new StructureProjectionState(previews);
                var world = new Object();
                for (int index = 0; index < 3; index++)
                    state.tick(world, (_, pos) -> pos.getY() == 2 ? status : StructureProjectionState.Status.DISPLAY_MATCH);
                assertNotNull(previews.projection());
                for (int index = 0; index < 3; index++) state.tick(world, (_, _) -> StructureProjectionState.Status.DISPLAY_MATCH);
                assertNull(previews.projection());
            }
        }
    }

    @Test
    void ordinaryProjectionWithoutHidingRemainsActiveAfterAllMatches() {
        try (var previews = previews(CELLS, false)) {
            var state = new StructureProjectionState(previews);
            for (int index = 0; index < 6; index++) state.tick(this, (_, _) -> StructureProjectionState.Status.DISPLAY_MATCH);
            assertNotNull(previews.projection());
        }
    }

    @Test
    void anchorOnlyPlanHasNoOutstandingProjection() {
        try (var previews = previews(List.of(), true)) {
            new StructureProjectionState(previews).tick(this, (_, _) -> { throw new AssertionError("Empty plan"); });
            assertNull(previews.projection());
        }
    }
}
