package com.circulation.prototype_eng.structure;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.IFluidBlock;
import org.jetbrains.annotations.Nullable;

/** The same per-position resource identity is used by previews, pattern imports and construction. */
public final class StructureMaterials {
    private StructureMaterials() {
    }

    public static @Nullable Fluid fluid(IBlockState state) {
        if (state.getBlock() instanceof IFluidBlock block) return block.getFluid();
        var fluid = FluidRegistry.lookupFluidForBlock(state.getBlock());
        if (fluid == null && state.getMaterial() == Material.WATER) fluid = FluidRegistry.WATER;
        if (fluid == null && state.getMaterial() == Material.LAVA) fluid = FluidRegistry.LAVA;
        return fluid;
    }

    public static @Nullable GenericStack resource(World world, BlockPos position, IBlockState state,
                                                   EntityPlayer player, @Nullable String tileNbt) {
        var fluid = fluid(state);
        if (fluid != null) {
            var key = AEFluidKey.of(fluid);
            return key == null ? null : new GenericStack(key, Fluid.BUCKET_VOLUME);
        }
        if (state.getMaterial().isLiquid() || state.getMaterial() == Material.AIR) return null;
        try (var picks = new StructurePickWorld(world, pos -> world.isBlockLoaded(pos, false)
            ? world.getBlockState(pos) : Blocks.AIR.getDefaultState())) {
            var key = AEItemKey.of(picks.pick(state, position, player, tileNbt));
            return key == null ? null : new GenericStack(key, 1);
        }
    }
}
