package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

/**
 * Original PM energy ingredient labels.
 */
public enum JeiEnergyText implements LocalizationEnum {
    Display, Consume, Produce, Once, PerTick, OnceAmount, TickAmount;
    private final String key = "jei_energy." + Tags.MOD_ID + "." + name();

    @Override
    public String getTranslationKey() {
        return key;
    }
}
