package com.circulation.prototype_eng.api.recipe.overlay;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * IO overrides: null inherits a list, while an empty list clears it.
 * The output option uses -1 to inherit, 0 for false, and 1 for true.
 * Non-null input lists are borrowed and must remain unchanged while this patch or its result is used.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record ResourceRequirementPatch(@Nullable List<GenericStack> inputs,
                                       @Nullable List<GenericStack> inputsPerTick,
                                       @Nullable List<GenericStack> outputsPerTick,
                                       @Nullable List<GenericStack> outputs,
                                       int voidExcessOutputs) {
    public ResourceRequirementPatch {
        inputs = validate(inputs);
        inputsPerTick = validate(inputsPerTick);
        outputsPerTick = validate(outputsPerTick);
        outputs = validate(outputs);
    }

    private static @Nullable List<GenericStack> validate(@Nullable List<GenericStack> stacks) {
        if (stacks == null) {
            return null;
        }
        ResourceRequirement.validateStacks(stacks);
        return stacks;
    }

    /**
     * Keeps probability, random outputs and group/lock definitions unchanged.
     */
    public ResourceRequirement apply(ResourceRequirement base) {
        if (inputs == null && inputsPerTick == null && outputsPerTick == null && outputs == null
            && voidExcessOutputs == -1) {
            return base;
        }
        return new ResourceRequirement(inputs == null ? base.inputs() : inputs,
            inputsPerTick == null ? base.inputsPerTick() : inputsPerTick,
            outputsPerTick == null ? base.outputsPerTick() : outputsPerTick,
            outputs == null ? base.outputs() : outputs,
            voidExcessOutputs == -1 ? base.voidExcessOutputs() : voidExcessOutputs == 1,
            base.chance(), base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs(), base.inputAllocation());
    }
}
