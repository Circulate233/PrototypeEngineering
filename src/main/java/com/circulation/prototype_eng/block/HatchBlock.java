package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Physical shell shared by tiered resource and energy hatches. Storage belongs to the tile.
 */
public final class HatchBlock extends Block {
    private final Supplier<? extends TileEntity> factory;

    public HatchBlock(String name, Supplier<? extends TileEntity> factory, float hardness) {
        super(Material.IRON);
        this.factory = factory;
        setRegistryName(Tags.MOD_ID, name);
        setTranslationKey(Tags.MOD_ID + "." + name);
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(hardness);
        setResistance(10.0F);
        setSoundType(SoundType.METAL);
        setHarvestLevel("pickaxe", 1);
    }

    @Override
    public boolean hasTileEntity(@NotNull IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(@NotNull World world, @NotNull IBlockState state) {
        return factory.get();
    }

    @Override
    public boolean onBlockActivated(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state, EntityPlayer player,
                                    @NotNull EnumHand hand, @NotNull EnumFacing face, float hitX, float hitY, float hitZ) {
        if (player.isSneaking()) {
            return false;
        }
        if (!world.isRemote && player instanceof EntityPlayerMP serverPlayer) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile == null || tile.isInvalid()) {
                return false;
            }
            var update = tile.getUpdatePacket();
            if (update != null) {
                serverPlayer.connection.sendPacket(update);
            }
            player.openGui(PrototypeEngineering.instance, 0, world, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    @Override
    public void getDrops(@NotNull NonNullList<ItemStack> drops, IBlockAccess world, @NotNull BlockPos pos, @NotNull IBlockState state, int fortune) {
        var stack = new ItemStack(this);
        TileEntity tile = world.getTileEntity(pos);
        if (tile != null) {
            NBTTagCompound saved = tile.writeToNBT(new NBTTagCompound());
            saved.removeTag("x");
            saved.removeTag("y");
            saved.removeTag("z");
            stack.setTagInfo("BlockEntityTag", saved);
        }
        drops.add(stack);
    }

    @Override
    public boolean removedByPlayer(@NotNull IBlockState state, @NotNull World world, @NotNull BlockPos pos, @NotNull EntityPlayer player, boolean willHarvest) {
        // Keep the tile until harvestBlock has encoded its contents in the drop.
        return willHarvest || super.removedByPlayer(state, world, pos, player, false);
    }

    @Override
    public void harvestBlock(@NotNull World world, @NotNull EntityPlayer player, @NotNull BlockPos pos, @NotNull IBlockState state,
                             @Nullable TileEntity tile, @NotNull ItemStack tool) {
        super.harvestBlock(world, player, pos, state, tile, tool);
        world.setBlockToAir(pos);
    }

    @Override
    public boolean canSilkHarvest(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state, @NotNull EntityPlayer player) {
        return false;
    }
}
