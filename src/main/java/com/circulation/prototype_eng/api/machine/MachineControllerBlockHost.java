package com.circulation.prototype_eng.api.machine;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.IOrientableBlock;
import net.minecraft.block.state.IBlockState;

/** Type and full orientation binding required by a controller tile's physical block. */
public interface MachineControllerBlockHost extends IOrientableBlock {
    MachineType type();

    IBlockState state(IBlockState state, BlockOrientation orientation);
}
