package com.circulation.prototype_eng.container;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.container.AEBaseContainer;
import ae2.container.SlotSemantics;
import ae2.container.slot.AppEngSlot;
import ae2.helpers.InventoryAction;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.storage.HatchGuiInventory;
import com.circulation.prototype_eng.storage.HatchSlotActions;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;
import ae2.api.stacks.AEFluidKey;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.gui.ResourceHatchGuiHost;
import com.circulation.prototype_eng.api.storage.FluidHatchSettings;
import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceHatchSettings;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.localization.HatchGuiTitle;
public final class ResourceHatchContainer extends AEBaseContainer {
    private static final String AUTO_INPUT = "autoInput";
    private static final String AUTO_OUTPUT = "autoOutput";
    public final int columns;
    public final int guiWidth;
    public final int guiHeight;
    public final int textureWidth;
    public final int visibleRows;
    public final int resourceTop;
    public final int playerTop;
    public final int rows;
    public final int outputLeft;
    private final ResourceHatchGuiHost tile;
    private final SlottedResourceHatch hatch;
    private final int inputSlots;
    private final int resourceSlots;
    @Nullable
    private final ResourceHatchSettings settings;
    @Nullable
    private final FluidHatchSettings fluidSettings;
    @GuiSync(0)
    public boolean autoInput;
    @GuiSync(1)
    public boolean autoOutput;

    public ResourceHatchContainer(InventoryPlayer player, ResourceHatchGuiHost tile) {
        super(player, tile.blockEntity());
        this.tile = tile;
        settings = tile instanceof ResourceHatchSettings hatchSettings ? hatchSettings : null;
        fluidSettings = tile instanceof FluidHatchSettings hatchSettings ? hatchSettings : null;
        hatch = tile.storage();
        SlottedResourceStorage input = hatch.input();
        SlottedResourceStorage output = hatch.output();
        inputSlots = input == null ? 0 : input.size();
        resourceSlots = inputSlots + (output == null ? 0 : output.size());
        boolean mixed = tile.mode() == HatchMode.MIXED;
        int level = tile.tier().level();
        visibleRows = level <= 2 ? 3 : level <= 4 ? 4 : 7;
        columns = mixed ? (level <= 4 ? 4 : 5) : (level <= 2 ? 8 : 9);
        guiWidth = mixed ? (level <= 4 ? 251 : 245) : (level <= 2 ? 225 : level <= 4 ? 243 : 213);
        guiHeight = level <= 2 ? 168 : level <= 4 ? 186 : mixed ? 240 : 239;
        textureWidth = mixed && level >= 5 ? 304 : 256;
        resourceTop = mixed ? 24 : 25;
        rows = (Math.max(inputSlots, resourceSlots - inputSlots) + columns - 1) / columns;
        playerTop = guiHeight - 84;
        outputLeft = mixed ? (level <= 2 ? 114 : level <= 4 ? 123 : 137) : 26;
        addBuffer(input, true, 26);
        addBuffer(output, false, outputLeft);
        addPlayerInventorySlots(27, playerTop);
        setGuiTitle(HatchGuiTitle.of(false, tile.mode(), tile.tier()).text());
        syncSettings();
        registerClientAction(AUTO_INPUT, () -> {
            if (canChangeSettings() && settings != null && tile.mode().hasInput()) settings.setAutoInput(!settings.autoInput());
        });
        registerClientAction(AUTO_OUTPUT, () -> {
            if (canChangeSettings() && settings != null && tile.mode().hasOutput()) settings.setAutoOutput(!settings.autoOutput());
        });
    }

    public int inputRows() { return (inputSlots + columns - 1) / columns; }
    public HatchTier tier() { return tile.tier(); }
    public int outputRows() { return (resourceSlots - inputSlots + columns - 1) / columns; }
    public boolean hasAutomationSettings() { return settings != null; }
    public void toggleInput() { sendClientAction(AUTO_INPUT); }
    public void toggleOutput() { sendClientAction(AUTO_OUTPUT); }

    private boolean canChangeSettings() {
        var player = getPlayer();
        var server = player.world.getMinecraftServer();
        return isServerSide() && server != null && server.isCallingFromMinecraftThread()
            && player.openContainer == this && canInteractWith(player)
            && player.world.isBlockModifiable(player, tile.blockEntity().getPos());
    }

    private void syncSettings() {
        if (settings != null) {
            autoInput = settings.autoInput();
            autoOutput = settings.autoOutput();
        }
    }

    @Override public void broadcastChanges() {
        if (isServerSide()) syncSettings();
        super.broadcastChanges();
    }

