package pe.visual.examples;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import javax.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static pe.visual.examples.VisualExamples.fluid;
import static pe.visual.examples.VisualExamples.id;
import static pe.visual.examples.VisualExamples.item;

/** Bounded, real addon content for manual JEI, hatch, controller and preview inspection. */
final class BoundaryExamples {
    record Scenario(String name, ExampleText title, int lanes, boolean cross, boolean large,
                    ComponentType<MachineInstance, RecipeScanner> scanner) {
        Scenario(String name, ExampleText title, int lanes, boolean cross, boolean large) {
            this(name, title, lanes, cross, large,
                VisualExamples.scanner(name + "_scan"));
        }
        ResourceLocation pool() { return id(name + "_pool"); }
    }

    static final List<Scenario> SCENARIOS = List.of(
        new Scenario("boundary_catalog", ExampleText.Catalog, 1, false, false),
        new Scenario("boundary_wide", ExampleText.Wide, 1, false, false),
        new Scenario("boundary_choices", ExampleText.Choices, 1, false, false),
        new Scenario("boundary_amounts", ExampleText.Amounts, 1, false, false),
        new Scenario("boundary_tick", ExampleText.Tick, 1, false, false),
        new Scenario("boundary_energy", ExampleText.Energy, 1, false, false),
        new Scenario("boundary_parallel", ExampleText.Parallel, 4, false, false),
        new Scenario("boundary_cross", ExampleText.Cross, 4, true, false),
        new Scenario("boundary_structure", ExampleText.Structure, 1, false, true),
        new Scenario("boundary_long_text", ExampleText.LongText, 1, false, false));

    private BoundaryExamples() { }

    static void declareMachines(MachineTypeRegistrationEvent event) {
        for (var scenario : SCENARIOS) {
            var builder = MachineType.builder(id(scenario.name()), scenario.title(), id(scenario.name() + "_structure"))
                .component(MachineWorkSlots.TYPE).component(scenario.scanner()).recipePool(scenario.pool())
                .attribute(StandardMachineAttributes.PROCESS_PARALLELISM, scenario.lanes())
                .attribute(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, scenario.lanes());
            if (!scenario.cross()) {
                for (int lane = 1; lane <= scenario.lanes(); lane++) {
                    builder.workSlot(new WorkSlotDefinition("lane_" + lane,
                        scenario.lanes() == 1 ? Set.of() : Set.of(id("parallel_lane_" + lane))));
                }
            }
            event.registry().register(builder.build());
        }
    }

    static void registerRuntime(MachineRuntimeRegistrationEvent event) {
        for (var scenario : SCENARIOS) {
            VisualExamples.install(event, scenario.name(), scenario.pool(), scenario.scanner(), true, scenario.cross(), scenario.large());
        }
    }

    static void registerRecipes(RecipeRegistrationEvent event) {
        for (var scenario : SCENARIOS) {
            var recipes = switch (scenario.name()) {
                case "boundary_catalog" -> catalog();
                case "boundary_wide" -> wide();
                case "boundary_choices" -> choices();
                case "boundary_amounts" -> amounts();
                case "boundary_tick" -> ticking();
                case "boundary_energy" -> energy();
                case "boundary_parallel" -> concurrent(false);
                case "boundary_cross" -> concurrent(true);
                case "boundary_structure" -> List.of(fixed("structure_glass", 200,
                    List.of(item(new ItemStack(Blocks.SAND), 4)), List.of(item(new ItemStack(Blocks.GLASS), 4))));
                case "boundary_long_text" -> longText();
                default -> throw new IllegalStateException("Unknown boundary scenario " + scenario.name());
            };
            VisualExamples.registerPool(event, new DefaultRecipePool(scenario.pool(), "pe_visual_examples.pool." + scenario.name(), recipes));
        }
    }

