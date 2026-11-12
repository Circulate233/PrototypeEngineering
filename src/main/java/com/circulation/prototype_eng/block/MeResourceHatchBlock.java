package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.MeAssemblyConfig;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.MeResourceHatchBlockEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.circulation.prototype_eng.PrototypeEngineering;
import org.jetbrains.annotations.NotNull;

/** Physical ME resource component; only the ME node and machine contract transfer resources. */
public final class MeResourceHatchBlock extends HatchBlock<MeResourceHatchBlockEntity> {
    private final HatchMode mode;

    public MeResourceHatchBlock(String id, HatchMode mode) {
        super(new ResourceLocation(Tags.MOD_ID, id), MeResourceHatchBlockEntity.class, 5.0F);
        this.mode = mode;
    }

    public HatchMode mode() {
        return mode;
    }

    public int inputSlots() {
        return MeAssemblyConfig.inputSlots();
    }

    @Override
    protected MeResourceHatchBlockEntity createHatch(World world, IBlockState state) {
        return new MeResourceHatchBlockEntity(mode, inputSlots());
    }

    @Override
    protected NBTTagCompound itemDropData(MeResourceHatchBlockEntity tile) {
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
            player.openGui(PrototypeEngineering.instance, 3, world, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }
}
