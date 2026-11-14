package com.circulation.prototype_eng.item;

import ae2.items.AEBaseItem;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.container.ContainerStructureTool;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.tool.ScannerState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.List;

/**
 * Selects a structure region.
 */
public final class StructureToolItem extends AEBaseItem {
    StructureToolItem() {
        setRegistryName(new ResourceLocation(Tags.MOD_ID, "scanner_instrument"));
        setMaxStackSize(1);
        setCreativeTab(PrototypeCreativeTab.INSTANCE);
    }

    @Override
    public @NotNull String getItemStackDisplayName(@NotNull ItemStack stack) {
        return StructureToolText.ScannerTitle.getLocal();
    }

    @Override
    @SideOnly(Side.CLIENT)
    protected void addCheckedInformation(@NotNull ItemStack stack, @Nullable World world, @NotNull List<String> lines, @NotNull ITooltipFlag flag) {
        super.addCheckedInformation(stack, world, lines, flag);
        lines.add(StructureToolText.ScanHint.getLocal());
        lines.add(StructureToolText.ScannerUsageExport.getLocal());
    }

    @Override
    public @NotNull ActionResult<ItemStack> onItemRightClick(@NotNull World world, @NotNull EntityPlayer player, @NotNull EnumHand hand) {
        var stack = player.getHeldItem(hand);
        if (!world.isRemote) {
            ScannerState.data(stack);
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
        if (!world.isRemote) {
            var data = ScannerState.data(stack);
            boolean hadCorner = ScannerState.position(data, "corner") != null;
            boolean fresh = ScannerState.select(data, pos, world.provider.getDimension(), player.isSneaking(), stack.getDisplayName());
            if (fresh) {
                player.sendMessage((player.isSneaking() ? StructureToolText.OriginRestart : StructureToolText.OriginSet).text(pos.getX(), pos.getY(), pos.getZ()));
                player.sendMessage(StructureToolText.SelectCorner.text());
            } else if (hadCorner) {
                player.sendMessage(StructureToolText.CornerUpdated.text(pos.getX(), pos.getY(), pos.getZ()));
            } else {
                player.sendMessage(StructureToolText.CornerSet.text(pos.getX(), pos.getY(), pos.getZ()));
                var box = ScannerState.box(data);
                if (box != null) {
                    var min = box.min();
                    var max = box.max();
                    var volume = BigInteger.valueOf(box.sizeX()).multiply(BigInteger.valueOf(box.sizeY())).multiply(BigInteger.valueOf(box.sizeZ()));
                    player.sendMessage(StructureToolText.Selection.text(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(),
                        box.sizeX(), box.sizeY(), box.sizeZ(), volume));
                    if (box.exceeds(64 * 64 * 64)) player.sendMessage(StructureToolText.LargeSelection.text(volume));
                }
                player.sendMessage(StructureToolText.ExportHint.text());
            }
            player.inventory.markDirty();
        }
        return EnumActionResult.SUCCESS;
    }
}
