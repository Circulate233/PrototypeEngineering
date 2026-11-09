package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.HatchCapabilities;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Detached facts for built-in structure predicates and validators. General world readers remain on the owner; explicit block-only readers may use loaded references
 * in the original scheduler's physically joined owner window after checking position availability. Merely loading this class
 * creates descriptors; block, tile and biome access occurs only when a reader is invoked.
 */
@SuppressWarnings("unused")
public final class StructureQueries {
    /** Minecraft block states are immutable and may be shared with snapshot readers. */
    public static final StructureQuery<IBlockState> BLOCK_STATE = new StructureQuery<>(id("block_state"),
        World::getBlockState, UnaryOperator.identity(), (first, second) -> first == second, StructureReadWindow.BlockStates::getBlockState);

    /** Preserves PM's block identity and material checks rather than invoking a custom isAir hook. */
    public static final StructureQuery<Boolean> NON_AIR = immutable("non_air", (world, position) -> {
        IBlockState state = world.getBlockState(position);
        return state.getBlock() != Blocks.AIR && state.getMaterial() != Material.AIR;
    }, (blocks, position) -> {
        IBlockState state = blocks.getBlockState(position);
        return state.getBlock() != Blocks.AIR && state.getMaterial() != Material.AIR;
    });

    /** Describes whether the block state declares a tile, independently of an instantiated tile. */
    public static final StructureQuery<Boolean> HAS_TILE_ENTITY = immutable("has_tile_entity", (world, position) -> {
        IBlockState state = world.getBlockState(position);
        return state.getBlock().hasTileEntity(state);
    }, (blocks, position) -> {
        IBlockState state = blocks.getBlockState(position);
        return state.getBlock().hasTileEntity(state);
    });

    /** Public unsided contracts, using the same validity rules as machine discovery. */
    public static final StructureQuery<Boolean> RESOURCE_HATCH = immutable("resource_hatch",
        (world, position) -> HatchCapabilities.findResource(world.getTileEntity(position), null) != null);
    public static final StructureQuery<Boolean> ENERGY_INPUT_HATCH = immutable("energy_input_hatch",
        (world, position) -> HatchCapabilities.findEnergyInput(world.getTileEntity(position), null) != null);
    public static final StructureQuery<Boolean> ENERGY_OUTPUT_HATCH = immutable("energy_output_hatch",
        (world, position) -> HatchCapabilities.findEnergyOutput(world.getTileEntity(position), null) != null);

    /** Absent tiles yield null; the query copier detaches mutable NBT on capture and delivery. */
    public static final StructureQuery<NBTTagCompound> TILE_NBT = new StructureQuery<>(id("tile_nbt"),
        StructureQueries::readTileNbt, NBTTagCompound::copy, Objects::equals);

    public static final StructureQuery<Integer> DIMENSION = immutable("dimension", (world, _) -> world.provider.getDimension());

    /** World time used for the day cycle, rather than the world's total elapsed ticks. */
    public static final StructureQuery<Long> WORLD_TIME = immutable("world_time", (world, _) -> world.getWorldTime());

    /** Preserve PM day/night gates using the actual world daytime rule, separately from day-cycle time. */
    public static final StructureQuery<Boolean> DAYTIME = immutable("daytime", (world, _) -> world.isDaytime());

    public static final StructureQuery<Boolean> RAINING = immutable("raining", (world, _) -> world.isRaining());
    public static final StructureQuery<Boolean> THUNDERING = immutable("thundering", (world, _) -> world.isThundering());

    public static final StructureQuery<ResourceLocation> BIOME = immutable("biome", (world, position) -> {
        ResourceLocation name = world.getBiome(position).getRegistryName();
        if (name == null) { throw new IllegalStateException("Structure biome has no registry name"); }
        return name;
    });

    private StructureQueries() {
    }

    @Nullable
    private static NBTTagCompound readTileNbt(World world, BlockPos position) {
        var tile = world.getTileEntity(position);
        return tile == null ? null : tile.writeToNBT(new NBTTagCompound());
    }

    /**
     * Captures a tile's advertised capability availability for a world-facing side. Null requests the
     * unsided capability. This query retains the capability descriptor, never a live handler.
     */
    public static StructureQuery<Boolean> capability(ResourceLocation id, Capability<?> capability, @Nullable EnumFacing side) {
        Objects.requireNonNull(capability, "capability");
        return new StructureQuery<>(id, (world, position) -> {
            var tile = world.getTileEntity(position);
            return tile != null && tile.hasCapability(capability, side);
        }, UnaryOperator.identity(), Boolean::equals);
    }

    private static <T> StructureQuery<T> immutable(String path, BiFunction<World, BlockPos, T> reader) {
        return new StructureQuery<>(id(path), reader, UnaryOperator.identity(), Objects::equals);
    }

    private static <T> StructureQuery<T> immutable(String path, BiFunction<World, BlockPos, T> reader,
                                                   BiFunction<StructureReadWindow.BlockStates, BlockPos, T> workerReader) {
        return new StructureQuery<>(id(path), reader, UnaryOperator.identity(), Objects::equals, workerReader);
    }

    private static ResourceLocation id(String path) { return new ResourceLocation(Tags.MOD_ID, path); }
}
