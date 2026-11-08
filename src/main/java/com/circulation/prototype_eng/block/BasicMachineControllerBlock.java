package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

/** Default physical host for a downstream machine declaration. */
public class BasicMachineControllerBlock extends MachineControllerBlock<MachineControllerBlockEntity> {
    public BasicMachineControllerBlock(MachineType type) {
        this(type, new ResourceLocation(type.id().getNamespace(), type.id().getPath() + "_controller"));
    }

    public BasicMachineControllerBlock(MachineType type, ResourceLocation id) {
        super(type, id, MachineControllerBlockEntity.class);
    }

    @Override
    protected MachineControllerBlockEntity createController(World world, IBlockState state) {
        return new MachineControllerBlockEntity(type().id(), getOrientation(state));
    }
}
