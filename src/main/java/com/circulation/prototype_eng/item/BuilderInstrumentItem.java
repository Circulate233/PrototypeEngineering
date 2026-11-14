package com.circulation.prototype_eng.item;

import ae2.api.features.GridLinkables;
import ae2.api.features.IGridLinkableHandler;
import ae2.api.implementations.guiobjects.IGuiItem;
import ae2.api.upgrades.IUpgradeInventory;
import ae2.api.upgrades.IUpgradeableItem;
import ae2.api.upgrades.UpgradeInventories;
import ae2.core.gui.locator.ItemGuiHostLocator;
import ae2.core.localization.GuiText;
import ae2.core.localization.Tooltips;
import ae2.items.AEBaseItem;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.container.ContainerStructureTool;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.tool.BuilderMiningTool;
import com.circulation.prototype_eng.structure.tool.BuilderState;
import com.circulation.prototype_eng.structure.tool.ConstructionTarget;
import net.minecraft.client.util.ITooltipFlag;
import com.circulation.prototype_eng.structure.tool.ConstructionMEAccess;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Stores its own AE binding and upgrades. Structure binding remains in the builder data.
 */
@SuppressWarnings("NonExtendableApiUsage") // AE upgrade slots and card recipes identify items through IUpgradeableItem.
public final class BuilderInstrumentItem extends AEBaseItem implements IGuiItem, IUpgradeableItem, IGridLinkableHandler {
    public static final String NETWORK = "BuilderNetwork";
    public static final int UPGRADE_SLOTS = 1;

    BuilderInstrumentItem() {
        setMaxStackSize(1);
        setRegistryName(new ResourceLocation(Tags.MOD_ID, "build_instrument"));
        setCreativeTab(PrototypeCreativeTab.INSTANCE);
        GridLinkables.register(this, this);
    }

    @Override
    public @NotNull String getItemStackDisplayName(@NotNull ItemStack stack) {
        return StructureToolText.BuilderTitle.getLocal();
    }

    @Override
    public boolean canLink(ItemStack stack) {
        return true;
    }

    @Override
    public void link(ItemStack stack, World world, BlockPos pos) {
        var link = new NBTTagCompound();
        link.setInteger("Dimension", world.provider.getDimension());
        link.setLong("Position", pos.toLong());
        BuilderState.data(stack).setTag(NETWORK, link);
    }

    @Override
    public void unlink(ItemStack stack) {
        var tag = stack.getTagCompound();
        if (tag != null) tag.removeTag(NETWORK);
    }

    @Override
    public IUpgradeInventory getUpgrades(ItemStack stack) {
        return UpgradeInventories.forItem(stack, UPGRADE_SLOTS);
    }

    @Override
    public ConstructionMEAccess getGuiHost(EntityPlayer player, ItemGuiHostLocator locator, @Nullable RayTraceResult hitResult) {
        return new ConstructionMEAccess(this, player, locator);
    }

    @Override
    public boolean shouldCauseReequipAnimation(@NotNull ItemStack oldStack, @NotNull ItemStack newStack, boolean slotChanged) {
        return slotChanged;
    }

