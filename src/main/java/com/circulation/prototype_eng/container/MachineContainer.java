package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.SlotSemantics;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Default real player inventory and a read-only machine page, synchronized through AE2.
 */
public final class MachineContainer extends AEBaseContainer {
    private static final String VIEW = "view";
    private static final String PAGE = "page";
    private final MachineControllerHost tile;
    @GuiSync(0)
    public MachineGuiSnapshot snapshot = MachineGuiSnapshot.EMPTY;
    private int requestedOffset;
    private int visibleRows = MachineGuiSnapshot.MAX_ROWS;
    private boolean captureQueued;
    private boolean inventoryVisible = true;

    public MachineContainer(InventoryPlayer player, MachineControllerHost tile) {
        super(player, tile.blockEntity());
        this.tile = tile;
        setGuiTitle(tile.type().displayName().text());
        // PM's 4x4 grid is an unimplemented placeholder. Keep its artwork without accepting real items.
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 9; column++) {
                addSlotToContainer(new PlayerSlot(player, 9 + row * 9 + column, 216 + column * 18, 172 + row * 18), SlotSemantics.PLAYER_INVENTORY);
            }
        for (int column = 0; column < 9; column++) {
            addSlotToContainer(new PlayerSlot(player, column, 216 + column * 18, 230), SlotSemantics.PLAYER_HOTBAR);
        }
        registerClientAction(PAGE, Integer.class, page -> {
            if (page == null || page < 0 || page > 1 || !canInteractWith(getPlayer())) return;
            inventoryVisible = page == 0;
        });
        registerClientAction(VIEW, View.class, 80, view -> {
            if (view != null && canInteractWith(getPlayer()) && view.offset >= 0 && view.rows >= 1 && view.rows <= MachineGuiSnapshot.MAX_ROWS) {
                requestedOffset = view.offset;
                visibleRows = view.rows;
            }
        });
    }

    public void requestView(int offset, int rows) {
        sendClientAction(VIEW, new View(offset, rows));
    }

    public void selectPage(int page) {
        if (page < 0 || page > 1) return;
        inventoryVisible = page == 0;
        sendClientAction(PAGE, page);
    }

    @Override
    public @NotNull ItemStack slotClick(int slotId, int dragType, @NotNull ClickType clickType, @NotNull EntityPlayer player) {
        if (!inventoryVisible && slotId >= 0) return ItemStack.EMPTY;
        return super.slotClick(slotId, dragType, clickType, player);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        var entity = tile.blockEntity();
        return !entity.isInvalid() && player.world == entity.getWorld() && player.world.isBlockLoaded(entity.getPos(), false)
            && player.world.getTileEntity(entity.getPos()) == entity && super.canInteractWith(player);
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide() && !captureQueued && canInteractWith(getPlayer())) {
            captureQueued = true;
            tile.readGuiAtBoundary(() -> {
                try {
                    if (getPlayer().openContainer != this || !canInteractWith(getPlayer())) {
                        return;
                    }
                    var machine = tile.machine();
                    snapshot = machine == null || !machine.active() ? MachineGuiSnapshot.EMPTY
                        : MachineGuiSnapshot.capture(machine, requestedOffset, visibleRows);
                } finally {
                    captureQueued = false;
                }
            });
        }
        super.broadcastChanges();
    }

    /**
     * AE2's Gson client-action codec reads these values; they grant no machine mutation permissions.
     */
    public static final class View {
        int offset;
        int rows;

        @SuppressWarnings("unused") // AE2's default Gson codec constructs and fills this wire DTO.
        public View() {
        }

        public View(int offset, int rows) {
            this.offset = offset;
            this.rows = rows;
        }
    }

    /** Ordinary player slots need a page gate too; AE's player inventory is not made of AppEngSlot. */
    private final class PlayerSlot extends Slot {
        private PlayerSlot(InventoryPlayer inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean isEnabled() {
            return inventoryVisible;
        }

        @Override
        public boolean isItemValid(@NotNull ItemStack stack) {
            return inventoryVisible && !isPlayerInventorySlotLocked(getSlotIndex()) && super.isItemValid(stack);
        }

        @Override
        public boolean canTakeStack(@NotNull EntityPlayer player) {
            return inventoryVisible && !isPlayerInventorySlotLocked(getSlotIndex()) && super.canTakeStack(player);
        }
    }
}
