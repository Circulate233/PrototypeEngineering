package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.client.gui.HatchItemTooltip;
import com.circulation.prototype_eng.storage.ComputationHatchBlockEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ComputationHatchBlock extends HatchBlock<ComputationHatchBlockEntity> {
    private final int tier;

    public ComputationHatchBlock(int tier) {
        super(new ResourceLocation(Tags.MOD_ID, "computation_hatch_" + tier), ComputationHatchBlockEntity.class, 5.0F);
        this.tier = tier;
    }

    @Override
    protected ComputationHatchBlockEntity createHatch(World world, IBlockState state) {
        return new ComputationHatchBlockEntity(tier);
    }

    @Override
    protected NBTTagCompound itemDropData(ComputationHatchBlockEntity tile) {
        return tile.saveForItemDrop();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addCheckedInformation(@NotNull ItemStack stack, @Nullable World world, @NotNull List<String> tooltip, @NotNull ITooltipFlag flag) {
        super.addCheckedInformation(stack, world, tooltip, flag);
        HatchItemTooltip.computation(tier, tooltip);
    }
}
