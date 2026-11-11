package pe.visual.examples.run;

import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.structure.TemplateStructure;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Additional data owners keep the existing visual-v1 owners and their persisted schema intact. */
public final class NativeExamples {
    public enum Text implements LocalizationEnum {
        Fixed, Cross, Pool, Structure;
        @Override public String getTranslationKey() { return "pe_run_tests.native." + name().toLowerCase(Locale.ROOT); }
    }
    private static final ResourceLocation POOL = NativeRunCases.id("native_pool");
    private static final ComponentType<MachineInstance, RecipeScanner> SCAN = RecipeScanning.component(
        NativeRunCases.id("native_scan"), host -> NativeRunCases.decorate(host.owner().createRecipeScanner(host,
            RecipeRegistry.INSTANCE.catalog(), () -> java.util.concurrent.ThreadLocalRandom.current().nextLong())),
        new ScanLimits(64, 64), _ -> true);
    private NativeExamples() { }

    public static void declareMachines(MachineTypeRegistrationEvent event) {
        for (boolean cross : new boolean[]{false, true}) {
            String name = cross ? "native_data_cross" : "native_data_fixed";
            var builder = MachineType.builder(NativeRunCases.id(name), cross ? Text.Cross : Text.Fixed, NativeRunCases.id("native_shape"))
                .component(DataComponents.MACHINE).component(NativeRunCases.MACHINE_TRACE)
                .component(MachineWorkSlots.TYPE).component(SCAN).recipePool(POOL)
                .attribute(StandardMachineAttributes.PROCESS_PARALLELISM, 4)
                .attribute(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 4);
            if (!cross) for (int lane = 1; lane <= 4; lane++) builder.workSlot(new WorkSlotDefinition("lane_" + lane, Set.of(NativeRunCases.id("native_lane_" + lane))));
            event.registry().register(builder.build());
        }
    }
    public static void registerRecipes(RecipeRegistrationEvent event) {
        var starts = List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.REDSTONE, Items.EMERALD);
        var recipes = new ObjectArrayList<MachineRecipe>();
        for (int lane = 1; lane <= 4; lane++) {
            var id = NativeRunCases.id("native_cycle_" + lane);
            var io = new ResourceRequirement(List.of(item(new ItemStack(starts.get(lane - 1)), 1)),
                List.of(item(new ItemStack(Items.COAL), 1), new GenericStack(Objects.requireNonNull(AEFluidKey.of(FluidRegistry.WATER)), 5)),
                List.of(item(new ItemStack(Items.IRON_NUGGET), 1)), List.of(item(new ItemStack(Items.DIAMOND), lane)), false);
            var recipe = new MachineRecipe(id, 400, List.of(
                RecipeRequirements.RESOURCES.requirement(NativeRunCases.id("native_io_" + lane), io),
                RecipeRequirements.ENERGY.requirement(NativeRunCases.id("native_fe_" + lane), new EnergyRequirement(0, 4, 0, 0, false))),
                Set.of(NativeRunCases.id("native_lane_" + lane)), 2, RecipeParallelismSelection.fixed(1));
            recipes.add(recipe);
            event.registry().declareRevision(recipe, "native-v1");
        }
        var weighted = new ResourceRequirement(List.of(item(new ItemStack(Blocks.GRAVEL), 1)), List.of(), List.of(), List.of(),
            false, ResourceChance.ALWAYS, new RandomOutputPool(List.of(
                new WeightedSampling.Weighted<>(item(new ItemStack(Items.FLINT), 1), 80),
                new WeightedSampling.Weighted<>(item(new ItemStack(Items.IRON_NUGGET), 1), 15),
                new WeightedSampling.Weighted<>(item(new ItemStack(Items.GOLD_NUGGET), 1), 5)), 1));
        var weightedRecipe = new MachineRecipe(NativeRunCases.id("native_weighted"), 400, List.of(
            RecipeRequirements.RESOURCES.requirement(NativeRunCases.id("native_weighted_io"), weighted)),
            Set.of(NativeRunCases.id("native_lane_1")), 1, RecipeParallelismSelection.fixed(1));
        var chanceRecipe = new MachineRecipe(NativeRunCases.id("native_chance"), 400, List.of(
            RecipeRequirements.RESOURCES.requirement(NativeRunCases.id("native_chance_input"), new ResourceRequirement(
                List.of(item(new ItemStack(Items.FLINT), 1), new GenericStack(Objects.requireNonNull(AEFluidKey.of(FluidRegistry.WATER)), 1000)), List.of())),
            RecipeRequirements.RESOURCES.requirement(NativeRunCases.id("native_chance_output"), new ResourceRequirement(
                List.of(), List.of(), List.of(), List.of(item(new ItemStack(Items.DIAMOND), 1)), false, new ResourceChance(25, null), null))),
            Set.of(NativeRunCases.id("native_lane_1")), 1, RecipeParallelismSelection.fixed(1));
        recipes.add(weightedRecipe); recipes.add(chanceRecipe);
        event.registry().declareRevision(weightedRecipe, "native-v1");
        event.registry().declareRevision(chanceRecipe, "native-v1");
        event.registry().register(new DefaultRecipePool(POOL, Text.Pool.getTranslationKey(), recipes));
    }
    public static void registerRuntime(MachineRuntimeRegistrationEvent event) {
        var predicates = new Object2ObjectLinkedOpenHashMap<BlockPos, StructurePredicate>();
        var cells = structure("native_data_fixed");
        Objects.requireNonNull(cells).forEach((position, block) -> {
            if (!position.equals(BlockPos.ORIGIN)) predicates.put(position, StructurePredicates.blocks(List.of(block)));
        });
        event.definition(new StructureDefinition(TemplateStructure.create(NativeRunCases.id("native_shape"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(predicates), List.of(), List.of()), Text.Structure.getTranslationKey(), Map.of()));
        for (String name : List.of("native_data_fixed", "native_data_cross")) {
            var type = Objects.requireNonNull(event.registry().get(NativeRunCases.id(name)));
            var codecs = new ObjectArrayList<RecipeProcessNbt>();
            for (var recipe : Objects.requireNonNull(RecipeRegistry.INSTANCE.get(POOL)).recipes()) codecs.add(
                RecipeProcessNbt.builder(recipe, "native-v1").component(DataComponents.PROCESS, DataComponents.PROCESS_CODEC)
                    .component(NativeRunCases.PROCESS_TRACE, NativeRunCases.PROCESS_TRACE_CODEC).build());
            var assembly = MachineAssembly.builder(type, "native-v1")
                .component(DataComponents.MACHINE, DataComponents.MACHINE_CODEC)
                .workSlots(new MachineWorkSlotsNbt("native-v1", codecs)).recreated(NativeRunCases.MACHINE_TRACE, SCAN);
            if (name.endsWith("cross")) assembly.instanceFactory(CrossRecipeController::new);
            else assembly.instanceFactory(MultithreadController::new);
            event.assembly(assembly.build());
        }
    }
    public static @Nullable Map<BlockPos, Block> structure(String name) {
        var id = new ResourceLocation(name.contains(":") ? name : "pe_run_tests:" + name);
        if (!id.getNamespace().equals("pe_run_tests") || !List.of("native_data_fixed", "native_data_cross").contains(id.getPath())) return null;
        var cells = new Object2ObjectLinkedOpenHashMap<BlockPos, Block>();
        cells.put(new BlockPos(-1, 0, 0), block("prototype_eng:input_hatch_10"));
        cells.put(new BlockPos(1, 0, 0), block("prototype_eng:output_hatch_10"));
        cells.put(new BlockPos(0, 1, 0), Blocks.IRON_BLOCK);
        cells.put(new BlockPos(0, 0, 1), block("prototype_eng:energy_input_hatch_4"));
        cells.put(BlockPos.ORIGIN, block(id + "_controller"));
        return cells;
    }
    private static Block block(String id) { return Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id)), id); }
    private static GenericStack item(ItemStack stack, long amount) { return new GenericStack(Objects.requireNonNull(AEItemKey.of(stack)), amount); }
}
