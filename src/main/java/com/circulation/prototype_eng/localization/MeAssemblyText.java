package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum MeAssemblyText implements LocalizationEnum {
    InputAssembly,
    OutputAssembly,
    Interface,
    TargetStock,
    StoredStock,
    MarkUsage,
    ReadOnlyBuffer,
    EmptyMark,
    ColorChannel,
    PatternAssembly,
    ReturnInputs,
    ModeDefault,
    ModeBlocking,
    ModeRecipeBlocking,
    ModePatternIsolation,
    ModeCraftingLock,
    PatternPage,
    PreviousPage,
    NextPage,
    MultiplyPatterns,
    DividePatterns,
    InputBuffer,
    CatalystBuffer,
    OutputBuffer,
    CatalystUsage;

    private final String translationKey = "gui." + Tags.MOD_ID + ".me." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
