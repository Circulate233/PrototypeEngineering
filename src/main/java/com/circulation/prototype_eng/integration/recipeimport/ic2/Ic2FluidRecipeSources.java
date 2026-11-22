package com.circulation.prototype_eng.integration.recipeimport.ic2;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ic2.ElectrolyzerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ic2.FermenterRecipePool;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import ic2.api.recipe.IElectrolyzerRecipeManager;
import ic2.api.recipe.IFermenterRecipeManager;
import ic2.api.recipe.Recipes;
import ic2.core.init.MainConfig;
import ic2.core.item.type.CropResItemType;
import ic2.core.ref.ItemName;
import ic2.core.util.ConfigUtil;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Ic2FluidRecipeSources {
    private Ic2FluidRecipeSources() {
    }

    public static List<MachineRecipe> readElectrolyzer(ResourceLocation poolId, @Nullable ElectrolyzerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return electrolyzer(poolId, Objects.requireNonNull(Recipes.electrolyzer, "IC2 electrolyzer recipes are not initialized").getRecipeMap(), onCreate);
    }

    public static List<MachineRecipe> readFermenter(ResourceLocation poolId, @Nullable FermenterRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("ic2");
        return fermenter(poolId, Objects.requireNonNull(Recipes.fermenter, "IC2 fermenter recipes are not initialized").getRecipeMap(), onCreate);
    }

    static List<MachineRecipe> electrolyzer(ResourceLocation poolId,
        Map<String, IElectrolyzerRecipeManager.ElectrolyzerRecipe> sources, @Nullable ElectrolyzerRecipePool.OnCreate onCreate) {
        var ordered = new ObjectArrayList<>(sources.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        var result = new ObjectArrayList<MachineRecipe>();
        for (var entry : ordered) {
            var source = entry.getValue();
            RecipeBuilder recipe;
            try {
                var fluid = Objects.requireNonNull(FluidRegistry.getFluid(entry.getKey()), "IC2 electrolyzer input fluid");
                var outputs = new ObjectArrayList<GenericStack>();
                for (var output : source.outputs) outputs.add(RecipeImportSupport.fluid(output.getOutput()));
                if (outputs.isEmpty()) throw new IllegalArgumentException("IC2 electrolyzer recipe has no output");
                recipe = new RecipeBuilder(RecipeImportSupport.id(poolId, "electrolyzer/" + entry.getKey()), source.ticksNeeded);
                RecipeImportSupport.resources(recipe, List.of(RecipeImportSupport.fluid(new FluidStack(fluid, source.inputAmount))), outputs, List.of());
                RecipeImportSupport.energy(recipe, Math.multiplyExact((long) source.EUaTick, 4));
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected IC2 electrolyzer fluid {} in pool {}", entry.getKey(), poolId, failure);
                continue;
            }
            if (onCreate == null || onCreate.create(recipe, source.EUaTick, directions(source.outputs))) result.add(recipe.build());
        }
        return result;
    }

    private static EnumFacing[] directions(IElectrolyzerRecipeManager.ElectrolyzerOutput[] outputs) {
        var directions = new EnumFacing[outputs.length];
        for (int index = 0; index < outputs.length; index++) directions[index] = outputs[index].tankDirection;
        return directions;
    }

    static List<MachineRecipe> fermenter(ResourceLocation poolId,
        Map<String, IFermenterRecipeManager.FermentationProperty> sources, @Nullable FermenterRecipePool.OnCreate onCreate) {
        var ordered = new ObjectArrayList<>(sources.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        var result = new ObjectArrayList<MachineRecipe>();
        for (var entry : ordered) {
            var source = entry.getValue();
            RecipeBuilder recipe;
            try {
                var input = Objects.requireNonNull(FluidRegistry.getFluid(entry.getKey()), "IC2 fermenter input fluid");
                recipe = new RecipeBuilder(RecipeImportSupport.id(poolId, "fermenter/" + entry.getKey()), 1);
                RecipeImportSupport.resources(recipe, List.of(RecipeImportSupport.fluid(new FluidStack(input, source.inputAmount))),
                    List.of(RecipeImportSupport.fluid(source.getOutput())), List.of());
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected IC2 fermenter fluid {} in pool {}", entry.getKey(), poolId, failure);
                continue;
            }
            if (onCreate == null || onCreate.create(recipe, source.heat,
                ConfigUtil.getInt(MainConfig.get(), "balance/fermenter/biomass_per_fertilizier"),
                ItemName.crop_res.getItemStack(CropResItemType.fertilizer))) result.add(recipe.build());
        }
        return result;
    }
}
