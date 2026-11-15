package com.circulation.prototype_eng.block;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.IOrientationStrategy;
import ae2.api.orientation.OrientationStrategies;
import ae2.api.orientation.RelativeSide;
import ae2.block.AEBaseBlockItem;
import ae2.block.AEBaseTileBlock;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.machine.ParallelControllerType;
import com.circulation.prototype_eng.item.PrototypeCreativeTab;
import com.circulation.prototype_eng.localization.ParallelControllerText;
import com.circulation.prototype_eng.machine.ControllerOrientation;
import com.circulation.prototype_eng.parallel.ParallelControllerBlockEntity;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * AE physical block with an external definition and shared controller entity contract.
 */
public class ParallelControllerBlock extends AEBaseTileBlock<ParallelControllerBlockEntity> {
    public static final int GUI_ID = 5;
    private final ParallelControllerType type;

    public ParallelControllerBlock(ParallelControllerType type) {
        super(Material.IRON);
        this.type = type;
        setTileEntity(ParallelControllerBlockEntity.class);
        setRegistryName(type.id());
        setTranslationKey(type.displayName().getTranslationKey());
        setCreativeTab(PrototypeCreativeTab.INSTANCE);
        setHardness(5);
        setResistance(10);
        setHarvestLevel("pickaxe", 1);
        setSoundType(SoundType.METAL);
        setDefaultState(getOrientationStrategy().setOrientation(blockState.getBaseState(), EnumFacing.NORTH,
            BlockOrientation.NORTH_UP.getSpin()));
    }

    public ParallelControllerType type() {
        return type;
    }

    public AEBaseBlockItem createItem() {
        return new ParallelControllerItem(this);
    }

    @Override
    public IOrientationStrategy getOrientationStrategy() {
        return OrientationStrategies.full();
    }

    @Override
    public @NotNull IBlockState getStateForPlacement(@NotNull World world, @NotNull BlockPos pos, @NotNull EnumFacing facing,
                                                     float hitX, float hitY, float hitZ, int meta, @NotNull EntityLivingBase placer,
                                                     @NotNull EnumHand hand) {
        var orientation = ControllerOrientation.placement(EnumFacing.getDirectionFromEntityLiving(pos, placer),
            placer.rotationYaw, placer.rotationPitch);
        return getOrientationStrategy().setOrientation(getDefaultState(), orientation.getSide(RelativeSide.FRONT), orientation.getSpin());
    }

    @Override
    public @NotNull String getTranslationKey() {
        return type.displayName().getTranslationKey();
    }

    @Override
    public @NotNull String getLocalizedName() {
        return type.displayName().getLocal();
    }

    @Override
    protected void addCheckedInformation(@NotNull ItemStack stack, @Nullable World world, @NotNull List<String> lines, @NotNull ITooltipFlag flag) {
        super.addCheckedInformation(stack, world, lines, flag);
        lines.add(ParallelControllerText.Tooltip.getLocal(type.maximum()));
    }

    @Override
    public ParallelControllerBlockEntity createTileEntity(@NotNull World world, @NotNull IBlockState state) {
        return new ParallelControllerBlockEntity(type);
    }

    @Override
    public boolean onBlockActivated(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state, @NotNull EntityPlayer player,
                                    @NotNull EnumHand hand, @NotNull EnumFacing side, float hitX, float hitY, float hitZ) {
        if (player.isSneaking()) return false;
        if (!world.isRemote)
            player.openGui(PrototypeEngineering.instance, GUI_ID, world, pos.getX(), pos.getY(), pos.getZ());
        return true;
    }
}