    static @Nullable Map<BlockPos, Block> structure(String name) {
        boolean energy = !name.equals("press");
        boolean large = false;
        if (!name.equals("press") && !name.equals("mixer") && !name.equals("sorter")) {
            Scenario scenario = null;
            for (var candidate : SCENARIOS) if (candidate.name().equals(name)) scenario = candidate;
            if (scenario == null) return null;
            large = scenario.large();
        }
        var result = new Object2ObjectLinkedOpenHashMap<>(VisualExamples.structureBlocks(name, energy, large));
        // Place the controller last so all real structure members are present on its first tick.
        result.put(BlockPos.ORIGIN, VisualExamples.block(id(name + "_controller").toString()));
        return result;
    }

    private static List<MachineRecipe> catalog() {
        var recipes = new ObjectArrayList<MachineRecipe>();
        for (int index = 1; index <= 64; index++) {
            String name = String.format(Locale.ROOT, "catalog_%02d", index);
            int color = (index - 1) % 16;
            int amount = 1 + (index - 1) / 16;
            recipes.add(fixed(name, 20 + index * 2,
                List.of(card(name), item(new ItemStack(Blocks.WOOL, 1, color), amount)),
                List.of(item(new ItemStack(Blocks.CONCRETE, 1, color), amount))));
        }
        return recipes;
    }

    private static List<MachineRecipe> wide() {
        var recipes = new ObjectArrayList<MachineRecipe>();
        for (int slots : new int[]{16, 17, 33}) {
            String name = "wide_" + slots;
            var inputs = new ObjectArrayList<GenericStack>();
            var outputs = new ObjectArrayList<GenericStack>();
            inputs.add(card(name));
            for (int index = 0; index < slots - 1; index++) inputs.add(palette(index, false));
            for (int index = 0; index < slots; index++) outputs.add(palette(index, true));
            recipes.add(fixed(name, 120, inputs, outputs));
        }
        return recipes;
    }

    private static GenericStack palette(int index, boolean output) {
        if (index == 32) return item(new ItemStack(Items.DIAMOND), 1);
        var block = output ? (index < 16 ? Blocks.CONCRETE : Blocks.STAINED_HARDENED_CLAY)
            : (index < 16 ? Blocks.WOOL : Blocks.STAINED_GLASS);
        return item(new ItemStack(block, 1, index % 16), 1);
    }

    private static List<MachineRecipe> choices() {
        var recipes = new ObjectArrayList<MachineRecipe>();
        var woods = new ObjectArrayList<AEKey>();
        for (int index = 0; index < 6; index++) woods.add(item(new ItemStack(Blocks.PLANKS, 1, index), 1).what());
        recipes.add(resources("choices_wood", 80, new ResourceRequirement(List.of(card("choices_wood")), List.of(), List.of(),
            List.of(item(new ItemStack(Items.STICK), 8)), false, ResourceChance.ALWAYS, null,
            List.of(new FuzzyInputGroup(woods, 2)), List.of())));
        recipes.add(selective("choices_same_key", List.of(item(new ItemStack(Items.IRON_INGOT), 1),
            item(new ItemStack(Items.IRON_INGOT), 8), item(new ItemStack(Items.IRON_INGOT), 64))));
        recipes.add(selective("choices_mixed", List.of(item(new ItemStack(Items.IRON_INGOT), 1), fluid(false, 250), fluid(true, 250))));
        var candidates = new ObjectArrayList<WeightedSampling.Weighted<GenericStack>>();
        for (int index = 0; index < 6; index++) candidates.add(new WeightedSampling.Weighted<>(palette(index, true), index + 1));
        recipes.add(resources("choices_weighted_two", 100, new ResourceRequirement(List.of(card("choices_weighted_two"),
            item(new ItemStack(Blocks.GRAVEL), 1)), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS,
            new RandomOutputPool(candidates, 2))));
        return recipes;
    }

    private static MachineRecipe selective(String name, List<GenericStack> alternatives) {
        var candidates = new ObjectArrayList<RecipeRequirement<?>>();
        for (int index = 0; index < alternatives.size(); index++) {
            candidates.add(RecipeRequirements.RESOURCES.requirement(id(name + "_alternative_" + index),
                new ResourceRequirement(List.of(alternatives.get(index)), List.of())));
        }
        return new MachineRecipe(id(name), 100, List.of(
            RecipeRequirements.RESOURCES.requirement(id(name + "_base"),
                new ResourceRequirement(List.of(card(name)), List.of(item(new ItemStack(Items.IRON_NUGGET), 9)))),
            RecipeRequirements.SELECTIVE.requirement(id(name + "_choose_one"), new SelectiveRequirement(candidates)),
            RecipeRequirements.ENERGY.requirement(id(name + "_fe"), new EnergyRequirement(0, 4, 0, 0, false))));
    }

