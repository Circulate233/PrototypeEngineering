package pe.visual.examples.run;

import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.block.AEBaseBlockItem;
import ae2.block.AEBaseTileBlock;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.data.DataComponents;
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
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.structure.TemplateStructure;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.common.registry.GameRegistry;
import pe.visual.examples.VisualExamples;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import java.util.Map;
import java.util.Objects;

/** World-facing external hatch fixture, independent of PE's built-in tiered hatch entities. */
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
public final class ExternalHatchExamples {
    public enum Text implements LocalizationEnum {
        Machine, Pool;
        @Override public String getTranslationKey() { return "pe_run_tests.external." + name().toLowerCase(Locale.ROOT); }
    }
    public static final ResourceLocation BLOCK_ID = NativeRunCases.id("external_filtered_hatch");
    private static final ResourceLocation MACHINE_ID = NativeRunCases.id("external_filter");
    private static final ResourceLocation POOL_ID = NativeRunCases.id("external_pool");
    private static final FilteredBlock BLOCK = new FilteredBlock();
    private static final ComponentType<MachineInstance, RecipeScanner> SCAN = RecipeScanning.component(
        NativeRunCases.id("external_scan"), host -> NativeRunCases.decorate(host.owner().createRecipeScanner(host,
            RecipeRegistry.INSTANCE.catalog(), () -> java.util.concurrent.ThreadLocalRandom.current().nextLong())),
        new ScanLimits(16, 16), _ -> true);
    private ExternalHatchExamples() { }

    @SubscribeEvent public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().register(BLOCK);
        GameRegistry.registerTileEntity(ExternalFilteredHatch.class, BLOCK_ID);
    }
    @SubscribeEvent public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(new AEBaseBlockItem(BLOCK).setRegistryName(BLOCK_ID));
    }
    public static void declareMachines(MachineTypeRegistrationEvent event) {
        event.registry().register(MachineType.builder(MACHINE_ID, Text.Machine, NativeRunCases.id("external_shape"))
            .component(DataComponents.MACHINE).component(NativeRunCases.MACHINE_TRACE)
            .component(MachineWorkSlots.TYPE).component(SCAN).recipePool(POOL_ID).workSlot(new WorkSlotDefinition("external"))
            .attribute(StandardMachineAttributes.PROCESS_PARALLELISM, 1)
            .attribute(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1).build());
    }
    public static void registerRecipes(RecipeRegistrationEvent event) {
        var recipe = new MachineRecipe(NativeRunCases.id("external_exchange"), 80, List.of(
            RecipeRequirements.RESOURCES.requirement(NativeRunCases.id("external_io"), new ResourceRequirement(
                List.of(item(Items.IRON_INGOT, 1), water(250)), List.of(item(Items.GOLD_NUGGET, 2), water(50))))));
        event.registry().register(new DefaultRecipePool(POOL_ID, Text.Pool.getTranslationKey(), List.of(recipe)));
        event.registry().declareRevision(recipe, "external-v1");
    }
    public static void registerRuntime(MachineRuntimeRegistrationEvent event) {
        var cells = new Object2ObjectLinkedOpenHashMap<BlockPos, StructurePredicate>();
        cells.put(new BlockPos(-1, 0, 0), StructurePredicates.blocks(List.of(BLOCK)));
        cells.put(new BlockPos(0, 1, 0), StructurePredicates.blocks(List.of(Blocks.IRON_BLOCK)));
        event.definition(new StructureDefinition(TemplateStructure.create(NativeRunCases.id("external_shape"), BlockOrientation.NORTH_UP,
            BlockPos.ORIGIN, false, new StructurePattern(cells), List.of(), List.of()), Text.Machine.getTranslationKey(), Map.of()));
        var type = Objects.requireNonNull(event.registry().get(MACHINE_ID));
        var recipe = Objects.requireNonNull(RecipeRegistry.INSTANCE.get(POOL_ID)).recipes().getFirst();
        var codec = RecipeProcessNbt.builder(recipe, "external-v1").component(DataComponents.PROCESS, DataComponents.PROCESS_CODEC)
            .component(NativeRunCases.PROCESS_TRACE, NativeRunCases.PROCESS_TRACE_CODEC).build();
        event.assembly(MachineAssembly.builder(type, "external-v1").instanceFactory(MultithreadController::new)
            .component(DataComponents.MACHINE, DataComponents.MACHINE_CODEC).recreated(NativeRunCases.MACHINE_TRACE, SCAN)
            .workSlots(new MachineWorkSlotsNbt("external-v1", List.of(codec))).build());
    }
    public static @Nullable Map<BlockPos, Block> placement(String machine) {
        var id = new ResourceLocation(machine.contains(":") ? machine : "pe_run_tests:" + machine);
        if (!id.equals(MACHINE_ID)) return null;
        var result = new Object2ObjectLinkedOpenHashMap<BlockPos, Block>();
        result.put(new BlockPos(-1, 0, 0), BLOCK);
        result.put(new BlockPos(0, 1, 0), Blocks.IRON_BLOCK);
        result.put(BlockPos.ORIGIN, Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id + "_controller"))));
        return result;
    }
    private static GenericStack item(Item item, long amount) { return new GenericStack(Objects.requireNonNull(AEItemKey.of(new ItemStack(item))), amount); }
    private static GenericStack water(long amount) { return new GenericStack(Objects.requireNonNull(AEFluidKey.of(FluidRegistry.WATER)), amount); }

    private static final class FilteredBlock extends AEBaseTileBlock<ExternalFilteredHatch> {
        private FilteredBlock() {
            super(Material.IRON);
            setRegistryName(BLOCK_ID); setTranslationKey("pe_run_tests.external_filtered_hatch");
            setTileEntity(ExternalFilteredHatch.class); setHardness(2); setResistance(10);
            setSoundType(SoundType.METAL); setCreativeTab(CreativeTabs.REDSTONE);
        }
        @Override public ExternalFilteredHatch createTileEntity(World world, IBlockState state) { return new ExternalFilteredHatch(); }
        @Override public boolean hasTileEntity(IBlockState state) { return true; }
        @Override public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                EnumHand hand, EnumFacing face, float hitX, float hitY, float hitZ) {
            if (player.isSneaking()) return false;
            if (!world.isRemote) player.openGui(PrototypeEngineering.instance, 0, world, pos.getX(), pos.getY(), pos.getZ());
            return true;
        }
    }
}