    private static ItemStack clickItem(SlottedResourceStorage inventory, int slot, ItemStack carried, boolean input, boolean right) {
        var stored = storedKey(inventory, slot);
        if (stored != null && !(stored instanceof AEItemKey) || GenericStack.isWrapped(carried)) {
            return carried;
        }
        var heldKey = AEItemKey.of(carried);
        int limit = stored instanceof AEItemKey item ? item.getMaxStackSize() : carried.getMaxStackSize();
        GenericStack held = heldKey == null ? null : new GenericStack(heldKey, carried.getCount());
        GenericStack result = HatchSlotActions.click(inventory, slot, held, limit, input, right);
        return result == null ? ItemStack.EMPTY : ((AEItemKey) result.what()).toStack((int) result.amount());
    }

    private static ItemStack takeItems(SlottedResourceStorage inventory, int slot, int requested) {
        inventory.context().lock();
        try {
            if (!(storedKey(inventory, slot) instanceof AEItemKey key)) {
                return ItemStack.EMPTY;
            }
            long amount = inventory.extract(slot, key, Math.min(requested, key.getMaxStackSize()), Actionable.MODULATE);
            return amount == 0 ? ItemStack.EMPTY : key.toStack((int) amount);
        } finally {
            inventory.context().unlock();
        }
    }

    @Nullable
    private static AEKey storedKey(SlottedResourceStorage inventory, int slot) {
        GenericStack stack = inventory.getStack(slot);
        return stack == null ? null : stack.what();
    }

    private void addBuffer(@Nullable SlottedResourceStorage inv, boolean input, int left) {
        if (inv == null) {
            return;
        }
        var display = new HatchGuiInventory(inv, isClientSide());
        for (int i = 0; i < inv.size(); i++) {
            var slot = new AppEngSlot(display, i, left + (i % columns) * 18 + 1, resourceTop + (i / columns) * 18 + 1) {
                @Override
                public boolean isItemValid(ItemStack stack) {
                    var key = AEItemKey.of(stack);
                    return input && !GenericStack.isWrapped(stack) && key != null && inv.isAllowedIn(getSlotIndex(), key);
                }

                @Override
                public boolean canTakeStack(EntityPlayer player) {
                    return isClientSide() ? display.getDisplayKey(getSlotIndex()) instanceof AEItemKey
                        : inv.isValid() && inv.availableAmount(getSlotIndex()) > 0
                        && storedKey(inv, getSlotIndex()) instanceof AEItemKey;
                }

                @Override
                public @NotNull ItemStack decrStackSize(int amount) {
                    return isClientSide() ? ItemStack.EMPTY : takeItems(inv, getSlotIndex(), amount);
                }
            };
            slot.setNotDraggable();
            addSlot(slot, input ? SlotSemantics.MACHINE_INPUT : SlotSemantics.MACHINE_OUTPUT);
        }
    }

    public boolean isResourceSlot(int index) {
        return index >= 0 && index < resourceSlots;
    }

    public boolean isInputSlot(int index) {
        return index >= 0 && index < inputSlots;
    }

    public HatchMode mode() {
        return tile.mode();
    }

    private SlottedResourceStorage buffer(int index) {
        return Objects.requireNonNull(index < inputSlots ? hatch.input() : hatch.output(), "Missing resource slot storage");
    }

    private int localSlot(int index) {
        return index < inputSlots ? index : index - inputSlots;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        var entity = tile.blockEntity();
        return entity == getTarget() && !entity.isInvalid() && player.world == entity.getWorld()
            && player.world.isBlockLoaded(entity.getPos(), false) && player.world.getTileEntity(entity.getPos()) == entity
            && hatch.isValid() && tile.storage() == hatch && super.canInteractWith(player);
    }

    @Override
    public boolean canMergeSlot(@NotNull ItemStack stack, Slot slot) {
        // Vanilla collect/merge manipulates ItemStack references; resource slots expose display copies.
        return !isResourceSlot(slot.slotNumber) && super.canMergeSlot(stack, slot);
    }

    @Override
    public void swapSlotContents(int first, int second) {
        // AE2's generic swap packet moves display ItemStacks. Resource exchanges use our real-quantity path.
        if (!canInteractWith(getPlayer()) || isResourceSlot(first) || isResourceSlot(second)) {
            return;
        }
        super.swapSlotContents(first, second);
    }

