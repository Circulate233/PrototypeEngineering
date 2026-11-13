package com.circulation.prototype_eng.container;

import ae2.api.inventories.InternalInventory;
import ae2.container.AEBaseContainer;
import ae2.container.SlotSemantics;
import ae2.container.guisync.GuiSync;
import ae2.container.slot.AppEngSlot;
import com.circulation.prototype_eng.api.machine.ControllerInventory;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Native controller items, player inventory and the joined machine status snapshot.
 */
public final class ContainerMachine extends AEBaseContainer {
    public static final int INVENTORY_COLUMNS = 4;
    public static final int INVENTORY_X = 139, INVENTORY_Y = 174;
    private static final String VIEW = "view";
    private final MachineControllerHost tile;
    @GuiSync(0)
    public MachineGuiSnapshot snapshot = MachineGuiSnapshot.EMPTY;
    @GuiSync(1)
    public int availableInventorySlots;
    private int requestedOffset;
    private int visibleRows = MachineGuiSnapshot.MAX_ROWS;
    private boolean captureQueued;

    public ContainerMachine(InventoryPlayer player, MachineControllerHost tile) {
        super(player, tile.blockEntity());
        this.tile = tile;
        setGuiTitle(tile.type().displayName().text());
        InternalInventory inventory;
        if (isClientSide()) {
            var clientInventory = new ControllerInventory(() -> {
            });
            clientInventory.setAvailableSlots(ControllerInventory.CAPACITY);
            inventory = clientInventory;
        } else {
            refreshInventory();
            var real = tile.controllerInventory();
            inventory = new InternalInventory() {
                @Override
                public int size() {
                    return ControllerInventory.CAPACITY;
                }

                @Override
                public ItemStack getStackInSlot(int slot) {
                    return slot < real.size() ? real.getStackInSlot(slot) : ItemStack.EMPTY;
                }

                @Override
                public void setItemDirect(int slot, ItemStack stack) {
                    if (slot < real.size()) real.setItemDirect(slot, stack);
                    else if (!stack.isEmpty()) throw new IllegalArgumentException("Unavailable controller slot");
                }

                @Override
                public boolean isItemValid(int slot, ItemStack stack) {
                    return isControllerSlotAvailable(slot) && slot < real.size() && real.isItemValid(slot, stack);
                }

                @Override
                @SuppressWarnings("UnstableApiUsage") // Forward native AE slot edits to the real persistent inventory.
                public void sendChangeNotification(int slot) {
                    if (slot < real.size()) real.sendChangeNotification(slot);
                }

                @Override
                public int getSlotLimit(int slot) {
                    return slot < real.size() ? real.getSlotLimit(slot) : 0;
                }
            };
        }
        for (int slot = 0; slot < ControllerInventory.CAPACITY; slot++) {
            int index = slot;
            var nativeSlot = new AppEngSlot(inventory, slot, inventoryX(slot), inventoryY(slot)) {
                @Override
                public boolean isEnabled() {
                    return isControllerSlotAvailable(index);
                }

                @Override
                public boolean isItemValid(@NotNull ItemStack stack) {
                    return isEnabled() && super.isItemValid(stack);
                }

                @Override
                public boolean canTakeStack(@NotNull EntityPlayer actor) {
                    return isEnabled() && super.canTakeStack(actor);
                }
            };
            addSlotToContainer(nativeSlot, SlotSemantics.STORAGE);
        }
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 9; column++)
                addSlotToContainer(new Slot(player, 9 + row * 9 + column, 216 + column * 18, 172 + row * 18), SlotSemantics.PLAYER_INVENTORY);
        for (int column = 0; column < 9; column++)
            addSlotToContainer(new Slot(player, column, 216 + column * 18, 230), SlotSemantics.PLAYER_HOTBAR);
        registerClientAction(VIEW, View.class, 80, view -> {
            if (view != null && canInteractWith(getPlayer()) && view.offset >= 0 && view.rows >= 1 && view.rows <= MachineGuiSnapshot.MAX_ROWS) {
                requestedOffset = view.offset;
                visibleRows = view.rows;
            }
        });
    }

    public static int inventoryX(int slot) {
        return INVENTORY_X + slot % INVENTORY_COLUMNS * 18;
    }

    public static int inventoryY(int slot) {
        return INVENTORY_Y + slot / INVENTORY_COLUMNS * 18;
    }

    public boolean isControllerSlotAvailable(int slot) {
        return slot >= 0 && slot < ControllerInventory.CAPACITY && slot < availableInventorySlots;
    }

    private void refreshInventory() {
        if (isServerSide()) availableInventorySlots = tile.refreshControllerInventorySlots();
    }

    public void requestView(int offset, int rows) {
        sendClientAction(VIEW, new View(offset, rows));
    }

    @Override
    public @NotNull ItemStack slotClick(int slotId, int dragType, @NotNull ClickType clickType, @NotNull EntityPlayer player) {
        refreshInventory();
        if (slotId >= 0 && slotId < ControllerInventory.CAPACITY && !isControllerSlotAvailable(slotId))
            return ItemStack.EMPTY;
        return super.slotClick(slotId, dragType, clickType, player);
    }

    @Override
    public @NotNull ItemStack transferStackInSlot(@NotNull EntityPlayer player, int index) {
        refreshInventory();
        if (index >= 0 && index < ControllerInventory.CAPACITY && !isControllerSlotAvailable(index))
            return ItemStack.EMPTY;
        return super.transferStackInSlot(player, index);
    }

    @Override
    protected boolean isValidQuickMoveDestination(Slot slot, ItemStack stack, boolean fromPlayer) {
        return slot.isEnabled() && super.isValidQuickMoveDestination(slot, stack, fromPlayer);
    }

    @Override
    public boolean canDragIntoSlot(@NotNull Slot slot) {
        return slot.isEnabled() && super.canDragIntoSlot(slot);
    }

    @Override
    public boolean canMergeSlot(@NotNull ItemStack stack, @NotNull Slot slot) {
        return slot.isEnabled() && super.canMergeSlot(stack, slot);
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide() && !captureQueued && canInteractWith(getPlayer())) {
            captureQueued = true;
            tile.readGuiAtBoundary(() -> {
                try {
                    if (getPlayer().openContainer != this || !canInteractWith(getPlayer())) return;
                    refreshInventory();
                    var machine = tile.machine();
                    snapshot = machine == null || !machine.active() ? MachineGuiSnapshot.unavailable(tile.formed(), tile.type().structureId().toString())
                        : MachineGuiSnapshot.capture(machine, requestedOffset, visibleRows);
                } finally {
                    captureQueued = false;
                }
            });
        }
        super.broadcastChanges();
    }

    /**
     * AE2's Gson codec reads this bounded view request; it grants no inventory permissions.
     */
    public static final class View {
        int offset;
        int rows;

        @SuppressWarnings("unused") // AE2's Gson codec constructs this wire DTO.
        public View() {
        }

        public View(int offset, int rows) {
            this.offset = offset;
            this.rows = rows;
        }
    }
}
