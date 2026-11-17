package pe.visual.examples;

import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
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
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import pe.visual.examples.run.NativeExamples;
import pe.visual.examples.run.ExternalHatchExamples;
import pe.visual.examples.run.RunTestsCommand;
import pe.visual.examples.run.RunNetworkRender;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.block.HatchBlocks;
import com.circulation.prototype_eng.block.ResourceHatchBlock;
import com.circulation.prototype_eng.block.EnergyHatchBlock;
import com.circulation.prototype_eng.structure.TemplateStructure;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.init.Blocks;
import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Development-only content. The PE production library remains content-free.
 */
@Mod(modid = VisualExamples.MOD_ID, name = "PE Visual Examples", version = "1.0.0-dev", dependencies = "required-after:prototype_eng")
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
public final class VisualExamples {
    public static final String MOD_ID = "pe_visual_examples";
    public static final ResourceLocation PRESS_POOL = id("press_pool");
    public static final ResourceLocation MIXER_POOL = id("mixer_pool");
    public static final ResourceLocation SORTER_POOL = id("sorter_pool");
    private static final ComponentType<MachineInstance, RecipeScanner> PRESS_SCAN = scanner("press_scan");
    private static final ComponentType<MachineInstance, RecipeScanner> MIXER_SCAN = scanner("mixer_scan");
    private static final ComponentType<MachineInstance, RecipeScanner> SORTER_SCAN = scanner("sorter_scan");

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    static ComponentType<MachineInstance, RecipeScanner> scanner(String name) {
        return RecipeScanning.component(id(name), RecipeRegistry.INSTANCE::catalog, new ScanLimits(16, 16));
    }

    @SubscribeEvent
    public static void declareMachines(MachineTypeRegistrationEvent event) {
        event.registry().register(machine("press", ExampleText.Press, PRESS_POOL, PRESS_SCAN, false));
        event.registry().register(machine("mixer", ExampleText.Mixer, MIXER_POOL, MIXER_SCAN, true));
        event.registry().register(machine("sorter", ExampleText.Sorter, SORTER_POOL, SORTER_SCAN, true));
        BoundaryExamples.declareMachines(event);
        NativeExamples.declareMachines(event);
        ExternalHatchExamples.declareMachines(event);
        CtmGalleryExamples.declareMachines(event);
    }

