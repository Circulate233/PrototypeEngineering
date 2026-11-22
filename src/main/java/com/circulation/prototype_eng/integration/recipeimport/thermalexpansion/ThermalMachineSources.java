package com.circulation.prototype_eng.integration.recipeimport.thermalexpansion;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import cofh.core.inventory.ComparableItemStackValidated;
import cofh.core.util.helpers.FluidHelper;
import cofh.thermalexpansion.util.managers.machine.BrewerManager;
import cofh.thermalexpansion.util.managers.machine.CentrifugeManager;
import cofh.thermalexpansion.util.managers.machine.ChargerManager;
import cofh.thermalexpansion.util.managers.machine.CompactorManager;
import cofh.thermalexpansion.util.managers.machine.CrucibleManager;
import cofh.thermalexpansion.util.managers.machine.EnchanterManager;
import cofh.thermalexpansion.util.managers.machine.ExtruderManager;
import cofh.thermalexpansion.util.managers.machine.FurnaceManager;
import cofh.thermalexpansion.util.managers.machine.PrecipitatorManager;
import cofh.thermalexpansion.util.managers.machine.PulverizerManager;
import cofh.thermalexpansion.util.managers.machine.RefineryManager;
import cofh.thermalexpansion.util.managers.machine.SawmillManager;
import cofh.thermalexpansion.util.managers.machine.SmelterManager;
import cofh.thermalexpansion.util.managers.machine.TransposerManager;
import cofh.thermalfoundation.init.TFFluids;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.BrewerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CentrifugeRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CentrifugeMobRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.ChargerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CompactorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CompactorPlateRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CompactorCoinRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CompactorGearRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.CrucibleRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.EnchanterRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.EnchanterEmpoweredRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.ExtruderIgneousRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.ExtruderSedimentaryRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.FurnaceRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.FurnacePyrolysisRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.PrecipitatorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.PulverizerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.RefineryRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.RefineryPotionRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.SawmillRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.SmelterRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.TransposerFillRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.TransposerExtractRecipePool;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.ResourcePresenceRequirement;
import com.circulation.prototype_eng.integration.recipeimport.RecipeImportSupport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

public final class ThermalMachineSources {
    private ThermalMachineSources() {
    }

