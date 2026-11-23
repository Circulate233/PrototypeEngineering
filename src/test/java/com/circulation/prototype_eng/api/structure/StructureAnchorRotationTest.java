package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.IOrientationStrategy;
import ae2.api.orientation.OrientationStrategies;
import ae2.api.orientation.RelativeSide;
import ae2.block.AEBaseTileBlock;
import ae2.tile.AEBaseTile;
import com.mojang.authlib.GameProfile;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAnchorRotationTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void fourWayRejectsVerticalAndWritesThePredictedHorizontalState() {
        var rotation = StructureAnchorRotation.fourWay(BlockStairs.FACING);
        var state = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.NORTH)
                                     .withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.TOP);
        var pos = new BlockPos(-2, 64, 15);
        var world = new RotationWorld(pos, state);
        var player = player(world);
        assertNull(rotation.project(BlockOrientation.get(EnumFacing.UP)));
        assertFalse(rotation.rotate(state, null, world, pos, player, BlockOrientation.NORTH_UP));
        assertTrue(rotation.rotate(state, null, world, pos, player, BlockOrientation.WEST_UP));
        assertSame(BlockOrientation.WEST_UP, rotation.read(world.state, null));
        assertSame(BlockStairs.EnumHalf.TOP, world.state.getValue(BlockStairs.HALF));
    }

    @Test
    void sixWayUsesOneTopPerFrontAndPreservesRedstoneState() {
        var rotation = StructureAnchorRotation.sixWay(BlockDispenser.FACING);
        var state = Blocks.DISPENSER.getDefaultState().withProperty(BlockDispenser.TRIGGERED, true);
        var upright = BlockOrientation.get(EnumFacing.UP);
        var requested = BlockOrientation.get(EnumFacing.UP, upright.getSide(RelativeSide.TOP).getOpposite());
        var projected = rotation.project(requested);
        assertSame(upright, projected);
        var preview = rotation.preview(state, projected);
        assertSame(EnumFacing.UP, preview.getValue(BlockDispenser.FACING));
        assertTrue(preview.getValue(BlockDispenser.TRIGGERED));
        assertSame(projected, rotation.read(preview, null));
    }

    @Test
    void twentyFourWayReadsBackEachFrontAndTopAndRejectsParallelProperties() {
        var rotation = StructureAnchorRotation.twentyFourWay(DualFacingBlock.FRONT, DualFacingBlock.TOP, (_, _, _, _, _, _) -> false);
        var state = new DualFacingBlock().getDefaultState();
        for (var orientation : BlockOrientation.values()) {
            var preview = rotation.preview(state, orientation);
            assertSame(orientation.getSide(RelativeSide.FRONT), preview.getValue(DualFacingBlock.FRONT));
            assertSame(orientation.getSide(RelativeSide.TOP), preview.getValue(DualFacingBlock.TOP));
            assertSame(orientation, rotation.read(preview, null));
        }
        var parallel = state.withProperty(DualFacingBlock.FRONT, EnumFacing.NORTH).withProperty(DualFacingBlock.TOP, EnumFacing.SOUTH);
        assertThrows(IllegalArgumentException.class, () -> rotation.read(parallel, null));
    }

    @Test
    void aePresetReadsAndPersistsTheFullPoseThroughNativeTileStorage() {
        var block = new AeAnchor();
        var state = block.getDefaultState();
        var rotation = StructureAnchorRotation.twentyFourWay(block);
        var tile = new AEBaseTile();
        var pos = new BlockPos(5, 64, 3);
        var world = new RotationWorld(pos, state);
        var player = player(world);
        for (var desired : BlockOrientation.values()) {
            assertFalse(rotation.rotate(state, null, world, pos, player, desired));
            assertTrue(rotation.rotate(state, tile, world, pos, player, desired));
            assertSame(desired, rotation.read(state, tile));
            var modeled = rotation.preview(state, desired);
            assertSame(tile.applyOrientationToBlockState(state), modeled);
            assertSame(desired, rotation.read(modeled, null));
            assertFalse(rotation.rotate(state, tile, world, pos, player, desired));
            var saved = new NBTTagCompound();
            tile.saveAdditional(saved);
            var restored = new AEBaseTile();
            restored.loadTag(saved);
            assertSame(desired, rotation.read(state, restored));
        }
    }

    @Test
    void tileDrivenRotationSeparatesPredictionAndServerCallbackAndPassesMissingTile() {
        var anchor = Blocks.DIAMOND_BLOCK.getDefaultState();
        var pos = new BlockPos(2, 69, -13);
        var world = new RotationWorld(pos, anchor);
        var player = player(world);
        var tile = new OrientationTile();
        var desired = BlockOrientation.get(EnumFacing.DOWN, EnumFacing.EAST);
        var rotation = StructureAnchorRotation.twentyFourWay(
            (_, foundTile) -> foundTile == null ? BlockOrientation.NORTH_UP : ((OrientationTile) foundTile).orientation,
            (state, _) -> state,
            (state, foundTile, foundWorld, foundPos, foundPlayer, orientation) -> {
                assertSame(anchor, state);
                assertSame(world, foundWorld);
                assertSame(pos, foundPos);
                assertSame(player, foundPlayer);
                if (foundTile == null) return false;
                assertSame(tile, foundTile);
                tile.orientation = orientation;
                return true;
            });
        assertSame(BlockOrientation.NORTH_UP, rotation.read(anchor, null));
        assertSame(anchor, rotation.preview(anchor, desired));
        assertSame(BlockOrientation.NORTH_UP, rotation.read(anchor, tile));
        assertFalse(rotation.rotate(anchor, null, world, pos, player, desired));
        assertTrue(rotation.rotate(anchor, tile, world, pos, player, desired));
        assertSame(desired, rotation.read(anchor, tile));
        assertSame(anchor, world.state);
    }

    private static EntityPlayer player(World world) {
        return new EntityPlayer(world, new GameProfile(null, "Construction")) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
    }

    private static final class DualFacingBlock extends Block {
        private static final PropertyDirection FRONT = PropertyDirection.create("front");
        private static final PropertyDirection TOP = PropertyDirection.create("top");

        DualFacingBlock() {
            super(Material.ROCK);
        }

        @Override
        protected @NotNull BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, FRONT, TOP);
        }
    }

    private static final class AeAnchor extends AEBaseTileBlock<AEBaseTile> {
        AeAnchor() {
            super(Material.IRON);
        }

        @Override
        public @NotNull IOrientationStrategy getOrientationStrategy() {
            return OrientationStrategies.full();
        }
    }

    private static final class OrientationTile extends TileEntity {
        private BlockOrientation orientation = BlockOrientation.NORTH_UP;
    }

    private static final class RotationWorld extends World {
        private final BlockPos position;
        private IBlockState state;

        RotationWorld(BlockPos position, IBlockState state) {
            super(new SaveHandlerMP(), new WorldInfo(new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.DEFAULT), "construction"),
                new WorldProviderSurface(), new Profiler(), false);
            this.position = position;
            this.state = state;
        }

        @Override
        protected @NotNull IChunkProvider createChunkProvider() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            return false;
        }

        @Override
        public @NotNull BlockPos getSpawnPoint() {
            return BlockPos.ORIGIN;
        }

        @Override
        public boolean setBlockState(@NotNull BlockPos pos, @NotNull IBlockState next, int flags) {
            assertSame(position, pos);
            state = next;
            return true;
        }
    }
}
