package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum ParallelControllerText implements LocalizationEnum {
    Normal, Reinforced, Elite, Super, Ultimate, Maximum, Selected, Tooltip;

    private final String key = "parallel." + Tags.MOD_ID + "." + name();

    @Override
    public String getTranslationKey() {
        return key;
    }
}
