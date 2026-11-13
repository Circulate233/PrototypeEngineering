package com.circulation.prototype_eng.block;

import ae2.block.AEBaseBlockItem;
import ae2.block.AEBaseTileBlock;
import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.item.PrototypeCreativeTab;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Physical shell shared by tiered resource and energy hatches. Storage belongs to the tile.
 */
public abstract class HatchBlock<T extends AEBaseTile> extends AEBaseTileBlock<T> {
    /**
     * The registered entity class must expose a public no-arg constructor for AE and Forge.
     */
    protected HatchBlock(ResourceLocation id, Class<T> tileClass, float hardness) {
        super(Material.IRON);
        setTileEntity(Objects.requireNonNull(tileClass));
        setRegistryName(Objects.requireNonNull(id));
        setTranslationKey(id.getNamespace() + "." + id.getPath());
        setCreativeTab(PrototypeCreativeTab.INSTANCE);
        setHardness(hardness);
        setResistance(10.0F);
        setSoundType(SoundType.METAL);
        setHarvestLevel("pickaxe", 1);
    }

    /**
     * Sparse emissive overlays require alpha testing even when CTM is not installed.
     */
    @Override
    @SideOnly(Side.CLIENT)
    public @NotNull BlockRenderLayer getRenderLayer() {
        return BlockRenderLayer.CUTOUT;
    }

    /**
     * Override to customize the registered item while retaining AE2S block item behavior.
     */
    public AEBaseBlockItem createItem() {
        return new PrototypeBlockItem(this);
    }

    @Override
    public boolean hasTileEntity(@NotNull IBlockState state) {
        return true;
    }

    @Override
    public T createTileEntity(@NotNull World world, @NotNull IBlockState state) {
        return Objects.requireNonNull(createHatch(world, state), "Hatch creation returned null");
    }

    /**
     * Creates the configured entity for Forge's block-state-aware placement path.
     */
    protected abstract T createHatch(World world, IBlockState state);

    @Override
    public boolean onBlockActivated(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState state, EntityPlayer player,
                                    @NotNull EnumHand hand, @NotNull EnumFacing face, float hitX, float hitY, float hitZ) {
        if (player.isSneaking()) {
            return false;
        }
        if (!world.isRemote && player instanceof EntityPlayerMP serverPlayer) {
            T tile = getTileEntity(world, pos);
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
        T tile = getTileEntity(world, pos);
        if (tile != null) {
            NBTTagCompound saved = itemDropData(tile);
            saved.removeTag("x");
            saved.removeTag("y");
            saved.removeTag("z");
            stack.setTagInfo("BlockEntityTag", saved);
        }
        drops.add(stack);
    }

    /**
     * Resource hatches keep settings here and eject their contents through AE's additional drops.
     */
    protected NBTTagCompound itemDropData(T tile) {
        return tile.writeToNBT(new NBTTagCompound());
    }

    @Override
    public boolean removedByPlayer(@NotNull IBlockState state, @NotNull World world, @NotNull BlockPos pos, @NotNull EntityPlayer player, boolean willHarvest) {
        // Keep the tile until harvestBlock has captured settings and AE has ejected its contents.
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
