package com.circulation.prototype_eng.api.research.manual;

import net.minecraft.util.ResourceLocation;


/**
 * Exact static input position within one research material requirement.
 */
public record ManualMaterialKey(ResourceLocation requirementId, int inputIndex) {
    public ManualMaterialKey {
        if (inputIndex < 0) {
            throw new IllegalArgumentException("Negative manual material input index");
        }
    }
}
