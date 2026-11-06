package com.circulation.prototype_eng.proxy;

import com.circulation.prototype_eng.container.EnergyHatchContainer;
import com.circulation.prototype_eng.container.ResourceHatchContainer;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import com.circulation.prototype_eng.storage.ResourceHatchBlockEntity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

public class CommonProxy implements IGuiHandler {
    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != 0 || !world.isBlockLoaded(new BlockPos(x, y, z))) { return null; }
        return switch (world.getTileEntity(new BlockPos(x, y, z))) {
            case ResourceHatchBlockEntity resource -> new ResourceHatchContainer(player.inventory, resource);
            case EnergyHatchBlockEntity energy -> new EnergyHatchContainer(player.inventory, energy);
            case null, default -> null;
        };
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) { return null; }
}
