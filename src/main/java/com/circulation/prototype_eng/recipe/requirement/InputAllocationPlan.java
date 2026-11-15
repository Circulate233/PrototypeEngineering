package com.circulation.prototype_eng.recipe.requirement;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Budgeted pure proposal over one frozen projection; it owns no storage or reservation.
 */
final class InputAllocationPlan {
    static final AEKey2LongMap NO_RESOURCES = ImmutableResourceAmounts.empty();
    private final InputAllocation policy;
    private final Facts facts;
    private final long[] amounts;
    private int source;
    private int portion;
    private long available;
    private long total;
    private long missing;
    @Nullable
    private List<InputAllocation.Portion> proposed;
    private boolean invoked;
    private boolean complete;

    /**
     * The source list is borrowed read-only for this plan's lifetime.
     */
    InputAllocationPlan(InputAllocation policy, RecipeStage stage, int projection, @Nullable AEKey resource,
                        long amount, ObjectArrayList<InputAllocation.Source> sources) {
        this.policy = policy;
        facts = new Facts(stage, projection, resource, amount, sources);
        amounts = new long[sources.size()];
        missing = amount;
    }

    @SuppressWarnings("DataFlowIssue")
    boolean advance(RecipeWorkBudget budget) {
        if (complete) return true;
        while (source < amounts.length) {
            if (!budget.take()) return false;
            long capacity = facts.sources().get(source).available();
            if (policy.kind() == InputAllocation.Kind.SINGLE_SOURCE) {
                available = Math.max(available, capacity);
                if (capacity >= facts.amount()) {
                    amounts[source] = facts.amount();
                    missing = 0;
                    complete = true;
                    return true;
                }
            } else available += Math.min(capacity, facts.amount() - available);
            source++;
        }
        if (available < facts.amount() || policy.kind() == InputAllocation.Kind.SINGLE_SOURCE) {
            missing = facts.amount() - Math.min(facts.amount(), available);
            complete = true;
            return true;
        }
        if (!invoked) {
            if (!budget.take()) return false;
            invoked = true;
            var returned = policy.allocate(facts);
            if (returned.size() > amounts.length)
                throw new IllegalArgumentException("Allocation has more portions than distinct sources: " + policy.id());
            proposed = returned;
        }
        var values = proposed;
        while (portion < values.size()) {
            if (!budget.take()) return false;
            var value = values.get(portion++);
            int index = value.source();
            if (index >= amounts.length || amounts[index] != 0 || value.amount() > facts.sources().get(index).available())
                throw new IllegalArgumentException("Invalid source or quantity in allocation: " + policy.id());
            total = Math.addExact(total, value.amount());
            if (total > facts.amount()) throw new IllegalArgumentException("Allocation exceeds input: " + policy.id());
            amounts[index] = value.amount();
        }
        if (total != facts.amount())
            throw new IllegalArgumentException("Allocation does not cover input: " + policy.id());
        missing = 0;
        complete = true;
        return true;
    }

    long missing() {
        return missing;
    }

    long amount(int index) {
        return amounts[index];
    }

    private record Facts(RecipeStage stage, int projection, @Nullable AEKey resource, long amount,
                         List<InputAllocation.Source> sources) implements InputAllocation.Context {
    }
}
