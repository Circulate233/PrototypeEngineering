package com.circulation.prototype_eng.block;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.IOrientationStrategy;
import ae2.api.orientation.OrientationStrategies;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.item.ControllerOrientationTool;
import com.circulation.prototype_eng.machine.ControllerOrientation;
import net.minecraft.block.BlockDirectional;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import ae2.block.AEBaseBlockItem;
import ae2.block.AEBaseTileBlock;
import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.machine.MachineControllerBlockHost;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import net.minecraft.util.ResourceLocation;

/** One physical controller per downstream declaration; no built-in gameplay machine is registered. */
@SuppressWarnings("deprecation") // Forge 1.12 block-state hooks remain the actual dispatch points.
public abstract class MachineControllerBlock<T extends AEBaseTile & MachineControllerHost>
    extends AEBaseTileBlock<T> implements MachineControllerBlockHost {
    public static final PropertyBool FORMED = PropertyBool.create("formed");
    private final MachineType type;

    /** Custom entity classes expose a public no-arg constructor and register their own Forge NBT ID. */
    protected MachineControllerBlock(MachineType type, ResourceLocation id, Class<T> tileClass) {
        super(Material.IRON);
        this.type = Objects.requireNonNull(type);
        setTileEntity(Objects.requireNonNull(tileClass));
        setRegistryName(Objects.requireNonNull(id));
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(10.0F);
        setResistance(20.0F);
        setHarvestLevel("pickaxe", 1);
        setSoundType(SoundType.METAL);
        setDefaultState(state(blockState.getBaseState(), BlockOrientation.NORTH_UP).withProperty(FORMED, false));
    }

    public MachineType type() { return type; }

    /** Override to supply an AE2S item subclass for this registered controller. */
    public AEBaseBlockItem createItem() {
        return new MachineControllerItem(this);
    }
    @Override public @NotNull String getTranslationKey() { return type.displayName().getTranslationKey(); }
    @Override public @NotNull String getLocalizedName() { return type.displayName().text().getUnformattedText(); }
    @Override public IOrientationStrategy getOrientationStrategy() { return OrientationStrategies.full(); }

    public IBlockState state(IBlockState state, BlockOrientation orientation) {
        return getOrientationStrategy().setOrientation(state, orientation.getSide(RelativeSide.FRONT), orientation.getSpin());
    }

    /** The registered controller's representative state, also used by the concrete structure preview. */
    public IBlockState previewState(BlockOrientation orientation, boolean formed) {
        return state(getDefaultState(), orientation).withProperty(FORMED, formed);
    }

    @Override protected @NotNull BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, BlockDirectional.FACING, IOrientationStrategy.SPIN, FORMED);
    }
    @Override public @NotNull IBlockState getStateFromMeta(int meta) {
        return state(getDefaultState(), BlockOrientation.get(EnumFacing.byIndex(meta & 7)));
    }
    @Override public int getMetaFromState(IBlockState state) { return state.getValue(BlockDirectional.FACING).getIndex(); }
    @Override public boolean hasTileEntity(@NotNull IBlockState state) { return true; }

    @Override
    public T createTileEntity(@NotNull World world, @NotNull IBlockState state) {
        T tile = Objects.requireNonNull(createController(world, state), "Controller creation returned null");
        if (tile.blockEntity() != tile) {
            throw new IllegalArgumentException("Controller must return its own block entity");
        }
        return tile;
    }

    /** Creates the configured host for Forge's block-state-aware placement path. */
    protected abstract T createController(World world, IBlockState state);

    @Override public @NotNull IBlockState getStateForPlacement(@NotNull World world, @NotNull BlockPos pos, @NotNull EnumFacing facing,
            float hitX, float hitY, float hitZ, int meta, @NotNull EntityLivingBase placer, @NotNull EnumHand hand) {
        // PM's vertical placement behavior is the vanilla six-facing piston rule.
        return state(getDefaultState(), BlockOrientation.get(EnumFacing.getDirectionFromEntityLiving(pos, placer)));
    }

    @Override
    public void onBlockPlacedBy(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state,
                                @NotNull EntityLivingBase placer, @NotNull ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        T tile = getTileEntity(world, pos);
        if (!world.isRemote && tile != null) {
            tile.placedBy(placer);
        }
    }

    @Override
    public @NotNull IBlockState getActualState(@NotNull IBlockState state, @NotNull IBlockAccess world, @NotNull BlockPos pos) {
        T tile = getTileEntity(world, pos);
        return tile == null ? state : updateBlockStateFromTileEntity(state, tile);
    }

    @Override
    protected IBlockState updateBlockStateFromTileEntity(IBlockState state, T tile) {
        return state(state, tile.orientation()).withProperty(FORMED, tile.formed());
    }
    @Override public @NotNull IBlockState withRotation(@NotNull IBlockState state, @NotNull Rotation rotation) {
        return state(state, ControllerOrientation.rotate(getOrientation(state), rotation));
    }
    @Override public @NotNull IBlockState withMirror(@NotNull IBlockState state, @NotNull Mirror mirror) {
        return state(state, ControllerOrientation.mirror(getOrientation(state), mirror));
    }

    @Override
    public boolean rotateBlock(@NotNull World world, @NotNull BlockPos pos, @NotNull EnumFacing axis) {
        T tile = getTileEntity(world, pos);
        if (world.isRemote || tile == null) {
            return false;
        }
        tile.transformOrientation(current -> current.rotateClockwiseAround(axis));
        return true;
    }

    @Override
    public boolean onBlockActivated(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state,
                                    @NotNull EntityPlayer player, @NotNull EnumHand hand, @NotNull EnumFacing face, float hitX, float hitY, float hitZ) {
        if (player.isSneaking() || player.getHeldItem(hand).getItem() instanceof ControllerOrientationTool
            || getTileEntity(world, pos) == null) {
            return false;
        }
        if (!world.isRemote) {
            player.openGui(PrototypeEngineering.instance, 0, world, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    @Override
    public void breakBlock(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state) {
        try {
            T tile = getTileEntity(world, pos);
            if (!world.isRemote && tile != null) {
                tile.controllerBroken();
            }
        } finally {
            super.breakBlock(world, pos, state);
        }
    }
}
