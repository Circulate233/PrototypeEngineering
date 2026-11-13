package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum NetworkCenterGuiText implements LocalizationEnum {
    Level, Active, Standby, Concurrency, Available, Resources, NoResources,
    NoState, Unformed, TeamRejected, NoPower, PowerHint, NoActiveCenter, LevelInsufficient, NoConcurrency;

    @Override
    public String getTranslationKey() {
        return "gui." + Tags.MOD_ID + ".network_center." + name();
    }
}
