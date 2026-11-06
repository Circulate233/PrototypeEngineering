package com.circulation.prototype_eng.api.recipe.probability;

import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** PM resource chance percentage, optionally multiplied by a process attribute. */
public record ResourceChance(double percent, @Nullable ResourceLocation attribute) {
    public static final ResourceChance ALWAYS = new ResourceChance(100, null);

    public ResourceChance {
        if (!Double.isFinite(percent) || percent < 0) {
            throw new IllegalArgumentException("Resource chance must be finite and nonnegative");
        }
    }

    public double effective(RecipeProcess process) {
        double multiplier = attribute == null ? 1 : process.attributes().value(attribute, 1);
        double result = percent * Math.max(0, multiplier);
        if (!Double.isFinite(result)) {
            throw new IllegalStateException("Resource chance multiplier overflow");
        }
        return result;
    }
}
