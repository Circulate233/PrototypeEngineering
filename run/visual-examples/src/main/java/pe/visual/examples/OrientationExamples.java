package pe.visual.examples;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.structure.StructureAnchorRotation;
import com.circulation.prototype_eng.api.structure.StructureConstructionRegistrationEvent;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureDefinitions;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.List;

@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
public final class OrientationExamples {
    private static final ResourceLocation ANCHOR_ID = VisualExamples.id("orientation_beacon");
    private static final ResourceLocation SHAPE_ID = VisualExamples.id("orientation_beacon_structure");
    private static final Block ANCHOR = new Beacon();
    private static final Item ANCHOR_ITEM = new ItemBlock(ANCHOR).setRegistryName(ANCHOR_ID);
    private static final StructureAnchorRotation ROTATION = StructureAnchorRotation.fourWay(BlockHorizontal.FACING);

    private OrientationExamples() {
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
        var pattern = new BlockPos2ObjectMap<StructurePredicate>(Layout.CELLS.size());
        for (int i = 0; i < Layout.CELLS.size(); i++) {
            var cell = Layout.CELLS.get(i);
            pattern.put(cell.offset, StructurePredicates.state(cell.state));
        }
        var shape = StructureDefinitions.template(SHAPE_ID, BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(pattern), List.of(), List.of());
        event.definition(new StructureDefinition(shape, ExampleText.OrientationBeacon.getTranslationKey(), BlockPos2ObjectMap.emptyMap()));
    }

    @SubscribeEvent
    public static void construction(StructureConstructionRegistrationEvent event) {
        event.registry().register(ANCHOR.getDefaultState(), SHAPE_ID, ExampleText.OrientationBeacon, ROTATION, (state, tile, world, origin) -> {
            var rotation = StructureTransform.of(ROTATION.read(state, tile));
            for (int i = 0; i < Layout.CELLS.size(); i++) {
                var cell = Layout.CELLS.get(i);
                var position = rotation.applyAt(origin, cell.offset);
                if (!world.isBlockLoaded(position, false) || world.getBlockState(position) != cell.state) return false;
            }
            return true;
        });
    }

    private record Cell(BlockPos offset, IBlockState state) {
    }

    private static final class Layout {
        private static final List<Cell> CELLS = List.of(
            new Cell(new BlockPos(0, 0, -1), Blocks.PRISMARINE.getDefaultState()),
            new Cell(new BlockPos(0, 0, -2), Blocks.GOLD_BLOCK.getDefaultState()),
            new Cell(new BlockPos(1, 0, -1), Blocks.QUARTZ_BLOCK.getDefaultState()),
            new Cell(new BlockPos(2, 0, -1), Blocks.QUARTZ_BLOCK.getDefaultState()),
            new Cell(new BlockPos(2, 1, -1), Blocks.SEA_LANTERN.getDefaultState()),
            new Cell(new BlockPos(0, 0, 1), Blocks.PRISMARINE.getDefaultState()));
    }

    private static final class Beacon extends BlockHorizontal {
        private Beacon() {
            super(Material.ROCK);
            setRegistryName(ANCHOR_ID);
            setTranslationKey("pe_visual_examples.orientation_beacon");
            setCreativeTab(CreativeTabs.BUILDING_BLOCKS);
            setSoundType(SoundType.STONE);
            setHardness(1.8F);
            setDefaultState(blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, FACING);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(FACING, EnumFacing.byHorizontalIndex(meta));
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(FACING).getHorizontalIndex();
        }

        @Override
        public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing side, float hitX, float hitY, float hitZ,
                                                int meta, EntityLivingBase placer, EnumHand hand) {
            return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
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
