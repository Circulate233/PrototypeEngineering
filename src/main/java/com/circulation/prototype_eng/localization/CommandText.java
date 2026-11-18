package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

import java.util.Locale;

public enum CommandText implements LocalizationEnum {
    Usage, ResearchUsage;

    private final String key = "commands." + Tags.MOD_ID + ".pe." + name().toLowerCase(Locale.ROOT);

    @Override
    public String getTranslationKey() {
        return key;
    }
}
