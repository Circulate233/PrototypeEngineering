package com.circulation.prototype_eng.integration.recipeimport.thermalexpansion;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
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
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourcePresenceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThermalMachineSourcesTest {
    @BeforeAll
    static void initializeMinecraft() {
        Bootstrap.register();
    }

    @Test
    void singleItemConvertersKeepNativeCountsOutputsAndExactEnergy() {
        var input = new ItemStack(Items.IRON_INGOT, 3);
        var output = new ItemStack(Items.GOLD_INGOT, 2);
        var charger = new ChargerManager.ChargerRecipe(input, output, 101);
        verifySingle(ThermalMachineSources.convertCharger(id("charger"), charger), input, output, charger.getEnergy());
        var compactor = Objects.requireNonNull(CompactorManager.addRecipe(203, input, output, CompactorManager.Mode.PLATE));
        try {
            verifySingle(ThermalMachineSources.convertCompactor(id("compactor"), compactor), input, output, compactor.getEnergy());
        } finally {
            CompactorManager.removeRecipe(input, CompactorManager.Mode.PLATE);
        }
        var pulverizer = Objects.requireNonNull(PulverizerManager.addRecipe(307, input, output, new ItemStack(Items.COAL, 4), 37));
        try {
            var draft = ThermalMachineSources.convertPulverizer(id("pulverizer"), pulverizer);
            verifySingle(draft, input, output, pulverizer.getEnergy());
            assertSecondary(draft, 37, item(Items.COAL), 4);
        } finally {
            PulverizerManager.removeRecipe(input);
        }
        var sawmill = Objects.requireNonNull(SawmillManager.addRecipe(409, input, output, new ItemStack(Items.STICK, 5), 150));
        try {
            var draft = ThermalMachineSources.convertSawmill(id("sawmill"), sawmill);
            verifySingle(draft, input, output, sawmill.getEnergy());
            assertSecondary(draft, sawmill.getSecondaryOutputChance(), item(Items.STICK), 5);
        } finally {
            SawmillManager.removeRecipe(input);
        }
    }

    @Test
    void fluidAndMixedConvertersKeepEachMaterialAndIndependentContainerChance() {
        var input = new ItemStack(Items.GUNPOWDER, 2);
        var water = new FluidStack(FluidRegistry.WATER, 137);
        var lava = new FluidStack(FluidRegistry.LAVA, 211);
        var brewer = Objects.requireNonNull(BrewerManager.addRecipe(101, input, water, lava));
        try {
            var draft = ThermalMachineSources.convertBrewer(id("brewer"), brewer);
            assertEquals(2, draft.resources(resourceId(draft)).dynamicInputs().size());
            assertEquals(water.amount, draft.resources(resourceId(draft)).dynamicInputs().getLast().amount());
            assertEquals(List.of(new GenericStack(fluid(lava), lava.amount)), draft.resources(resourceId(draft)).outputs());
            assertEnergy(draft, brewer.getEnergy());
        } finally {
            BrewerManager.removeRecipe(input, water);
        }
        var crucible = Objects.requireNonNull(CrucibleManager.addRecipe(203, input, lava));
        try {
            var draft = ThermalMachineSources.convertCrucible(id("crucible"), crucible);
            assertEquals(1, draft.resources(resourceId(draft)).dynamicInputs().size());
            assertEquals(List.of(new GenericStack(fluid(lava), lava.amount)), draft.resources(resourceId(draft)).outputs());
            assertEnergy(draft, crucible.getEnergy());
        } finally {
            CrucibleManager.removeRecipe(input);
        }
        var transposer = new TransposerManager.TransposerRecipe(input, new ItemStack(Items.BOWL), water, 307, 41);
        var fill = ThermalMachineSources.convertTransposer(id("fill"), transposer, true);
        assertEquals(2, fill.resources(resourceId(fill)).dynamicInputs().size());
        assertEquals(List.of(new GenericStack(item(Items.BOWL), 1)), fill.resources(resourceId(fill)).outputs());
        var extract = ThermalMachineSources.convertTransposer(id("extract"), transposer, false);
        assertEquals(1, extract.resources(resourceId(extract)).dynamicInputs().size());
        assertEquals(List.of(new GenericStack(fluid(water), water.amount)), extract.resources(resourceId(extract)).outputs());
        assertSecondary(extract, transposer.getChance(), item(Items.BOWL), 1);
    }

    @Test
    void refineryAndPrecipitatorUseActualFluidCountsAndTaggedFluidMatching() {
        var water = new FluidStack(FluidRegistry.WATER, 47);
        water.tag = new NBTTagCompound();
        water.tag.setString("Potion", "source");
        var output = new FluidStack(FluidRegistry.LAVA, 73);
        var refinery = Objects.requireNonNull(RefineryManager.addRecipe(101, water, output, new ItemStack(Items.COAL, 2), 29));
        try {
            var draft = ThermalMachineSources.convertRefinery(id("refinery"), refinery);
            var dynamic = draft.resources(resourceId(draft)).dynamicInputs().getFirst();
            assertTrue(dynamic.matcher().matches(fluid(water), dynamic.pattern()));
            assertFalse(dynamic.matcher().matches(fluid(new FluidStack(FluidRegistry.WATER, water.amount)), dynamic.pattern()));
            assertEquals(water.amount, dynamic.amount());
            assertSecondary(draft, refinery.getChance(), item(Items.COAL), 2);
            SimpleRecipePool.forRecipes(id("refinery_pool"), "pool.title", List.of(draft.build()));
        } finally {
            RefineryManager.removeRecipe(water);
        }
        var snow = new ItemStack(Items.SNOWBALL, 3);
        var precipitator = Objects.requireNonNull(PrecipitatorManager.addRecipe(203, snow, water));
        var draft = ThermalMachineSources.convertPrecipitator(id("precipitator"), precipitator);
        assertEquals(List.of(new GenericStack(fluid(water), water.amount)), draft.resources(resourceId(draft)).inputs());
        assertEquals(List.of(new GenericStack(item(Items.SNOWBALL), snow.getCount())), draft.resources(resourceId(draft)).outputs());
        assertEnergy(draft, precipitator.getEnergy());
    }

    @Test
    void centrifugeMobOutputsKeepPerUnitProbabilitiesWhileOrdinaryOutputsKeepWholeStacks() {
        var input = new ItemStack(Items.ROTTEN_FLESH, 2);
        var output = new ItemStack(Items.BONE, 3);
        var source = Objects.requireNonNull(CentrifugeManager.addRecipe(101, input, List.of(output), List.of(50), null));
        try {
            var ordinary = ThermalMachineSources.convertCentrifuge(id("centrifuge"), source, false);
            assertSecondary(ordinary, 50, item(Items.BONE), output.getCount());
            var mob = ThermalMachineSources.convertCentrifuge(id("centrifuge_mob"), source, true);
            var probabilityOutputs = resources(mob.build()).stream().filter(resource -> !resource.outputs().isEmpty()).toList();
            assertEquals(output.getCount(), probabilityOutputs.size());
            for (int unit = 0; unit < output.getCount(); unit++) {
                var converted = probabilityOutputs.get(unit);
                assertEquals(100.0 * 50 / (100.0 + 10.0 * unit), converted.chance().percent());
                assertEquals(List.of(new GenericStack(item(Items.BONE), 1)), converted.outputs());
            }
            assertEnergy(mob, source.getEnergy());
        } finally {
            CentrifugeManager.removeRecipe(input);
        }
    }

    @Test
    void enchanterConsumesExperienceFluidAndPyrolysisProducesCreosote() {
        var previousExperience = TFFluids.fluidExperience;
        var previousCreosote = TFFluids.fluidCreosote;
        TFFluids.fluidExperience = FluidRegistry.WATER;
        TFFluids.fluidCreosote = FluidRegistry.LAVA;
        var input = new ItemStack(Items.BOOK, 2);
        var secondary = new ItemStack(Items.DIAMOND, 3);
        var enchanter = Objects.requireNonNull(EnchanterManager.addRecipe(101, input, secondary, new ItemStack(Items.ENCHANTED_BOOK),
            137, EnchanterManager.Type.EMPOWERED));
        var furnace = Objects.requireNonNull(FurnaceManager.addRecipePyrolysis(203, new ItemStack(Items.COAL, 2), new ItemStack(Items.STICK, 4), 173));
        try {
            var draft = ThermalMachineSources.convertEnchanter(id("enchanter"), enchanter);
            var resources = draft.resources(resourceId(draft));
            assertEquals(List.of(new GenericStack(fluid(new FluidStack(FluidRegistry.WATER, 1)), enchanter.getExperience())), resources.inputs());
            assertEquals(2, resources.dynamicInputs().size());
            assertEnergy(draft, enchanter.getEnergy());
            var pyrolysis = ThermalMachineSources.convertFurnace(id("pyrolysis"), furnace);
            assertEquals(List.of(new GenericStack(item(Items.STICK), 4),
                new GenericStack(fluid(new FluidStack(FluidRegistry.LAVA, 1)), furnace.getCreosote())), pyrolysis.resources(resourceId(pyrolysis)).outputs());
            SimpleRecipePool.forRecipes(id("pyrolysis_pool"), "pool.title", List.of(pyrolysis.build()));
        } finally {
            EnchanterManager.removeRecipe(input, secondary);
            FurnaceManager.removeRecipePyrolysis(furnace.getInput());
            TFFluids.fluidExperience = previousExperience;
            TFFluids.fluidCreosote = previousCreosote;
        }
    }

    @Test
    void extruderRetainsUnconsumedFluidThresholdsAndUsesOrdinaryResourceTransactions() {
        var source = Objects.requireNonNull(ExtruderManager.addRecipeIgneous(101, new ItemStack(Items.BRICK),
            new FluidStack(FluidRegistry.LAVA, 0), new FluidStack(FluidRegistry.WATER, 0)));
        var draft = ThermalMachineSources.convertExtruder(id("extruder"), source);
        var presence = draft.requirements().stream().filter(requirement -> requirement.type() == RecipeRequirements.RESOURCE_PRESENCE)
            .map(requirement -> (ResourcePresenceRequirement) requirement.payload()).findFirst().orElseThrow();
        assertEquals(List.of(new GenericStack(fluid(new FluidStack(FluidRegistry.LAVA, 1)), 1000),
            new GenericStack(fluid(new FluidStack(FluidRegistry.WATER, 1)), 1000)), presence.resources().inputs());
        assertTrue(draft.resources(resourceId(draft)).inputs().isEmpty());
        draft.requirements().removeIf(requirement -> requirement.type() == RecipeRequirements.ENERGY);
        draft.durationTicks(1);
        var stock = stock();
        var lava = fluid(new FluidStack(FluidRegistry.LAVA, 1));
        var water = fluid(new FluidStack(FluidRegistry.WATER, 1));
        stock.insert(lava, 1000, Actionable.MODULATE);
        stock.insert(water, 999, Actionable.MODULATE);
        var process = new RecipeProcess(machine(stock), draft.build(), 7);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(1000, stock.availableAmount(lava));
        assertEquals(999, stock.availableAmount(water));
        stock.insert(water, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1000, stock.availableAmount(lava));
        assertEquals(1000, stock.availableAmount(water));
        assertEquals(1, stock.availableAmount(item(Items.BRICK)));
    }

    @Test
    void samePrimaryInputKeepsDistinctSecondaryRecipesAndStableEnumerationWhileCallbacksCanEditOrReject() {
        var primary = new ItemStack(Items.CLAY_BALL, 2);
        var firstInput = new ItemStack(Items.COAL, 3);
        var secondInput = new ItemStack(Items.REDSTONE, 4);
        var first = Objects.requireNonNull(SmelterManager.addRecipe(101, primary, firstInput, new ItemStack(Items.BRICK, 5)));
        var second = Objects.requireNonNull(SmelterManager.addRecipe(203, primary, secondInput, new ItemStack(Items.GOLD_INGOT, 6)));
        try {
            var forward = smelterRecipes(List.of(first, second));
            var reverse = smelterRecipes(List.of(second, first));
            assertEquals(2, forward.size());
            for (int index = 0; index < forward.size(); index++) {
                assertEquals(forward.get(index).id(), reverse.get(index).id());
                assertEquals(resources(forward.get(index)).getFirst().outputs(), resources(reverse.get(index)).getFirst().outputs());
                assertEquals(2, resources(forward.get(index)).getFirst().dynamicInputs().size());
            }
            var changed = ThermalMachineSources.convertAll(id("callbacks"), "smelter", List.of(first, second),
                source -> ThermalMachineSources.key(source.getPrimaryInput(), source.getSecondaryInput()), ThermalMachineSources::convertSmelter,
                (draft, source) -> {
                    draft.durationTicks(source.getEnergy());
                    draft.addInput(id("auxiliary"), new GenericStack(item(Items.STICK), 1));
                    return source == second;
                });
            assertEquals(1, changed.size());
            assertEquals(second.getEnergy(), changed.getFirst().durationTicks());
            assertEquals(3, resources(changed.getFirst()).stream().mapToInt(resource -> resource.inputs().size() + resource.dynamicInputs().size()).sum());
            var failure = new IllegalStateException("creation rejected");
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ThermalMachineSources.convertAll(id("failure"), "smelter", List.of(first), _ -> "source",
                    ThermalMachineSources::convertSmelter, (_, _) -> { throw failure; })));
        } finally {
            SmelterManager.removeRecipe(primary, firstInput);
            SmelterManager.removeRecipe(primary, secondInput);
        }
    }

    @Test
    void nativeWildcardMatchingAndSourceCopiesRemainIndependentOfLaterSourceEdits() {
        var input = new ItemStack(Items.DYE, 2, OreDictionary.WILDCARD_VALUE);
        var source = new ChargerManager.ChargerRecipe(input, new ItemStack(Items.GOLD_INGOT, 3), 101);
        var draft = ThermalMachineSources.convertCharger(id("wildcard"), source);
        var resource = draft.resources(resourceId(draft));
        var matcher = resource.dynamicInputs().getFirst();
        input.setCount(17);
        source.getOutput().setCount(19);
        assertEquals(2, matcher.amount());
        assertEquals(3, resource.outputs().getFirst().amount());
        assertTrue(matcher.matcher().matches(Objects.requireNonNull(AEItemKey.of(new ItemStack(Items.DYE, 1, 7))), matcher.pattern()));
        assertFalse(matcher.matcher().matches(item(Items.DIAMOND), matcher.pattern()));
    }

    private static List<MachineRecipe> smelterRecipes(List<SmelterManager.SmelterRecipe> sources) {
        return ThermalMachineSources.convertAll(id("smelters"), "smelter", sources,
            source -> ThermalMachineSources.key(source.getPrimaryInput(), source.getSecondaryInput(), source.getPrimaryOutput(), source.getEnergy()),
            ThermalMachineSources::convertSmelter, null);
    }

    private static void verifySingle(RecipeBuilder draft, ItemStack input, ItemStack output, int energy) {
        var resource = draft.resources(resourceId(draft));
        var dynamic = resource.dynamicInputs().getFirst();
        assertEquals(1, resource.dynamicInputs().size());
        assertEquals(input.getCount(), dynamic.amount());
        assertTrue(dynamic.matcher().matches(Objects.requireNonNull(AEItemKey.of(input)), dynamic.pattern()));
        assertEquals(List.of(new GenericStack(Objects.requireNonNull(AEItemKey.of(output)), output.getCount())), resource.outputs());
        assertEnergy(draft, energy);
        assertNotNull(SimpleRecipePool.forRecipes(id("single_pool"), "pool.title", List.of(draft.build())));
    }

    private static void assertEnergy(RecipeBuilder draft, int energy) {
        var payload = draft.requirements().stream().filter(requirement -> requirement.type() == RecipeRequirements.ENERGY)
            .map(requirement -> (EnergyRequirement) requirement.payload()).findFirst().orElseThrow();
        assertEquals(energy, payload.input());
        assertEquals(0, payload.inputPerTick());
    }

    private static void assertSecondary(RecipeBuilder draft, double chance, AEKey output, long amount) {
        assertTrue(resources(draft.build()).stream().anyMatch(resource -> resource.dynamicInputs().isEmpty()
            && resource.outputs().equals(List.of(new GenericStack(output, amount))) && resource.chance().percent() == chance));
    }

    private static List<ResourceRequirement> resources(MachineRecipe recipe) {
        return recipe.orderedRequirements().stream().filter(requirement -> requirement.type() == RecipeRequirements.RESOURCES)
            .map(requirement -> (ResourceRequirement) requirement.payload()).toList();
    }

    private static ResourceLocation resourceId(RecipeBuilder recipe) {
        return new ResourceLocation(recipe.id().getNamespace(), recipe.id().getPath() + "/resources");
    }

    private static AEItemKey item(Item item) {
        return Objects.requireNonNull(AEItemKey.of(new ItemStack(item)));
    }

    private static AEFluidKey fluid(FluidStack fluid) {
        return Objects.requireNonNull(AEFluidKey.of(fluid));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("thermal_sources_test", path);
    }

    private static SlottedResourceBuffer stock() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(AEKeyType.items(), 64);
        capacities.put(AEKeyType.fluids(), 2000);
        return new SlottedResourceBuffer(5, capacities, () -> {});
    }

    private static MachineInstance machine(SlottedResourceBuffer stock) {
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new ResourceHatch() {
            @Override
            public ResourceStorage input() { return stock; }

            @Override
            public ResourceStorage output() { return stock; }

            @Override
            public boolean isValid() { return true; }
        }), List.of(), List.of()));
        return machine;
    }
}
