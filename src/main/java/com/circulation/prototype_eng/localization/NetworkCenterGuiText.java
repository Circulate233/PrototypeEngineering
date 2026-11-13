package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum NetworkCenterGuiText implements LocalizationEnum {
    Level, Active, Inactive, Standby, Concurrency, Available, Resources, NoResources;

    @Override
    public String getTranslationKey() {
        return "gui." + Tags.MOD_ID + ".network_center." + name();
    }
}
