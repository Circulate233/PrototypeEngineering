package com.circulation.prototype_eng.storage;

import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.implementations.items.IMemoryCard;
import ae2.api.implementations.items.MemoryCardMessages;
import ae2.api.inventories.InternalInventory;
import ae2.api.util.DimensionalBlockPos;
import ae2.core.definitions.AEItems;
import ae2.core.localization.PlayerMessages;
import ae2.crafting.pattern.AEProcessingPattern;
import ae2.helpers.WirelessTerminalActions;
import ae2.items.tools.MemoryCardItem;
import ae2.util.Platform;
import ae2.util.inv.AppEngInternalInventory;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Native memory-card gestures for assembly settings, paid pattern restoration and mirror locations.
 */
public final class MEPatternAssemblyMemoryCard {
    private static final String SOURCE = "tile." + Tags.MOD_ID + ".me_pattern_assembly.name";
    private static final String SETTINGS = "settings";
    private static final String SOURCE_ID = "exported_settings_source";
    private static final String DIMENSION = "SourceDimension";
    private static final String POSITION = "SourcePosition";

    private MEPatternAssemblyMemoryCard() {
    }

    public static boolean useOnAssembly(@Nullable MEPatternAssemblyBlockEntity assembly,
                                        EntityPlayer player, ItemStack card) {
        if (!(card.getItem() instanceof IMemoryCard memory)) return false;
        if (player.world.isRemote) return true;
        if (assembly == null || !Platform.hasPermissions(new DimensionalBlockPos(assembly), player)) {
            memory.notifyUser(player, MemoryCardMessages.INVALID_MACHINE);
            return true;
        }
        try {
            if (player.isSneaking()) {
                var settings = assembly.exportMemoryCardSettings();
                settings.setInteger(DIMENSION, assembly.getWorld().provider.getDimension());
                settings.setLong(POSITION, assembly.getPos().toLong());
                MemoryCardItem.clearCard(card);
                var tag = Platform.openNbtData(card);
                tag.setTag(SETTINGS, settings);
                tag.setString(SOURCE_ID, SOURCE);
                memory.notifyUser(player, MemoryCardMessages.SETTINGS_SAVED);
            } else {
                var settings = readSettings(card);
                boolean imported = settings != null && assembly.importMemoryCardSettings(settings, player);
                memory.notifyUser(player, imported ? MemoryCardMessages.SETTINGS_LOADED : MemoryCardMessages.INVALID_MACHINE);
            }
        } catch (RuntimeException failure) {
            PrototypeEngineering.LOGGER.error("Failed to apply pattern assembly memory card at {} in dimension {}",
                assembly.getPos(), assembly.getWorld().provider.getDimension(), failure);
            memory.notifyUser(player, MemoryCardMessages.INVALID_MACHINE);
        }
        return true;
    }

    public static boolean useOnMirror(@Nullable MEPatternMirrorBlockEntity mirror, EntityPlayer player, ItemStack card) {
        if (player.isSneaking() || !(card.getItem() instanceof IMemoryCard memory)) return false;
        if (player.world.isRemote) return true;
        if (mirror == null || !Platform.hasPermissions(new DimensionalBlockPos(mirror), player)) {
            memory.notifyUser(player, MemoryCardMessages.INVALID_MACHINE);
            return true;
        }
        var settings = readSettings(card);
        if (settings == null || !settings.hasKey(DIMENSION, 3) || !settings.hasKey(POSITION, 4)) {
            memory.notifyUser(player, MemoryCardMessages.INVALID_MACHINE);
            return true;
        }
        mirror.bindSource(settings.getInteger(DIMENSION), settings.getLong(POSITION));
        memory.notifyUser(player, MemoryCardMessages.SETTINGS_LOADED);
        return true;
    }

    private static @Nullable NBTTagCompound readSettings(ItemStack card) {
        var tag = card.getTagCompound();
        return tag != null && SOURCE.equals(tag.getString(SOURCE_ID)) && tag.hasKey(SETTINGS, 10)
            ? tag.getCompoundTag(SETTINGS) : null;
    }

    static void restorePatterns(NBTTagCompound settings, InternalInventory target, EntityPlayer player, World world) {
        if (!settings.hasKey("patterns", 9)) return;
        clearPatterns(target, player);
        var desired = new AppEngInternalInventory(target.size());
        desired.readFromNBT(settings, "patterns");
        var blank = AEItems.BLANK_PATTERN.stack();
        int blankSlot = 0;
        int targetSlot = 0;
        int missing = 0;
        for (int slot = 0; slot < desired.size(); slot++) {
            var stack = desired.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            var pattern = PatternDetailsHelper.decodePattern(stack, world);
            if (!(pattern instanceof AEProcessingPattern)) continue;
            if (installFromWireless(target, targetSlot, player, stack)) {
                targetSlot++;
                continue;
            }
            var encoded = pattern.getDefinition().toStack();
            if (player.capabilities.isCreativeMode) {
                if (target.insertItem(targetSlot, encoded, false).isEmpty()) targetSlot++;
                continue;
            }
            while (blankSlot < player.inventory.getSizeInventory()) {
                var available = player.inventory.getStackInSlot(blankSlot);
                if (!available.isEmpty() && ItemStack.areItemsEqual(available, blank)
                    && ItemStack.areItemStackTagsEqual(available, blank)) break;
                blankSlot++;
            }
            if (blankSlot == player.inventory.getSizeInventory()) {
                missing++;
                continue;
            }
            var payment = player.inventory.getStackInSlot(blankSlot);
            payment.shrink(1);
            if (target.insertItem(targetSlot, encoded, false).isEmpty()) targetSlot++;
            else payment.grow(1);
        }
        if (missing > 0) player.sendMessage(PlayerMessages.MissingBlankPatterns.text(missing));
    }

    private static void clearPatterns(InternalInventory target, EntityPlayer player) {
        int blanks = 0;
        for (int slot = 0; slot < target.size(); slot++) {
            var stack = target.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            target.setItemDirect(slot, ItemStack.EMPTY);
            if (player.capabilities.isCreativeMode) continue;
            var item = stack.getItem();
            if (item == AEItems.CRAFTING_PATTERN.item() || item == AEItems.PROCESSING_PATTERN.item()
                || item == AEItems.BLANK_PATTERN.item()) blanks += stack.getCount();
            else give(player, stack);
        }
        if (blanks > 0) give(player, AEItems.BLANK_PATTERN.stack(blanks));
    }

    private static boolean installFromWireless(InternalInventory target, int slot, EntityPlayer player, ItemStack desired) {
        if (!(player instanceof EntityPlayerMP serverPlayer) || !target.insertItem(slot, desired, true).isEmpty())
            return false;
        var extracted = WirelessTerminalActions.extractStack(serverPlayer, desired, 1);
        if (extracted.isEmpty()) return false;
        var remainder = target.insertItem(slot, extracted, false);
        if (remainder.isEmpty()) return true;
        give(player, remainder);
        return false;
    }

    private static void give(EntityPlayer player, ItemStack stack) {
        if (!player.inventory.addItemStackToInventory(stack) && !stack.isEmpty()) player.dropItem(stack, false);
    }
}
