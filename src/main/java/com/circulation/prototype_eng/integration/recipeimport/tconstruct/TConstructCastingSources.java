package com.circulation.prototype_eng.integration.recipeimport.tconstruct;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.tconstruct.BasinCastingRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.tconstruct.TableCastingRecipePool;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourcePresenceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.TinkerRegistry;
import slimeknights.tconstruct.library.smeltery.CastingRecipe;
import slimeknights.tconstruct.library.smeltery.ICastingRecipe;
import slimeknights.tconstruct.library.smeltery.OreCastingRecipe;
import slimeknights.tconstruct.library.smeltery.PreferenceCastingRecipe;

import java.util.List;

public final class TConstructCastingSources {
    private TConstructCastingSources() {
    }

    @FunctionalInterface
    interface Handler {
        boolean create(RecipeBuilder recipe, boolean consumesCast, boolean switchOutputs);
    }

    public static List<MachineRecipe> readTable(ResourceLocation poolId, @Nullable TableCastingRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("tconstruct");
        return convertAll(poolId, "table", TinkerRegistry.getAllTableCastingRecipes(), onCreate == null ? null : onCreate::create);
    }

    public static List<MachineRecipe> readBasin(ResourceLocation poolId, @Nullable BasinCastingRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("tconstruct");
        return convertAll(poolId, "basin", TinkerRegistry.getAllBasinCastingRecipes(), onCreate == null ? null : onCreate::create);
    }

    static List<MachineRecipe> convertAll(ResourceLocation poolId, String group, List<ICastingRecipe> sources, @Nullable Handler onCreate) {
        var result = new ObjectArrayList<MachineRecipe>();
        for (int index = 0; index < sources.size(); index++) {
            var source = sources.get(index);
            RecipeBuilder recipe;
            try {
                recipe = draft(RecipeImportSupport.id(poolId, group + "_casting/" + index), source);
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected TConstruct {} casting recipe {} ({}) in pool {}", group, index,
                    source.getClass().getName(), poolId, failure);
                continue;
            }
            if (onCreate == null || onCreate.create(recipe, source.consumesCast(), source.switchOutputs())) result.add(recipe.build());
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private static RecipeBuilder draft(ResourceLocation id, ICastingRecipe source) {
        if (!(source instanceof CastingRecipe casting)
            || source.getClass() != CastingRecipe.class && source.getClass() != PreferenceCastingRecipe.class
                && source.getClass() != OreCastingRecipe.class)
            throw new IllegalArgumentException("Casting recipe does not expose a static fluid, cast and result");
        var fluid = casting.getFluid();
        var recipe = new RecipeBuilder(id, casting.getTime());
        var castInputs = new ObjectArrayList<DynamicInputGroup>();
        if (casting.cast != null) {
            if (casting.cast.amountNeeded != 1)
                throw new IllegalArgumentException("Casting recipe requires more than the native cast slot can hold");
            var candidates = new ObjectArrayList<AEKey>();
            for (var stack : casting.cast.getInputs()) {
                var key = AEItemKey.of(stack);
                if (key != null && !candidates.contains(key)) candidates.add(key);
            }
            if (candidates.isEmpty()) throw new IllegalArgumentException("Casting recipe has no displayable cast");
            var matcher = new NamedResourceMatcher(RecipeImportSupport.id(id, "cast"),
                (candidate, _) -> candidate instanceof AEItemKey item && casting.matches(item.toStack(1), fluid.getFluid()));
            castInputs.add(new DynamicInputGroup(matcher, candidates.getFirst(), 1, candidates, Integer.MAX_VALUE));
            if (!casting.consumesCast()) {
                var presence = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false,
                    ResourceChance.ALWAYS, null, List.of(), castInputs);
                recipe.addRequirement(RecipeRequirements.RESOURCE_PRESENCE.requirement(
                    RecipeImportSupport.requirementId(recipe, "cast_presence"), new ResourcePresenceRequirement(presence)));
            }
        }
        var materials = new ResourceRequirement(List.of(RecipeImportSupport.fluid(fluid)), List.of(), List.of(),
            List.of(RecipeImportSupport.item(casting.getResult())), false, ResourceChance.ALWAYS, null, List.of(),
            casting.consumesCast() ? castInputs : List.of());
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.requirementId(recipe, "resources"), materials));
        return recipe;
    }
}
