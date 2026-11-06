package com.circulation.prototype_eng.container;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.container.AEBaseContainer;
import ae2.container.SlotSemantics;
import ae2.container.slot.AppEngSlot;
import ae2.helpers.InventoryAction;
import ae2.helpers.externalstorage.GenericStackInv;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.storage.HatchSlotActions;
import com.circulation.prototype_eng.storage.ResourceHatchBlockEntity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentTranslation;

public final class ResourceHatchContainer extends AEBaseContainer {
    private final ResourceHatchBlockEntity tile;
    private final BufferedResourceHatch hatch;
    private final int inputSlots;
    private final int resourceSlots;
    public final int columns;
    public final int guiWidth;
    public final int playerTop;

    public ResourceHatchContainer(InventoryPlayer player, ResourceHatchBlockEntity tile) {
        super(player, tile);
        this.tile = tile;
        hatch = tile.storage();
        inputSlots = hatch.input() == null ? 0 : hatch.input().size();
        resourceSlots = inputSlots + (hatch.output() == null ? 0 : hatch.output().size());
        boolean mixed = tile.mode() == HatchMode.MIXED;
        columns = mixed ? (tile.slotsPerSide() <= 9 ? 3 : 6) : 9;
        guiWidth = mixed && columns == 6 ? 248 : 176;
        playerTop = 50 + ((tile.slotsPerSide() + columns - 1) / columns) * 18;
        addBuffer(hatch.input(), true, 8);
        addBuffer(hatch.output(), false, mixed ? guiWidth - 8 - columns * 18 : 8);
        addPlayerInventorySlots((guiWidth - 162) / 2, playerTop);
        setGuiTitle(new TextComponentTranslation("tile.prototype_eng." + switch (tile.mode()) {
            case INPUT -> "input_hatch";
            case OUTPUT -> "output_hatch";
            case MIXED -> "mixed_hatch";
        } + ".name"));
    }

    private void addBuffer(GenericStackInv inventory, boolean input, int left) {
        if (inventory == null) { return; }
        var display = inventory.createGuiWrapper();
        for (int i = 0; i < inventory.size(); i++) {
            var slot = new AppEngSlot(display, i, left + (i % columns) * 18, 32 + (i / columns) * 18) {
                @Override
                public boolean isItemValid(ItemStack stack) {
                    var key = AEItemKey.of(stack);
                    return input && !GenericStack.isWrapped(stack) && key != null && inventory.isAllowedIn(getSlotIndex(), key);
                }

                @Override
                public boolean canTakeStack(EntityPlayer player) {
                    return inventory.canExtract() && inventory.getKey(getSlotIndex()) instanceof AEItemKey;
                }

                @Override
                public ItemStack decrStackSize(int amount) {
                    return takeItems(inventory, getSlotIndex(), amount);
                }
            };
            slot.setNotDraggable();
            addSlot(slot, input ? SlotSemantics.MACHINE_INPUT : SlotSemantics.MACHINE_OUTPUT);
        }
    }

    public boolean isResourceSlot(int index) { return index >= 0 && index < resourceSlots; }
    public boolean isInputSlot(int index) { return index >= 0 && index < inputSlots; }
    public HatchMode mode() { return tile.mode(); }

