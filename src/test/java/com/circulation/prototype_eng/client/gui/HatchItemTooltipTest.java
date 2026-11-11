package com.circulation.prototype_eng.client.gui;

import it.unimi.dsi.fastutil.objects.Reference2LongMaps;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.localization.HatchItemText;
import com.circulation.prototype_eng.storage.HatchItemProfiles;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.resources.JeiTestLocale;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HatchItemTooltipTest {
    @Test
    void resourceLinesShowOnlyDeclaredPerSideSlotCountAndItemEquivalentCapacity() {
        var custom = new TooltipType();
        var profile = new ResourceHatchConfiguration(ResourceHatchConfiguration.Side.fromItemEquivalent(96, 64, List.of(custom), Reference2LongMaps.singleton(custom, 7L)),
            ResourceHatchConfiguration.Side.fromItemEquivalent(128, Long.MAX_VALUE, List.of(AEKeyType.items())));
        JeiTestLocale.withTranslations(Map.of(
            GuiText.Input.getTranslationKey(), "Input", GuiText.Output.getTranslationKey(), "Output",
            HatchItemText.Slots.getTranslationKey(), "Slot count: %s",
            HatchItemText.ItemEquivalentCapacity.getTranslationKey(), "Slot capacity: %s",
            HatchItemText.UndeclaredCapacity.getTranslationKey(), "Not declared"), () -> {
            var tooltip = new ObjectArrayList<String>();
            HatchItemTooltip.resource(profile, tooltip);
            assertEquals(List.of("Input", "Slot count: 96", "Slot capacity: 64",
                "Output", "Slot count: 128", "Slot capacity: 9,223,372,036,854,775,807"), tooltip);
            tooltip.clear();
            HatchItemTooltip.resource(new ResourceHatchConfiguration(new ResourceHatchConfiguration.Side(2, Reference2LongMaps.singleton(custom, 13L)), null), tooltip);
            assertEquals(List.of("Slot count: 2", "Slot capacity: Not declared"), tooltip);
        });
    }

    @Test
    void energyLinesRemainIndependentAndLabelBothEffectiveFeCapacities() {
        var profile = new EnergyHatchConfiguration(new EnergyHatchConfiguration.Side(1234, 32), new EnergyHatchConfiguration.Side(987_654_321, 64));
        JeiTestLocale.withTranslations(Map.of(
            GuiText.Input.getTranslationKey(), "Input", GuiText.Output.getTranslationKey(), "Output",
            HatchItemText.EnergyCapacity.getTranslationKey(), "%s: %s FE",
            HatchItemText.EnergyOperationLimit.getTranslationKey(), "%s limit: %s FE per operation"), () -> {
            var tooltip = new ObjectArrayList<String>();
            HatchItemTooltip.energy(profile, HatchItemProfiles.energyRates(null, profile), tooltip);
            assertEquals(List.of("Input: 1,234 FE", "Input limit: 32 FE per operation",
                "Output: 987,654,321 FE", "Output limit: 64 FE per operation"), tooltip);
            var saved = new NBTTagCompound();
            saved.setLong("MaxInRate", 11);
            saved.setLong("MaxOutRate", 7);
            tooltip.clear();
            HatchItemTooltip.energy(profile, HatchItemProfiles.energyRates(saved, profile), tooltip);
            assertEquals(List.of("Input: 1,234 FE", "Input limit: 11 FE per operation",
                "Output: 987,654,321 FE", "Output limit: 7 FE per operation"), tooltip);
            var output = new EnergyHatchConfiguration(null, profile.output());
            tooltip.clear();
            HatchItemTooltip.energy(output, HatchItemProfiles.energyRates(saved, output), tooltip);
            assertEquals(List.of("Output: 987,654,321 FE", "Output limit: 7 FE per operation"), tooltip);
        });
    }

    private static final class TooltipType extends AEKeyType {
        private TooltipType() {
            super(new ResourceLocation("prototype_eng_test", "tooltip_units"), Key.class, new TextComponentString("custom"));
        }

        @Override
        public int getAmountPerUnit() {
            return 250;
        }

        @Override
        public String getUnitSymbol() {
            return "U";
        }

        @Override
        public AEKey readFromPacket(PacketBuffer input) {
            return null;
        }

        @Override
        public AEKey loadKeyFromTag(NBTTagCompound tag) {
            return null;
        }
    }
}
