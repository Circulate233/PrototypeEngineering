package com.circulation.prototype_eng.structure.tool;

import ae2.api.config.Actionable;
import ae2.api.implementations.items.WirelessTerminalApi;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.storage.StorageHelper;
import ae2.container.implementations.ContainerCraftConfirm;
import ae2.crafting.TemporaryPseudoCraftingProvider;
import ae2.me.helpers.PlayerSource;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Uses AE's usable wireless-terminal lookup, player permissions, range and powered transfers.
 */
final class ConstructionMEAccess {
    private final EntityPlayerMP player;
    private final WirelessTerminalApi.TerminalContext terminal;

    private ConstructionMEAccess(EntityPlayerMP player, WirelessTerminalApi.TerminalContext terminal) {
        this.player = player;
        this.terminal = terminal;
    }

    static @Nullable ConstructionMEAccess find(EntityPlayerMP player) {
        var terminal = WirelessTerminalApi.findUsableWirelessTermOfPlayer(player, _ -> true);
        return terminal == null ? null : new ConstructionMEAccess(player, terminal);
    }

    private boolean current() {
        var next = WirelessTerminalApi.findUsableWirelessTermOfPlayer(player,
            context -> context.host() == terminal.host() && context.node().grid() == terminal.node().grid());
        return next != null;
    }

    long extract(AEKey key, long amount, Actionable action) {
        if (amount <= 0) return 0;
        if (!current()) return 0;
        return StorageHelper.poweredExtraction(terminal.energySource(), terminal.host().getInventory(), key, amount,
            new PlayerSource(player, terminal.actionHost()), action);
    }

    long refund(AEKey key, long amount) {
        if (!current()) return 0;
        // Returned escrow must not require additional energy after a failed placement.
        return terminal.host().getInventory().insert(key, amount, Actionable.MODULATE, new PlayerSource(player, terminal.actionHost()));
    }

    boolean craftable(AEKey key) {
        return current() && terminal.node().grid().getCraftingService().isCraftable(key);
    }

    boolean openCrafting(List<GenericStack> missing, ItemStack tool) {
        if (!current() || missing.isEmpty()) return false;
        var result = AEItemKey.of(tool);
        if (result == null) return false;
        var provider = new TemporaryPseudoCraftingProvider(missing, List.of(new GenericStack(result, 1)));
        // The pseudo result only groups missing materials; it never places a block or copies the tool.
        return ContainerCraftConfirm.openWithBatchCrafting(terminal.actionHost(), player, terminal.locator(), missing,
            provider, player.inventoryContainer);
    }
}
