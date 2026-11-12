package com.circulation.prototype_eng.block;

import ae2.api.orientation.IOrientationStrategy;
import ae2.api.orientation.OrientationStrategies;
import ae2.block.AEBaseBlock;
import com.circulation.prototype_eng.Tags;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class DecorativeBlocks {
    private static final DecorativeBlock[] BLOCKS = createBlocks();

    private DecorativeBlocks() {
    }

    private static DecorativeBlock[] createBlocks() {
        return new DecorativeBlock[] {
            create("block_casing", false),
            create("block_casing_plain", false),
            create("casing", false),
            create("casing_frame", false),
            create("cartesian_glass", true),
            create("pure_cartesian_glass", true),
            create("rounded_glass", true),
            create("pure_rounded_glass", true),
            create("slanted_glass", true),
            create("pure_slanted_glass", true),
            new CircuitBlock(),
            new VentilationBlock("ventilation_port"),
            new VentilationBlock("ventilation_port_cover"),
            new VentilationBlock("ventilation_port_horizontal_cover"),
            new VentilationBlock("ventilation_port_vertical_cover")
        };
    }

    private static DecorativeBlock create(String name, boolean glass) {
        return new DecorativeBlock(name, glass);
    }

    public static Block[] all() {
        return BLOCKS.clone();
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().registerAll(BLOCKS);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        for (var block : BLOCKS) {
            event.getRegistry().register(new PrototypeBlockItem(block).setRegistryName(Objects.requireNonNull(block.getRegistryName())));
        }
    }

    @SuppressWarnings("deprecation") // Forge 1.12 dispatches these block-state hooks.
    private static class DecorativeBlock extends AEBaseBlock {
        private final boolean glass;
        private DecorativeBlock(String name, boolean glassBlock) {
            super(glassBlock ? Material.GLASS : Material.IRON);
            this.glass = glassBlock;
            setRegistryName(new ResourceLocation(Tags.MOD_ID, name));
            setTranslationKey(Tags.MOD_ID + "." + name);
            setCreativeTab(CreativeTabs.REDSTONE);
            setSoundType(glassBlock ? SoundType.GLASS : SoundType.METAL);
            setHardness(glassBlock ? 0.3F : 1.5F);
            setResistance(glassBlock ? 1.5F : 6.0F);
            if (glassBlock) {
                setOpaque();
                setLightOpacity(0);
            }
            else setHarvestLevel("pickaxe", 0);
        }

        @Override
        @SideOnly(Side.CLIENT)
        public boolean shouldSideBeRendered(@NotNull IBlockState state, @NotNull IBlockAccess world,
                                            @NotNull BlockPos pos, @NotNull EnumFacing side) {
            return (!glass || world.getBlockState(pos.offset(side)).getBlock() != this)
                && super.shouldSideBeRendered(state, world, pos, side);
        }

        @Override
        @SideOnly(Side.CLIENT)
        public @NotNull BlockRenderLayer getRenderLayer() {
            return glass ? BlockRenderLayer.TRANSLUCENT : BlockRenderLayer.SOLID;
        }
    }

    private static final class CircuitBlock extends DecorativeBlock {
        private CircuitBlock() {
            super("block_control_circuit_1", false);
        }

        @Override
        @SideOnly(Side.CLIENT)
        public @NotNull BlockRenderLayer getRenderLayer() {
            return BlockRenderLayer.CUTOUT;
        }
    }

    private static final class VentilationBlock extends DecorativeBlock {
        private VentilationBlock(String name) {
            super(name, false);
        }

        @Override
        public IOrientationStrategy getOrientationStrategy() {
            return OrientationStrategies.horizontalFacing();
        }
    }
}
