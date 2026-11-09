package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.item.OrientationToolOrientations;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructureToolStateTest {
    @Test
    void selectionCornerRestartAndDimensionChangeKeepOriginalSemantics() {
        var tag = new NBTTagCompound();
        ScannerState.defaults(tag, "sample");
        assertTrue(ScannerState.select(tag, new BlockPos(4, 5, 6), 0, false, "My Structure"));
        assertFalse(ScannerState.select(tag, new BlockPos(1, 2, 3), 0, false, "ignored"));
        var box = ScannerState.box(tag);
        assertNotNull(box);
        assertEquals(new BlockPos(1, 2, 3), box.min());
        assertEquals("my_structure", tag.getString(ScannerState.Field.ID.key));
        assertTrue(ScannerState.select(tag, new BlockPos(9, 8, 7), 0, true, "new"));
        assertNull(ScannerState.box(tag));
        assertTrue(ScannerState.select(tag, BlockPos.ORIGIN, 1, false, "dimension"));
        assertEquals(1, tag.getInteger("dimension"));
        assertEquals(BlockPos.ORIGIN, ScannerState.origin(tag));
    }

    @Test
    void editsRejectOverflowAndRoundTripAll24Orientations() {
        var tag = new NBTTagCompound();
        assertFalse(ScannerState.edit(tag, ScannerState.Field.X, "2147483648"));
        assertFalse(ScannerState.edit(tag, ScannerState.Field.X, "--1"));
        assertFalse(ScannerState.edit(tag, ScannerState.Field.NBT, "not_boolean"));
        assertTrue(ScannerState.edit(tag, ScannerState.Field.QUANTITY, "-10"));
        assertEquals(1, tag.getInteger(ScannerState.Field.QUANTITY.key));
        assertTrue(ScannerState.edit(tag, ScannerState.Field.X, "-2147483648"));
        assertEquals(Integer.MIN_VALUE, tag.getInteger(ScannerState.Field.X.key));
        for (var front : EnumFacing.VALUES) {
            var orientation = OrientationToolOrientations.canonical(front);
            for (int twist = 0; twist < 4; twist++) {
                ScannerState.orientation(tag, orientation);
                assertSame(orientation, ScannerState.orientation(tag));
                var local = new BlockPos(2, -4, 3);
                assertEquals(local, ScannerState.worldToTemplate(tag).apply(StructureTransform.of(orientation).apply(local)));
                orientation = orientation.rotateClockwiseAround(front);
            }
        }
    }

    @Test
    void hugePreviewSelectionIsBoundedAndContainsBothEdges() {
        var box = new ScannerState.Box(new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE), new BlockPos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(box.exceeds(Long.MAX_VALUE));
        var points = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> box.previewPositions(32768));
        assertTrue(points.size() <= 32768);
        assertTrue(points.contains(box.min()));
        assertTrue(points.contains(box.max()));
        var tiny = new ScannerState.Box(BlockPos.ORIGIN, new BlockPos(1, 2, 3));
        assertEquals(24, tiny.previewPositions(32768).size());
    }

    @Test
    void materialsWithDifferentNbtCannotShareSelectionKeys() {
        var id = new ResourceLocation("test", "block");
        var a = new StructureDisplay.BlockOption(id, 0, Map.of("a", "b, c=d"));
        var b = new StructureDisplay.BlockOption(id, 0, Map.of("a", "b", "c", "d"));
        assertNotEquals(BuilderState.optionKey(a), BuilderState.optionKey(b));
        var display = new StructureDisplay("choice", List.of(a, b), null);
        var tag = new NBTTagCompound();
        assertEquals(a, BuilderState.choice(tag, display));
        BuilderState.choose(tag, display.key(), BuilderState.optionKey(b));
        assertEquals(b, BuilderState.choice(tag, display));
        var copy = tag.copy();
        BuilderState.state(copy, BuilderState.State.PAUSED_BUILDING);
        BuilderState.progress(copy, 17, 31);
        assertEquals(BuilderState.State.PAUSED_BUILDING, BuilderState.state(copy));
        assertEquals(17, BuilderState.done(copy));
        assertEquals(31, BuilderState.total(copy));
    }
}
