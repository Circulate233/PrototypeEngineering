package com.circulation.prototype_eng.client.preview;

import net.minecraft.nbt.NBTException;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreviewTileEntitiesTest {
    private record Tile(Object world, BlockPos position, NBTTagCompound tag) {}
    private static StructurePreviewModel.Cell cell(int x, int y, int z) {
        return new StructurePreviewModel.Cell(new BlockPos(x, y, z), StructurePreviewPath.ROOT, 0, -1, null, false);
    }
    private static final class Owner implements PreviewTileEntities.Owner<Tile> {
        final ObjectArrayList<Tile> created = new ObjectArrayList<>(), released = new ObjectArrayList<>();
        Runnable afterCreate = () -> {};
        RuntimeException drawFailure;
        int drawCalls;
        @Override public Tile create(Object world, IBlockState ignoredRepresentative, BlockPos position, NBTTagCompound tag) {
            var tile = new Tile(world, position, tag); created.add(tile);
            try { afterCreate.run(); return tile; }
            catch (RuntimeException | Error failure) { release(tile); throw failure; }
        }
        @Override public void release(Tile tile) { assertFalse(released.contains(tile), "Each tile has one retirement owner"); released.add(tile); }
        @Override public void draw(Tile tile, BlockPos position, float partialTicks) {
            drawCalls++;
            if (drawFailure != null) throw drawFailure;
            fail("Acquisition tests never tick or render native tiles");
        }
    }
    @Test void nbtIsCopiedPerTileAndPositionOverridesTemplateWithoutSharingChildren() {
        var owner = new Owner(); var world = new Object(); var token = new Object();
        var tag = new NBTTagCompound(); tag.setInteger("x", 99); var nested = new NBTTagCompound(); nested.setString("data", "source"); tag.setTag("nested", nested);
        try (var tiles = new PreviewTileEntities<>(owner)) {
            tiles.synchronize(token, world);
            tiles.capture(token, world, cell(-4, 3, 8), null, tag, () -> true);
            tiles.capture(token, world, cell(7, 9, -3), null, tag, () -> true);
            assertEquals(2, owner.created.size());
            var first = owner.created.getFirst(); var second = owner.created.getLast();
            assertNotSame(first.tag, second.tag); assertNotSame(tag, first.tag);
            assertEquals(-4, first.tag.getInteger("x")); assertEquals(3, first.tag.getInteger("y")); assertEquals(8, first.tag.getInteger("z"));
            assertEquals(7, second.tag.getInteger("x")); assertEquals(99, tag.getInteger("x"));
            first.tag.getCompoundTag("nested").setString("data", "changed");
            assertEquals("source", second.tag.getCompoundTag("nested").getString("data"));
            assertEquals("source", tag.getCompoundTag("nested").getString("data"));
        }
        assertEquals(2, owner.released.size());
    }
    @Test void worldSelectionAndGenerationReplacementRetireOldTilesExactlyOnce() {
        var owner = new Owner(); var tiles = new PreviewTileEntities<>(owner); var world = new Object(); var token = new Object();
        tiles.synchronize(token, world); tiles.capture(token, world, cell(1, 0, 0), null, null, () -> true);
        var replacement = new Object(); tiles.synchronize(replacement, world);
        assertEquals(List.of(owner.created.getFirst()), owner.released);
        tiles.capture(token, world, cell(2, 0, 0), null, null, () -> true);
        assertEquals(1, owner.created.size(), "Late old selection cannot create a tile");
        tiles.capture(replacement, world, cell(3, 0, 0), null, null, () -> true);
        tiles.synchronize(replacement, new Object()); assertEquals(2, owner.released.size());
        tiles.close(); tiles.close(); assertEquals(2, owner.released.size());
    }
    @Test void reentrantReplacementDuringFactoryReleasesUnpublishedOldTile() {
        var owner = new Owner(); var tiles = new PreviewTileEntities<>(owner); var world = new Object(); var token = new Object();
        tiles.synchronize(token, world);
        owner.afterCreate = () -> tiles.synchronize(new Object(), new Object());
        tiles.capture(token, world, cell(1, 0, 0), null, null, () -> true);
        assertEquals(owner.created, owner.released);
        tiles.close(); assertEquals(1, owner.released.size());
    }
    @Test void vmErrorFromFactoryIsNeverSwallowedAsUnsupportedTesr() {
        var failure = new OutOfMemoryError("synthetic owner failure");
        var owner = new Owner(); owner.afterCreate = () -> { throw failure; };
        try (var tiles = new PreviewTileEntities<>(owner)) {
            var token = new Object(); var world = new Object(); tiles.synchronize(token, world);
            assertSame(failure, assertThrows(OutOfMemoryError.class, () -> tiles.capture(token, world, cell(0, 1, 0), null, null, () -> true)));
        }
    }
    @Test void ordinaryFactoryFailurePropagatesAfterItsOwnedTileCleanup() {
        var failure = new IllegalStateException("broken representative factory");
        var owner = new Owner(); owner.afterCreate = () -> { throw failure; };
        try (var tiles = new PreviewTileEntities<>(owner)) {
            var token = new Object(); var world = new Object(); tiles.synchronize(token, world);
            var template = new NBTTagCompound(); template.setInteger("x", 99);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> tiles.capture(token, world, cell(0, 1, 0), null, template, () -> true)));
            assertEquals(1, owner.created.size(), "The owned tag is applied through one acquisition callback");
            assertEquals(0, owner.created.getFirst().tag.getInteger("x")); assertEquals(99, template.getInteger("x"));
            assertEquals(owner.created, owner.released);
        }
        assertEquals(1, owner.released.size());
    }
    @Test void malformedDisplaySnbtFailsWithItsOriginalParserCause() {
        var failure = assertThrows(IllegalArgumentException.class, () -> PreviewTileEntities.parse("{unclosed:"));
        assertInstanceOf(NBTException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("Invalid structure preview tile SNBT"));
    }
    @Test void drawingFailurePropagatesAndClosingRetiresTheOwnedTileExactlyOnce() {
        var owner = new Owner(); var failure = new IllegalStateException("broken TESR"); owner.drawFailure = failure;
        try (var fixture = StructurePreviewInteractionTest.fixture(); var tiles = new PreviewTileEntities<>(owner)) {
            var view = fixture.first(); var world = new Object(); tiles.synchronize(view.token(), world);
            tiles.capture(view.token(), world, Objects.requireNonNull(view.model()).cells().getFirst(), null, new NBTTagCompound(), () -> true);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> tiles.draw(view, 0.5f, 1)));
            assertEquals(1, owner.drawCalls); tiles.close(); tiles.close(); assertEquals(owner.created, owner.released);
        }
        assertEquals(1, owner.released.size());
    }
}
