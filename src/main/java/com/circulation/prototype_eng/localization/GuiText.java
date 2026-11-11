package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

/**
 * AE2S's LocalizationEnum contract supplies client strings and server-safe text components.
 */
public enum GuiText implements LocalizationEnum {
    Input,
    Output,
    Energy,
    ResourceStorage,
    InputRate,
    OutputRate,
    AutoInput,
    AutoOutput,
    ResourceTypes,
    ResourceStored;

    private final String translationKey = "gui." + Tags.MOD_ID + "." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
