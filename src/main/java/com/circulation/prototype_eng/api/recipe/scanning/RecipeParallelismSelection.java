package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;

/** One pure process-local selection after the base suggestion, before the complete admission probe. */
@FunctionalInterface
public interface RecipeParallelismSelection {
    RecipeParallelismSelection NONE = new RecipeParallelismSelection() {
        @Override
        public void select(RecipeProcess process) {
        }

        @Override
        public boolean stableFixedInputLowerBounds() {
            return true;
        }
    };

    static RecipeParallelismSelection fixed(int parallelism) {
        if (parallelism < 1) throw new IllegalArgumentException("Selected parallelism must be positive");
        return new RecipeParallelismSelection() {
            @Override
            public void select(RecipeProcess process) {
                process.attributes().setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, parallelism);
            }

            @Override
            public boolean stableFixedInputLowerBounds() {
                return true;
            }
        };
    }

    /** May modify local parallel attributes. It must not reserve, execute START or mutate the world. */
    void select(RecipeProcess process);

    /** Immutable assertion for this definition's lifetime, all owners/seeds and selected quantities:
     * the complete START cannot reduce or replace any canonical fixed-input lower bound.
     * Arbitrary callbacks default to unknown; changing the assertion requires a new definition. */
    default boolean stableFixedInputLowerBounds() {
        return false;
    }
}
