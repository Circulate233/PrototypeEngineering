package com.circulation.prototype_eng.api.client.jei;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.localization.JeiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

/**
 * Complete displayed processing resources; each inner list contains the candidates shown in one slot.
 * Outer and candidate lists are borrowed read-only and must remain unchanged while the recipe is in use.
 */
@SideOnly(Side.CLIENT)
public record JeiPatternRecipe(List<List<GenericStack>> inputs, List<List<GenericStack>> outputs) {
    public JeiPatternRecipe {
        validate(inputs);
        validate(outputs);
    }

    private static void validate(List<List<GenericStack>> groups) {
        for (int index4 = 0; index4 < groups.size(); index4++) {
            var group = groups.get(index4);
            if (group.isEmpty()) throw new IllegalArgumentException("Empty pattern candidate group");
            for (int index2 = 0; index2 < group.size(); index2++) {
                var stack = group.get(index2);
                if (stack == null || stack.amount() <= 0) throw new IllegalArgumentException("Invalid pattern amount");
            }
        }
    }

    public static JeiPatternRecipe fromGroups(List<List<JeiRecipeDisplay.Resource>> inputs,
                                              List<List<JeiRecipeDisplay.Resource>> outputs, long durationTicks) {
        return new JeiPatternRecipe(stacks(inputs, durationTicks, true), stacks(outputs, durationTicks, false));
    }

    private static List<List<GenericStack>> stacks(List<List<JeiRecipeDisplay.Resource>> groups, long durationTicks, boolean input) {
        var result = new ObjectArrayList<List<GenericStack>>(groups.size());
        for (int index6 = 0; index6 < groups.size(); index6++) {
            var group = groups.get(index6);
            var candidates = new ObjectArrayList<GenericStack>(group.size());
            for (int index5 = 0; index5 < group.size(); index5++) {
                var row = group.get(index5);
                if (input && row.role() == JeiRecipeDisplay.Role.INPUT && row.hasDetail(JeiText.NotConsumed)) continue;
                boolean perTick = row.hasDetail(JeiText.Tick);
                long amount = perTick ? Math.multiplyExact(row.stack().amount(), durationTicks) : row.stack().amount();
                candidates.add(new GenericStack(row.stack().what(), amount));
            }
            if (!candidates.isEmpty()) result.add(candidates);
        }
        return result;
    }

}