    @Override
    public @NotNull ItemStack slotClick(int index, int button, ClickType click, EntityPlayer player) {
        if (!canInteractWith(player)) {
            return ItemStack.EMPTY;
        }
        if (click == ClickType.QUICK_MOVE) {
            transferStackInSlot(player, index);
            sync(player);
            return ItemStack.EMPTY;
        }
        if (!isResourceSlot(index)) {
            return super.slotClick(index, button, click, player);
        }
        if (isClientSide()) {
            return ItemStack.EMPTY;
        }
        if (click == ClickType.PICKUP_ALL) {
            collectItems();
            sync(player);
            return ItemStack.EMPTY;
        }
        SlottedResourceStorage inventory = buffer(index);
        int slot = localSlot(index);
        inventory.context().lock();
        try {
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
                        if (!dropped.isEmpty()) {
                            player.dropItem(dropped, true);
                        }
                    }
                }
                case CLONE -> {
                    if (player.capabilities.isCreativeMode && getCarried().isEmpty() && storedKey(inventory, slot) instanceof AEItemKey key) {
                        setCarried(key.toStack(key.getMaxStackSize()));
                    }
                }
                case QUICK_CRAFT -> { /* Resource slots do not participate in vanilla drag distribution. */ }
            }
        } finally {
            inventory.context().unlock();
        }
        sync(player);
        return ItemStack.EMPTY;
    }

    private void collectItems() {
        ItemStack carried = getCarried();
        AEItemKey key = AEItemKey.of(carried);
        if (key != null && !GenericStack.isWrapped(carried)) {
            // Lock one side at a time; never retain an input lock while entering output.
            for (int i = 0; i < resourceSlots && carried.getCount() < carried.getMaxStackSize(); i++) {
                SlottedResourceStorage inventory = buffer(i);
                inventory.context().lock();
                try {
                    long amount = inventory.extract(localSlot(i), key, carried.getMaxStackSize() - carried.getCount(), Actionable.MODULATE);
                    carried.grow((int) amount);
                } finally {
                    inventory.context().unlock();
                }
            }
        }
    }

    @Override
    public @NotNull ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (isClientSide() || !canInteractWith(player) || index < 0 || index >= inventorySlots.size()) {
            return ItemStack.EMPTY;
        }
        if (isResourceSlot(index)) {
            SlottedResourceStorage inventory = buffer(index);
            inventory.context().lock();
            try {
                // Each iteration moves at most one native stack. The bound also limits work per click.
                for (int i = 0; i < 36; i++) {
                    ItemStack moved = takeItems(inventory, localSlot(index), 64);
                    if (moved.isEmpty()) {
                        break;
                    }
                    mergeItemStack(moved, resourceSlots, inventorySlots.size(), true);
                    if (!moved.isEmpty()) {
                        long restored = inventory.insert(localSlot(index), Objects.requireNonNull(AEItemKey.of(moved)), moved.getCount(), Actionable.MODULATE);
                        if (restored != moved.getCount()) {
                            throw new IllegalStateException("Hatch rejected its own untransferred items");
                        }
                        break;
                    }
                }
            } finally {
                inventory.context().unlock();
            }
        } else {
            SlottedResourceStorage input = hatch.input();
            if (input == null) {
                return ItemStack.EMPTY;
            }
            input.context().lock();
            try {
                Slot source = inventorySlots.get(index);
                ItemStack stack = source.getStack();
                AEItemKey key = AEItemKey.of(stack);
                if (key != null && !GenericStack.isWrapped(stack)) {
                    long inserted = input.insert(key, stack.getCount(), Actionable.MODULATE);
                    source.decrStackSize((int) inserted);
                    source.onSlotChanged();
                }
            } finally {
                input.context().unlock();
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void doAction(EntityPlayerMP player, InventoryAction action, int index, long id) {
        if (player.openContainer != this || !canInteractWith(player)) {
            return;
        }
        if (isResourceSlot(index)) {
            boolean fill = action == InventoryAction.FILL_ITEM || action == InventoryAction.FILL_ENTIRE_ITEM;
            boolean empty = action == InventoryAction.EMPTY_ITEM || action == InventoryAction.EMPTY_ENTIRE_ITEM;
            if (empty && !isInputSlot(index)) {
                return;
            }
            if (fill || empty) {
                transferContainerItem(buffer(index), localSlot(index), action, fill);
                syncInventoryActionState(player);
                return;
            }
        }
        super.doAction(player, action, index, id);
        syncInventoryActionState(player);
    }

    private void transferContainerItem(SlottedResourceStorage inventory, int slot, InventoryAction action, boolean fill) {
        inventory.context().lock();
        try {
            if (fill) {
                AEKey key = storedKey(inventory, slot);
                if (key != null) {
                    handleFillingHeldItem(new FillingSource() {
                        @Override
                        public long extract(long amount, Actionable mode) {
                            long limited = key instanceof AEFluidKey && fluidSettings != null
                                ? fluidSettings.limitFluidOutput(amount) : amount;
                            return inventory.extract(slot, key, limited, mode);
                        }

                        @Override
                        public long insert(AEKey what, long amount, Actionable mode) {
                            // Exact failed-fill compensation is never clipped by an automation limit.
                            return inventory.insert(slot, what, amount, mode);
                        }
                    }, key, action == InventoryAction.FILL_ENTIRE_ITEM);
                }
            } else {
                handleEmptyHeldItem((what, amount, mode) -> {
                    long limited = what instanceof AEFluidKey && fluidSettings != null
                        ? fluidSettings.limitFluidInput(amount) : amount;
                    return inventory.insert(slot, what, limited, mode);
                },
                    action == InventoryAction.EMPTY_ENTIRE_ITEM);
            }
        } finally {
            inventory.context().unlock();
        }
    }

    private void sync(EntityPlayer player) {
        if (player instanceof EntityPlayerMP serverPlayer) {
            syncInventoryActionState(serverPlayer);
        }
    }
}