    private static List<MachineRecipe> amounts() {
        var recipes = new ObjectArrayList<MachineRecipe>();
        for (long amount : new long[]{1, 63, 64, 65, 4_096, 65_536, 2_147_483_648L, 9_000_000_000L}) {
            String name = "amount_" + amount;
            recipes.add(fixed(name, 60, List.of(card(name), item(new ItemStack(Items.COAL), amount)),
                List.of(item(new ItemStack(Items.REDSTONE), amount))));
        }
        recipes.add(fixed("amount_fluid_long", 60, List.of(card("amount_fluid_long"), fluid(false, 9_000_000_000L)),
            List.of(fluid(false, 9_000_000_000L))));
        return recipes;
    }

    private static List<MachineRecipe> ticking() {
        return List.of(
            resources("tick_items", 40, new ResourceRequirement(List.of(card("tick_items")),
                List.of(item(new ItemStack(Items.COAL), 1)), List.of(item(new ItemStack(Items.IRON_NUGGET), 2)),
                List.of(item(new ItemStack(Items.DIAMOND), 1)), false)),
            resources("tick_fluid", 80, new ResourceRequirement(List.of(card("tick_fluid")),
                List.of(fluid(false, 25)), List.of(fluid(false, 10)), List.of(item(new ItemStack(Items.CLAY_BALL), 1)), false)),
            resources("tick_mixed_same_key", 60, new ResourceRequirement(
                List.of(card("tick_mixed_same_key"), item(new ItemStack(Items.REDSTONE), 2), fluid(false, 100)),
                List.of(item(new ItemStack(Items.REDSTONE), 1), fluid(false, 5)), List.of(fluid(false, 3)),
                List.of(fluid(false, 20), item(new ItemStack(Items.IRON_INGOT), 1)), false)));
    }

    private static List<MachineRecipe> concurrent(boolean cross) {
        var recipes = new ObjectArrayList<MachineRecipe>();
        var inputs = List.of(Items.COAL, Items.IRON_INGOT, Items.GOLD_INGOT, Items.REDSTONE);
        var outputs = List.of(Items.FLINT, Items.IRON_NUGGET, Items.GOLD_NUGGET, Items.GLOWSTONE_DUST);
        for (int index = 0; index < 4; index++) {
            String name = (cross ? "cross_" : "parallel_") + (index + 1);
            var io = new ResourceRequirement(List.of(item(new ItemStack(inputs.get(index)), 1)),
                List.of(item(new ItemStack(outputs.get(index)), index + 1)));
            recipes.add(new MachineRecipe(id(name), 100 + index * 80, List.of(
                RecipeRequirements.RESOURCES.requirement(id(name + "_io"), io),
                RecipeRequirements.ENERGY.requirement(id(name + "_fe"), new EnergyRequirement(0, 4, 0, 0, false))),
                cross ? Set.of() : Set.of(id("parallel_lane_" + (index + 1))), 1, RecipeParallelismSelection.fixed(1)));
        }
        if (cross) {
            recipes.add(new MachineRecipe(id("cross_repeat_limit2"), 400, List.of(
                RecipeRequirements.RESOURCES.requirement(id("cross_repeat_io"),
                    new ResourceRequirement(List.of(item(new ItemStack(Items.DIAMOND), 1)), List.of(item(new ItemStack(Items.EMERALD), 1))))),
                Set.of(), 2, RecipeParallelismSelection.fixed(1)));
        }
        return recipes;
    }

    private static List<MachineRecipe> energy() {
        return List.of(energy("energy_consume", 80, new EnergyRequirement(512, 32, 0, 0, false)),
            energy("energy_generate", 80, new EnergyRequirement(0, 0, 64, 1_024, false)),
            energy("energy_recycle", 40, new EnergyRequirement(256, 16, 8, 128, false)));
    }

