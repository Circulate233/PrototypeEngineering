package example;

import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.*;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.recipe.*;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.requirement.*;
import com.circulation.prototype_eng.api.recipe.scanning.*;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.structure.*;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Objects;

@Mod.EventBusSubscriber(modid = "example")
public final class ExampleMachines {
    private static final ResourceLocation POOL = id("smelting");
    private static final ResourceLocation SHAPE = id("smelter_shape");
    private static final ComponentType<MachineInstance, RecipeScanner> SCANNER =
        RecipeScanning.component(id("scanner"), RecipeRegistry.INSTANCE::catalog, new ScanLimits(32, 64));
    private static MachineType type;
    private static RecipeProcessNbt recipeCodec;

    private enum Text implements LocalizationEnum {
        MACHINE("example.machine.smelter"),
        POOL("example.recipe_pool.smelting"),
        SHAPE("example.structure.smelter"),
        CAP("example.structure.smelter_cap"),
        COLUMN("example.structure.smelter_column");

        private final String translationKey;

        Text(String translationKey) {
            this.translationKey = translationKey;
        }

        @Override
        public String getTranslationKey() {
            return translationKey;
        }
    }

    @SubscribeEvent
    public static void machines(MachineTypeRegistrationEvent event) {
        type = MachineType.builder(id("smelter"), Text.MACHINE, SHAPE)
                          .recipePool(POOL)
                          .component(MachineWorkSlots.TYPE)
                          .component(SCANNER)
                          .attribute(StandardMachineAttributes.PROCESS_PARALLELISM, 4)
                          .executionPolicy(ControllerExecutionPolicy.FULL_ASYNC)
                          .build();
        event.registry().register(type);
    }

    @SubscribeEvent
    public static void recipes(RecipeRegistrationEvent event) {
        var input = new GenericStack(AEItemKey.of(new ItemStack(Items.IRON_INGOT)), 2);
        var output = new GenericStack(AEItemKey.of(new ItemStack(Items.GOLD_INGOT)), 1);
        var io = RecipeRequirements.RESOURCES.requirement(id("items"),
            new ResourceRequirement(ObjectList.of(input), ObjectList.of(output)));
        var energy = RecipeRequirements.ENERGY.requirement(id("power"),
            new EnergyRequirement(0, 8, 0, 0, false));
        var recipe = new MachineRecipe(id("iron_to_gold"), 80, ObjectList.of(io, energy));
        event.registry().register(new DefaultRecipePool(POOL, Text.POOL.getTranslationKey(), ObjectList.of(recipe)));
        event.registry().declareRevision(recipe, "recipe-v1");
        recipeCodec = RecipeProcessNbt.builder(recipe, "recipe-v1").build();
    }

    @SubscribeEvent
    public static void runtime(MachineRuntimeRegistrationEvent event) {
        var cells = new BlockPos2ObjectMap<StructurePredicate>();
        cells.put(BlockPos2ObjectMap.toLong(-1, 0, 0), block("prototype_eng:input_hatch_1"));
        cells.put(BlockPos2ObjectMap.toLong(1, 0, 0), block("prototype_eng:output_hatch_1"));
        cells.put(BlockPos2ObjectMap.toLong(0, 0, 1), block("prototype_eng:energy_input_hatch_1"));
        var capCells = new BlockPos2ObjectMap<StructurePredicate>(1);
        capCells.put(BlockPos.ORIGIN, block("minecraft:gold_block"));
        var columnCells = new BlockPos2ObjectMap<StructurePredicate>(1);
        columnCells.put(BlockPos.ORIGIN, block("minecraft:iron_block"));
        var cap = StructureDefinitions.template(id("smelter_cap"), BlockOrientation.NORTH_UP,
            new BlockPos(0, 1, 0), false,
            new StructurePattern(capCells),
            ObjectList.of(), ObjectList.of());
        var column = StructureDefinitions.slice(id("smelter_column"), BlockOrientation.NORTH_UP,
            new BlockPos(0, 1, 0), false,
            new StructurePattern(columnCells), 1, 4,
            new BlockPos(0, 1, 0), ObjectList.of(), ObjectList.of(cap));
        var shape = StructureDefinitions.template(SHAPE, BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(cells), ObjectList.of(), ObjectList.of(column));
        event.definition(new StructureDefinition(cap, Text.CAP.getTranslationKey(), BlockPos2ObjectMap.emptyMap()));
        event.definition(new StructureDefinition(column, Text.COLUMN.getTranslationKey(), BlockPos2ObjectMap.emptyMap()));
        event.definition(new StructureDefinition(shape, Text.SHAPE.getTranslationKey(), BlockPos2ObjectMap.emptyMap()));
        var assembly = MachineAssembly.builder(type, "machine-v1")
                                      .instanceFactory(MultithreadController::new)
                                      .workSlots(new MachineWorkSlotsNbt("slots-v1", ObjectList.of(recipeCodec)))
                                      .recreated(SCANNER)
                                      .build();
        event.assembly(assembly);
    }

    private static StructurePredicate block(String id) {
        var block = Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id)), id);
        return StructurePredicates.state(block.getDefaultState());
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("example", path);
    }
}
