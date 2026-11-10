package com.circulation.prototype_eng.api.research;

import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * The minimum completed level at which another research blocks this stage.
 */
public record ResearchMutexRule(ResourceLocation researchId, int minLevel) {
    public ResearchMutexRule {
        Objects.requireNonNull(researchId);
        if (minLevel < 1) {
            throw new IllegalArgumentException("Research mutex level must be positive");
        }
    }
}
