package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * Server-safe reason identifiers; clients render them through AE2S's localization contract.
 */
public enum RecipeText implements LocalizationEnum {
    StorageChanged,
    NoResourceInput,
    NoResourceOutput,
    ResourcesMissing,
    ResourceOutputFull,
    NoEnergyInput,
    NoEnergyOutput,
    EnergyMissing,
    EnergyOutputFull,
    NetworkUnavailable,
    NetworkResearchMissing,
    NetworkCapacityMissing,
    Cancelled,
    ControllerBroken;

    /**
     * Shared declaration order; callers must not modify this array.
     */
    public static final RecipeText[] VALUES = values();

    private final String translationKey = "recipe." + Tags.MOD_ID + "." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }

    public ProcessResult.Blocked blocked(String... arguments) {
        return new ProcessResult.Blocked(translationKey, ObjectArrayList.of(arguments));
    }

    public ProcessResult.Failure failure(String... arguments) {
        return new ProcessResult.Failure(translationKey, ObjectArrayList.of(arguments));
    }
}
