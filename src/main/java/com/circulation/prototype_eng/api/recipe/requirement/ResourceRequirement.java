package com.circulation.prototype_eng.api.recipe.requirement;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Fixed resource IO payload, combining PM item/fluid IO with AE2's extensible key types.
 * This is one built-in requirement, not the recipe model or a replacement resource type system.
 * Counts describe one execution, scaled by effective parallelism and chance at acquisition.
 * Optional weighted outputs run at END; fuzzy/dynamic input selection is separate migration work.
 */
public record ResourceRequirement(List<GenericStack> inputs, List<GenericStack> inputsPerTick,
                                  List<GenericStack> outputsPerTick, List<GenericStack> outputs,
                                  boolean voidExcessOutputs, ResourceChance chance,
                                  @Nullable RandomOutputPool randomOutputs) {
    public ResourceRequirement {
        inputs = copy(inputs);
        inputsPerTick = copy(inputsPerTick);
        outputsPerTick = copy(outputsPerTick);
        outputs = copy(outputs);
        Objects.requireNonNull(chance);
    }

    public ResourceRequirement(List<GenericStack> inputs, List<GenericStack> inputsPerTick,
                               List<GenericStack> outputsPerTick, List<GenericStack> outputs,
                               boolean voidExcessOutputs) {
        this(inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs, ResourceChance.ALWAYS, null);
    }

    public ResourceRequirement(List<GenericStack> inputs, List<GenericStack> outputs) {
        this(inputs, ObjectLists.emptyList(), ObjectLists.emptyList(), outputs, false);
    }

    private static List<GenericStack> copy(List<GenericStack> stacks) {
        var result = new ObjectArrayList<GenericStack>(stacks.size());
        for (var stack : stacks) {
            Objects.requireNonNull(stack);
            Objects.requireNonNull(stack.what());
            if (stack.amount() <= 0) {
                throw new IllegalArgumentException("Resource requirements need positive quantities");
            }
            result.add(stack);
        }
        return ObjectLists.unmodifiable(result);
    }
}
