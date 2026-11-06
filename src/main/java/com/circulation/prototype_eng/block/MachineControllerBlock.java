package com.circulation.prototype_eng.block;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.IOrientableBlock;
import ae2.api.orientation.IOrientationStrategy;
import ae2.api.orientation.OrientationStrategies;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.machine.ControllerOrientation;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDirectional;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** One physical controller per downstream declaration; no built-in gameplay machine is registered. */
@SuppressWarnings("deprecation") // Forge 1.12 block-state hooks remain the actual dispatch points.
public final class MachineControllerBlock extends Block implements IOrientableBlock {
    public static final PropertyBool FORMED = PropertyBool.create("formed");
    private final MachineType type;

    public MachineControllerBlock(MachineType type) {
        super(Material.IRON);
        this.type = Objects.requireNonNull(type);
        setRegistryName(type.id().getNamespace(), type.id().getPath() + "_controller");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(10.0F);
        setResistance(20.0F);
        setHarvestLevel("pickaxe", 1);
        setSoundType(SoundType.METAL);
        setDefaultState(state(blockState.getBaseState(), BlockOrientation.NORTH_UP).withProperty(FORMED, false));
    }

    public MachineType type() { return type; }
    @Override public @NotNull String getTranslationKey() { return type.displayName().getTranslationKey(); }
    @Override public @NotNull String getLocalizedName() { return type.displayName().text().getUnformattedText(); }
    @Override public IOrientationStrategy getOrientationStrategy() { return OrientationStrategies.full(); }

    public IBlockState state(IBlockState state, BlockOrientation orientation) {
        return getOrientationStrategy().setOrientation(state, orientation.getSide(RelativeSide.FRONT), orientation.getSpin());
    }

    @Override protected @NotNull BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, BlockDirectional.FACING, IOrientationStrategy.SPIN, FORMED);
    }
    @Override public @NotNull IBlockState getStateFromMeta(int meta) {
        return state(getDefaultState(), BlockOrientation.get(EnumFacing.byIndex(meta & 7)));
    }
    @Override public int getMetaFromState(IBlockState state) { return state.getValue(BlockDirectional.FACING).getIndex(); }
    @Override public boolean hasTileEntity(@NotNull IBlockState state) { return true; }
    @Override public TileEntity createTileEntity(@NotNull World world, @NotNull IBlockState state) {
        return new MachineControllerBlockEntity(type.id(), getOrientation(state));
    }

    @Override public @NotNull IBlockState getStateForPlacement(@NotNull World world, @NotNull BlockPos pos, @NotNull EnumFacing facing,
            float hitX, float hitY, float hitZ, int meta, @NotNull EntityLivingBase placer, @NotNull EnumHand hand) {
        // PM's vertical placement behavior is the vanilla six-facing piston rule.
        return state(getDefaultState(), BlockOrientation.get(EnumFacing.getDirectionFromEntityLiving(pos, placer)));
    }
    @Override public void onBlockPlacedBy(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state,
            @NotNull EntityLivingBase placer, @NotNull ItemStack stack) {
        if (!world.isRemote && world.getTileEntity(pos) instanceof MachineControllerBlockEntity tile) { tile.placedBy(placer); }
    }
    @Override public @NotNull IBlockState getActualState(@NotNull IBlockState state, IBlockAccess world, @NotNull BlockPos pos) {
        return world.getTileEntity(pos) instanceof MachineControllerBlockEntity tile
            ? state(state, tile.orientation()).withProperty(FORMED, tile.formed()) : state;
    }
    @Override public @NotNull IBlockState withRotation(@NotNull IBlockState state, @NotNull Rotation rotation) {
        return state(state, ControllerOrientation.rotate(getOrientation(state), rotation));
    }
    @Override public @NotNull IBlockState withMirror(@NotNull IBlockState state, @NotNull Mirror mirror) {
        return state(state, ControllerOrientation.mirror(getOrientation(state), mirror));
    }
    @Override public boolean rotateBlock(World world, @NotNull BlockPos pos, @NotNull EnumFacing axis) {
        if (world.isRemote || !(world.getTileEntity(pos) instanceof MachineControllerBlockEntity tile)) { return false; }
        tile.rotateAround(axis);
        return true;
    }
    @Override public void breakBlock(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state) {
        try {
            if (!world.isRemote && world.getTileEntity(pos) instanceof MachineControllerBlockEntity tile) { tile.controllerBroken(); }
        } finally { super.breakBlock(world, pos, state); }
    }
}
