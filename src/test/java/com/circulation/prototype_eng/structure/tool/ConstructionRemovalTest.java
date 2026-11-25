package com.circulation.prototype_eng.structure.tool;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFlowerPot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionRemovalTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void deferredRemovalCanHarvestWithTheOriginalTileAndTool() {
        var world = new TestWorld();
        var tool = new ItemStack(Items.IRON_PICKAXE);
        var block = new HarvestBlock(true, true) {
            @Override
            public void harvestBlock(@NotNull World world, @NotNull EntityPlayer player, @NotNull BlockPos pos, @NotNull IBlockState state,
                                     @Nullable TileEntity tile, @NotNull ItemStack stack) {
                assertSame(state, world.getBlockState(pos));
                assertSame(world.getTileEntity(pos), tile);
                assertSame(tool, stack);
                super.harvestBlock(world, player, pos, state, tile, stack);
            }
        };

        assertEquals(ConstructionPlacement.Removal.REMOVED, world.remove(block, tool));
        assertSame(Blocks.AIR, world.state.getBlock());
        assertEquals(1, block.harvested);
        assertEquals(1, block.experienceDrops);
    }

    @Test
    void refusedRemovalDoesNotHarvest() {
        var block = new HarvestBlock(false, false);
        assertEquals(ConstructionPlacement.Removal.BLOCKED, new TestWorld().remove(block, ItemStack.EMPTY));
        assertEquals(0, block.harvested);
        assertEquals(0, block.experienceDrops);
    }

    @Test
    void unchangedBlockReportsFailureWithoutAwardingExperience() {
        var world = new TestWorld();
        var block = new HarvestBlock(true, false);
        assertEquals(ConstructionPlacement.Removal.UNCHANGED, world.remove(block, ItemStack.EMPTY));
        assertSame(block, world.state.getBlock());
        assertEquals(1, block.harvested);
        assertEquals(0, block.experienceDrops);
    }

    private static class HarvestBlock extends Block {
        private final boolean accepts;
        private final boolean removeOnHarvest;
        int harvested;
        int experienceDrops;

        HarvestBlock(boolean accepts, boolean removeOnHarvest) {
            super(Material.ROCK);
            this.accepts = accepts;
            this.removeOnHarvest = removeOnHarvest;
        }

        @Override
        public boolean removedByPlayer(@NotNull IBlockState state, @NotNull World world, @NotNull BlockPos pos, @NotNull EntityPlayer player,
                                       boolean willHarvest) {
            assertTrue(willHarvest);
            return accepts;
        }

        @Override
        public void harvestBlock(@NotNull World world, @NotNull EntityPlayer player, @NotNull BlockPos pos, @NotNull IBlockState state,
                                 @Nullable TileEntity tile, @NotNull ItemStack tool) {
            harvested++;
            if (removeOnHarvest) world.setBlockToAir(pos);
        }

        @Override
        public void dropXpOnBlockBreak(@NotNull World world, @NotNull BlockPos pos, int experience) {
            experienceDrops++;
        }
    }

    private static final class TestWorld extends World {
        private final TileEntity tile = new TileEntityFlowerPot();
        private IBlockState state = Blocks.AIR.getDefaultState();

        TestWorld() {
            super(new SaveHandlerMP(), new WorldInfo(new WorldSettings(0, GameType.SURVIVAL, false, false, WorldType.DEFAULT), "construction"),
                new WorldProviderSurface(), new Profiler(), false);
        }

        ConstructionPlacement.Removal remove(Block block, ItemStack tool) {
            state = block.getDefaultState();
            var player = new EntityPlayer(this, new GameProfile(null, "Construction")) {
                @Override
                public boolean isSpectator() {
                    return false;
                }

                @Override
                public boolean isCreative() {
                    return false;
                }
            };
            var chunk = new Chunk(this, 0, 0) {
                @Override
                public @NotNull IBlockState getBlockState(@NotNull BlockPos pos) {
                    return state;
                }
            };
            return ConstructionPlacement.removeWithCallbacks(this, BlockPos.ORIGIN, chunk, state, player, true, tool, 2);
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
        public @NotNull IBlockState getBlockState(@NotNull BlockPos pos) {
            return state;
        }

        @Override
        public @NotNull TileEntity getTileEntity(@NotNull BlockPos pos) {
            return tile;
        }

        @Override
        public boolean setBlockToAir(@NotNull BlockPos pos) {
            state = Blocks.AIR.getDefaultState();
            return true;
        }
    }
}
