package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum CreativeTabText implements LocalizationEnum {
    Main;

    @Override
    public String getTranslationKey() {
        return "itemGroup." + Tags.MOD_ID;
    }
}
