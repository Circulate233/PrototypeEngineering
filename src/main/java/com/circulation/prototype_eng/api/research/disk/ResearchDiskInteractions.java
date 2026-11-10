package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.localization.ResearchGuiText;
import com.circulation.prototype_eng.research.ServerResearch;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Objects;

/**
 * Common pre-block interaction for consumer-registered disk items and external controller hosts.
 */
public final class ResearchDiskInteractions {
    private ResearchDiskInteractions() {
    }

    /**
     * Call before ordinary block activation. The library's RightClickBlock hook already does this
     * for carrier items; custom interaction dispatchers can use the same authoritative request.
     */
    public static EnumActionResult use(EntityPlayer player, World world, BlockPos pos, EnumHand hand, EnumFacing facing) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(world);
        Objects.requireNonNull(pos);
        Objects.requireNonNull(hand);
        Objects.requireNonNull(facing);
        var stack = player.getHeldItem(hand);
        if (!(stack.getItem() instanceof ResearchDiskCarrier carrier) || !carrier.hasResearchDiskData(stack)
            || !(world.getTileEntity(pos) instanceof MachineControllerHost)) return EnumActionResult.PASS;
        if (!world.isBlockModifiable(player, pos) || !player.canPlayerEdit(pos, facing, stack))
            return EnumActionResult.FAIL;
        if (!world.isRemote && player instanceof EntityPlayerMP actor) {
            ServerResearch.requestDiskImport(actor, pos, hand, facing,
                result -> actor.sendStatusMessage(ResearchGuiText.diskImport(result.status()).text(), true));
        }
        return EnumActionResult.SUCCESS;
    }
}
