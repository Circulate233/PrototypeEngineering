package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;

import java.util.Locale;

/**
 * Hatch screens use their original tier-specific block title, as PM's IKey.lang did.
 */
public record HatchGuiTitle(String translationKey) implements LocalizationEnum {
    public static HatchGuiTitle of(boolean energy, HatchMode mode, HatchTier tier) {
        return new HatchGuiTitle("tile." + Tags.MOD_ID + "." + (energy ? "energy_" : "")
            + mode.name().toLowerCase(Locale.ROOT) + "_hatch_" + tier.level() + ".name");
    }

    public static HatchGuiTitle computation(HatchTier tier) {
        return new HatchGuiTitle("tile." + Tags.MOD_ID + ".computation_hatch_" + tier.level() + ".name");
    }

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