    public static List<MachineRecipe> readBrewer(ResourceLocation poolId, @Nullable BrewerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "brewer", Arrays.asList(BrewerManager.getRecipeList()),
            s -> key(s.getInput(), s.getInputFluid(), s.getOutputFluid(), s.getEnergy()), ThermalMachineSources::convertBrewer,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy()));
    }

    public static List<MachineRecipe> readCentrifuge(ResourceLocation poolId, @Nullable CentrifugeRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "centrifuge", Arrays.asList(CentrifugeManager.getRecipeList()),
            s -> key(s.getInput(), s.getOutput(), s.getChance(), s.getFluid(), s.getEnergy()), (recipeId, source) -> convertCentrifuge(recipeId, source, false),
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), chances(s)));
    }

    public static List<MachineRecipe> readCentrifugeMob(ResourceLocation poolId, @Nullable CentrifugeMobRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "centrifugemob", Arrays.asList(CentrifugeManager.getRecipeListMobs()),
            s -> key(s.getInput(), s.getOutput(), s.getChance(), s.getFluid(), s.getEnergy()), (recipeId, source) -> convertCentrifuge(recipeId, source, true),
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), chances(s)));
    }

    public static List<MachineRecipe> readCharger(ResourceLocation poolId, @Nullable ChargerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "charger", Arrays.asList(ChargerManager.getRecipeList()),
            s -> key(s.getInput(), s.getOutput(), s.getEnergy()), ThermalMachineSources::convertCharger,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy()));
    }

    public static List<MachineRecipe> readCompactor(ResourceLocation poolId, @Nullable CompactorRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "compactor", Arrays.asList(CompactorManager.getRecipeList(CompactorManager.Mode.ALL)),
            s -> key(s.getInput(), s.getOutput(), s.getEnergy()), ThermalMachineSources::convertCompactor,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy()));
    }

    public static List<MachineRecipe> readCompactorPlate(ResourceLocation poolId, @Nullable CompactorPlateRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "compactorplate", Arrays.asList(CompactorManager.getRecipeList(CompactorManager.Mode.PLATE)),
            s -> key(s.getInput(), s.getOutput(), s.getEnergy()), ThermalMachineSources::convertCompactor,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy()));
    }

    public static List<MachineRecipe> readCompactorCoin(ResourceLocation poolId, @Nullable CompactorCoinRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "compactorcoin", Arrays.asList(CompactorManager.getRecipeList(CompactorManager.Mode.COIN)),
            s -> key(s.getInput(), s.getOutput(), s.getEnergy()), ThermalMachineSources::convertCompactor,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy()));
    }

    public static List<MachineRecipe> readCompactorGear(ResourceLocation poolId, @Nullable CompactorGearRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "compactorgear", Arrays.asList(CompactorManager.getRecipeList(CompactorManager.Mode.GEAR)),
            s -> key(s.getInput(), s.getOutput(), s.getEnergy()), ThermalMachineSources::convertCompactor,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy()));
    }

    public static List<MachineRecipe> readCrucible(ResourceLocation poolId, @Nullable CrucibleRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "crucible", Arrays.asList(CrucibleManager.getRecipeList()),
            s -> key(s.getInput(), s.getOutput(), s.getEnergy()), ThermalMachineSources::convertCrucible,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy()));
    }

    public static List<MachineRecipe> readEnchanter(ResourceLocation poolId, @Nullable EnchanterRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "enchanter", Arrays.stream(EnchanterManager.getRecipeList()).filter(source -> source.getType() == EnchanterManager.Type.STANDARD).toList(),
            s -> key(s.getPrimaryInput(), s.getSecondaryInput(), s.getOutput(), s.getEnergy(), s.getExperience(), s.getType(), s.getEnchantName()), ThermalMachineSources::convertEnchanter,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getExperience(), s.getEnchantName()));
    }

    public static List<MachineRecipe> readEnchanterEmpowered(ResourceLocation poolId, @Nullable EnchanterEmpoweredRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "enchanterempowered", Arrays.stream(EnchanterManager.getRecipeList()).filter(source -> source.getType() == EnchanterManager.Type.EMPOWERED).toList(),
            s -> key(s.getPrimaryInput(), s.getSecondaryInput(), s.getOutput(), s.getEnergy(), s.getExperience(), s.getType(), s.getEnchantName()), ThermalMachineSources::convertEnchanter,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getExperience(), s.getEnchantName()));
    }

    public static List<MachineRecipe> readExtruderIgneous(ResourceLocation poolId, @Nullable ExtruderIgneousRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "extruderigneous", Arrays.asList(ExtruderManager.getRecipeList(false)),
            s -> key(s.getOutput(), s.getInputHot(), s.getInputCold(), s.getEnergy()), ThermalMachineSources::convertExtruder,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getInputHot().amount, s.getInputCold().amount));
    }

    public static List<MachineRecipe> readExtruderSedimentary(ResourceLocation poolId, @Nullable ExtruderSedimentaryRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "extrudersedimentary", Arrays.asList(ExtruderManager.getRecipeList(true)),
            s -> key(s.getOutput(), s.getInputHot(), s.getInputCold(), s.getEnergy()), ThermalMachineSources::convertExtruder,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getInputHot().amount, s.getInputCold().amount));
    }

    public static List<MachineRecipe> readFurnace(ResourceLocation poolId, @Nullable FurnaceRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "furnace", Arrays.asList(FurnaceManager.getRecipeList(false)),
            s -> key(s.getInput(), s.getOutput(), s.getEnergy(), s.getCreosote()), ThermalMachineSources::convertFurnace,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy()));
    }

    public static List<MachineRecipe> readFurnacePyrolysis(ResourceLocation poolId, @Nullable FurnacePyrolysisRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "furnacepyrolysis", Arrays.asList(FurnaceManager.getRecipeList(true)),
            s -> key(s.getInput(), s.getOutput(), s.getEnergy(), s.getCreosote()), ThermalMachineSources::convertFurnace,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getCreosote()));
    }

    public static List<MachineRecipe> readPrecipitator(ResourceLocation poolId, @Nullable PrecipitatorRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "precipitator", Arrays.asList(PrecipitatorManager.getRecipeList()),
            s -> key(s.getInput(), s.getOutput(), s.getEnergy()), ThermalMachineSources::convertPrecipitator,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy()));
    }

    public static List<MachineRecipe> readPulverizer(ResourceLocation poolId, @Nullable PulverizerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "pulverizer", Arrays.asList(PulverizerManager.getRecipeList()),
            s -> key(s.getInput(), s.getPrimaryOutput(), s.getSecondaryOutput(), s.getSecondaryOutputChance(), s.getEnergy()), ThermalMachineSources::convertPulverizer,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getSecondaryOutputChance()));
    }

    public static List<MachineRecipe> readRefinery(ResourceLocation poolId, @Nullable RefineryRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "refinery", Arrays.asList(RefineryManager.getRecipeList()),
            s -> key(s.getInput(), s.getOutputFluid(), s.getOutputItem(), s.getEnergy(), s.getChance()), ThermalMachineSources::convertRefinery,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getChance()));
    }

    public static List<MachineRecipe> readRefineryPotion(ResourceLocation poolId, @Nullable RefineryPotionRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "refinerypotion", Arrays.asList(RefineryManager.getRecipeListPotion()),
            s -> key(s.getInput(), s.getOutputFluid(), s.getOutputItem(), s.getEnergy(), s.getChance()), ThermalMachineSources::convertRefinery,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getChance()));
    }

    public static List<MachineRecipe> readSawmill(ResourceLocation poolId, @Nullable SawmillRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "sawmill", Arrays.asList(SawmillManager.getRecipeList()),
            s -> key(s.getInput(), s.getPrimaryOutput(), s.getSecondaryOutput(), s.getSecondaryOutputChance(), s.getEnergy()), ThermalMachineSources::convertSawmill,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getSecondaryOutputChance()));
    }

    public static List<MachineRecipe> readSmelter(ResourceLocation poolId, @Nullable SmelterRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "smelter", Arrays.asList(SmelterManager.getRecipeList()),
            s -> key(s.getPrimaryInput(), s.getSecondaryInput(), s.getPrimaryOutput(), s.getSecondaryOutput(), s.getSecondaryOutputChance(), s.getEnergy(), s.hasFlux()), ThermalMachineSources::convertSmelter,
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getSecondaryOutputChance(), s.hasFlux()));
    }

    public static List<MachineRecipe> readTransposerFill(ResourceLocation poolId, @Nullable TransposerFillRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "transposerfill", Arrays.asList(TransposerManager.getFillRecipeList()),
            s -> key(s.getInput(), s.getOutput(), s.getFluid(), s.getEnergy(), s.getChance()), (recipeId, source) -> convertTransposer(recipeId, source, true),
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getChance()));
    }

    public static List<MachineRecipe> readTransposerExtract(ResourceLocation poolId, @Nullable TransposerExtractRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("thermalexpansion");
        return convertAll(poolId, "transposerextract", Arrays.asList(TransposerManager.getExtractRecipeList()),
            s -> key(s.getInput(), s.getOutput(), s.getFluid(), s.getEnergy(), s.getChance()), (recipeId, source) -> convertTransposer(recipeId, source, false),
            onCreate == null ? null : (recipe, s) -> onCreate.create(recipe, s.getEnergy(), s.getChance()));
    }

    static <S> List<MachineRecipe> convertAll(ResourceLocation poolId, String kind, Collection<S> sources,
                                             Function<S, String> sourceKey, BiFunction<ResourceLocation, S, RecipeBuilder> convert,
                                             @Nullable BiPredicate<RecipeBuilder, S> onCreate) {
        var ordered = new ObjectArrayList<Ordered<S>>(sources.size());
        for (var source : sources) {
            try {
                ordered.add(new Ordered<>(sourceKey.apply(source), source));
            } catch (RuntimeException exception) {
                PrototypeEngineering.LOGGER.error("Rejected Thermal {} source while sorting pool {}", kind, poolId, exception);
            }
        }
        ordered.sort(Comparator.comparing(Ordered::key));
        var result = new ObjectArrayList<MachineRecipe>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            var source = ordered.get(index).source();
            RecipeBuilder draft;
            try {
                draft = convert.apply(RecipeImportSupport.id(poolId, kind + "/" + index), source);
            } catch (RuntimeException exception) {
                PrototypeEngineering.LOGGER.error("Rejected Thermal {} recipe {} in pool {}", kind, index, poolId, exception);
                continue;
            }
            if (onCreate == null || onCreate.test(draft, source)) result.add(draft.build());
        }
        return result;
    }

    private record Ordered<S>(String key, S source) {
    }

    static String key(Object... parts) {
        var result = new StringBuilder();
        for (var part : parts) {
            String value = switch (part) {
                case ItemStack item -> item.getCount() + ":" + item.getMetadata() + ":" + item.serializeNBT();
                case FluidStack fluid -> fluid.writeToNBT(new NBTTagCompound()).toString();
                case List<?> list -> key(list.toArray());
                case null -> "null";
                default -> part.toString();
            };
            result.append(value.length()).append(':').append(value);
        }
        return result.toString();
    }

    static RecipeBuilder convertBrewer(ResourceLocation id, BrewerManager.BrewerRecipe source) {
        var recipe = recipe(id, source.getEnergy());
        resources(recipe, List.of(), List.of(
            itemInput(id, "item", source.getInput(), BrewerManager::convertInput),
            fluidInput(id, source.getInputFluid())), List.of(RecipeImportSupport.fluid(source.getOutputFluid())));
        return recipe;
    }

    static RecipeBuilder convertCentrifuge(ResourceLocation id, CentrifugeManager.CentrifugeRecipe source, boolean mob) {
        var recipe = recipe(id, source.getEnergy());
        var outputs = source.getOutput();
        var chances = source.getChance();
        if (outputs.size() != chances.size()) throw new IllegalArgumentException("Thermal Centrifuge output/chance count differs");
        resources(recipe, List.of(), List.of(itemInput(id, "item", source.getInput(), CentrifugeManager::convertInput)),
            source.getFluid() == null || source.getFluid().amount == 0 ? List.of() : List.of(RecipeImportSupport.fluid(source.getFluid())));
        for (int i = 0; i < outputs.size(); i++) {
            var output = outputs.get(i);
            int chance = chances.get(i);
            if (chance < 0) throw new IllegalArgumentException("Negative Thermal Centrifuge output chance");
            if (output.isEmpty()) continue;
            if (mob) {
                for (int unit = 0; unit < output.getCount(); unit++) {
                    var single = RecipeImportSupport.item(output.copy());
                    secondary(recipe, "output_" + i + "_" + unit, new GenericStack(single.what(), 1),
                        Math.min(100, 100.0 * chance / (100.0 + 10.0 * unit)));
                }
            } else {
                secondary(recipe, "output_" + i, RecipeImportSupport.item(output), Math.min(100, chance));
            }
        }
        return recipe;
    }

    private static int[] chances(CentrifugeManager.CentrifugeRecipe source) {
        return source.getChance().stream().mapToInt(Integer::intValue).toArray();
    }

    static RecipeBuilder convertCharger(ResourceLocation id, ChargerManager.ChargerRecipe source) {
        var recipe = recipe(id, source.getEnergy());
        resources(recipe, List.of(), List.of(itemInput(id, "item", source.getInput(), ComparableItemStackValidated::new)),
            List.of(RecipeImportSupport.item(source.getOutput())));
        return recipe;
    }

    static RecipeBuilder convertCompactor(ResourceLocation id, CompactorManager.CompactorRecipe source) {
        var recipe = recipe(id, source.getEnergy());
        resources(recipe, List.of(), List.of(itemInput(id, "item", source.getInput(), CompactorManager::convertInput)),
            List.of(RecipeImportSupport.item(source.getOutput())));
        return recipe;
    }

    static RecipeBuilder convertCrucible(ResourceLocation id, CrucibleManager.CrucibleRecipe source) {
        var recipe = recipe(id, source.getEnergy());
        resources(recipe, List.of(), List.of(itemInput(id, "item", source.getInput(), CrucibleManager::convertInput)),
            List.of(RecipeImportSupport.fluid(source.getOutput())));
        return recipe;
    }

    static RecipeBuilder convertEnchanter(ResourceLocation id, EnchanterManager.EnchanterRecipe source) {
        if (source.getExperience() < 0) throw new IllegalArgumentException("Negative Thermal Enchanter experience amount");
        var recipe = recipe(id, source.getEnergy());
        var fluids = source.getExperience() == 0 ? List.<GenericStack>of()
            : List.of(RecipeImportSupport.fluid(new FluidStack(TFFluids.fluidExperience, source.getExperience())));
        resources(recipe, fluids, List.of(
            itemInput(id, "primary", source.getPrimaryInput(), EnchanterManager::convertInput),
            itemInput(id, "secondary", source.getSecondaryInput(), EnchanterManager::convertInput)),
            List.of(RecipeImportSupport.item(source.getOutput())));
        return recipe;
    }

    static RecipeBuilder convertExtruder(ResourceLocation id, ExtruderManager.ExtruderRecipe source) {
        var hot = source.getInputHot();
        var cold = source.getInputCold();
        if (hot.amount < 0 || cold.amount < 0) throw new IllegalArgumentException("Negative Thermal Extruder fluid amount");
        var recipe = recipe(id, source.getEnergy());
        var inputs = new ObjectArrayList<GenericStack>(2);
        if (hot.amount > 0) inputs.add(RecipeImportSupport.fluid(hot));
        if (cold.amount > 0) inputs.add(RecipeImportSupport.fluid(cold));
        var hotMinimum = hot.copy();
        var coldMinimum = cold.copy();
        hotMinimum.amount = 1000;
        coldMinimum.amount = 1000;
        recipe.addRequirement(RecipeRequirements.RESOURCE_PRESENCE.requirement(RecipeImportSupport.id(id, "fluid_presence"),
            new ResourcePresenceRequirement(List.of(RecipeImportSupport.fluid(hotMinimum), RecipeImportSupport.fluid(coldMinimum)))));
        resources(recipe, inputs, List.of(), List.of(RecipeImportSupport.item(source.getOutput())));
        return recipe;
    }

    static RecipeBuilder convertFurnace(ResourceLocation id, FurnaceManager.FurnaceRecipe source) {
        if (source.getCreosote() < 0) throw new IllegalArgumentException("Negative Thermal Furnace creosote amount");
        var recipe = recipe(id, source.getEnergy());
        var outputs = new ObjectArrayList<GenericStack>(2);
        outputs.add(RecipeImportSupport.item(source.getOutput()));
        if (source.getCreosote() > 0)
            outputs.add(RecipeImportSupport.fluid(new FluidStack(TFFluids.fluidCreosote, source.getCreosote())));
        resources(recipe, List.of(), List.of(itemInput(id, "item", source.getInput(), FurnaceManager::convertInput)), outputs);
        return recipe;
    }

    static RecipeBuilder convertPrecipitator(ResourceLocation id, PrecipitatorManager.PrecipitatorRecipe source) {
        var recipe = recipe(id, source.getEnergy());
        resources(recipe, List.of(RecipeImportSupport.fluid(source.getInput())), List.of(),
            List.of(RecipeImportSupport.item(source.getOutput())));
        return recipe;
    }

    static RecipeBuilder convertPulverizer(ResourceLocation id, PulverizerManager.PulverizerRecipe source) {
        var recipe = recipe(id, source.getEnergy());
        resources(recipe, List.of(), List.of(itemInput(id, "item", source.getInput(), PulverizerManager::convertInput)),
            optionalItem(source.getPrimaryOutput()));
        secondary(recipe, source.getSecondaryOutput(), source.getSecondaryOutputChance());
        return recipe;
    }

    static RecipeBuilder convertRefinery(ResourceLocation id, RefineryManager.RefineryRecipe source) {
        var recipe = recipe(id, source.getEnergy());
        resources(recipe, List.of(), List.of(fluidInput(id, source.getInput())),
            source.getOutputFluid() == null || source.getOutputFluid().amount == 0 ? List.of()
                : List.of(RecipeImportSupport.fluid(source.getOutputFluid())));
        secondary(recipe, source.getOutputItem(), source.getChance());
        return recipe;
    }

    static RecipeBuilder convertSawmill(ResourceLocation id, SawmillManager.SawmillRecipe source) {
        var recipe = recipe(id, source.getEnergy());
        resources(recipe, List.of(), List.of(itemInput(id, "item", source.getInput(), SawmillManager::convertInput)),
            optionalItem(source.getPrimaryOutput()));
        secondary(recipe, source.getSecondaryOutput(), source.getSecondaryOutputChance());
        return recipe;
    }

    static RecipeBuilder convertSmelter(ResourceLocation id, SmelterManager.SmelterRecipe source) {
        var recipe = recipe(id, source.getEnergy());
        resources(recipe, List.of(), List.of(
            itemInput(id, "primary", source.getPrimaryInput(), SmelterManager::convertInput),
            itemInput(id, "secondary", source.getSecondaryInput(), SmelterManager::convertInput)),
            optionalItem(source.getPrimaryOutput()));
        secondary(recipe, source.getSecondaryOutput(), source.getSecondaryOutputChance());
        return recipe;
    }

    static RecipeBuilder convertTransposer(ResourceLocation id, TransposerManager.TransposerRecipe source, boolean fill) {
        var recipe = recipe(id, source.getEnergy());
        resources(recipe, List.of(), fill
            ? List.of(itemInput(id, "item", source.getInput(), TransposerManager::convertInput), fluidInput(id, source.getFluid()))
            : List.of(itemInput(id, "item", source.getInput(), TransposerManager::convertInput)),
            fill ? optionalItem(source.getOutput()) : List.of(RecipeImportSupport.fluid(source.getFluid())));
        if (!fill) {
            if (source.getChance() < 0) throw new IllegalArgumentException("Negative Thermal container chance");
            if (!source.getOutput().isEmpty())
                secondary(recipe, "container", RecipeImportSupport.item(source.getOutput()), Math.min(100, source.getChance()));
        }
        return recipe;
    }

    private static RecipeBuilder recipe(ResourceLocation id, int energy) {
        if (energy < 0) throw new IllegalArgumentException("Negative Thermal recipe energy");
        var recipe = new RecipeBuilder(id, Math.max(1, Math.toIntExact((energy + 19L) / 20L)));
        if (energy > 0) RecipeImportSupport.energyTotal(recipe, energy);
        return recipe;
    }

    private static List<GenericStack> optionalItem(ItemStack stack) {
        return stack.isEmpty() ? List.of() : List.of(RecipeImportSupport.item(stack));
    }

    private static void resources(RecipeBuilder recipe, List<GenericStack> inputs, List<DynamicInputGroup> dynamic,
                                  List<GenericStack> outputs) {
        if (outputs.isEmpty() && dynamic.isEmpty() && inputs.isEmpty()) throw new IllegalArgumentException("Empty Thermal recipe");
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.id(recipe.id(), "resources"),
            new ResourceRequirement(inputs, List.of(), List.of(), outputs, false, ResourceChance.ALWAYS, null, List.of(), dynamic)));
    }

    private static void secondary(RecipeBuilder recipe, ItemStack output, int chance) {
        if (chance < 0) throw new IllegalArgumentException("Negative Thermal output chance");
        if (!output.isEmpty()) secondary(recipe, "secondary", RecipeImportSupport.item(output), Math.min(200, chance));
    }

    private static void secondary(RecipeBuilder recipe, String suffix, GenericStack output, double chance) {
        if (chance == 0) return;
        recipe.addRequirement(RecipeRequirements.RESOURCES.requirement(RecipeImportSupport.id(recipe.id(), suffix),
            new ResourceRequirement(List.of(), List.of(), List.of(), List.of(output), false, new ResourceChance(chance, null), null)));
    }

    static DynamicInputGroup itemInput(ResourceLocation id, String suffix, ItemStack stack, Function<ItemStack, ?> convert) {
        var snapshot = stack.copy();
        var displayed = RecipeImportSupport.item(snapshot);
        var expected = convert.apply(snapshot);
        boolean wildcard = snapshot.getMetadata() == OreDictionary.WILDCARD_VALUE;
        var matcher = new NamedResourceMatcher(RecipeImportSupport.id(id, suffix), (candidate, _) -> {
            if (!(candidate instanceof AEItemKey item)) return false;
            var actual = item.getReadOnlyStack();
            if (expected.equals(convert.apply(actual))) return true;
            if (!wildcard) return false;
            var wildcardStack = actual.copy();
            wildcardStack.setItemDamage(OreDictionary.WILDCARD_VALUE);
            return expected.equals(convert.apply(wildcardStack));
        });
        return new DynamicInputGroup(matcher, displayed.what(), displayed.amount(), List.of(displayed.what()), Integer.MAX_VALUE);
    }

    private static DynamicInputGroup fluidInput(ResourceLocation id, FluidStack stack) {
        var displayed = RecipeImportSupport.fluid(stack.copy());
        int expected = FluidHelper.getFluidHash(stack);
        var matcher = new NamedResourceMatcher(RecipeImportSupport.id(id, "fluid"),
            (candidate, _) -> candidate instanceof AEFluidKey fluid
                && FluidHelper.getFluidHash(Objects.requireNonNull(fluid.getReadOnlyStack())) == expected);
        return new DynamicInputGroup(matcher, displayed.what(), displayed.amount(), List.of(displayed.what()), Integer.MAX_VALUE);
    }
}