    private GenericStackInv buffer(int index) { return index < inputSlots ? hatch.input() : hatch.output(); }
    private int localSlot(int index) { return index < inputSlots ? index : index - inputSlots; }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return hatch.isValid() && tile.storage() == hatch && super.canInteractWith(player);
    }

    @Override
    public boolean canMergeSlot(ItemStack stack, Slot slot) {
        // Vanilla collect/merge manipulates ItemStack references; resource slots expose display copies.
        return !isResourceSlot(slot.slotNumber) && super.canMergeSlot(stack, slot);
    }

    @Override
    public void swapSlotContents(int first, int second) {
        // AE2's generic swap packet moves display ItemStacks. Resource exchanges use our real-quantity path.
        if (!canInteractWith(getPlayer()) || isResourceSlot(first) || isResourceSlot(second)) { return; }
        super.swapSlotContents(first, second);
    }

    @Override
    public ItemStack slotClick(int index, int button, ClickType click, EntityPlayer player) {
        if (!canInteractWith(player)) { return ItemStack.EMPTY; }
        if (click == ClickType.QUICK_MOVE) {
            transferStackInSlot(player, index);
            sync(player);
            return ItemStack.EMPTY;
        }
        if (!isResourceSlot(index)) { return super.slotClick(index, button, click, player); }
        if (isClientSide()) { return ItemStack.EMPTY; }
        GenericStackInv inventory = buffer(index);
        int slot = localSlot(index);
        switch (click) {
            case PICKUP -> {
                if (button == 0 || button == 1) {
                    setCarried(clickItem(inventory, slot, getCarried(), index < inputSlots, button == 1));
                }
            }
            case SWAP -> {
                if ((button >= 0 && button < 9) || button == 40) {
                    ItemStack old = player.inventory.getStackInSlot(button);
                    player.inventory.setInventorySlotContents(button, clickItem(inventory, slot, old, index < inputSlots, false));
                }
            }
            case THROW -> {
                if (getCarried().isEmpty() && (button == 0 || button == 1)) {
                    ItemStack dropped = takeItems(inventory, slot, button == 0 ? 1 : 64);
                    if (!dropped.isEmpty()) { player.dropItem(dropped, true); }
                }
            }
            case CLONE -> {
                if (player.capabilities.isCreativeMode && getCarried().isEmpty() && inventory.getKey(slot) instanceof AEItemKey key) {
                    setCarried(key.toStack(key.getMaxStackSize()));
                }
            }
            case PICKUP_ALL -> {
                ItemStack carried = getCarried();
                AEItemKey key = AEItemKey.of(carried);
                if (key != null && !GenericStack.isWrapped(carried)) {
                    for (int i = 0; i < resourceSlots && carried.getCount() < carried.getMaxStackSize(); i++) {
                        long amount = buffer(i).extract(localSlot(i), key, carried.getMaxStackSize() - carried.getCount(), Actionable.MODULATE);
                        carried.grow((int) amount);
                    }
                }
            }
            case QUICK_CRAFT, QUICK_MOVE -> { /* Resource slots do not participate in vanilla drag distribution. */ }
        }
        sync(player);
        return ItemStack.EMPTY;
    }

    private static ItemStack clickItem(GenericStackInv inventory, int slot, ItemStack carried, boolean input, boolean right) {
        var stored = inventory.getKey(slot);
        if (stored != null && !(stored instanceof AEItemKey) || GenericStack.isWrapped(carried)) { return carried; }
        var heldKey = AEItemKey.of(carried);
        int limit = stored instanceof AEItemKey item ? item.getMaxStackSize() : carried.getMaxStackSize();
        GenericStack held = heldKey == null ? null : new GenericStack(heldKey, carried.getCount());
        GenericStack result = HatchSlotActions.click(inventory, slot, held, limit, input, right);
        return result == null ? ItemStack.EMPTY : ((AEItemKey) result.what()).toStack((int) result.amount());
    }

    private static ItemStack takeItems(GenericStackInv inventory, int slot, int requested) {
        if (!(inventory.getKey(slot) instanceof AEItemKey key)) { return ItemStack.EMPTY; }
        long amount = inventory.extract(slot, key, Math.min(requested, key.getMaxStackSize()), Actionable.MODULATE);
        return amount == 0 ? ItemStack.EMPTY : key.toStack((int) amount);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (isClientSide() || !canInteractWith(player) || index < 0 || index >= inventorySlots.size()) { return ItemStack.EMPTY; }
        if (isResourceSlot(index)) {
            // Each iteration moves at most one native stack. The bound also limits work per click.
            for (int i = 0; i < 36; i++) {
                ItemStack moved = takeItems(buffer(index), localSlot(index), 64);
                if (moved.isEmpty()) { break; }
                mergeItemStack(moved, resourceSlots, inventorySlots.size(), true);
                if (!moved.isEmpty()) {
                    long restored = buffer(index).insert(localSlot(index), AEItemKey.of(moved), moved.getCount(), Actionable.MODULATE);
                    if (restored != moved.getCount()) { throw new IllegalStateException("Hatch rejected its own untransferred items"); }
                    break;
                }
            }
        } else if (hatch.input() != null) {
            Slot source = inventorySlots.get(index);
            ItemStack stack = source.getStack();
            AEItemKey key = AEItemKey.of(stack);
            if (key != null && !GenericStack.isWrapped(stack)) {
                long inserted = hatch.input().insert(key, stack.getCount(), Actionable.MODULATE, getActionSource());
                source.decrStackSize((int) inserted);
                source.onSlotChanged();
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void doAction(EntityPlayerMP player, InventoryAction action, int index, long id) {
        if (player.openContainer != this || !canInteractWith(player)) { return; }
        if (isResourceSlot(index) && index >= inputSlots
                && (action == InventoryAction.EMPTY_ITEM || action == InventoryAction.EMPTY_ENTIRE_ITEM)) { return; }
        super.doAction(player, action, index, id);
        syncInventoryActionState(player);
    }

    private void sync(EntityPlayer player) {
        if (player instanceof EntityPlayerMP serverPlayer) { syncInventoryActionState(serverPlayer); }
    }
}
