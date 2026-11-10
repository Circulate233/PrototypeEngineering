package com.circulation.prototype_eng.structure.tool;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.client.preview.StructurePreviewExpansion;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureBuildPlanTest {
    private static final ResourceLocation ROOT = new ResourceLocation("test", "root"), CHILD = new ResourceLocation("test", "child");

    private static StructureCatalogView view(int offset) {
        var display = new StructureDisplay("stone", List.of(new StructureDisplay.BlockOption(new ResourceLocation("minecraft", "stone"), 0, Map.of())), null);
        var nodes = List.of(new StructureCatalogView.Node(ROOT, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.SLICE, new BlockPos(offset, 0, 0), 0, 5, new BlockPos(0, 2, 0), List.of(BlockPos.ORIGIN, new BlockPos(1, 0, 0)), List.of(1)),
            new StructureCatalogView.Node(CHILD, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.TEMPLATE, new BlockPos(0, 0, 1), 1, 1, BlockPos.ORIGIN, List.of(new BlockPos(1, 0, 0)), List.of()));
        return new StructureCatalogView(UUID.randomUUID(), 0, nodes, Map.of(ROOT, new StructureCatalogView.Entry(0, null, Map.of(BlockPos.ORIGIN, display, new BlockPos(1, 0, 0), display)), CHILD, new StructureCatalogView.Entry(1, null, Map.of(new BlockPos(1, 0, 0), display))));
    }

    @Test
    void constructionAndPreviewAgreeForZeroAndRepeatedSlicesInEveryOrientation() {
        var catalog = view(0);
        for (var front : EnumFacing.VALUES)
            for (int count : List.of(0, 1, 4)) {
                var orientation = BlockOrientation.get(front, front.getAxis() == EnumFacing.Axis.Y ? EnumFacing.NORTH : EnumFacing.UP);
                var construction = StructureBuildPlan.expand(catalog, ROOT, orientation, Map.of("0", count));
                var preview = new StructurePreviewExpansion(catalog, ROOT, new StructurePreviewSettings(orientation, Map.of(StructurePreviewPath.ROOT, count), 100, 1000));
                while (preview.model() == null) preview.advance(32);
                assertEquals(preview.model().cells().stream().map(StructurePreviewModel.Cell::position).toList(), construction.cells().stream().map(StructureBuildPlan.Cell::position).toList());
                assertTrue(construction.cells().stream().noneMatch(cell -> cell.position().equals(BlockPos.ORIGIN)));
            }
    }

    @Test
    void coordinateOverflowIsReportedBeforeWrappingIntoUnrelatedBlocks() {
        assertThrows(ArithmeticException.class, () -> StructureBuildPlan.expand(view(Integer.MAX_VALUE), ROOT, BlockOrientation.NORTH_UP, Map.of("0", 1)));
        assertThrows(ArithmeticException.class, () -> StructureBuildPlan.add(new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(-1, 0, 0)));
    }
}
