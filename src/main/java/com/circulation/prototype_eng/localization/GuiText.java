package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;

/** AE2S's LocalizationEnum contract supplies client strings and server-safe text components. */
public enum GuiText implements LocalizationEnum {
    Input,
    Output,
    Energy,
    Capacity,
    ResourceStorage,
    ResourceInputHatch,
    ResourceOutputHatch,
    ResourceMixedHatch,
    EnergyInputHatch,
    EnergyOutputHatch,
    EnergyMixedHatch,
    InputRate,
    OutputRate,
    AutoInput,
    AutoOutput,
    On,
    Off,
    Apply;

    private final String translationKey = "gui." + Tags.MOD_ID + "." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }

    public static GuiText resourceHatch(HatchMode mode) {
        return switch (mode) {
            case INPUT -> ResourceInputHatch;
            case OUTPUT -> ResourceOutputHatch;
            case MIXED -> ResourceMixedHatch;
        };
    }

    public static GuiText energyHatch(HatchMode mode) {
        return switch (mode) {
            case INPUT -> EnergyInputHatch;
            case OUTPUT -> EnergyOutputHatch;
            case MIXED -> EnergyMixedHatch;
        };
    }
}
