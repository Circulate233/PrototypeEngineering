package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure optional candidate filtering inside an immutable scope. Empty groups denote the complete
 * global catalog, including ungrouped recipes; nonempty groups denote their union. A nonnull result must include
 * every recipe that could succeed under this index's responsibility; other indices may narrow it
 * further. It must retain scope recipes with no requirement for its own resource type, so an item
 * index cannot discard energy-only or resource-free recipes. Null expresses no opinion; an empty
 * collection rejects the entire scope and must never trigger a global fallback.
 *
 * <p>Results contain only canonical instances from scope. Input groups and scope are immutable.
 * Lookup must not execute START, mutate the machine, consume resources or publish world effects.
 * This contract does not bound arbitrary external lookup CPU work or replace scheduler budgets.
 */
@FunctionalInterface
public interface RecipeCandidateIndex {
    @Nullable
    Collection<MachineRecipe> lookup(MachineInstance machine, Set<ResourceLocation> groups, List<MachineRecipe> scope);

    /**
     * Optional incremental lookup. The default invokes the original callback once after a credit;
     * that opaque callback is cooperative. The catalog charges every returned member separately.
     * A cursor must keep partial opinions private until advance returns true.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    default Cursor beginLookup(MachineInstance machine, Set<ResourceLocation> groups, List<MachineRecipe> scope) {
        return new Cursor() {
            private boolean complete;
            @Nullable
            private Collection<MachineRecipe> result;

            @Override
            public boolean advance(RecipeWorkBudget budget) {
                Objects.requireNonNull(budget);
                if (complete) {
                    return true;
                }
                if (!budget.take()) {
                    return false;
                }
                result = lookup(machine, groups, scope);
                complete = true;
                return true;
            }

            @Override
            public @Nullable Collection<MachineRecipe> result() {
                if (!complete) {
                    throw new IllegalStateException("Index opinion is incomplete");
                }
                return result;
            }
        };
    }

    interface Cursor {
        boolean advance(RecipeWorkBudget budget);

        @Nullable Collection<MachineRecipe> result();

        /** Canonical fixed-input ceiling only; a scanner must prove its complete linear input path before using it. */
        default int inputParallelismLimit(MachineRecipe recipe) {
            return Integer.MAX_VALUE;
        }

        /**
         * A live capture is a search observation, never admission authority.
         */
        default boolean current() {
            return true;
        }

        /** Retained freshness proof. Built-in live cursors charge each inspected binding separately. */
        @SuppressWarnings("ReturnOfInnerClass") // The retained proof belongs to this exact cursor observation.
        default Validation beginValidation() {
            return new Validation() {
                private boolean complete;
                private boolean valid;

                @Override
                public boolean advance(RecipeWorkBudget budget) {
                    Objects.requireNonNull(budget);
                    if (!complete) {
                        if (!budget.take()) return false;
                        valid = Cursor.this.current();
                        complete = true;
                    }
                    return true;
                }

                @Override
                public boolean current() {
                    if (!complete) throw new IllegalStateException("Index validation is incomplete");
                    return valid;
                }
            };
        }
    }

    /** advance returning true means the proof finished; current then returns its captured verdict. */
    interface Validation {
        boolean advance(RecipeWorkBudget budget);

        boolean current();
    }
}
