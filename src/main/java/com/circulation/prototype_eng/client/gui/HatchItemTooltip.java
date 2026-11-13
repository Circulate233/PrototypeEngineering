package com.circulation.prototype_eng.client.gui;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.localization.HatchItemText;
import com.circulation.prototype_eng.proxy.ClientProxy;
import com.circulation.prototype_eng.storage.HatchConfigurationSnapshot;
import com.circulation.prototype_eng.storage.HatchItemProfiles;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Native block-item tooltip; all resource capacities are explicitly per slot.
 */
@SideOnly(Side.CLIENT)
public final class HatchItemTooltip {
    private HatchItemTooltip() {
    }

    public static void resource(ItemStack stack, HatchTier tier, HatchMode mode, List<String> tooltip) {
        ResourceHatchConfiguration profile;
        try {
            var saved = saved(stack);
            var minecraft = Minecraft.getMinecraft();
            boolean clientOwner = minecraft.isCallingFromMinecraftThread();
            var authoritative = clientOwner ? authoritative() : null;
            // Forge also rebuilds the search tree on the stopping integrated-server thread.
            if (needsTarget(saved, false) && (!clientOwner || minecraft.getConnection() != null && authoritative == null)) {
                tooltip.add(HatchItemText.AwaitingConfiguration.getLocal());
                return;
            }
            profile = HatchItemProfiles.resource(saved, tier, mode, authoritative);
        } catch (IllegalArgumentException invalid) {
            tooltip.add(HatchItemText.InvalidSavedData.getLocal());
            return;
        }
        resource(profile, tooltip);
    }

    static void resource(ResourceHatchConfiguration profile, List<String> tooltip) {
        boolean mixed = profile.mode() == HatchMode.MIXED;
        if (mixed) tooltip.add(GuiText.Input.getLocal());
        resourceSide(profile.input(), tooltip);
        if (mixed) tooltip.add(GuiText.Output.getLocal());
        resourceSide(profile.output(), tooltip);
    }

    private static void resourceSide(@Nullable ResourceHatchConfiguration.Side side, List<String> tooltip) {
        if (side == null) return;
        tooltip.add(HatchItemText.Slots.getLocal(GuiNumbers.grouped(side.slots())));
        var basis = side.basis();
        tooltip.add(HatchItemText.ItemEquivalentCapacity.getLocal(basis == null ? HatchItemText.UndeclaredCapacity.getLocal()
            : GuiNumbers.grouped(basis.itemEquivalent())));
    }

    private static @Nullable HatchConfigurationSnapshot authoritative() {
        return ((ClientProxy) PrototypeEngineering.proxy).hatchConfiguration();
    }

    private static boolean needsTarget(@Nullable NBTTagCompound saved, boolean energy) {
        return saved == null || !saved.hasKey("EffectiveProfile") && (energy || !saved.hasKey("Slots") && !saved.hasKey("Capacities"));
    }

    public static void energy(ItemStack stack, HatchTier tier, HatchMode mode, List<String> tooltip) {
        EnergyHatchConfiguration profile;
        HatchItemProfiles.EnergyRates rates;
        try {
            var saved = saved(stack);
            var minecraft = Minecraft.getMinecraft();
            boolean clientOwner = minecraft.isCallingFromMinecraftThread();
            var authoritative = clientOwner ? authoritative() : null;
            if (needsTarget(saved, true) && (!clientOwner || minecraft.getConnection() != null && authoritative == null)) {
                tooltip.add(HatchItemText.AwaitingConfiguration.getLocal());
                return;
            }
            profile = HatchItemProfiles.energy(saved, tier, mode, authoritative);
            rates = HatchItemProfiles.energyRates(saved, profile);
        } catch (IllegalArgumentException invalid) {
            tooltip.add(HatchItemText.InvalidSavedData.getLocal());
            return;
        }
        energy(profile, rates, tooltip);
    }

    static void energy(EnergyHatchConfiguration profile, HatchItemProfiles.EnergyRates rates, List<String> tooltip) {
        if (profile.input() != null) {
            tooltip.add(HatchItemText.EnergyCapacity.getLocal(GuiText.Input.getLocal(), GuiNumbers.grouped(profile.input().capacity())));
            tooltip.add(HatchItemText.EnergyOperationLimit.getLocal(GuiText.Input.getLocal(), GuiNumbers.grouped(rates.input())));
        }
        if (profile.output() != null) {
            tooltip.add(HatchItemText.EnergyCapacity.getLocal(GuiText.Output.getLocal(), GuiNumbers.grouped(profile.output().capacity())));
            tooltip.add(HatchItemText.EnergyOperationLimit.getLocal(GuiText.Output.getLocal(), GuiNumbers.grouped(rates.output())));
        }
    }

    private static @Nullable NBTTagCompound saved(ItemStack stack) {
        var tag = stack.getTagCompound();
        return tag == null || !tag.hasKey("BlockEntityTag") ? null : StrictNbt.requireCompound(tag, "BlockEntityTag");
    }
}
