package com.circulation.prototype_eng.api.client.jei;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.localization.JeiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

/** Complete displayed processing resources; each inner list contains the candidates shown in one slot. */
public record JeiPatternRecipe(List<List<GenericStack>> inputs, List<List<GenericStack>> outputs) {
    public JeiPatternRecipe {
        inputs = copy(inputs);
        outputs = copy(outputs);
    }

    private static List<List<GenericStack>> copy(List<List<GenericStack>> groups) {
        var result = new ObjectArrayList<List<GenericStack>>(groups.size());
        for (var group : groups) {
            if (group.isEmpty()) throw new IllegalArgumentException("Empty pattern candidate group");
            for (var stack : group)
                if (stack == null || stack.amount() <= 0) throw new IllegalArgumentException("Invalid pattern amount");
            result.add(List.copyOf(group));
        }
        return List.copyOf(result);
    }

    public static JeiPatternRecipe fromGroups(List<List<JeiRecipeDisplay.Resource>> inputs,
                                              List<List<JeiRecipeDisplay.Resource>> outputs, long durationTicks) {
        return new JeiPatternRecipe(stacks(inputs, durationTicks), stacks(outputs, durationTicks));
    }

    private static List<List<GenericStack>> stacks(List<List<JeiRecipeDisplay.Resource>> groups, long durationTicks) {
        var result = new ObjectArrayList<List<GenericStack>>(groups.size());
        for (var group : groups) {
            var candidates = new ObjectArrayList<GenericStack>(group.size());
            for (var row : group) {
                boolean perTick = false;
                for (var detail : row.details()) if (perTick(detail)) {
                    perTick = true;
                    break;
                }
                long amount = perTick ? Math.multiplyExact(row.stack().amount(), durationTicks) : row.stack().amount();
                candidates.add(new GenericStack(row.stack().what(), amount));
            }
            result.add(candidates);
        }
        return result;
    }

    private static boolean perTick(JeiRecipeDisplay.Text text) {
        if (text.text() == JeiText.Tick) return true;
        for (var scope : text.context()) if (perTick(scope)) return true;
        return false;
    }
}
