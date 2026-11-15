package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.localization.RecipeText;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Explicit whole-recipe scan constraints. Bounds must exclude no feasible base amount, and checks
 * must be pure; they must aggregate shared sources/requirements rather than reuse balances per entry.
 * An accepted constraint never replaces the mandatory transactional START probe.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public interface RecipeParallelismConstraint {
    default int upperBound(MachineInstance machine, MachineRecipe recipe, int currentLimit) {
        return currentLimit;
    }

    default ProcessResult test(RecipeProcess process) {
        return ProcessResult.SUCCESS;
    }

    /**
     * Existing external checks remain one cooperative callback; built-ins override their loops.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    default Check beginCheck(RecipeProcess process) {
        return new Check() {
            private boolean finished;
            private boolean cancelled;
            private ProcessResult result;

            @Override
            public boolean advance(RecipeWorkBudget budget, BooleanSupplier forwardAllowed) {
                if (finished) return true;
                if (cancelled || !forwardAllowed.getAsBoolean()) {
                    result = RecipeText.Cancelled.blocked();
                    finished = true;
                    return true;
                }
                if (!budget.take()) return false;
                result = test(process);
                finished = true;
                return true;
            }

            @Override
            public ProcessResult result() {
                if (!finished) throw new IllegalStateException("Constraint check is incomplete");
                return result;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        };
    }

    interface Check {
        boolean advance(RecipeWorkBudget budget, BooleanSupplier forwardAllowed);

        ProcessResult result();

        void cancel();

        default @Nullable RecipePureComputation computation() {
            return null;
        }

        default void finishComputation(RecipePureComputation expected) {
            throw new IllegalArgumentException("No constraint computation");
        }

        default List<ResourceBatch.Source> sources() {
            return List.of();
        }

        default boolean resolvingSources() {
            return false;
        }

        default void finishSourceResolution(ResourceBatch.Source expected, boolean resolved) {
            throw new IllegalArgumentException("No constraint source action");
        }

        default boolean hasObligations() {
            return false;
        }
    }
}
