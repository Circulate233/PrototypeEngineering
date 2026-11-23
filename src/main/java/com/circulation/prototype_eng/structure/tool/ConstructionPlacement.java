package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.DecorativeBlocks;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.Constants;

final class ConstructionPlacement {
    private static final int SUPPRESS_NEIGHBORS_AND_OBSERVERS = Constants.BlockFlags.NO_OBSERVERS;

    private ConstructionPlacement() {
    }

    static boolean accepts(World world) {
        return !world.captureBlockSnapshots && !world.restoringBlockSnapshots
            && world.getWorldType() != WorldType.DEBUG_ALL_BLOCK_STATES;
    }

    static BlockSnapshot snapshot(World world, BlockPos pos, IBlockState current) {
        return current.getBlock() == Blocks.AIR ? new BlockSnapshot(world, pos, current, null)
            : new BlockSnapshot(world, pos, current);
    }

    static boolean write(World world, BlockPos pos, Chunk chunk, IBlockState current, IBlockState desired,
                         ConstructionLightingBatch lighting) {
        if (!accepts(world)) return false;
        return usesChunkPlacement(current, desired) ? writeChunk(world, pos, chunk, current, desired, lighting)
            : world.setBlockState(pos, desired, SUPPRESS_NEIGHBORS_AND_OBSERVERS);
    }

    private static boolean writeChunk(World world, BlockPos pos, Chunk chunk, IBlockState current, IBlockState desired,
                                      ConstructionLightingBatch lighting) {
        long before = ConstructionLightingBatch.lightProperties(current, world, pos);
        if (chunk.setBlockState(pos, desired) == null) return false;
        var actual = chunk.getBlockState(pos);
        lighting.light(chunk, pos, before, ConstructionLightingBatch.lightProperties(actual, world, pos));
        return true;
    }

    static boolean isStatic(IBlockState state) {
        var block = state.getBlock();
        if (VanillaMaterials.BLOCKS.contains(block)) return true;
        var id = block.getRegistryName();
        return id != null && Tags.MOD_ID.equals(id.getNamespace()) && Decorations.BLOCKS.contains(block);
    }

    private static boolean usesChunkPlacement(IBlockState current, IBlockState desired) {
        return (current.getBlock() == Blocks.AIR || isStatic(current)) && (desired.getBlock() == Blocks.AIR || isStatic(desired));
    }

    static boolean removeStatic(World world, BlockPos pos, Chunk chunk, IBlockState state, EntityPlayer player,
                                ConstructionLightingBatch lighting) {
        state.getBlock().onBlockHarvested(world, pos, state, player);
        var air = Blocks.AIR.getDefaultState();
        if (!writeChunk(world, pos, chunk, state, air, lighting)) return false;
        lighting.notify(chunk, pos, state, air);
        return true;
    }

    static void notify(World world, BlockPos pos, Chunk chunk, IBlockState previous, IBlockState placed,
                       ConstructionLightingBatch lighting) {
        if (usesChunkPlacement(previous, placed)) lighting.notify(chunk, pos, previous, placed);
        else world.markAndNotifyBlock(pos, chunk, previous, placed, Constants.BlockFlags.DEFAULT);
    }

    static void restore(World world, Chunk chunk, BlockSnapshot snapshot, IBlockState written, ConstructionLightingBatch lighting) {
        var pos = snapshot.getPos();
        if (world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4) != chunk) {
            PrototypeEngineering.LOGGER.error("Construction rollback lost access to the chunk at {}", pos);
            return;
        }
        if (!chunk.getBlockState(pos).equals(written)) return;
        boolean restoring = world.restoringBlockSnapshots;
        world.restoringBlockSnapshots = true;
        try {
            var restored = snapshot.getReplacedBlock();
            boolean reverted = usesChunkPlacement(written, restored) ? writeChunk(world, pos, chunk, written, restored, lighting)
                : world.setBlockState(pos, restored, SUPPRESS_NEIGHBORS_AND_OBSERVERS);
            if (!reverted || !chunk.getBlockState(pos).equals(restored)) {
                PrototypeEngineering.LOGGER.error("Construction rollback failed for {}", pos);
                return;
            }
            var nbt = snapshot.getNbt();
            if (nbt == null) return;
            var tile = world.getTileEntity(pos);
            if (tile == null) {
                PrototypeEngineering.LOGGER.error("Construction rollback could not restore block entity at {}", pos);
                return;
            }
            tile.readFromNBT(nbt);
            tile.markDirty();
        } finally {
            world.restoringBlockSnapshots = restoring;
        }
    }

    private static final class VanillaMaterials {
        private static final ReferenceOpenHashSet<Block> BLOCKS = new ReferenceOpenHashSet<>(new Block[]{
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.STONEBRICK, Blocks.BRICK_BLOCK, Blocks.PLANKS, Blocks.OBSIDIAN,
            Blocks.CONCRETE, Blocks.HARDENED_CLAY, Blocks.STAINED_HARDENED_CLAY, Blocks.IRON_BLOCK, Blocks.QUARTZ_BLOCK,
            Blocks.PRISMARINE, Blocks.GLASS, Blocks.STAINED_GLASS, Blocks.GOLD_BLOCK, Blocks.GLOWSTONE, Blocks.SEA_LANTERN
        });
    }

    private static final class Decorations {
        private static final ReferenceOpenHashSet<Block> BLOCKS = new ReferenceOpenHashSet<>(DecorativeBlocks.all());
    }
}
