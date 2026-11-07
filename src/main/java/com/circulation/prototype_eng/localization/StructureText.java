package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum StructureText implements LocalizationEnum {
    ReloadUsage, Reloaded, ReloadFailed, ReloadRefreshFailed;
    private final String key = "structure." + Tags.MOD_ID + "." + name();
    @Override public String getTranslationKey() { return key; }
}