    private static MachineRecipe energy(String name, int ticks, EnergyRequirement energy) {
        return new MachineRecipe(id(name), ticks, List.of(
            RecipeRequirements.RESOURCES.requirement(id(name + "_io"), new ResourceRequirement(
                List.of(card(name), item(new ItemStack(Items.COAL), 1)), List.of(item(new ItemStack(Items.REDSTONE), 1)))),
            RecipeRequirements.ENERGY.requirement(id(name + "_fe"), energy)));
    }

    private static List<MachineRecipe> longText() {
        return List.of(fixed("long_text_bilingual", 200,
                List.of(named("长中文输入名称：检查候选提示、数量叠字和样板导入完整保留 / A deliberately long English input name for tooltip wrapping", 1)),
                List.of(named("长中文输出名称：结构预览与配方窗口尺寸边界 / A deliberately long English output label that should remain readable in its tooltip", 65))),
            fixed("long_text_large_duration", 72_000, List.of(card("long_text_large_duration")), List.of(item(new ItemStack(Items.CLOCK), 1))));
    }

    private static GenericStack card(String name) { return named("PE selector / 示例选择卡: " + name, 1); }
    private static GenericStack named(String text, long amount) {
        return item(new ItemStack(Items.PAPER).setStackDisplayName(text), amount);
    }
    private static MachineRecipe fixed(String name, int ticks, List<GenericStack> inputs, List<GenericStack> outputs) {
        return resources(name, ticks, new ResourceRequirement(inputs, outputs));
    }
    private static MachineRecipe resources(String name, int ticks, ResourceRequirement resources) {
        return new MachineRecipe(id(name), ticks, List.of(
            RecipeRequirements.RESOURCES.requirement(id(name + "_io"), resources),
            RecipeRequirements.ENERGY.requirement(id(name + "_fe"), new EnergyRequirement(0, 4, 0, 0, false))));
    }

    static @Nullable MachineRecipe findRecipe(String path) {
        var requested = new ResourceLocation(path.contains(":") ? path : VisualExamples.MOD_ID + ":" + path);
        for (var pool : RecipeRegistry.INSTANCE.pools()) {
            if (!pool.id().getNamespace().equals(VisualExamples.MOD_ID) && !pool.id().getNamespace().equals("pe_run_tests")) continue;
            for (var recipe : pool.recipes()) if (recipe.id().equals(requested)) return recipe;
        }
        return null;
    }

    /** Explicit stock helper: one selected branch, full duration, no chance scaling, checked before insertion. */
    static AEKey2LongMap stock(MachineRecipe recipe, int runs, int candidate) {
        AEKey2LongMap amounts = new AEKey2LongMap.LinkdOpenHashMap();
        for (var requirement : recipe.orderedRequirements()) stock(requirement, recipe.durationTicks(), runs, candidate, amounts);
        return amounts;
    }

    private static void stock(RecipeRequirement<?> requirement, int ticks, int runs, int candidate, AEKey2LongMap amounts) {
        if (requirement.payload() instanceof ResourceRequirement resource) {
            for (var input : resource.inputs()) add(amounts, input, runs);
            for (var input : resource.inputsPerTick()) add(amounts, input, Math.multiplyExact((long) runs, ticks));
            for (var fuzzy : resource.fuzzyInputs()) {
                if (!fuzzy.candidates().isEmpty()) add(amounts,
                    new GenericStack(fuzzy.candidates().get((candidate - 1) % fuzzy.candidates().size()), fuzzy.amount()), runs);
            }
        } else if (requirement.payload() instanceof SelectiveRequirement selective && !selective.candidates().isEmpty()) {
            stock(selective.candidates().get((candidate - 1) % selective.candidates().size()), ticks, runs, candidate, amounts);
        }
    }

    private static void add(AEKey2LongMap amounts, GenericStack stack, long multiplier) {
        long amount = Math.multiplyExact(stack.amount(), multiplier);
        if (amount > Long.MAX_VALUE - amounts.getLong(stack.what())) throw new ArithmeticException("Example stock quantity overflow");
        amounts.addTo(stack.what(), amount);
    }
}
