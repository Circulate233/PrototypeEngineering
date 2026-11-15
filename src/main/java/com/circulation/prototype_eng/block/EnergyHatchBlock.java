package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.client.gui.HatchItemTooltip;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Default tiered energy hatch; independent entities extend HatchBlock with their own entity type.
 */
public class EnergyHatchBlock extends HatchBlock<EnergyHatchBlockEntity> {
    private final HatchMode mode;
    private final HatchTier tier;

    public EnergyHatchBlock(HatchMode mode, HatchTier tier) {
        this(new ResourceLocation(Tags.MOD_ID, "energy_" + mode.name().toLowerCase(Locale.ROOT)
            + "_hatch_" + tier.level()), mode, tier);
    }

    public EnergyHatchBlock(ResourceLocation id, HatchMode mode, HatchTier tier) {
        super(id, EnergyHatchBlockEntity.class, 3.5F);
        this.mode = mode;
        this.tier = tier;
    }

    public HatchMode mode() {
        return mode;
    }

    public HatchTier tier() {
        return tier;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addCheckedInformation(@NotNull ItemStack stack, @Nullable World world, @NotNull List<String> tooltip, @NotNull ITooltipFlag flag) {
        super.addCheckedInformation(stack, world, tooltip, flag);
        HatchItemTooltip.energy(stack, tier, mode, tooltip);
    }

    @Override
    protected EnergyHatchBlockEntity createHatch(World world, IBlockState state) {
        return new EnergyHatchBlockEntity(mode, tier);
    }
}
