package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;

import java.util.Objects;

/**
 * Fresh unpublished candidates only. Respect identity, seed and tuning, and publish no world effects.
 */
@FunctionalInterface
public interface RecipeProcessFactory {
    RecipeProcessFactory STANDARD = new RecipeProcessFactory() {
        @Override
        public RecipeProcess create(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
            return new RecipeProcess(machine, recipe, seed, tuning);
        }

        @Override
        public Creation beginCreation(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
            return RecipeProcess.beginCreation(machine, recipe, seed, tuning);
        }
    };

    RecipeProcess create(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning);

    /**
     * Legacy external factories remain one cooperative callback; the standard factory meters its own capture.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    default Creation beginCreation(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
        return new Creation() {
            private RecipeProcess process;

            @Override
            public boolean advance(RecipeWorkBudget budget) {
                if (process != null) return true;
                if (!budget.take()) return false;
                process = Objects.requireNonNull(create(machine, recipe, seed, tuning));
                return true;
            }

            @Override
            public RecipeProcess result() {
                if (process == null) throw new IllegalStateException("Factory creation is incomplete");
                return process;
            }
        };
    }

    interface Creation {
        boolean advance(RecipeWorkBudget budget);

        RecipeProcess result();

        default boolean current() {
            return true;
        }
    }
}
