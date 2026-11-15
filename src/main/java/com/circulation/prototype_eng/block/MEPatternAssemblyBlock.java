package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.storage.MEPatternAssemblyBlockEntity;
import com.circulation.prototype_eng.storage.MEPatternAssemblyMemoryCard;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

/**
 * Processing patterns enter through the ME crafting service and feed the selected machine inventory group.
 */
public final class MEPatternAssemblyBlock extends HatchBlock<MEPatternAssemblyBlockEntity> {
    public MEPatternAssemblyBlock() {
        super(new ResourceLocation(Tags.MOD_ID, "me_pattern_assembly"), MEPatternAssemblyBlockEntity.class, 5.0F);
    }

    @Override
    protected MEPatternAssemblyBlockEntity createHatch(World world, IBlockState state) {
        return new MEPatternAssemblyBlockEntity();
    }

    @Override
    protected NBTTagCompound itemDropData(MEPatternAssemblyBlockEntity tile) {
        return tile.saveForItemDrop();
    }

    @Override
    public boolean onBlockActivated(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state,
                                    EntityPlayer player, @NotNull EnumHand hand, @NotNull EnumFacing face,
                                    float hitX, float hitY, float hitZ) {
        var tile = getTileEntity(world, pos);
        if (MEPatternAssemblyMemoryCard.useOnAssembly(tile, player, player.getHeldItem(hand))) return true;
        if (player.isSneaking()) return false;
        if (!world.isRemote && player instanceof EntityPlayerMP) {
            if (tile == null || tile.isInvalid()) return false;
            player.openGui(PrototypeEngineering.instance, 4, world, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }
}
