package com.circulation.prototype_eng.client.preview;

import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructurePreviewExpansionTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("preview_test", name);
    }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static StructureCatalogView.Node template(String name, BlockPos offset, List<BlockPos> cells, int... children) {
        return new StructureCatalogView.Node(id(name), BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.TEMPLATE, offset, 1, 1, BlockPos.ORIGIN, cells, IntList.of(children));
    }

    private static StructureCatalogView view(List<StructureCatalogView.Node> nodes, Map<ResourceLocation, StructureCatalogView.Entry> entries) {
        return new StructureCatalogView(UUID.randomUUID(), 0, nodes, entries);
    }

    private static Map<ResourceLocation, StructureCatalogView.Entry> entries(List<StructureCatalogView.Node> nodes) {
        var entries = new LinkedHashMap<ResourceLocation, StructureCatalogView.Entry>();
        for (int i = 0; i < nodes.size(); i++)
            entries.put(nodes.get(i).id(), new StructureCatalogView.Entry(i, null, Map.of()));
        return entries;
    }

    private static StructurePreviewModel finish(StructurePreviewExpansion expansion) {
        for (int i = 0; expansion.model() == null && i < 100_000; i++) assertTrue(expansion.advance(8) <= 8);
        return Objects.requireNonNull(expansion.model(), "Expansion did not terminate");
    }

    private static StructureDisplay display(String tileNbt) {
        return new StructureDisplay("shared", List.of(new StructureDisplay.BlockOption(id("block"), 0, Map.of())), tileNbt);
    }

    @Test
    void sharedSliceNodeUsesChildEdgeOccurrencesAndFreezesCallerChoices() {
        var nodes = List.of(template("root", BlockPos.ORIGIN, List.of(), 1, 1),
            new StructureCatalogView.Node(id("slice"), BlockOrientation.NORTH_UP, true, StructurePreviewProvider.Kind.SLICE, pos(2, 0, 0), 0, 3, pos(0, 1, 0), List.of(pos(1, 0, 0)), IntList.of()));
        var choices = new Object2IntLinkedOpenHashMap<StructurePreviewPath>();
        choices.put(StructurePreviewPath.ROOT.child(0), -20);
        choices.put(StructurePreviewPath.ROOT.child(1), 20);
        var settings = new StructurePreviewSettings(BlockOrientation.NORTH_UP, choices, 10, 10);
        assertThrows(UnsupportedOperationException.class, () -> settings.sliceCounts().object2IntEntrySet().iterator().next().setValue(1));
        choices.clear();
        var model = finish(new StructurePreviewExpansion(view(nodes, entries(nodes)), id("root"), settings));
        assertEquals(3, model.cells().size());
        assertEquals(List.of(1, 0, 3), model.occurrences().stream().map(StructurePreviewModel.Occurrence::selectedSlices).toList());
        assertTrue(model.cells().stream().allMatch(cell -> cell.occurrence().equals(StructurePreviewPath.ROOT.child(1))));
        assertTrue(model.cells().stream().allMatch(StructurePreviewModel.Cell::hideWorldBlocks));
        assertEquals(List.of(pos(3, 0, 0), pos(3, 1, 0), pos(3, 2, 0)), model.cells().stream().map(StructurePreviewModel.Cell::position).toList());
    }

    @Test
    void zeroSliceChildrenAttachOneSliceBeforeBaseAndKeepTheirOwnOffsets() {
        var nodes = List.of(new StructureCatalogView.Node(id("root"), BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.SLICE, pos(2, 1, 0), 0, 0, pos(0, 3, 0), List.of(pos(1, 0, 0)), IntList.of(1)),
            template("child", pos(0, 0, 1), List.of(pos(1, 0, 0))));
        var model = finish(new StructurePreviewExpansion(view(nodes, entries(nodes)), id("root"), new StructurePreviewSettings(BlockOrientation.NORTH_UP)));
        assertEquals(List.of(pos(3, -2, 1)), model.cells().stream().map(StructurePreviewModel.Cell::position).toList());
    }

    @Test
    void finalCoordinateWinnerOwnsBomAndTileNbtHashCollisionsCannotMergeDisplays() {
        var nodes = List.of(template("root", BlockPos.ORIGIN, List.of(BlockPos.ORIGIN, pos(1, 0, 0), pos(2, 0, 0)), 1),
            template("child", BlockPos.ORIGIN, List.of(pos(1, 0, 0))));
        var first = display("{x:\"Aa\"}");
        var second = display("{x:\"BB\"}");
        assertEquals(Objects.requireNonNull(first.tileNbt()).hashCode(), Objects.requireNonNull(second.tileNbt()).hashCode());
        var entries = entries(nodes);
        entries.put(id("root"), new StructureCatalogView.Entry(0, null, Map.of(BlockPos.ORIGIN, first, pos(1, 0, 0), first, pos(2, 0, 0), first)));
        entries.put(id("child"), new StructureCatalogView.Entry(1, null, Map.of(pos(1, 0, 0), second)));
        var model = finish(new StructurePreviewExpansion(view(nodes, entries), id("root"), new StructurePreviewSettings(BlockOrientation.NORTH_UP)));
        assertEquals(2, model.cells().size());
        assertEquals(second, model.cells().getFirst().display());
        assertEquals(StructurePreviewPath.ROOT.child(0), model.cells().getFirst().occurrence());
        assertEquals(2, model.bom().size());
        assertTrue(model.bom().stream().allMatch(line -> line.count() == 1));
    }

    @Test
    void rotationIsTheRootTransformAppliedToEntireHierarchyOnce() {
        var nodes = List.of(template("root", pos(2, 1, 0), List.of(), 1), template("child", BlockPos.ORIGIN, List.of(pos(3, 0, 0))));
        var model = finish(new StructurePreviewExpansion(view(nodes, entries(nodes)), id("root"), new StructurePreviewSettings(BlockOrientation.NORTH_WEST)));
        assertEquals(pos(-1, 5, 0), model.cells().getFirst().position());
        var rotated = List.of(new StructureCatalogView.Node(id("root"), BlockOrientation.EAST_UP, false, StructurePreviewProvider.Kind.TEMPLATE, BlockPos.ORIGIN, 1, 1, BlockPos.ORIGIN, List.of(pos(0, 0, 2)), IntList.of()));
        assertEquals(pos(2, 0, 0), finish(new StructurePreviewExpansion(view(rotated, entries(rotated)), id("root"),
            new StructurePreviewSettings(BlockOrientation.NORTH_UP))).cells().getFirst().position());
    }

    @Test
    void incompleteExpansionNeverPublishesAndEmptyHugeSliceSkipsEmptyIterations() {
        var nodes = List.of(new StructureCatalogView.Node(id("root"), BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.SLICE, BlockPos.ORIGIN, Integer.MAX_VALUE, Integer.MAX_VALUE, BlockPos.ORIGIN, List.of(), IntList.of(1)),
            template("child", BlockPos.ORIGIN, List.of(pos(1, 0, 0))));
        var expansion = new StructurePreviewExpansion(view(nodes, entries(nodes)), id("root"), new StructurePreviewSettings(BlockOrientation.NORTH_UP));
        assertEquals(1, expansion.advance(1));
        assertNull(expansion.model());
        assertEquals(0, expansion.advance(0));
        assertNull(expansion.model());
        var model = finish(expansion);
        assertEquals(1, model.cells().size());
        assertEquals(Integer.MAX_VALUE, model.occurrences().getFirst().selectedSlices());
        assertEquals(0, expansion.advance(1000));
        assertSame(model, expansion.model());
        assertThrows(UnsupportedOperationException.class, () -> model.cells().clear());
        assertThrows(UnsupportedOperationException.class, () -> model.bom().clear());
        assertThrows(UnsupportedOperationException.class, () -> model.occurrences().clear());
    }

    @Test
    void visitLimitCountsOverwrittenAndExcludedCellsAndOverflowNeverWraps() {
        var nodes = List.of(template("root", BlockPos.ORIGIN, List.of(BlockPos.ORIGIN, pos(1, 0, 0))));
        var limited = new StructurePreviewSettings(BlockOrientation.NORTH_UP, Object2IntMaps.emptyMap(), 10, 1);
        var expansion = new StructurePreviewExpansion(view(nodes, entries(nodes)), id("root"), limited);
        assertThrows(IllegalArgumentException.class, () -> expansion.advance(100));
        assertNull(expansion.model());
        var large = List.of(template("root", pos(Integer.MAX_VALUE, 0, 0), List.of(pos(1, 0, 0))));
        var overflow = new StructurePreviewExpansion(view(large, entries(large)), id("root"), new StructurePreviewSettings(BlockOrientation.NORTH_UP));
        assertThrows(ArithmeticException.class, () -> overflow.advance(100));
        assertNull(overflow.model());
    }

    @Test
    void deepGraphUsesIterativePathsAndStopsAtOccurrenceLimit() {
        var nodes = new ArrayList<StructureCatalogView.Node>();
        for (int i = 0; i < 5000; i++) nodes.add(template("node" + i, BlockPos.ORIGIN, List.of(), i + 1));
        nodes.add(template("node5000", BlockPos.ORIGIN, List.of(pos(1, 0, 0))));
        var view = view(nodes, entries(nodes));
        var model = finish(new StructurePreviewExpansion(view, id("node0"), new StructurePreviewSettings(BlockOrientation.NORTH_UP)));
        assertEquals(5000, model.cells().getFirst().occurrence().depth());
        var limited = new StructurePreviewExpansion(view, id("node0"), new StructurePreviewSettings(BlockOrientation.NORTH_UP, Object2IntMaps.emptyMap(), 4, 10));
        assertThrows(IllegalArgumentException.class, () -> limited.advance(6000));
        assertNull(limited.model());
    }

    @Test
    void opaqueExternalGeometryIsReportedAndDescendsWithoutInventingItsCells() {
        var nodes = List.of(new StructureCatalogView.Node(id("root"), BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.OPAQUE, pos(2, 0, 0), 0, 0, BlockPos.ORIGIN, List.of(pos(40, 0, 0)), IntList.of(1)),
            template("child", BlockPos.ORIGIN, List.of(pos(1, 0, 0))));
        var model = finish(new StructurePreviewExpansion(view(nodes, entries(nodes)), id("root"), new StructurePreviewSettings(BlockOrientation.NORTH_UP)));
        assertTrue(model.occurrences().getFirst().opaque());
        assertEquals(List.of(pos(3, 0, 0)), model.cells().stream().map(StructurePreviewModel.Cell::position).toList());
        assertNull(model.bom().getFirst().display());
    }

    @Test
    void modelBuilderCannotMutateTransferredListsAfterFreeze() {
        var nodes = List.of(template("root", BlockPos.ORIGIN, List.of(pos(1, 0, 0))));
        var builder = new StructurePreviewModel.Builder(view(nodes, entries(nodes)), id("root"), new StructurePreviewSettings(BlockOrientation.NORTH_UP));
        var cell = new StructurePreviewModel.Cell(pos(1, 0, 0), StructurePreviewPath.ROOT, 0, -1, null, false);
        builder.addCell(cell);
        builder.addBom(new StructurePreviewModel.BomLine(null, 1));
        var occurrence = new StructurePreviewModel.Occurrence(StructurePreviewPath.ROOT, 0, BlockPos.ORIGIN, 1, false);
        builder.addOccurrence(occurrence);
        var model = builder.freeze(StructureBounds.at(cell.position()));
        assertThrows(IllegalStateException.class, () -> builder.addCell(cell));
        assertThrows(IllegalStateException.class, () -> builder.addBom(new StructurePreviewModel.BomLine(null, 9)));
        assertThrows(IllegalStateException.class, () -> builder.addOccurrence(occurrence));
        assertThrows(IllegalStateException.class, () -> builder.freeze(StructureBounds.at(BlockPos.ORIGIN)));
        assertThrows(UnsupportedOperationException.class, () -> model.cells().clear());
        assertEquals(1, model.cells().size());
        assertEquals(1, model.bom().getFirst().count());
    }
}
