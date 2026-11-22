package com.circulation.prototype_eng.integration.recipeimport.nuclearcraft;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.ManufactoryRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.SeparatorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.DecayHastenerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.FuelReprocessorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.AlloyFurnaceRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.InfuserRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.MelterRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.SupercoolerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.ElectrolyzerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.AssemblerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.IngotFormerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.PressurizerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.ChemicalReactorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.SaltMixerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.CrystallizerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.EnricherRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.ExtractorRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.CentrifugeRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.RockCrusherRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.ElectricFurnaceRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.MultiblockElectrolyzerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.MultiblockDistillerRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.MultiblockInfiltratorRecipePool;
import org.jetbrains.annotations.Nullable;
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
import nc.config.NCConfig;
import nc.handler.TileInfoHandler;
import nc.tile.processor.info.ProcessorContainerInfo;
import nc.recipe.processor.ElectricFurnaceRecipes;
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
import net.minecraft.item.crafting.FurnaceRecipes;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class NuclearCraftRecipeSources {
    private NuclearCraftRecipeSources() {
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readManufactory(ResourceLocation poolId) {
        return readManufactory(poolId, null);
    }

    public static List<MachineRecipe> readManufactory(ResourceLocation poolId, @Nullable ManufactoryRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "manufactory", required(NCRecipes.manufactory, "manufactory").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readSeparator(ResourceLocation poolId) {
        return readSeparator(poolId, null);
    }

    public static List<MachineRecipe> readSeparator(ResourceLocation poolId, @Nullable SeparatorRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "separator", required(NCRecipes.separator, "separator").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readDecayHastener(ResourceLocation poolId) {
        return readDecayHastener(poolId, null);
    }

    public static List<MachineRecipe> readDecayHastener(ResourceLocation poolId, @Nullable DecayHastenerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "decay_hastener", required(NCRecipes.decay_hastener, "decay_hastener").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readFuelReprocessor(ResourceLocation poolId) {
        return readFuelReprocessor(poolId, null);
    }

    public static List<MachineRecipe> readFuelReprocessor(ResourceLocation poolId, @Nullable FuelReprocessorRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "fuel_reprocessor", required(NCRecipes.fuel_reprocessor, "fuel_reprocessor").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readAlloyFurnace(ResourceLocation poolId) {
        return readAlloyFurnace(poolId, null);
    }

    public static List<MachineRecipe> readAlloyFurnace(ResourceLocation poolId, @Nullable AlloyFurnaceRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "alloy_furnace", required(NCRecipes.alloy_furnace, "alloy_furnace").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readInfuser(ResourceLocation poolId) {
        return readInfuser(poolId, null);
    }

    public static List<MachineRecipe> readInfuser(ResourceLocation poolId, @Nullable InfuserRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "infuser", required(NCRecipes.infuser, "infuser").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readMelter(ResourceLocation poolId) {
        return readMelter(poolId, null);
    }

    public static List<MachineRecipe> readMelter(ResourceLocation poolId, @Nullable MelterRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "melter", required(NCRecipes.melter, "melter").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readSupercooler(ResourceLocation poolId) {
        return readSupercooler(poolId, null);
    }

    public static List<MachineRecipe> readSupercooler(ResourceLocation poolId, @Nullable SupercoolerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "supercooler", required(NCRecipes.supercooler, "supercooler").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readElectrolyzer(ResourceLocation poolId) {
        return readElectrolyzer(poolId, null);
    }

    public static List<MachineRecipe> readElectrolyzer(ResourceLocation poolId, @Nullable ElectrolyzerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "electrolyzer", required(NCRecipes.electrolyzer, "electrolyzer").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readAssembler(ResourceLocation poolId) {
        return readAssembler(poolId, null);
    }

    public static List<MachineRecipe> readAssembler(ResourceLocation poolId, @Nullable AssemblerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "assembler", required(NCRecipes.assembler, "assembler").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readIngotFormer(ResourceLocation poolId) {
        return readIngotFormer(poolId, null);
    }

    public static List<MachineRecipe> readIngotFormer(ResourceLocation poolId, @Nullable IngotFormerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "ingot_former", required(NCRecipes.ingot_former, "ingot_former").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readPressurizer(ResourceLocation poolId) {
        return readPressurizer(poolId, null);
    }

    public static List<MachineRecipe> readPressurizer(ResourceLocation poolId, @Nullable PressurizerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "pressurizer", required(NCRecipes.pressurizer, "pressurizer").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readChemicalReactor(ResourceLocation poolId) {
        return readChemicalReactor(poolId, null);
    }

    public static List<MachineRecipe> readChemicalReactor(ResourceLocation poolId, @Nullable ChemicalReactorRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "chemical_reactor", required(NCRecipes.chemical_reactor, "chemical_reactor").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readSaltMixer(ResourceLocation poolId) {
        return readSaltMixer(poolId, null);
    }

    public static List<MachineRecipe> readSaltMixer(ResourceLocation poolId, @Nullable SaltMixerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "salt_mixer", required(NCRecipes.salt_mixer, "salt_mixer").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readCrystallizer(ResourceLocation poolId) {
        return readCrystallizer(poolId, null);
    }

    public static List<MachineRecipe> readCrystallizer(ResourceLocation poolId, @Nullable CrystallizerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "crystallizer", required(NCRecipes.crystallizer, "crystallizer").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readEnricher(ResourceLocation poolId) {
        return readEnricher(poolId, null);
    }

    public static List<MachineRecipe> readEnricher(ResourceLocation poolId, @Nullable EnricherRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "enricher", required(NCRecipes.enricher, "enricher").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readExtractor(ResourceLocation poolId) {
        return readExtractor(poolId, null);
    }

    public static List<MachineRecipe> readExtractor(ResourceLocation poolId, @Nullable ExtractorRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "extractor", required(NCRecipes.extractor, "extractor").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readCentrifuge(ResourceLocation poolId) {
        return readCentrifuge(poolId, null);
    }

    public static List<MachineRecipe> readCentrifuge(ResourceLocation poolId, @Nullable CentrifugeRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "centrifuge", required(NCRecipes.centrifuge, "centrifuge").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readRockCrusher(ResourceLocation poolId) {
        return readRockCrusher(poolId, null);
    }

    public static List<MachineRecipe> readRockCrusher(ResourceLocation poolId, @Nullable RockCrusherRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return readProcessor(poolId, "rock_crusher", required(NCRecipes.rock_crusher, "rock_crusher").getRecipeList(),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readElectricFurnace(ResourceLocation poolId) {
        return readElectricFurnace(poolId, null);
    }

    public static List<MachineRecipe> readElectricFurnace(ResourceLocation poolId, @Nullable ElectricFurnaceRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        var handler = required(NCRecipes.electric_furnace, "electric_furnace");
        return readProcessor(poolId, "electric_furnace", electricFurnaceSources(handler),
            onCreate == null ? null : onCreate::create);
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readMultiblockElectrolyzer(ResourceLocation poolId) {
        return readMultiblockElectrolyzer(poolId, null);
    }

    public static List<MachineRecipe> readMultiblockElectrolyzer(ResourceLocation poolId, @Nullable MultiblockElectrolyzerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return read(poolId, "multiblock_electrolyzer", required(NCRecipes.multiblock_electrolyzer, "multiblock_electrolyzer").getRecipeList(),
            NCConfig.machine_electrolyzer_time, NCConfig.machine_electrolyzer_power,
            multiblock_electrolyzerHandler(onCreate));
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readMultiblockDistiller(ResourceLocation poolId) {
        return readMultiblockDistiller(poolId, null);
    }

    public static List<MachineRecipe> readMultiblockDistiller(ResourceLocation poolId, @Nullable MultiblockDistillerRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return read(poolId, "multiblock_distiller", required(NCRecipes.multiblock_distiller, "multiblock_distiller").getRecipeList(),
            NCConfig.machine_distiller_time, NCConfig.machine_distiller_power,
            multiblock_distillerHandler(onCreate));
    }

    @SuppressWarnings("unused")
    public static List<MachineRecipe> readMultiblockInfiltrator(ResourceLocation poolId) {
        return readMultiblockInfiltrator(poolId, null);
    }

    public static List<MachineRecipe> readMultiblockInfiltrator(ResourceLocation poolId, @Nullable MultiblockInfiltratorRecipePool.OnCreate onCreate) {
        RecipeImportSupport.requireMod("nuclearcraft");
        return read(poolId, "multiblock_infiltrator", required(NCRecipes.multiblock_infiltrator, "multiblock_infiltrator").getRecipeList(),
            NCConfig.machine_infiltrator_time, NCConfig.machine_infiltrator_power,
            multiblock_infiltratorHandler(onCreate));
    }

    @SuppressWarnings("SameParameterValue")
    static List<MachineRecipe> convertMultiblockElectrolyzer(ResourceLocation poolId, int sourceIndex, BasicRecipe source,
        double baseDuration, double baseEnergyPerTick, @Nullable MultiblockElectrolyzerRecipePool.OnCreate onCreate) {
        var result = new ObjectArrayList<MachineRecipe>();
        accept(drafts(poolId, "multiblock_electrolyzer", sourceIndex, source, baseDuration, baseEnergyPerTick), source,
            baseDuration, baseEnergyPerTick, multiblock_electrolyzerHandler(onCreate), result);
        return result;
    }

    private static @Nullable SourceCreationHandler multiblock_electrolyzerHandler(@Nullable MultiblockElectrolyzerRecipePool.OnCreate onCreate) {
        return onCreate == null ? null : (recipe, source, duration, power) -> onCreate.create(recipe,
            source.getBaseProcessTime(duration), source.getBaseProcessPower(power), source.getBaseProcessRadiation(),
            List.copyOf(source.getExtras()), (String) source.getExtras().get(3));
    }

    @SuppressWarnings("SameParameterValue")
    static List<MachineRecipe> convertMultiblockDistiller(ResourceLocation poolId, int sourceIndex, BasicRecipe source,
        double baseDuration, double baseEnergyPerTick, @Nullable MultiblockDistillerRecipePool.OnCreate onCreate) {
        var result = new ObjectArrayList<MachineRecipe>();
        accept(drafts(poolId, "multiblock_distiller", sourceIndex, source, baseDuration, baseEnergyPerTick), source,
            baseDuration, baseEnergyPerTick, multiblock_distillerHandler(onCreate), result);
        return result;
    }

    private static @Nullable SourceCreationHandler multiblock_distillerHandler(@Nullable MultiblockDistillerRecipePool.OnCreate onCreate) {
        return onCreate == null ? null : (recipe, source, duration, power) -> onCreate.create(recipe,
            source.getBaseProcessTime(duration), source.getBaseProcessPower(power), source.getBaseProcessRadiation(),
            List.copyOf(source.getExtras()), source.getDistillerSieveTrayCount());
    }

    @SuppressWarnings("SameParameterValue")
    static List<MachineRecipe> convertMultiblockInfiltrator(ResourceLocation poolId, int sourceIndex, BasicRecipe source,
        double baseDuration, double baseEnergyPerTick, @Nullable MultiblockInfiltratorRecipePool.OnCreate onCreate) {
        var result = new ObjectArrayList<MachineRecipe>();
        accept(drafts(poolId, "multiblock_infiltrator", sourceIndex, source, baseDuration, baseEnergyPerTick), source,
            baseDuration, baseEnergyPerTick, multiblock_infiltratorHandler(onCreate), result);
        return result;
    }

    private static @Nullable SourceCreationHandler multiblock_infiltratorHandler(@Nullable MultiblockInfiltratorRecipePool.OnCreate onCreate) {
        return onCreate == null ? null : (recipe, source, duration, power) -> onCreate.create(recipe,
            source.getBaseProcessTime(duration), source.getBaseProcessPower(power), source.getBaseProcessRadiation(),
            List.copyOf(source.getExtras()), source.getInfiltratorHeatingFactor());
    }

    private static BasicRecipeHandler required(@Nullable BasicRecipeHandler handler, String group) {
        return Objects.requireNonNull(handler, "NuclearCraft " + group + " recipes are not initialized");
    }

    private static List<MachineRecipe> readProcessor(ResourceLocation poolId, String group, List<BasicRecipe> sources,
                                                      @Nullable CreationHandler onCreate) {
        ProcessorContainerInfo<?, ?, ?> info = Objects.requireNonNull(TileInfoHandler.getProcessorContainerInfo(group),
            "NuclearCraft " + group + " processor information is not initialized");
        return read(poolId, group, sources, info.defaultProcessTime, info.defaultProcessPower, sourceHandler(onCreate));
    }

    static List<BasicRecipe> electricFurnaceSources(BasicRecipeHandler handler) {
        var sources = new ObjectArrayList<>(handler.getRecipeList());
        var entries = new ObjectArrayList<>(FurnaceRecipes.instance().getSmeltingList().entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().serializeNBT().toString()));
        for (var entry : entries) {
            var input = entry.getKey().copy();
            input.setCount(1);
            sources.add(ElectricFurnaceRecipes.getVanillaFurnaceRecipe(input, entry.getValue().copy()));
        }
        return sources;
    }

    private static List<MachineRecipe> read(ResourceLocation poolId, String group, List<BasicRecipe> sources,
                                            double baseDuration, double baseEnergyPerTick,
                                            @Nullable SourceCreationHandler onCreate) {
        var result = new ObjectArrayList<MachineRecipe>();
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            var source = sources.get(sourceIndex);
            List<RecipeBuilder> recipes;
            try {
                recipes = drafts(poolId, group, sourceIndex, source, baseDuration, baseEnergyPerTick);
            } catch (RuntimeException failure) {
                PrototypeEngineering.LOGGER.error("Rejected NuclearCraft recipe {}[{}] while importing pool {}",
                    group, sourceIndex, poolId, failure);
                continue;
            }
            accept(recipes, source, baseDuration, baseEnergyPerTick, onCreate, result);
        }
        return result;
    }

    private static @Nullable SourceCreationHandler sourceHandler(@Nullable CreationHandler onCreate) {
        return onCreate == null ? null : (recipe, source, duration, power) -> onCreate.create(recipe,
            source.getBaseProcessTime(duration), source.getBaseProcessPower(power), source.getBaseProcessRadiation(),
            List.copyOf(source.getExtras()));
    }

    @FunctionalInterface
    private interface SourceCreationHandler {
        boolean create(RecipeBuilder recipe, BasicRecipe source, double baseDuration, double baseEnergyPerTick);
    }

    @SuppressWarnings("SameParameterValue")
    static List<MachineRecipe> convert(ResourceLocation poolId, String group,
        int sourceIndex, BasicRecipe source, double baseDuration, double baseEnergyPerTick) {
        return convert(poolId, group, sourceIndex, source, baseDuration, baseEnergyPerTick, null);
    }

    static List<MachineRecipe> convert(ResourceLocation poolId, String group,
        int sourceIndex, BasicRecipe source, double baseDuration, double baseEnergyPerTick, @Nullable CreationHandler onCreate) {
        var result = new ObjectArrayList<MachineRecipe>();
        accept(drafts(poolId, group, sourceIndex, source, baseDuration, baseEnergyPerTick), source,
            baseDuration, baseEnergyPerTick, sourceHandler(onCreate), result);
        return result;
    }

    private static List<RecipeBuilder> drafts(ResourceLocation poolId, String group,
        int sourceIndex, BasicRecipe source, double baseDuration, double baseEnergyPerTick) {
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

        var result = new ObjectArrayList<RecipeBuilder>(variants.size());
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
            result.add(recipe);
        }
        return result;
    }

    @FunctionalInterface
    interface CreationHandler {
        boolean create(RecipeBuilder recipe, double durationTicks, double energyPerTick, double radiation, List<Object> extras);
    }

    private static void accept(List<RecipeBuilder> recipes, BasicRecipe source, double baseDuration, double baseEnergyPerTick,
                               @Nullable SourceCreationHandler onCreate, List<MachineRecipe> accepted) {
        for (int i = 0; i < recipes.size(); i++) {
            var recipe = recipes.get(i);
            if (onCreate == null || onCreate.create(recipe, source, baseDuration, baseEnergyPerTick))
                accepted.add(recipe.build());
        }
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
