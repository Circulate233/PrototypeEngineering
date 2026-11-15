package com.circulation.prototype_eng.api.recipe.overlay;

import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;


/**
 * Tick-rate overrides use -1 to inherit and zero to disable a rate.
 * The output option uses -1 to inherit, 0 for false, and 1 for true.
 */
public record EnergyRequirementPatch(long inputsPerTick, long outputsPerTick,
                                     int voidExcessOutputs) {
    public EnergyRequirementPatch {
        if (inputsPerTick < -1 || outputsPerTick < -1) {
            throw new IllegalArgumentException("Energy overlay rates must be nonnegative or -1 to inherit");
        }
    }

    /**
     * START input and END output retain their original values.
     */
    public EnergyRequirement apply(EnergyRequirement base) {
        if (inputsPerTick == -1 && outputsPerTick == -1 && voidExcessOutputs == -1) {
            return base;
        }
        return new EnergyRequirement(base.input(), inputsPerTick == -1 ? base.inputPerTick() : inputsPerTick,
            outputsPerTick == -1 ? base.outputPerTick() : outputsPerTick, base.output(),
            voidExcessOutputs == -1 ? base.voidExcessOutputs() : voidExcessOutputs == 1, base.inputAllocation());
    }
}
