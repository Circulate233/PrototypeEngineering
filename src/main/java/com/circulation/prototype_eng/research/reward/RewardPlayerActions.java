package com.circulation.prototype_eng.research.reward;

import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.CPacketClickWindow;
import net.minecraft.network.play.client.CPacketCloseWindow;
import net.minecraft.network.play.client.CPacketCreativeInventoryAction;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.network.play.server.SPacketConfirmTransaction;
import net.minecraft.network.play.server.SPacketHeldItemChange;
import net.minecraft.network.play.server.SPacketSetSlot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

/**
 * Guard for selected vanilla player item entry points, after their original MAIN-thread handoff.
 * It uses the executing handler's player, without introducing a second packet queue or inventory.
 * Direct addon inventory writes must consult RewardItemProtection; native death and hopper paths
 * have their own guards and do not rely on this packet entry.
 */
public final class RewardPlayerActions {
    private RewardPlayerActions() { }

    /** Returns whether the original vanilla handler must stop before performing its item action. */
    public static boolean reject(MinecraftServer server, NetHandlerPlayServer handler, Packet<?> packet) {
        // Vanilla's PacketThreadUtil normally prevents this path; never read inventory off MAIN.
        if (!server.isCallingFromMinecraftThread()) return true;
        if (packet instanceof CPacketCustomPayload payload && !hasItemSideEffects(payload.getChannelName())) {
            return false;
        }
        var player = handler.player;
        if (!isCurrent(server, handler, player)) return true;
        if (packet instanceof CPacketCloseWindow) {
            if (!hasProtectedClosingContents(player)) return false;
        } else {
            // Creative installs the client stack; click's clickedItem is only a prediction comparison.
            // A creative snapshot can replay a formerly protected stack after its real tag cleared.
            boolean protectedInput = packet instanceof CPacketCreativeInventoryAction creative
                && RewardItemProtection.isProtected(creative.getStack());
            if (!protectedInput && !RewardItemProtection.hasProtectedInventory(player)) return false;
        }
        // Cancelling the inner custom-payload handler still returns to CPacketCustomPayload.processPacket,
        // which releases its buffer once. Do not release it here or consume its reader index.
        synchronizeRejected(player, handler, packet);
        return true;
    }

    private static boolean hasProtectedClosingContents(EntityPlayerMP player) {
        // ContainerMixin retains a protected cursor even for server-initiated closes. Player slots
        // also survive closing; foreign container slots can still be cleared by subclass callbacks.
        for (var slot : player.openContainer.inventorySlots) {
            if (slot.inventory != player.inventory && RewardItemProtection.isProtected(slot.getStack())) return true;
        }
        return false;
    }

    /** The client normally clears its cursor on close; publish the actual retained server value. */
    public static void synchronizeProtectedCursor(MinecraftServer server, NetHandlerPlayServer handler) {
        if (!server.isCallingFromMinecraftThread() || !isCurrent(server, handler, handler.player)) return;
        var cursor = handler.player.inventory.getItemStack();
        if (RewardItemProtection.isProtected(cursor)) handler.sendPacket(new SPacketSetSlot(-1, -1, cursor));
    }

    private static boolean isCurrent(MinecraftServer server, NetHandlerPlayServer handler, EntityPlayerMP player) {
        return player != null && !server.isServerStopped() && handler.netManager.isChannelOpen()
            && handler.netManager.getNetHandler() == handler && player.connection == handler
            && player.getServer() == server && !player.world.isRemote
            && server.getPlayerList().getPlayerByUUID(player.getUniqueID()) == player;
    }

    private static boolean hasItemSideEffects(String channel) {
        return "MC|BEdit".equals(channel) || "MC|BSign".equals(channel) || "MC|TrSel".equals(channel)
            || "MC|PickItem".equals(channel) || "MC|Beacon".equals(channel) || "MC|ItemName".equals(channel);
    }

    private static void synchronizeRejected(EntityPlayerMP player, NetHandlerPlayServer handler, Packet<?> packet) {
        if (packet instanceof CPacketClickWindow click) {
            // Do not setCanCraft(false): no vanilla pending transaction was registered by this denial.
            handler.sendPacket(new SPacketConfirmTransaction(click.getWindowId(), click.getActionNumber(), false));
        }
        // The inventory container includes armor and offhand; a chest window need not expose them.
        // sendContainerToPlayer also sends the actual carried cursor, never the client prediction.
        player.sendContainerToPlayer(player.inventoryContainer);
        if (player.openContainer != player.inventoryContainer) player.sendContainerToPlayer(player.openContainer);
        handler.sendPacket(new SPacketHeldItemChange(player.inventory.currentItem));
        if (packet instanceof CPacketPlayerTryUseItemOnBlock use) {
            synchronizeBlock(player, handler, use.getPos());
            synchronizeBlock(player, handler, use.getPos().offset(use.getDirection()));
        } else if (packet instanceof CPacketPlayerDigging digging && isBlockDigging(digging.getAction())) {
            player.interactionManager.cancelDestroyingBlock();
            synchronizeBlock(player, handler, digging.getPosition());
        }
    }

    private static boolean isBlockDigging(CPacketPlayerDigging.Action action) {
        return action == CPacketPlayerDigging.Action.START_DESTROY_BLOCK
            || action == CPacketPlayerDigging.Action.ABORT_DESTROY_BLOCK
            || action == CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK;
    }

    private static void synchronizeBlock(EntityPlayerMP player, NetHandlerPlayServer handler, BlockPos position) {
        var world = player.getServerWorld();
        // Rejection must not load a chunk merely because a client supplied a distant position.
        if (world.isBlockLoaded(position)) handler.sendPacket(new SPacketBlockChange(world, position));
    }
}
