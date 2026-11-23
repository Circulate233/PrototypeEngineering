package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
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
                         ConstructionLightingBatch<World> lighting) {
        if (!accepts(world)) return false;
        return usesChunkPlacement(current, desired) ? writeChunk(world, pos, chunk, current, desired, lighting)
            : world.setBlockState(pos, desired, SUPPRESS_NEIGHBORS_AND_OBSERVERS);
    }

    private static boolean writeChunk(World world, BlockPos pos, Chunk chunk, IBlockState current, IBlockState desired,
                                      ConstructionLightingBatch<World> lighting) {
        int light = current.getLightValue(world, pos);
        int opacity = current.getLightOpacity(world, pos);
        if (chunk.setBlockState(pos, desired) == null) return false;
        if (light != desired.getLightValue(world, pos) || opacity != desired.getLightOpacity(world, pos))
            lighting.add(world, pos);
        return true;
    }

    static void checkLight(World world, BlockPos pos) {
        try {
            world.checkLight(pos);
        } catch (RuntimeException failure) {
            PrototypeEngineering.LOGGER.error("Construction lighting failed at {} in dimension {}", pos, world.provider.getDimension(), failure);
        }
    }

    private static boolean usesChunkPlacement(IBlockState current, IBlockState desired) {
        if (current.getBlock() != Blocks.AIR) return false;
        Block block = desired.getBlock();
        var id = block.getRegistryName();
        if (id != null && id.getNamespace().equals(Tags.MOD_ID)) {
            return switch (id.getPath()) {
                case "block_casing", "block_casing_plain", "casing", "casing_frame" -> true;
                default -> false;
            };
        }
        return block == Blocks.STONE || block == Blocks.COBBLESTONE || block == Blocks.STONEBRICK
            || block == Blocks.BRICK_BLOCK || block == Blocks.PLANKS || block == Blocks.OBSIDIAN || block == Blocks.CONCRETE
            || block == Blocks.HARDENED_CLAY || block == Blocks.STAINED_HARDENED_CLAY || block == Blocks.IRON_BLOCK;
    }

    static void notify(World world, BlockPos pos, Chunk chunk, IBlockState previous, IBlockState placed) {
        world.markAndNotifyBlock(pos, chunk, previous, placed, Constants.BlockFlags.DEFAULT);
    }

    static void restore(World world, Chunk chunk, BlockSnapshot snapshot, IBlockState written, ConstructionLightingBatch<World> lighting) {
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
            boolean reverted = usesChunkPlacement(restored, written) ? writeChunk(world, pos, chunk, written, restored, lighting)
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
}
