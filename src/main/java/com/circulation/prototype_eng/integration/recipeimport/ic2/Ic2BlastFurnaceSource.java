package com.circulation.prototype_eng.integration.recipeimport.ic2;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ic2.BlastFurnaceRecipePool;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.Recipes;
import ic2.core.block.machine.tileentity.TileEntityBlastFurnace;
import ic2.core.ref.FluidName;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class Ic2BlastFurnaceSource {
    private Ic2BlastFurnaceSource() {
    }

    public static List<MachineRecipe> read(ResourceLocation poolId, @Nullable BlastFurnaceRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        var manager = Objects.requireNonNull(Recipes.blastfurnace, "IC2 blast furnace recipes are not initialized");
        if (!manager.isIterable()) throw new IllegalStateException("IC2 blast furnace recipes cannot be enumerated");
        return convertAll(poolId, manager.getRecipes(), onCreate);
    }

    static List<MachineRecipe> convertAll(ResourceLocation poolId,
        Iterable<? extends ic2.api.recipe.MachineRecipe<IRecipeInput, Collection<ItemStack>>> sources,
        @Nullable BlastFurnaceRecipePool.OnCreate onCreate) {
        var ordered = new ObjectArrayList<ic2.api.recipe.MachineRecipe<IRecipeInput, Collection<ItemStack>>>();
        sources.forEach(ordered::add);
        ordered.sort(Comparator.comparing(source -> source.getInput().getInputs().toString() + source.getInput().getAmount()
            + source.getOutput() + source.getMetaData()));
        var result = new ObjectArrayList<MachineRecipe>();
        for (int index = 0; index < ordered.size(); index++) {
            var source = ordered.get(index);
            RecipeBuilder recipe;
            try {
                var metadata = Objects.requireNonNull(source.getMetaData(), "IC2 blast furnace metadata");
                int airPerTick = metadata.getInteger("fluid");
                if (airPerTick <= 0) throw new IllegalArgumentException("IC2 blast furnace air consumption must be positive");
                var id = RecipeImportSupport.id(poolId, "blast_furnace/" + index);
                recipe = new RecipeBuilder(id, metadata.getInteger("duration"));
                var outputs = new ObjectArrayList<GenericStack>();
                for (var output : source.getOutput()) outputs.add(RecipeImportSupport.item(output));
                if (outputs.isEmpty()) throw new IllegalArgumentException("IC2 blast furnace recipe has no output");
                var resources = new ResourceRequirement(List.of(),
                    List.of(RecipeImportSupport.fluid(new FluidStack(FluidName.air.getInstance(), airPerTick))), List.of(), outputs,
                    false, ResourceChance.ALWAYS, null, List.of(), List.of(Ic2RecipeSources.input(id, "input", source.getInput())));
                recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.requirementId(recipe, "resources"), resources));
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected IC2 blast furnace recipe {} in pool {}", index, poolId, failure);
                continue;
            }
            if (onCreate == null || onCreate.create(recipe, Objects.requireNonNull(source.getMetaData()).copy(),
                TileEntityBlastFurnace.maxHeat)) result.add(recipe.build());
        }
        return result;
    }
}
