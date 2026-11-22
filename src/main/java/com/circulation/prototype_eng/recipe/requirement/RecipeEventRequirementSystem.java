package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeEventRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;

import java.util.Objects;

public final class RecipeEventRequirementSystem implements RecipeRequirementSystem<RecipeEventRequirement> {
    @Override
    public Object crossRecipeTickIo(RecipeEventRequirement payload) {
        return payload.stage() == RecipeStage.TICK ? payload : null;
    }

    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<RecipeEventRequirement> requirement) {
        return acquire(process, requirement, RecipeStage.START);
    }

    @Override
    public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<RecipeEventRequirement> requirement) {
        return acquire(process, requirement, RecipeStage.TICK);
    }

    @Override
    public RequirementTransaction end(RecipeProcess process, RecipeRequirement<RecipeEventRequirement> requirement) {
        return acquire(process, requirement, RecipeStage.END);
    }

    private static RequirementTransaction acquire(RecipeProcess process, RecipeRequirement<RecipeEventRequirement> requirement,
                                                  RecipeStage stage) {
        if (process.probing() || requirement.payload().stage() != stage) return RequirementTransaction.SUCCESS;
        try {
            return Objects.requireNonNull(requirement.payload().handler().acquire(process, stage), "Recipe event transaction");
        } catch (RuntimeException | Error failure) {
            PrototypeEngineering.LOGGER.error("Recipe event {} failed for {} at {}", requirement.id(), process.recipe().id(), stage, failure);
            throw failure;
        }
    }
}
