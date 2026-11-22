package com.circulation.prototype_eng.integration.recipeimport;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Loader;

import java.util.List;
import java.util.Objects;

public final class RecipeImportSupport {
    private RecipeImportSupport() {
    }

    public static ResourceLocation id(ResourceLocation poolId, String suffix) {
        return new ResourceLocation(poolId.getNamespace(), poolId.getPath() + "/" + suffix);
    }

    public static void requireMod(String modId) {
        if (!Loader.isModLoaded(modId)) throw new IllegalStateException("Recipe import requires mod " + modId);
    }

    public static GenericStack item(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            throw new IllegalArgumentException("Imported item stack must not be empty");
        return new GenericStack(Objects.requireNonNull(AEItemKey.of(stack)), stack.getCount());
    }

    public static GenericStack fluid(FluidStack stack) {
        if (stack == null || stack.amount <= 0)
            throw new IllegalArgumentException("Imported fluid stack must not be empty");
        return new GenericStack(Objects.requireNonNull(AEFluidKey.of(stack)), stack.amount);
    }

    public static void resources(RecipeBuilder recipe, List<GenericStack> inputs, List<GenericStack> outputs,
                                 List<FuzzyInputGroup> fuzzyInputs) {
        if (inputs.isEmpty() && outputs.isEmpty() && fuzzyInputs.isEmpty()) return;
        var resources = new ResourceRequirement(List.copyOf(inputs), List.of(), List.of(), List.copyOf(outputs),
            false, ResourceChance.ALWAYS, null, List.copyOf(fuzzyInputs), List.of());
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(requirementId(recipe, "resources"), resources));
    }

    public static void energy(RecipeBuilder recipe, long inputPerTick) {
        if (inputPerTick < 0) throw new IllegalArgumentException("Negative imported energy usage");
        if (inputPerTick > 0) {
            recipe.addRequirement(RecipeRequirements.ENERGY.requirement(requirementId(recipe, "energy"),
                new EnergyRequirement(0, inputPerTick, 0, 0, false)));
        }
    }

    public static void energyTotal(RecipeBuilder recipe, long input) {
        if (input < 0) throw new IllegalArgumentException("Negative imported energy usage");
        if (input > 0) {
            recipe.addRequirement(RecipeRequirements.ENERGY.requirement(requirementId(recipe, "energy"),
                new EnergyRequirement(input, 0, 0, 0, false)));
        }
    }

    public static ResourceLocation requirementId(RecipeBuilder recipe, String kind) {
        return id(recipe.id(), kind + "/" + recipe.requirements().size());
    }

    public static int duration(double ticks) {
        if (!Double.isFinite(ticks) || ticks < 0 || ticks > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid imported recipe duration " + ticks);
        }
        return Math.max(1, (int) Math.ceil(ticks));
    }

    public static long energy(double amount) {
        if (!Double.isFinite(amount) || amount < 0 || amount >= 0x1p63) {
            throw new IllegalArgumentException("Invalid imported energy amount " + amount);
        }
        return (long) Math.ceil(amount);
    }
}