    private static MachineType machine(String name, ExampleText text, ResourceLocation pool,
                                       ComponentType<MachineInstance, RecipeScanner> scan, boolean dynamic) {
        var builder = MachineType.builder(id(name), text, id(name + "_structure"))
                                 .component(MachineWorkSlots.TYPE).component(scan).recipePool(pool)
                                 .attribute(StandardMachineAttributes.PROCESS_PARALLELISM, 1)
                                 .attribute(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
        if (!dynamic) builder.workSlot(new WorkSlotDefinition("main"));
        return builder.build();
    }

    @SubscribeEvent
    public static void registerRecipes(RecipeRegistrationEvent event) {
        var press = new ObjectArrayList<SimpleRecipePool.Entry>();
        press.add(simple("crush_stone", 40, new ItemStack(Blocks.COBBLESTONE), 2, new ItemStack(Blocks.SAND), 1));
        press.add(simple("cut_iron", 60, new ItemStack(Items.IRON_INGOT), 1, new ItemStack(Items.IRON_NUGGET), 9));
        press.add(simple("saw_log", 20, new ItemStack(Blocks.LOG), 1, new ItemStack(Blocks.PLANKS), 4));
        registerPool(event, new SimpleRecipePool(PRESS_POOL, ExampleText.PoolPress.getTranslationKey(), press));
        var mix = new ObjectArrayList<MachineRecipe>();
        mix.add(recipe("wet_clay", 60, List.of(item(new ItemStack(Blocks.SAND), 1), fluid(false, 250)),
            List.of(item(new ItemStack(Items.CLAY_BALL), 1)), 16));
        mix.add(recipe("lava_stone", 100, List.of(item(new ItemStack(Blocks.COBBLESTONE), 1), fluid(true, 250)),
            List.of(item(new ItemStack(Blocks.OBSIDIAN), 1)), 32));
        mix.add(recipe("water_transfer", 40, List.of(fluid(false, 1000)), List.of(fluid(false, 1000)), 8));
        registerPool(event, new DefaultRecipePool(MIXER_POOL, ExampleText.PoolMixer.getTranslationKey(), mix));
        var outputs = new RandomOutputPool(List.of(
            new WeightedSampling.Weighted<>(item(new ItemStack(Items.FLINT), 1), 80),
            new WeightedSampling.Weighted<>(item(new ItemStack(Items.IRON_NUGGET), 1), 15),
            new WeightedSampling.Weighted<>(item(new ItemStack(Items.GOLD_NUGGET), 1), 5)), 1);
        var weighted = new ResourceRequirement(List.of(item(new ItemStack(Blocks.GRAVEL), 1)), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS, outputs);
        var lottery = new MachineRecipe(id("gravel_sort"), 80, List.of(
            RecipeRequirements.RESOURCES.requirement(id("gravel_sort_io"), weighted),
            RecipeRequirements.ENERGY.requirement(id("gravel_sort_fe"), new EnergyRequirement(0, 16, 0, 0, false))));
        var lucky = new MachineRecipe(id("lucky_wash"), 100, List.of(
            RecipeRequirements.RESOURCES.requirement(id("lucky_input"), new ResourceRequirement(List.of(item(new ItemStack(Items.FLINT), 1), fluid(false, 1000)), List.of())),
            RecipeRequirements.RESOURCES.requirement(id("lucky_output"), new ResourceRequirement(List.of(), List.of(), List.of(),
                List.of(item(new ItemStack(Items.DIAMOND), 1)), false, new ResourceChance(25, null), null)),
            RecipeRequirements.ENERGY.requirement(id("lucky_fe"), new EnergyRequirement(0, 16, 0, 0, false))));
        registerPool(event, new DefaultRecipePool(SORTER_POOL, ExampleText.PoolSorter.getTranslationKey(), List.of(lottery, lucky)));
        BoundaryExamples.registerRecipes(event);
        NativeExamples.registerRecipes(event);
        ExternalHatchExamples.registerRecipes(event);
    }

    static void registerPool(RecipeRegistrationEvent event, RecipePool pool) {
        event.registry().register(pool);
        for (var recipe : pool.recipes()) event.registry().declareRevision(recipe, "visual-v1");
    }

    private static SimpleRecipePool.Entry simple(String name, int ticks, ItemStack in, long amount, ItemStack out, long output) {
        return SimpleRecipePool.Entry.create(id(name), ticks, item(in, amount), item(out, output), Set.of());
    }

    static GenericStack item(ItemStack stack, long amount) {
        return new GenericStack(AEItemKey.of(stack), amount);
    }

    static GenericStack fluid(boolean lava, long amount) {
        return new GenericStack(AEFluidKey.of(lava ? FluidRegistry.LAVA : FluidRegistry.WATER), amount);
    }

    private static MachineRecipe recipe(String name, int ticks, List<GenericStack> in, List<GenericStack> out, long energy) {
        return new MachineRecipe(id(name), ticks, List.of(
            RecipeRequirements.RESOURCES.requirement(id(name + "_io"), new ResourceRequirement(in, out)),
            RecipeRequirements.ENERGY.requirement(id(name + "_fe"), new EnergyRequirement(0, energy, 0, 0, false))));
    }

    @SubscribeEvent
    public static void registerRuntime(MachineRuntimeRegistrationEvent event) {
        install(event, "press", PRESS_POOL, PRESS_SCAN, false);
        install(event, "mixer", MIXER_POOL, MIXER_SCAN, true);
        install(event, "sorter", SORTER_POOL, SORTER_SCAN, true);
        BoundaryExamples.registerRuntime(event);
        NativeExamples.registerRuntime(event);
        ExternalHatchExamples.registerRuntime(event);
        CtmGalleryExamples.registerRuntime(event);
    }

    private static void install(MachineRuntimeRegistrationEvent event, String name, ResourceLocation poolId,
                                ComponentType<MachineInstance, RecipeScanner> scan, boolean energy) {
        install(event, name, poolId, scan, energy, energy, false);
    }

    static void install(MachineRuntimeRegistrationEvent event, String name, ResourceLocation poolId,
                        ComponentType<MachineInstance, RecipeScanner> scan, boolean energy, boolean cross, boolean large) {
        var cells = new BlockPos2ObjectMap<StructurePredicate>();
        var resource = new ObjectArrayList<Block>();
        var energies = new ObjectArrayList<Block>();
        for (var block : HatchBlocks.all()) {
            if (block instanceof ResourceHatchBlock hatch) resource.add(hatch);
            if (block instanceof EnergyHatchBlock hatch) energies.add(hatch);
        }
        for (var entries = structureBlocks(name, energy, large).fastIterator(); entries.hasNext(); ) {
            var cell = entries.next();
            var block = cell.getValue();
            cells.put(cell.getLongKey(), block instanceof ResourceHatchBlock ? StructurePredicates.blocks(resource)
                : block instanceof EnergyHatchBlock ? StructurePredicates.blocks(energies)
                : name.equals("boundary_structure") && block == Blocks.GLASS ? StructurePredicates.blocks(List.of(Blocks.GLASS, Blocks.STAINED_GLASS))
                : StructurePredicates.blocks(List.of(block)));
        }
        var type = Objects.requireNonNull(event.registry().get(id(name)));
        var structure = TemplateStructure.create(id(name + "_structure"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(cells), List.of(), List.of());
        event.definition(new StructureDefinition(structure, type.displayName().getTranslationKey(), BlockPos2ObjectMap.emptyMap()));
        RecipePool pool = Objects.requireNonNull(RecipeRegistry.INSTANCE.get(poolId));
        var codecs = new ObjectArrayList<RecipeProcessNbt>();
        for (var recipe : pool.recipes()) codecs.add(RecipeProcessNbt.builder(recipe, "visual-v1").build());
        var assembly = MachineAssembly.builder(type, "visual-v1").workSlots(new MachineWorkSlotsNbt("visual-v1", codecs)).recreated(scan);
        if (cross) assembly.instanceFactory(CrossRecipeController::new);
        else assembly.instanceFactory(MultithreadController::new);
        event.assembly(assembly.build());
    }

    /**
     * Concrete placeable representatives shared by the structure declaration and explicit build command.
     */
    static BlockPos2ObjectMap<Block> structureBlocks(String name, boolean energy, boolean large) {
        var cells = new BlockPos2ObjectMap<Block>();
        if (large) {
            for (int x = -3; x <= 3; x++)
                for (int y = 0; y <= 4; y++)
                    for (int z = 0; z <= 6; z++) {
                        int edges = (Math.abs(x) == 3 ? 1 : 0) + (y == 0 || y == 4 ? 1 : 0) + (z == 0 || z == 6 ? 1 : 0);
                        if (edges == 0 || x == 0 && y == 0 && z == 0) continue;
                        cells.put(new BlockPos(x, y, z), edges >= 2 ? Blocks.IRON_BLOCK : Blocks.GLASS);
                    }
        }
        int tier = name.startsWith("boundary_") ? 10 : 4;
        cells.put(new BlockPos(-1, 0, 0), block("prototype_eng:input_hatch_" + tier));
        cells.put(new BlockPos(1, 0, 0), block("prototype_eng:output_hatch_" + tier));
        if (!large) cells.put(new BlockPos(0, 1, 0), Blocks.IRON_BLOCK);
        if (energy) cells.put(new BlockPos(0, 0, 1), block("prototype_eng:energy_input_hatch_4"));
        if (name.equals("boundary_energy"))
            cells.put(new BlockPos(0, 0, 2), block("prototype_eng:energy_output_hatch_4"));
        return cells;
    }

    static Block block(String name) {
        return Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation(name)), name);
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void clientInit(FMLInitializationEvent event) {
        ExamplesClient.init();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new ExamplesCommand());
        event.registerServerCommand(new RunTestsCommand());
        event.registerServerCommand(new MEAssemblyExamples.Command());
        RunNetworkRender.registerUserCommands(event.getServer());
    }
}
