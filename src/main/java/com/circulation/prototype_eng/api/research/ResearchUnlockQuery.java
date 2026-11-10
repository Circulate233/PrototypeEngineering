package com.circulation.prototype_eng.api.research;

import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * Derived unlock query; no unlock flags are stored independently of the completed research level.
 */
public record ResearchUnlockQuery(ResourceLocation effectId, ResourceLocation targetId, int completedLevel) {
    public ResearchUnlockQuery {
        Objects.requireNonNull(effectId);
        Objects.requireNonNull(targetId);
        if (completedLevel < 0) {
            throw new IllegalArgumentException("Negative completed research level");
        }
    }
}
