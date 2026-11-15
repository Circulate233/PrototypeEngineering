package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.storage.MEPatternAssemblyMemoryCard;
import com.circulation.prototype_eng.storage.MEPatternMirrorBlockEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

public final class MEPatternMirrorBlock extends HatchBlock<MEPatternMirrorBlockEntity> {
    public MEPatternMirrorBlock() {
        super(new ResourceLocation(Tags.MOD_ID, "me_pattern_mirror"), MEPatternMirrorBlockEntity.class, 5.0F);
    }

    @Override
    protected MEPatternMirrorBlockEntity createHatch(World world, IBlockState state) {
        return new MEPatternMirrorBlockEntity();
    }

    @Override
    public boolean onBlockActivated(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state,
                                    EntityPlayer player, @NotNull EnumHand hand, @NotNull EnumFacing face,
                                    float hitX, float hitY, float hitZ) {
        return MEPatternAssemblyMemoryCard.useOnMirror(getTileEntity(world, pos), player, player.getHeldItem(hand));
    }
}
