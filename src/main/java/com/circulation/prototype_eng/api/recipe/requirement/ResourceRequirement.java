package com.circulation.prototype_eng.api.recipe.requirement;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Resource IO payload, combining PM item/fluid IO with AE2's extensible key types.
 * This is one built-in requirement, not the recipe model or a replacement resource type system.
 * Counts describe one execution, scaled by effective parallelism and chance at acquisition.
 * Fuzzy/dynamic groups resolve and lock one key per group at START; weighted outputs run at END.
 * All supplied lists and their elements are borrowed immutable values for this definition's lifetime.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record ResourceRequirement(List<GenericStack> inputs, List<GenericStack> inputsPerTick,
                                  List<GenericStack> outputsPerTick, List<GenericStack> outputs,
                                  boolean voidExcessOutputs, ResourceChance chance,
                                  @Nullable RandomOutputPool randomOutputs, List<FuzzyInputGroup> fuzzyInputs,
                                  List<DynamicInputGroup> dynamicInputs, InputAllocation inputAllocation) {
    public ResourceRequirement {
        validateStacks(inputs);
        validateStacks(inputsPerTick);
        validateStacks(outputsPerTick);
        validateStacks(outputs);
    }

    public ResourceRequirement(List<GenericStack> inputs, List<GenericStack> inputsPerTick,
                               List<GenericStack> outputsPerTick, List<GenericStack> outputs,
                               boolean voidExcessOutputs, ResourceChance chance, @Nullable RandomOutputPool randomOutputs,
                               List<FuzzyInputGroup> fuzzyInputs, List<DynamicInputGroup> dynamicInputs) {
        this(inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs, chance, randomOutputs,
            fuzzyInputs, dynamicInputs, InputAllocation.SPLITTABLE);
    }

    public ResourceRequirement(List<GenericStack> inputs, List<GenericStack> inputsPerTick,
                               List<GenericStack> outputsPerTick, List<GenericStack> outputs,
                               boolean voidExcessOutputs, ResourceChance chance, @Nullable RandomOutputPool randomOutputs) {
        this(inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs, chance, randomOutputs,
            ObjectLists.emptyList(), ObjectLists.emptyList());
    }

    public ResourceRequirement(List<GenericStack> inputs, List<GenericStack> inputsPerTick,
                               List<GenericStack> outputsPerTick, List<GenericStack> outputs,
                               boolean voidExcessOutputs) {
        this(inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs, ResourceChance.ALWAYS, null);
    }

    public ResourceRequirement(List<GenericStack> inputs, List<GenericStack> outputs) {
        this(inputs, ObjectLists.emptyList(), ObjectLists.emptyList(), outputs, false);
    }

    /**
     * Validates declaration quantities and borrows the list unchanged; no defensive copy is made.
     */
    public static void validateStacks(List<GenericStack> stacks) {
        for (int index = 0; index < stacks.size(); index++) {
            var stack = stacks.get(index);
            if (stack.amount() <= 0)
                throw new IllegalArgumentException("Resource requirements need positive quantities");
        }
    }

    public ResourceRequirement withInputAllocation(InputAllocation allocation) {
        return new ResourceRequirement(inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs,
            chance, randomOutputs, fuzzyInputs, dynamicInputs, allocation);
    }

}
