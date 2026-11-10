package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

/** Item tooltips share the actual profile used by native hatch storage. */
public enum HatchItemText implements LocalizationEnum {
    Slots, ItemEquivalentCapacity, UndeclaredCapacity, AwaitingConfiguration, EnergyCapacity, EnergyOperationLimit, InvalidSavedData;

    private final String translationKey = "tooltip." + Tags.MOD_ID + ".hatch." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
