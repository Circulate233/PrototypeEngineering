package com.circulation.prototype_eng.structure.tool;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.structure.StructureAnchorRotation;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureConstructionRegistry;
import com.circulation.prototype_eng.api.structure.StructureDefinitions;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.client.preview.StructurePreviewExpansion;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import net.minecraft.block.BlockFurnace;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureBuildPlanTest {
    private static final ResourceLocation ROOT = new ResourceLocation("test", "root"), CHILD = new ResourceLocation("test", "child");

    private static StructureCatalogView view() {
        var display = new StructureDisplay("stone", List.of(new StructureDisplay.BlockOption(new ResourceLocation("minecraft", "stone"), 0, Map.of())), null);
        var nodes = List.of(new StructureCatalogView.Node(ROOT, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.SLICE, BlockPos.ORIGIN, 0, 5, new BlockPos(0, 2, 0), List.of(BlockPos.ORIGIN, new BlockPos(1, 0, 0)), IntList.of(1)),
            new StructureCatalogView.Node(CHILD, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.TEMPLATE, new BlockPos(0, 0, 1), 1, 1, BlockPos.ORIGIN, List.of(new BlockPos(1, 0, 0)), IntList.of()));
        return new StructureCatalogView(UUID.randomUUID(), 0, nodes, Map.of(ROOT, new StructureCatalogView.Entry(0, null, new BlockPos2ObjectMap<>(Map.of(BlockPos.ORIGIN, display, new BlockPos(1, 0, 0), display))), CHILD, new StructureCatalogView.Entry(1, null, new BlockPos2ObjectMap<>(Map.of(new BlockPos(1, 0, 0), display)))));
    }

    @Test
    void suspendingExpansionKeepsNestedVisitsInTheirOriginalOrderAndDoesNotExposePartialCells() {
        var catalog = view();
        var expansion = new StructureBuildPlan.Expansion(catalog, ROOT, BlockOrientation.NORTH_UP,
            Object2IntMaps.singleton("0", 3));
        assertTrue(expansion.step());
        do {
            assertThrows(IllegalStateException.class, expansion::cells);
        } while (expansion.step());
        var cells = new ArrayList<BlockPos>();
        expansion.cells().forEachRemaining(cell -> cells.add(cell.position()));
        assertEquals(List.of(new BlockPos(1, 0, 0), new BlockPos(0, 2, 0), new BlockPos(1, 2, 0),
            new BlockPos(0, 4, 0), new BlockPos(1, 4, 0), new BlockPos(1, 4, 1)), cells);
    }

    @Test
    void constructionAndPreviewAgreeForZeroAndRepeatedSlicesInEveryOrientation() {
        var catalog = view();
        for (var orientation : BlockOrientation.values())
            for (int count : List.of(0, 1, 4)) {
                var construction = StructureBuildPlan.expand(catalog, ROOT, orientation, Object2IntMaps.singleton("0", count));
                var preview = new StructurePreviewExpansion(catalog, ROOT, new StructurePreviewSettings(orientation, Object2IntMaps.singleton(StructurePreviewPath.ROOT, count), 100, 1000));
                while (preview.model() == null) preview.advance(32);
                assertEquals(preview.model().cells().stream().map(StructurePreviewModel.Cell::position).toList(), construction.cells().stream().map(StructureBuildPlan.Cell::position).toList());
                assertTrue(construction.cells().stream().noneMatch(cell -> cell.position().equals(BlockPos.ORIGIN)));
            }
    }

    @Test
    void fixedAndRotatingAnchorsUseTheCurrentStructureBasisForPlacement() {
        Bootstrap.register();
        var anchor = Blocks.FURNACE.getDefaultState().withProperty(BlockFurnace.FACING, EnumFacing.EAST);
        var registryCatalog = StructureCatalog.fromShapes(Map.of(ROOT, StructureDefinitions.template(ROOT,
            BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false, new StructurePattern(BlockPos2ObjectMap.emptyMap()), List.of(), List.of())));
        var declaredCell = BlockPos.ORIGIN.west();
        var node = new StructureCatalogView.Node(ROOT, BlockOrientation.WEST_UP, false, StructurePreviewProvider.Kind.TEMPLATE,
            BlockPos.ORIGIN, 1, 1, BlockPos.ORIGIN, List.of(declaredCell), IntList.of());
        var reloadedCatalog = new StructureCatalogView(UUID.randomUUID(), 1, List.of(node),
            Map.of(ROOT, new StructureCatalogView.Entry(0, null, BlockPos2ObjectMap.emptyMap())));
        var fixedRegistry = new StructureConstructionRegistry();
        fixedRegistry.register(anchor, ROOT, () -> "test.fixed");
        fixedRegistry.freeze(registryCatalog);
        var rotatingRegistry = new StructureConstructionRegistry();
        rotatingRegistry.register(anchor, ROOT, () -> "test.rotating", StructureAnchorRotation.fourWay(BlockFurnace.FACING));
        rotatingRegistry.freeze(registryCatalog);

        var fixed = Objects.requireNonNull(fixedRegistry.find(anchor));
        var free = Objects.requireNonNull(rotatingRegistry.find(anchor));
        var blockId = anchor.getBlock().getRegistryName();
        var fixedTarget = new ConstructionTarget(null, BlockPos.ORIGIN, blockId, ROOT, fixed.name(), fixed.orientation(anchor, null), null, anchor, fixed);
        var rotatingTarget = new ConstructionTarget(null, BlockPos.ORIGIN, blockId, ROOT, free.name(), free.orientation(anchor, null), null, anchor, free);
        assertEquals(declaredCell, StructureBuildPlan.expand(reloadedCatalog, ROOT, fixedTarget.orientation(reloadedCatalog),
            Object2IntMaps.emptyMap()).cells().getFirst().position());
        assertEquals(BlockPos.ORIGIN.east(), StructureBuildPlan.expand(reloadedCatalog, ROOT, rotatingTarget.orientation(reloadedCatalog),
            Object2IntMaps.emptyMap()).cells().getFirst().position());
    }

}
