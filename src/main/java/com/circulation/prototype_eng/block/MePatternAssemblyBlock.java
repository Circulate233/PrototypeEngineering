package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.storage.MePatternAssemblyBlockEntity;
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

/** Processing patterns enter through the ME crafting service and feed the selected machine inventory group. */
public final class MePatternAssemblyBlock extends HatchBlock<MePatternAssemblyBlockEntity> {
    public MePatternAssemblyBlock() {
        super(new ResourceLocation(Tags.MOD_ID, "me_pattern_assembly"), MePatternAssemblyBlockEntity.class, 5.0F);
    }

    @Override
    protected MePatternAssemblyBlockEntity createHatch(World world, IBlockState state) {
        return new MePatternAssemblyBlockEntity();
    }

    @Override
    protected NBTTagCompound itemDropData(MePatternAssemblyBlockEntity tile) {
        return tile.saveForItemDrop();
    }

    @Override
    public boolean onBlockActivated(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state,
                                    EntityPlayer player, @NotNull EnumHand hand, @NotNull EnumFacing face,
                                    float hitX, float hitY, float hitZ) {
        if (player.isSneaking()) return false;
        if (!world.isRemote && player instanceof EntityPlayerMP) {
            var tile = getTileEntity(world, pos);
            if (tile == null || tile.isInvalid()) return false;
            player.openGui(PrototypeEngineering.instance, 4, world, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }
}