    @Override
    @SideOnly(Side.CLIENT)
    protected void addCheckedInformation(@NotNull ItemStack stack, @Nullable World world, @NotNull List<String> lines, @NotNull ITooltipFlag flag) {
        super.addCheckedInformation(stack, world, lines, flag);
        var tag = stack.getTagCompound();
        boolean linked = tag != null && tag.hasKey(NETWORK, 10);
        lines.add(Tooltips.colored(linked ? GuiText.Linked : GuiText.Unlinked, linked ? Tooltips.GREEN : Tooltips.RED));
        lines.add(StructureToolText.BuilderNetworkBind.getLocal());
        lines.add(StructureToolText.BuilderUsageBind.getLocal());
        lines.add(StructureToolText.BuilderUsageOpen.getLocal());
        lines.add(StructureToolText.BuilderUsageSneakOpen.getLocal());
        lines.add(StructureToolText.BuilderUsageAir.getLocal());
        lines.add(StructureToolText.BuilderUsageProjection.getLocal());
        lines.add(StructureToolText.BuilderUsageProjectionLayer.getLocal());
        lines.add(StructureToolText.BuilderToolFusion.getLocal());
        lines.add(StructureToolText.BuilderToolFusionPermanent.getLocal());
        var miningTool = BuilderMiningTool.profile(stack);
        if (!miningTool.isEmpty()) lines.add(StructureToolText.BuilderToolFused.getLocal(miningTool.getDisplayName()));
        lines.add(StructureToolText.BuilderFluidRecovery.getLocal());
    }

    @Override
    public @NotNull ActionResult<ItemStack> onItemRightClick(@NotNull World world, @NotNull EntityPlayer player, @NotNull EnumHand hand) {
        var stack = player.getHeldItem(hand);
        if (!world.isRemote) {
            int slot = hand == EnumHand.MAIN_HAND ? player.inventory.currentItem : 40;
            player.openGui(PrototypeEngineering.instance, ContainerStructureTool.GUI_ID, world, slot, 0, 0);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    @Override
    public @NotNull EnumActionResult onItemUseFirst(@NotNull EntityPlayer player, @NotNull World world, @NotNull BlockPos pos,
                                                    @NotNull EnumFacing side, float hitX, float hitY, float hitZ, @NotNull EnumHand hand) {
        return onItemUse(player, world, pos, hand, side, hitX, hitY, hitZ);
    }

    @Override
    public @NotNull EnumActionResult onItemUse(@NotNull EntityPlayer player, @NotNull World world, @NotNull BlockPos pos,
                                               @NotNull EnumHand hand, @NotNull EnumFacing side, float hitX, float hitY, float hitZ) {
        var stack = player.getHeldItem(hand);
        if (!world.isBlockLoaded(pos, false)) return EnumActionResult.FAIL;
        var target = ConstructionTarget.resolve(world, pos);
        if (target == null) return EnumActionResult.PASS;
        if (!world.isBlockModifiable(player, pos) || !player.canPlayerEdit(pos, side, stack))
            return EnumActionResult.FAIL;
        if (!world.isRemote) {
            var position = pos.toImmutable();
            var open = player.openContainer;
            boolean sneaking = player.isSneaking();
            target.atBoundary(() -> {
                var current = ConstructionTarget.resolve(world, position);
                if (current == null || !target.current()) return;
                if (player.world != world || !player.isEntityAlive() || player.getHeldItem(hand) != stack || stack.isEmpty()
                    || player.openContainer != open || !world.isBlockLoaded(position, false)
                    || !world.isBlockModifiable(player, position) || !player.canPlayerEdit(position, side, stack))
                    return;
                var data = stack.getTagCompound();
                boolean same = data != null && data.getBoolean("Bound") && data.getInteger("BoundDim") == world.provider.getDimension()
                    && BuilderState.position(data).equals(position) && data.getString("BoundMachineId").equals(current.binding().toString())
                    && data.getString("BoundStructureId").equals(current.structure().toString());
                if (!same || sneaking) {
                    BuilderState.bind(stack, world.provider.getDimension(), position, current.binding().toString(), current.structure().toString());
                    player.inventory.markDirty();
                    player.sendStatusMessage(StructureToolText.Bound.text(current.name().text(), position.getX(), position.getY(), position.getZ()), true);
                }
                if (same || sneaking) {
                    int slot = hand == EnumHand.MAIN_HAND ? player.inventory.currentItem : 40;
                    player.openGui(PrototypeEngineering.instance, ContainerStructureTool.GUI_ID, world, slot, 0, 0);
                }
            });
        }
        return EnumActionResult.SUCCESS;
    }
}
