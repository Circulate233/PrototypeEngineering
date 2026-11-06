package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.recipe.requirement.AttributeRequirementSystem;
import com.circulation.prototype_eng.recipe.requirement.EnergyRequirementSystem;
import com.circulation.prototype_eng.recipe.requirement.ResourceRequirementSystem;
import net.minecraft.util.ResourceLocation;

/** Built-in execution responsibilities; downstream types use the same public registration path. */
public final class RecipeRequirements {
    public static final RecipeRequirementType<AttributeRequirement> ATTRIBUTES =
        new RecipeRequirementType<>(new ResourceLocation(Tags.MOD_ID, "attribute_modifier"), new AttributeRequirementSystem());
    public static final RecipeRequirementType<ResourceRequirement> RESOURCES =
        new RecipeRequirementType<>(new ResourceLocation(Tags.MOD_ID, "resources"), new ResourceRequirementSystem());
    public static final RecipeRequirementType<EnergyRequirement> ENERGY =
        new RecipeRequirementType<>(new ResourceLocation(Tags.MOD_ID, "energy"), new EnergyRequirementSystem());

    private RecipeRequirements() {
    }
}
