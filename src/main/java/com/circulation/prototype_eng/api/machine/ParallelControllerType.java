package com.circulation.prototype_eng.api.machine;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;


/**
 * One registered physical controller; null model uses the original PM controller artwork.
 */
public record ParallelControllerType(ResourceLocation id, LocalizationEnum displayName, int maximum,
                                     @Nullable ResourceLocation model) {
    public ParallelControllerType {
        if (maximum < 1) throw new IllegalArgumentException("Parallel maximum must be positive");
    }

    public ResourceLocation modelLocation() {
        return model == null ? new ResourceLocation(Tags.MOD_ID, "parallelization_controller") : model;
    }
}
