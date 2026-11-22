package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourcePresenceRequirement;

public final class ResourcePresenceRequirementSystem implements RecipeRequirementSystem<ResourcePresenceRequirement> {
    @Override
    public boolean requiresOwnerThread() {
        return true;
    }

    @Override
    public Object crossRecipeTickIo(ResourcePresenceRequirement payload) {
        return null;
    }

    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<ResourcePresenceRequirement> requirement) {
        return ResourceRequirementSystem.checkPresence(process, requirement.id(), requirement.payload().resources());
    }
}
