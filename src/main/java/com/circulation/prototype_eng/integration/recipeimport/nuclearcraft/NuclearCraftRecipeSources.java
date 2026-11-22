package com.circulation.prototype_eng.integration.recipeimport.nuclearcraft;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipe;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import nc.recipe.BasicRecipe;
import nc.recipe.BasicRecipeHandler;
import nc.recipe.IngredientSorption;
import nc.recipe.NCRecipes;
import nc.recipe.ingredient.FluidArrayIngredient;
import nc.recipe.ingredient.IChanceFluidIngredient;
import nc.recipe.ingredient.IChanceItemIngredient;
import nc.recipe.ingredient.IFluidIngredient;
import nc.recipe.ingredient.IItemIngredient;
import nc.recipe.ingredient.ItemArrayIngredient;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class NuclearCraftRecipeSources {
    private NuclearCraftRecipeSources() {
    }

    public static List<ImportedRecipe<NuclearCraftRecipeContext>> readAlloyFurnace(ResourceLocation poolId) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return read(poolId, "alloy_furnace", NCRecipes.alloy_furnace, 400, 10);
    }

    public static List<ImportedRecipe<NuclearCraftRecipeContext>> readInfuser(ResourceLocation poolId) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return read(poolId, "infuser", NCRecipes.infuser, 600, 10);
    }

    public static List<ImportedRecipe<NuclearCraftRecipeContext>> readChemicalReactor(ResourceLocation poolId) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return read(poolId, "chemical_reactor", NCRecipes.chemical_reactor, 400, 5);
    }

    public static List<ImportedRecipe<NuclearCraftRecipeContext>> readMelter(ResourceLocation poolId) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return read(poolId, "melter", NCRecipes.melter, 800, 40);
    }

    private static List<ImportedRecipe<NuclearCraftRecipeContext>> read(ResourceLocation poolId, String group,
                                                                        BasicRecipeHandler handler, int baseDuration,
                                                                        int baseEnergyPerTick) {
        Objects.requireNonNull(handler, "NuclearCraft " + group + " recipes are not initialized");
        var result = new ObjectArrayList<ImportedRecipe<NuclearCraftRecipeContext>>();
        var sources = handler.getRecipeList();
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            try {
                result.addAll(convert(poolId, group, sourceIndex, sources.get(sourceIndex), baseDuration, baseEnergyPerTick));
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected NuclearCraft recipe {}[{}] while importing pool {}",
                    group, sourceIndex, poolId, failure);
            }
        }
        return result;
    }

    static List<ImportedRecipe<NuclearCraftRecipeContext>> convert(ResourceLocation poolId, String group,
                                                                   int sourceIndex, BasicRecipe source,
                                                                   int baseDuration, int baseEnergyPerTick) {
        var sourceId = RecipeImportSupport.id(poolId, "nuclearcraft/" + group + "/" + sourceIndex);
        double duration = source.getBaseProcessTime(baseDuration);
        double energy = source.getBaseProcessPower(baseEnergyPerTick);
        var template = new RecipeBuilder(sourceId, RecipeImportSupport.duration(duration));
        RecipeImportSupport.energy(template, RecipeImportSupport.energy(energy));
        var ordinaryOutputs = new ObjectArrayList<GenericStack>();
        for (var ingredient : source.getItemProducts()) addItemOutput(template, ingredient, ordinaryOutputs);
        for (var ingredient : source.getFluidProducts()) addFluidOutput(template, ingredient, ordinaryOutputs);
        RecipeImportSupport.resources(template, List.of(), ordinaryOutputs, List.of());

        var slots = new ObjectArrayList<List<InputOption>>();
        for (var ingredient : source.getItemIngredients()) {
            var alternatives = new ObjectArrayList<InputOption>();
            itemOptions(ingredient, alternatives);
            slots.add(combineAmounts(alternatives));
        }
        for (var ingredient : source.getFluidIngredients()) {
            var alternatives = new ObjectArrayList<InputOption>();
            fluidOptions(ingredient, alternatives);
            slots.add(combineAmounts(alternatives));
        }
        var variants = new ObjectArrayList<List<InputOption>>();
        variants.add(List.of());
        for (var slot : slots) {
            int size = Math.multiplyExact(variants.size(), slot.size());
            var expanded = new ObjectArrayList<List<InputOption>>(size);
            for (var previous : variants) {
                for (var option : slot) {
                    var combined = new ObjectArrayList<InputOption>(previous.size() + 1);
                    combined.addAll(previous);
                    combined.add(option);
                    expanded.add(List.copyOf(combined));
                }
            }
            variants = expanded;
        }

        var result = new ObjectArrayList<ImportedRecipe<NuclearCraftRecipeContext>>(variants.size());
        for (int variantIndex = 0; variantIndex < variants.size(); variantIndex++) {
            var recipeId = RecipeImportSupport.id(sourceId, "variant/" + variantIndex);
            var recipe = new RecipeBuilder(recipeId, template.durationTicks()).addRequirements(template.requirements());
            var inputs = new ObjectArrayList<DynamicInputGroup>();
            var chosen = variants.get(variantIndex);
            for (int slotIndex = 0; slotIndex < chosen.size(); slotIndex++) {
                var option = chosen.get(slotIndex);
                if (option.amount == 0) continue;
                var matcher = new NamedResourceMatcher(RecipeImportSupport.id(recipeId, "input/" + slotIndex),
                    (candidate, _) -> option.matcher.test(candidate));
                inputs.add(new DynamicInputGroup(matcher, option.displayed.getFirst(), option.amount,
                    option.displayed, Integer.MAX_VALUE));
            }
            if (!inputs.isEmpty()) {
                var resources = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false,
                    ResourceChance.ALWAYS, null, List.of(), List.copyOf(inputs));
                recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(
                    RecipeImportSupport.requirementId(recipe, "inputs"), resources));
            }
            var context = new NuclearCraftRecipeContext(group, sourceIndex, variantIndex, source,
                duration, energy, source.getBaseProcessRadiation(), List.copyOf(source.getExtras()));
            result.add(new ImportedRecipe<>(recipe, context));
        }
        return result;
    }

    private static void itemOptions(IItemIngredient ingredient, ObjectArrayList<InputOption> result) {
        if (ingredient instanceof IChanceItemIngredient) {
            throw new IllegalArgumentException("NuclearCraft chance inputs require a custom import conversion");
        }
        if (ingredient instanceof ItemArrayIngredient alternatives) {
            for (var alternative : alternatives.ingredientList) itemOptions(alternative, result);
            return;
        }
        if (ingredient.isEmpty()) {
            result.add(InputOption.empty());
            return;
        }
        int amount = ingredient.getMaxStackSize(0);
        if (amount <= 0) throw new IllegalArgumentException("NuclearCraft item input amount must be positive");
        var displayed = new ObjectLinkedOpenHashSet<AEKey>();
        for (var stack : ingredient.getInputStackList()) {
            if (stack != null && !stack.isEmpty()) displayed.add(Objects.requireNonNull(AEItemKey.of(stack)));
        }
        if (displayed.isEmpty())
            throw new IllegalArgumentException("NuclearCraft item input has no displayable candidates");
        result.add(new InputOption(amount, List.copyOf(displayed), candidate -> candidate instanceof AEItemKey key
            && ingredient.match(key.toStack(amount), IngredientSorption.INPUT).matches()));
    }

    private static void fluidOptions(IFluidIngredient ingredient, ObjectArrayList<InputOption> result) {
        if (ingredient instanceof IChanceFluidIngredient) {
            throw new IllegalArgumentException("NuclearCraft chance inputs require a custom import conversion");
        }
        if (ingredient instanceof FluidArrayIngredient alternatives) {
            for (var alternative : alternatives.ingredientList) fluidOptions(alternative, result);
            return;
        }
        if (ingredient.isEmpty()) {
            result.add(InputOption.empty());
            return;
        }
        int amount = ingredient.getMaxStackSize(0);
        if (amount <= 0) throw new IllegalArgumentException("NuclearCraft fluid input amount must be positive");
        var displayed = new ObjectLinkedOpenHashSet<AEKey>();
        for (var stack : ingredient.getInputStackList()) {
            if (stack != null && stack.amount > 0) displayed.add(Objects.requireNonNull(AEFluidKey.of(stack)));
        }
        if (displayed.isEmpty())
            throw new IllegalArgumentException("NuclearCraft fluid input has no displayable candidates");
        result.add(new InputOption(amount, List.copyOf(displayed), candidate -> candidate instanceof AEFluidKey key
            && ingredient.match(key.toStack(amount), IngredientSorption.INPUT).matches()));
    }

    private static List<InputOption> combineAmounts(List<InputOption> alternatives) {
        if (alternatives.isEmpty()) throw new IllegalArgumentException("NuclearCraft input has no alternatives");
        var grouped = new Int2ObjectLinkedOpenHashMap<List<InputOption>>();
        for (var alternative : alternatives) {
            grouped.computeIfAbsent(alternative.amount, _ -> new ObjectArrayList<>()).add(alternative);
        }
        var result = new ObjectArrayList<InputOption>(grouped.size());
        for (var entry : grouped.int2ObjectEntrySet()) {
            var options = List.copyOf(entry.getValue());
            if (entry.getIntKey() == 0) {
                result.add(InputOption.empty());
                continue;
            }
            var displayed = new ObjectLinkedOpenHashSet<AEKey>();
            for (var option : options) displayed.addAll(option.displayed);
            result.add(new InputOption(entry.getIntKey(), List.copyOf(displayed), candidate -> {
                for (int index = 0; index < options.size(); index++) {
                    if (options.get(index).matcher.test(candidate)) return true;
                }
                return false;
            }));
        }
        return result;
    }

    private static void addItemOutput(RecipeBuilder recipe, IItemIngredient ingredient, List<GenericStack> ordinary) {
        if (ingredient.isEmpty()) return;
        var stack = Objects.requireNonNull(ingredient.getStack(), "NuclearCraft item product has no stack");
        if (ingredient instanceof IChanceItemIngredient chance) {
            var key = Objects.requireNonNull(AEItemKey.of(stack));
            int minimum = chance.getMinStackSize();
            int steps = ingredient.getMaxStackSize(0) - minimum;
            addChanceOutputs(recipe, key, minimum, 1, steps, chance.getChancePercent());
        } else {
            ordinary.add(new GenericStack(Objects.requireNonNull(AEItemKey.of(stack)), ingredient.getMaxStackSize(0)));
        }
    }

    private static void addFluidOutput(RecipeBuilder recipe, IFluidIngredient ingredient, List<GenericStack> ordinary) {
        if (ingredient.isEmpty()) return;
        var stack = Objects.requireNonNull(ingredient.getStack(), "NuclearCraft fluid product has no stack");
        if (ingredient instanceof IChanceFluidIngredient chance) {
            var key = Objects.requireNonNull(AEFluidKey.of(stack));
            addChanceOutputs(recipe, key, chance.getMinStackSize(), chance.getStackDiff(),
                chance.getSizeIncrSteps(), chance.getChancePercent());
        } else {
            ordinary.add(new GenericStack(Objects.requireNonNull(AEFluidKey.of(stack)), ingredient.getMaxStackSize(0)));
        }
    }

    private static void addChanceOutputs(RecipeBuilder recipe, AEKey key, int minimum, int stepAmount, int steps, int percent) {
        if (minimum < 0 || stepAmount <= 0 || steps < 0 || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("Invalid NuclearCraft output distribution");
        }
        if (minimum > 0)
            RecipeImportSupport.resources(recipe, List.of(), List.of(new GenericStack(key, minimum)), List.of());
        if (percent == 0 || steps == 0) return;
        if (percent == 100) {
            RecipeImportSupport.resources(recipe, List.of(), List.of(new GenericStack(key, Math.multiplyExact((long) stepAmount, steps))), List.of());
            return;
        }
        for (int step = 0; step < steps; step++) {
            var resource = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(new GenericStack(key, stepAmount)),
                false, new ResourceChance(percent, null), null);
            long salt = HashCommon.mix(((long) recipe.requirements().size() << 32) ^ (step + 1L));
            recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(
                RecipeImportSupport.requirementId(recipe, "chance_output/" + Long.toUnsignedString(salt, 36)), resource));
        }
    }

    private record InputOption(int amount, List<AEKey> displayed, Predicate<AEKey> matcher) {
        private static InputOption empty() {
            return new InputOption(0, List.of(), _ -> false);
        }
    }
}
