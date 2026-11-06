package com.circulation.prototype_eng.api.recipe.overlay;

import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Immutable tick-rate overrides; null inherits the base, and zero explicitly disables that rate. */
public record EnergyRequirementPatch(@Nullable Long inputsPerTick, @Nullable Long outputsPerTick,
                                     @Nullable Boolean voidExcessOutputs) {
    public EnergyRequirementPatch {
        if (inputsPerTick != null && inputsPerTick < 0 || outputsPerTick != null && outputsPerTick < 0) {
            throw new IllegalArgumentException("Energy overlay rates must be nonnegative");
        }
    }

    /** START input and END output retain their original values. */
    public EnergyRequirement apply(EnergyRequirement base) {
        Objects.requireNonNull(base, "base");
        if (inputsPerTick == null && outputsPerTick == null && voidExcessOutputs == null) {
            return base;
        }
        return new EnergyRequirement(base.input(), inputsPerTick == null ? base.inputPerTick() : inputsPerTick,
            outputsPerTick == null ? base.outputPerTick() : outputsPerTick, base.output(),
            voidExcessOutputs == null ? base.voidExcessOutputs() : voidExcessOutputs);
    }
}
