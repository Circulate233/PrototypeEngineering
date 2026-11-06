package com.circulation.prototype_eng.api.recipe.overlay;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import net.minecraft.util.ResourceLocation;

/** Built-in typed overlays, retaining direct immutable applier bindings instead of a mutable registry. */
public final class RecipeOverlayTypes {
    public static final RecipeOverlayType<ResourceRequirement, ResourceRequirementPatch> RESOURCE_IO = new RecipeOverlayType<>(
        new ResourceLocation(Tags.MOD_ID, "resource_io"), RecipeRequirements.RESOURCES, (base, patch) -> patch.apply(base));
    public static final RecipeOverlayType<EnergyRequirement, EnergyRequirementPatch> ENERGY_RATES = new RecipeOverlayType<>(
        new ResourceLocation(Tags.MOD_ID, "energy_rates"), RecipeRequirements.ENERGY, (base, patch) -> patch.apply(base));

    private RecipeOverlayTypes() {
    }
}
