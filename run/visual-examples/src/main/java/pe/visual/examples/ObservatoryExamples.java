package pe.visual.examples;

import ae2.api.orientation.BlockOrientation;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.structure.StructureConstructionRegistrationEvent;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureDefinitions;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockPrismarine;
import net.minecraft.block.BlockQuartz;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.BlockStone;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.EnumMap;
import java.util.List;

@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
public final class ObservatoryExamples {
    private static final ResourceLocation ANCHOR_ID = VisualExamples.id("observatory_anchor");
    private static final ResourceLocation SHAPE_ID = VisualExamples.id("observatory");
    private static final LocalizationEnum NAME = () -> "pe_visual_examples.observatory";
    private static final Block ANCHOR = new Anchor();
    private static final Item ANCHOR_ITEM = new ItemBlock(ANCHOR).setRegistryName(ANCHOR_ID);
    private static final BlockPos2ObjectMap<ObservatoryLayout.Surface> CELLS = ObservatoryLayout.build();

    private ObservatoryExamples() {
    }

    static Item anchorItem() {
        return ANCHOR_ITEM;
    }

    @SubscribeEvent
    public static void registerBlock(RegistryEvent.Register<Block> event) {
        event.getRegistry().register(ANCHOR);
    }

    @SubscribeEvent
    public static void registerItem(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(ANCHOR_ITEM);
    }

    @SubscribeEvent
    public static void structure(MachineRuntimeRegistrationEvent event) {
        var materials = new EnumMap<ObservatoryLayout.Surface, StructurePredicate>(ObservatoryLayout.Surface.class);
        for (var material : ObservatoryLayout.Surface.values())
            materials.put(material, StructurePredicates.state(MaterialStates.STATES.get(material)));
        var predicates = new BlockPos2ObjectMap<StructurePredicate>(CELLS.size());
        for (var iterator = CELLS.fastIterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            predicates.put(entry.getLongKey(), materials.get(entry.getValue()));
        }
        var shape = StructureDefinitions.template(SHAPE_ID, BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(predicates), List.of(), List.of());
        event.definition(new StructureDefinition(shape, NAME.getTranslationKey(), BlockPos2ObjectMap.emptyMap()));
    }

    @SubscribeEvent
    public static void construction(StructureConstructionRegistrationEvent event) {
        event.registry().register(ANCHOR.getDefaultState(), SHAPE_ID, NAME, _ -> BlockOrientation.NORTH_UP,
            ObservatoryExamples::formed);
    }

    private static boolean formed(IBlockState state, TileEntity tile, World world, BlockPos origin) {
        var pos = new BlockPos.MutableBlockPos();
        if (!matches(world, pos, origin, ObservatoryLayout.DOME_TIP, CELLS.get(ObservatoryLayout.DOME_TIP))) return false;
        for (var iterator = CELLS.fastIterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (!matches(world, pos, origin, entry.getLongKey(), entry.getValue())) return false;
        }
        return true;
    }

    private static boolean matches(World world, BlockPos.MutableBlockPos pos, BlockPos origin, long cell,
                                   ObservatoryLayout.Surface material) {
        pos.setPos(origin.getX() + BlockPos2ObjectMap.unpackX(cell), origin.getY() + BlockPos2ObjectMap.unpackY(cell),
            origin.getZ() + BlockPos2ObjectMap.unpackZ(cell));
        return world.isBlockLoaded(pos, false) && world.getBlockState(pos) == MaterialStates.STATES.get(material);
    }

    private static final class MaterialStates {
        private static final EnumMap<ObservatoryLayout.Surface, IBlockState> STATES = new EnumMap<>(ObservatoryLayout.Surface.class);

        static {
            for (var material : ObservatoryLayout.Surface.values()) STATES.put(material, state(material));
        }
    }

    private static IBlockState state(ObservatoryLayout.Surface material) {
        return switch (material) {
            case STONE_BRICK -> Blocks.STONEBRICK.getDefaultState();
            case ANDESITE -> Blocks.STONE.getDefaultState().withProperty(BlockStone.VARIANT, BlockStone.EnumType.ANDESITE_SMOOTH);
            case QUARTZ -> Blocks.QUARTZ_BLOCK.getDefaultState();
            case CARVED_QUARTZ -> Blocks.QUARTZ_BLOCK.getDefaultState().withProperty(BlockQuartz.VARIANT, BlockQuartz.EnumType.CHISELED);
            case PRISMARINE -> Blocks.PRISMARINE.getDefaultState().withProperty(BlockPrismarine.VARIANT, BlockPrismarine.EnumType.BRICKS);
            case DARK_PRISMARINE -> Blocks.PRISMARINE.getDefaultState().withProperty(BlockPrismarine.VARIANT, BlockPrismarine.EnumType.DARK);
            case BLUE_GLASS -> glass(EnumDyeColor.BLUE);
            case CYAN_GLASS -> glass(EnumDyeColor.CYAN);
            case SKY_GLASS -> glass(EnumDyeColor.LIGHT_BLUE);
            case SEA_LANTERN -> Blocks.SEA_LANTERN.getDefaultState();
            case GOLD -> Blocks.GOLD_BLOCK.getDefaultState();
            case GLOWSTONE -> Blocks.GLOWSTONE.getDefaultState();
        };
    }

    private static IBlockState glass(EnumDyeColor color) {
        return Blocks.STAINED_GLASS.getDefaultState().withProperty(BlockStainedGlass.COLOR, color);
    }

    private static final class Anchor extends Block {
        private Anchor() {
            super(Material.ROCK);
            setRegistryName(ANCHOR_ID);
            setTranslationKey("pe_visual_examples.observatory_anchor");
            setCreativeTab(CreativeTabs.BUILDING_BLOCKS);
            setSoundType(SoundType.STONE);
            setHardness(1.8F);
        }
    }

    @Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID, value = Side.CLIENT)
    public static final class ClientModels {
        private ClientModels() {
        }

        @SubscribeEvent
        public static void models(ModelRegistryEvent event) {
            ModelLoader.setCustomModelResourceLocation(ANCHOR_ITEM, 0, new ModelResourceLocation(ANCHOR_ID, "inventory"));
        }
    }
}
