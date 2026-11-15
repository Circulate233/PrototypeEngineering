package com.circulation.prototype_eng.recipe;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * Internal receipt/transfer prototype retained for conservation regression checks. This is not a
 * public recipe definition and does not implement PM's transactional requirement contract.
 */
public record ResourceTransferPlan(ResourceLocation id, List<GenericStack> inputs, List<GenericStack> outputs,
                                   int durationTicks, long energyPerTick) {
    public ResourceTransferPlan {
        if (durationTicks < 1 || energyPerTick < 0) {
            throw new IllegalArgumentException("Recipe duration must be positive and FE/t must not be negative");
        }
        inputs = normalize(inputs);
        outputs = normalize(outputs);
    }

    private static List<GenericStack> normalize(List<GenericStack> stacks) {
        var amounts = new AEKeyLinkedOpenHashMap(stacks.size());
        for (int index = 0; index < stacks.size(); index++) {
            GenericStack stack = stacks.get(index);
            if (stack.amount() <= 0) {
                throw new IllegalArgumentException("Recipe amounts must be positive");
            }
            if (stack.amount() > Long.MAX_VALUE - amounts.getLong(stack.what())) {
                throw new ArithmeticException("long overflow");
            }
            amounts.addTo(stack.what(), stack.amount());
        }
        var result = new ObjectArrayList<GenericStack>(amounts.size());
        for (var entry : amounts.object2LongEntrySet()) {
            result.add(new GenericStack(entry.getKey(), entry.getLongValue()));
        }
        return result;
    }
}
