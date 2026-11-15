package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;


/**
 * Dynamic addon pool titles reuse their existing key through AE2S's localization contract.
 */
public record RecipePoolTitle(String translationKey) implements LocalizationEnum {
    public RecipePoolTitle {
        if (translationKey.isBlank()) {
            throw new IllegalArgumentException("Recipe pool title is empty");
        }
    }

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
