package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

/**
 * All tool messages, item name, tooltips and advancement text share the AE2S contract.
 */
public enum OrientationToolText implements LocalizationEnum {
    Name,
    Enabled,
    Disabled,
    State,
    StateOn,
    StateOff,
    ToggleHint,
    CherishTitle,
    CherishDescription;

    private final String translationKey = "orientation_tool." + Tags.MOD_ID + "." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
