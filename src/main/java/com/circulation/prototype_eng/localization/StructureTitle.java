package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;


/**
 * Dynamic addon structure titles use the same AE2S contract as recipe pool titles.
 */
public record StructureTitle(String translationKey) implements LocalizationEnum {
    public StructureTitle {
        if (translationKey.isBlank()) throw new IllegalArgumentException("Empty structure title");
    }

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
