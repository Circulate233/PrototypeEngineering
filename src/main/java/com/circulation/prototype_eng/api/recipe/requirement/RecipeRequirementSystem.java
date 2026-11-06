package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;

/**
 * External start/tick/end extension. Acquisition may reserve/apply reversible changes. It must
 * return a transaction for ALL effects, including partial acquisition, or undo its own changes
 * before throwing. Immutable definitions/systems can be shared; process state cannot be shared
 * between concurrent owners without an explicit reservation/ownership protocol.
 */
public interface RecipeRequirementSystem<C> {
    default RequirementTransaction start(RecipeProcess process, RecipeRequirement<C> requirement) { return RequirementTransaction.SUCCESS; }
    default RequirementTransaction tick(RecipeProcess process, RecipeRequirement<C> requirement) { return RequirementTransaction.SUCCESS; }
    default RequirementTransaction end(RecipeProcess process, RecipeRequirement<C> requirement) { return RequirementTransaction.SUCCESS; }
}
