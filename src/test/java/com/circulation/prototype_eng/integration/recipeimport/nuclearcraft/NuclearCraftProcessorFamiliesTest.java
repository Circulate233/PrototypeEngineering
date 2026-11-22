package com.circulation.prototype_eng.integration.recipeimport.nuclearcraft;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.ManufactoryRecipePool;
import nc.recipe.processor.ManufactoryRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.SeparatorRecipePool;
import nc.recipe.processor.SeparatorRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.DecayHastenerRecipePool;
import nc.recipe.processor.DecayHastenerRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.FuelReprocessorRecipePool;
import nc.recipe.processor.FuelReprocessorRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.AlloyFurnaceRecipePool;
import nc.recipe.processor.AlloyFurnaceRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.InfuserRecipePool;
import nc.recipe.processor.InfuserRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.MelterRecipePool;
import nc.recipe.processor.MelterRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.SupercoolerRecipePool;
import nc.recipe.processor.SupercoolerRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.ElectrolyzerRecipePool;
import nc.recipe.processor.ElectrolyzerRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.AssemblerRecipePool;
import nc.recipe.processor.AssemblerRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.IngotFormerRecipePool;
import nc.recipe.processor.IngotFormerRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.PressurizerRecipePool;
import nc.recipe.processor.PressurizerRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.ChemicalReactorRecipePool;
import nc.recipe.processor.ChemicalReactorRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.SaltMixerRecipePool;
import nc.recipe.processor.SaltMixerRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.CrystallizerRecipePool;
import nc.recipe.processor.CrystallizerRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.EnricherRecipePool;
import nc.recipe.processor.EnricherRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.ExtractorRecipePool;
import nc.recipe.processor.ExtractorRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.CentrifugeRecipePool;
import nc.recipe.processor.CentrifugeRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.RockCrusherRecipePool;
import nc.recipe.processor.RockCrusherRecipes;
import com.circulation.prototype_eng.api.recipe.importing.nuclearcraft.ElectricFurnaceRecipePool;
import nc.recipe.processor.ElectricFurnaceRecipes;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import nc.config.NCConfig;
import nc.recipe.BasicRecipeHandler;
import nc.recipe.BasicRecipe;
import nc.recipe.ingredient.ItemIngredient;
import nc.recipe.ingredient.FluidIngredient;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NuclearCraftProcessorFamiliesTest {
    @BeforeAll
    static void initializeMinecraft() {
        Bootstrap.register();
    }

    static Stream<ProcessorCase> processors() {
        return Stream.of(
            new ProcessorCase("manufactory", ManufactoryRecipes::new, new ManufactoryRecipePool(id("manufactory"), "nc.test.title")),
            new ProcessorCase("separator", SeparatorRecipes::new, new SeparatorRecipePool(id("separator"), "nc.test.title")),
            new ProcessorCase("decay_hastener", DecayHastenerRecipes::new, new DecayHastenerRecipePool(id("decay_hastener"), "nc.test.title")),
            new ProcessorCase("fuel_reprocessor", FuelReprocessorRecipes::new, new FuelReprocessorRecipePool(id("fuel_reprocessor"), "nc.test.title")),
            new ProcessorCase("alloy_furnace", AlloyFurnaceRecipes::new, new AlloyFurnaceRecipePool(id("alloy_furnace"), "nc.test.title")),
            new ProcessorCase("infuser", InfuserRecipes::new, new InfuserRecipePool(id("infuser"), "nc.test.title")),
            new ProcessorCase("melter", MelterRecipes::new, new MelterRecipePool(id("melter"), "nc.test.title")),
            new ProcessorCase("supercooler", SupercoolerRecipes::new, new SupercoolerRecipePool(id("supercooler"), "nc.test.title")),
            new ProcessorCase("electrolyzer", ElectrolyzerRecipes::new, new ElectrolyzerRecipePool(id("electrolyzer"), "nc.test.title")),
            new ProcessorCase("assembler", AssemblerRecipes::new, new AssemblerRecipePool(id("assembler"), "nc.test.title")),
            new ProcessorCase("ingot_former", IngotFormerRecipes::new, new IngotFormerRecipePool(id("ingot_former"), "nc.test.title")),
            new ProcessorCase("pressurizer", PressurizerRecipes::new, new PressurizerRecipePool(id("pressurizer"), "nc.test.title")),
            new ProcessorCase("chemical_reactor", ChemicalReactorRecipes::new, new ChemicalReactorRecipePool(id("chemical_reactor"), "nc.test.title")),
            new ProcessorCase("salt_mixer", SaltMixerRecipes::new, new SaltMixerRecipePool(id("salt_mixer"), "nc.test.title")),
            new ProcessorCase("crystallizer", CrystallizerRecipes::new, new CrystallizerRecipePool(id("crystallizer"), "nc.test.title")),
            new ProcessorCase("enricher", EnricherRecipes::new, new EnricherRecipePool(id("enricher"), "nc.test.title")),
            new ProcessorCase("extractor", ExtractorRecipes::new, new ExtractorRecipePool(id("extractor"), "nc.test.title")),
            new ProcessorCase("centrifuge", CentrifugeRecipes::new, new CentrifugeRecipePool(id("centrifuge"), "nc.test.title")),
            new ProcessorCase("rock_crusher", RockCrusherRecipes::new, new RockCrusherRecipePool(id("rock_crusher"), "nc.test.title")),
            new ProcessorCase("electric_furnace", ElectricFurnaceRecipes::new, new ElectricFurnaceRecipePool(id("electric_furnace"), "nc.test.title")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("processors")
    void actualProcessorSlotShapesMatchAndExecuteEveryInputOutputWithEnergy(ProcessorCase fixture) {
        var handler = emptyProcessor(fixture.handler());
        var itemInputs = new ObjectArrayList<ItemStack>();
        var fluidInputs = new ObjectArrayList<FluidIngredient>();
        var itemOutputs = new ObjectArrayList<ItemStack>();
        var fluidOutputs = new ObjectArrayList<FluidIngredient>();
        var materials = new AEKeyLinkedOpenHashMap(4);
        var products = new AEKeyLinkedOpenHashMap(2);
        var inputItems = List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.REDSTONE, Items.GLOWSTONE_DUST);
        for (int slot = 0; slot < handler.itemInputSize; slot++) {
            var stack = new ItemStack(inputItems.get(slot), slot + 2);
            itemInputs.add(stack);
            materials.addTo(Objects.requireNonNull(AEItemKey.of(stack)), stack.getCount());
        }
        for (int slot = 0; slot < handler.fluidInputSize; slot++) {
            var stack = new FluidStack(slot == 0 ? FluidRegistry.WATER : FluidRegistry.LAVA, 125 + 25 * slot);
            fluidInputs.add(new FluidIngredient(stack));
            materials.addTo(Objects.requireNonNull(AEFluidKey.of(stack)), stack.amount);
        }
        for (int slot = 0; slot < handler.itemOutputSize; slot++) {
            var stack = new ItemStack(Items.DIAMOND, slot + 1);
            itemOutputs.add(stack);
            products.addTo(Objects.requireNonNull(AEItemKey.of(stack)), stack.getCount());
        }
        for (int slot = 0; slot < handler.fluidOutputSize; slot++) {
            var stack = new FluidStack(FluidRegistry.WATER, 25 + 5 * slot);
            fluidOutputs.add(new FluidIngredient(stack));
            products.addTo(Objects.requireNonNull(AEFluidKey.of(stack)), stack.amount);
        }
        BasicRecipe source;
        var fluidExpansion = NCConfig.enable_fluid_recipe_expansion;
        NCConfig.enable_fluid_recipe_expansion = new boolean[]{false, false};
        try {
            source = Objects.requireNonNull(handler.buildRecipe(itemInputs, fluidInputs, itemOutputs, fluidOutputs,
                List.of(1.25D, 3.25D, 0.125D), true));
        } finally {
            NCConfig.enable_fluid_recipe_expansion = fluidExpansion;
        }
        double baseTime = 2.5;
        double basePower = 1.5;
        var recipe = NuclearCraftRecipeSources.convert(id(fixture.name()), fixture.name(), 0, source, baseTime, basePower,
            (_, duration, power, radiation, extras) -> {
                assertEquals(source.getBaseProcessTime(baseTime), duration);
                assertEquals(source.getBaseProcessPower(basePower), power);
                assertEquals(source.getBaseProcessRadiation(), radiation);
                assertEquals(source.getExtras(), extras);
                return true;
            }).getFirst();
        var pool = fixture.pool().poolType().create(id(fixture.name()), "nc.test.title", List.of(recipe));
        assertSame(recipe, pool.match(materials));
        var resourceInputs = recipe.requirements().get(RecipeRequirements.RESOURCES);
        int groups = 0;
        for (var binding : resourceInputs) groups += ((ResourceRequirement) binding.payload()).dynamicInputs().size();
        assertEquals(handler.itemInputSize + handler.fluidInputSize, groups);
        var input = hatch(HatchMode.INPUT);
        var output = hatch(HatchMode.OUTPUT);
        var inventory = Objects.requireNonNull(input.input());
        var stock = materials.object2LongEntrySet().iterator();
        var first = stock.next();
        AEKey missing = first.getKey();
        for (var entry : materials.object2LongEntrySet())
            inventory.insert(entry.getKey(), entry.getLongValue() - (entry.getKey().equals(missing) ? 1 : 0), Actionable.MODULATE);
        long powerPerTick = (long) Math.ceil(source.getBaseProcessPower(basePower));
        var energy = new EnergyBuffer(1000, () -> {
        });
        energy.receiveEnergy(powerPerTick * recipe.durationTicks(), Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(input, output), List.of(energy), List.of()));
        var process = new RecipeProcess(machine, recipe, 23);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        assertEquals(powerPerTick * recipe.durationTicks(), energy.storedEnergy());
        for (var entry : materials.object2LongEntrySet())
            assertEquals(entry.getLongValue() - (entry.getKey().equals(missing) ? 1 : 0), inventory.amount(entry.getKey()));
        inventory.insert(missing, 1, Actionable.MODULATE);
        for (int tick = 0; tick < recipe.durationTicks(); tick++) assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, energy.storedEnergy());
        for (var entry : materials.object2LongEntrySet()) assertEquals(0, inventory.amount(entry.getKey()));
        for (var entry : products.object2LongEntrySet())
            assertEquals(entry.getLongValue(), Objects.requireNonNull(output.output()).amount(entry.getKey()));
        assertTrue(fixture.pool().machines().isEmpty());
    }

    @Test
    void electricFurnaceIncludesNativeSmeltingAndPrefersItsExplicitRecipes() {
        var furnace = FurnaceRecipes.instance().getSmeltingList();
        var input = new ItemStack(Items.PRISMARINE_SHARD);
        var output = new ItemStack(Items.GOLD_INGOT, 3);
        var custom = new BasicRecipe(List.of(new ItemIngredient(input.copy())), List.of(),
            List.of(new ItemIngredient(new ItemStack(Items.DIAMOND, 2))), List.of(), List.of(1D, 1D, 0D), true);
        var handler = emptyProcessor(ElectricFurnaceRecipes::new);
        handler.getRecipeList().add(custom);
        furnace.put(input, output);
        try {
            var sources = NuclearCraftRecipeSources.electricFurnaceSources(handler);
            assertSame(custom, sources.getFirst());
            assertEquals(furnace.size() + handler.getRecipeList().size(), sources.size());
            var recipes = new ObjectArrayList<MachineRecipe>();
            for (int index = 0; index < sources.size(); index++)
                recipes.addAll(NuclearCraftRecipeSources.convert(id("electric_furnace"), "electric_furnace", index, sources.get(index), 2.5, 1.5));
            var strategy = new ElectricFurnaceRecipePool(id("electric"), "nc.electric.title").poolType();
            var pool = strategy.create(id("electric"), "nc.electric.title", recipes);
            var inventory = new AEKeyLinkedOpenHashMap(1);
            inventory.put(Objects.requireNonNull(AEItemKey.of(input)), 1);
            assertSame(recipes.getFirst(), pool.match(inventory));
            MachineRecipe nativeRecipe = null;
            for (var candidate : recipes) {
                for (var requirement : candidate.requirements().get(RecipeRequirements.RESOURCES)) {
                    var resources = (ResourceRequirement) requirement.payload();
                    for (var product : resources.outputs()) {
                        if (product.what().equals(AEItemKey.of(output)) && product.amount() == output.getCount()) nativeRecipe = candidate;
                    }
                }
            }
            assertNotSame(recipes.getFirst(), Objects.requireNonNull(nativeRecipe));
        } finally {
            furnace.remove(input);
        }
    }

    private static BasicRecipeHandler emptyProcessor(Supplier<BasicRecipeHandler> factory) {
        boolean previous = NCConfig.default_processor_recipes_global;
        NCConfig.default_processor_recipes_global = false;
        try {
            return factory.get();
        } finally {
            NCConfig.default_processor_recipes_global = previous;
        }
    }

    private static BufferedResourceHatch hatch(HatchMode mode) {
        var capacity = new Reference2LongArrayMap<AEKeyType>();
        capacity.put(AEKeyType.items(), 1000);
        capacity.put(AEKeyType.fluids(), 1000);
        return new BufferedResourceHatch(mode, 12, capacity, () -> {
        });
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("nc_processor_families", name);
    }

    record ProcessorCase(String name, Supplier<BasicRecipeHandler> handler, ImportedRecipePool pool) {
        @Override
        @NotNull
        public String toString() {
            return name;
        }
    }
}
