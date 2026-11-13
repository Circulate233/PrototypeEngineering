package com.circulation.prototype_eng.container;

import ae2.api.behaviors.ContainerItemStrategies;
import ae2.api.config.Actionable;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.util.AEColor;
import ae2.container.AEBaseContainer;
import ae2.container.SlotSemantics;
import ae2.container.guisync.GuiSync;
import ae2.container.slot.AppEngSlot;
import ae2.core.network.InitNetwork;
import ae2.core.network.serverbound.InventoryActionPacket;
import ae2.helpers.InventoryAction;
import ae2.items.misc.GenericResourcePackageItem;
import com.circulation.prototype_eng.api.gui.ResourceHatchGuiHost;
import com.circulation.prototype_eng.api.storage.FluidHatchSettings;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchGrouping;
import com.circulation.prototype_eng.api.storage.ResourceHatchSettings;
import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.storage.HatchGuiInventory;
import com.circulation.prototype_eng.storage.HatchPackageTransfer;
import com.circulation.prototype_eng.storage.HatchSlotActions;
import com.circulation.prototype_eng.util.ExternalEnumValues;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.SPacketSetSlot;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ContainerResourceHatch extends AEBaseContainer {
    private static final String VIEW = "resourceView";
    private static final String CLICK = "resourceClick";
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
    public final int inputLeft;
    public final ResourceLocation background;
    public final boolean scrollbars;
    public final ResourceHatchPresentation presentation = new ResourceHatchPresentation();
    private final ResourceHatchGuiHost tile;
    private final SlottedResourceHatch hatch;
    private final ResourceHatchWindow window;
    private final int inputSlots;
    private final int resourceSlots;
    @Nullable
    private final ResourceHatchSettings settings;
    @Nullable
    private final FluidHatchSettings fluidSettings;
    @GuiSync(3)
    public AEColor color = AEColor.TRANSPARENT;
    @GuiSync(2)
    public ResourceHatchWindowSnapshot snapshot = ResourceHatchWindowSnapshot.EMPTY;
    @GuiSync(0)
    public boolean autoInput;
    @GuiSync(1)
    public boolean autoOutput;
    private long requestedRevision = -1;
    private long lastQuickMoveRevision, lastQuickMoveTime;
    private int lastQuickMoveSlot = -1;
    private boolean lastQuickMoveInput;
    private AEKey lastQuickMoveKey;

    public ContainerResourceHatch(InventoryPlayer player, ResourceHatchGuiHost tile) {
        super(player, tile.blockEntity());
        this.tile = tile;
        settings = tile instanceof ResourceHatchSettings hatchSettings ? hatchSettings : null;
        fluidSettings = tile instanceof FluidHatchSettings hatchSettings ? hatchSettings : null;
        hatch = tile.storage();
        SlottedResourceStorage input = hatch.input();
        SlottedResourceStorage output = hatch.output();
        var layout = tile.layout();
        visibleRows = layout.visibleRows();
        columns = layout.columns();
        guiWidth = layout.width();
        guiHeight = layout.height();
        textureWidth = layout.textureWidth();
        resourceTop = layout.resourceTop();
        inputLeft = layout.inputLeft();
        outputLeft = layout.outputLeft();
        background = layout.background();
        scrollbars = layout.scrollbars();
        inputSlots = input == null ? 0 : columns * (visibleRows + 1);
        resourceSlots = inputSlots + (output == null ? 0 : columns * (visibleRows + 1));
        rows = ResourceHatchWindow.rows(Math.max(input == null ? 0 : input.size(), output == null ? 0 : output.size()), columns);
        window = new ResourceHatchWindow(hatch, columns, visibleRows);
        if (isServerSide()) snapshot = window.capture();
        playerTop = guiHeight - 84;
        addBuffer(input, true, inputLeft);
        addBuffer(output, false, outputLeft);
        addPlayerInventorySlots(layout.playerLeft(), playerTop);
        setGuiTitle(tile.guiTitle());
        syncSettings();
        registerClientAction("resourceColor", Integer.class, ordinal -> {
            if (canChangeSettings() && tile instanceof ResourceHatchGrouping grouping
                && ordinal >= 0 && ordinal < AEColor.VALUES.length)
                grouping.setResourceColor(AEColor.VALUES[ordinal]);
        });
        registerClientAction(VIEW, long[].class, 96, values -> {
            if (!canUseServerWindow() || values == null || values.length != 3
                || values[1] < 0 || values[1] > Integer.MAX_VALUE || values[2] < 0 || values[2] > Integer.MAX_VALUE)
                return;
            window.move(values[0], (int) values[1], (int) values[2]);
            sync(getPlayer());
        });
        registerClientAction(CLICK, long[].class, 128, this::receiveResourceClick);
        registerClientAction(AUTO_INPUT, () -> {
            if (canChangeSettings() && settings != null && tile.mode().hasInput())
                settings.setAutoInput(!settings.autoInput());
        });
        registerClientAction(AUTO_OUTPUT, () -> {
            if (canChangeSettings() && settings != null && tile.mode().hasOutput())
                settings.setAutoOutput(!settings.autoOutput());
        });
    }

    private static ItemStack clickItem(SlottedResourceStorage inventory, int slot, ItemStack carried, boolean input, boolean right) {
        if (GenericResourcePackageItem.isPackage(carried)) return insertPackage(inventory, slot, carried);
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

    private static void collectItems(@Nullable SlottedResourceStorage inventory, AEItemKey key, ItemStack carried) {
        if (inventory == null) return;
        inventory.context().lock();
        try {
            for (int slot = 0; slot < inventory.size() && carried.getCount() < carried.getMaxStackSize(); slot++)
                carried.grow((int) inventory.extract(slot, key, carried.getMaxStackSize() - carried.getCount(), Actionable.MODULATE));
        } finally {
            inventory.context().unlock();
        }
    }

    private static ItemStack insertPackage(SlottedResourceStorage inventory, int slot, ItemStack packageStack) {
        GenericStack packaged = GenericResourcePackageItem.unwrap(packageStack);
        if (packaged == null) return packageStack;
        GenericStack remainder = HatchSlotActions.insertPackage(inventory, slot, packaged);
        if (remainder == packaged) return packageStack;
        return remainder == null ? ItemStack.EMPTY : GenericResourcePackageItem.wrap(remainder.what(), remainder.amount());
    }

    public int inputRows() {
        return ResourceHatchWindow.rows(typeSlots(true), columns);
    }

    public HatchTier tier() {
        return tile.tier();
    }

    public int outputRows() {
        return ResourceHatchWindow.rows(typeSlots(false), columns);
    }

    public boolean hasAutomationSettings() {
        return settings != null;
    }

    public void toggleInput() {
        sendClientAction(AUTO_INPUT);
    }

    public void toggleOutput() {
        sendClientAction(AUTO_OUTPUT);
    }

    private boolean canChangeSettings() {
        var player = getPlayer();
        return canInteractWith(player)
            && player.world.isBlockModifiable(player, tile.blockEntity().getPos());
    }

    public boolean hasColorGrouping() {
        return tile instanceof ResourceHatchGrouping;
    }

    public void setResourceColor(AEColor next) {
        sendClientAction("resourceColor", next.ordinal());
    }

    private void syncSettings() {
        if (tile instanceof ResourceHatchGrouping grouping) color = grouping.resourceColor();
        if (settings != null) {
            autoInput = settings.autoInput();
            autoOutput = settings.autoOutput();
        }
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            syncSettings();
            if (canInteractWith(getPlayer())) snapshot = window.capture();
        }
        super.broadcastChanges();
    }

    @Override
    public void onServerDataSync(ShortSet updatedFields) {
        snapshot = presentation.receive(snapshot);
        super.onServerDataSync(updatedFields);
    }

    private void addBuffer(@Nullable SlottedResourceStorage inv, boolean input, int left) {
        if (inv == null) {
            return;
        }
        var display = new HatchGuiInventory(columns * (visibleRows + 1), isClientSide(), cell -> snapshot.side(input).stack(cell));
        for (int i = 0; i < display.size(); i++) {
            var slot = new AppEngSlot(display, i, left + (i % columns) * 18 + 1, resourceTop + (i / columns) * 18 + 1) {
                @Override
                public boolean isItemValid(ItemStack stack) {
                    int logical = snapshot.side(input).logicalSlot(getSlotIndex());
                    if (logical < 0) return false;
                    if (GenericResourcePackageItem.isPackage(stack)) {
                        var packaged = GenericResourcePackageItem.unwrap(stack);
                        return packaged != null && (isClientSide() || inv.isAllowedIn(logical, packaged.what()));
                    }
                    var key = AEItemKey.of(stack);
                    return input && !GenericStack.isWrapped(stack) && key != null
                        && (isClientSide() || inv.isAllowedIn(logical, key));
                }

                @Override
                public boolean canTakeStack(EntityPlayer player) {
                    int logical = snapshot.side(input).logicalSlot(getSlotIndex());
                    return logical >= 0 && (isClientSide() ? display.getDisplayKey(getSlotIndex()) instanceof AEItemKey
                        : inv.isValid() && inv.availableAmount(logical) > 0 && storedKey(inv, logical) instanceof AEItemKey);
                }

                @Override
                public @NotNull ItemStack decrStackSize(int amount) {
                    // All extraction needs a versioned resource action, never an unversioned native slot write.
                    return ItemStack.EMPTY;
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

    public int typeSlots(boolean input) {
        return snapshot.side(input).totalSlots();
    }

    /**
     * Retained for callers interested in distinct keys, rather than occupied slots.
     */
    @SuppressWarnings("unused")
    public int usedTypes(boolean input) {
        return snapshot.side(input).usedTypes();
    }

    public int usedSlots(boolean input) {
        return snapshot.side(input).usedSlots();
    }

    public long capacity(int index, AEKey key) {
        var side = snapshot.side(isInputSlot(index));
        int cell = resourceCell(index);
        var stack = side.stack(cell);
        return stack != null && stack.what().equals(key) ? side.entries().get(cell).capacity() : 0;
    }

    public int resourceCell(int index) {
        return isInputSlot(index) ? index : index - inputSlots;
    }

    public int logicalSlot(int index) {
        return snapshot.side(isInputSlot(index)).logicalSlot(resourceCell(index));
    }

    public boolean resourceViewReady() {
        return requestedRevision != snapshot.revision() && presentation.accepts(snapshot.revision(), presentation.gestureRevision());
    }

    public void requestView(int inputRow, int outputRow) {
        if (!resourceViewReady() || snapshot.input().firstSlot() / columns == inputRow
            && snapshot.output().firstSlot() / columns == outputRow) return;
        requestedRevision = snapshot.revision();
        sendClientAction(VIEW, new long[]{snapshot.revision(), inputRow, outputRow});
    }

    public void clickResource(int index, int button, ClickType click, boolean matching) {
        sendResourceClick(index, button, click, matching ? 1 : 0);
    }

    public void doubleClickResource(int index, int button) {
        sendResourceClick(index, button, ClickType.PICKUP, 2);
    }

    private void sendResourceClick(int index, int button, ClickType click, int mode) {
        if (!resourceViewReady() || !isResourceSlot(index) || logicalSlot(index) < 0) return;
        sendClientAction(CLICK, new long[]{snapshot.revision(), isInputSlot(index) ? 1 : 0,
            logicalSlot(index), button, click.ordinal(), mode});
    }

    public void resourceAction(InventoryAction action, int index) {
        if (resourceViewReady() && isResourceSlot(index) && logicalSlot(index) >= 0)
            InitNetwork.sendToServer(new InventoryActionPacket(windowId, action, index, snapshot.revision()));
    }

    private boolean canUseServerWindow() {
        return isServerSide() && canInteractWith(getPlayer());
    }

    private void receiveResourceClick(long[] values) {
        if (!canUseServerWindow() || values == null || values.length != 6 || values[1] < 0 || values[1] > 1
            || values[2] < 0 || values[2] > Integer.MAX_VALUE || values[3] < 0 || values[3] > 40
            || values[4] < 0 || values[4] >= ExternalEnumValues.CLICK_TYPES.length || values[5] < 0 || values[5] > 2)
            return;
        boolean input = values[1] == 1;
        int logical = (int) values[2];
        ClickType click = ExternalEnumValues.CLICK_TYPES[(int) values[4]];
        window.interact(values[0], input, logical, inventory -> {
            if (values[5] == 2 && click == ClickType.PICKUP && values[3] <= 1)
                doubleClickResource(inventory, logical, input, (int) values[3]);
            else if (values[5] == 1 && click == ClickType.QUICK_MOVE) {
                var current = storedKey(inventory, logical);
                if (lastQuickMoveRevision == values[0] && lastQuickMoveSlot == logical && lastQuickMoveInput == input
                    && System.currentTimeMillis() - lastQuickMoveTime <= 1_000
                    && (current == null || current.equals(lastQuickMoveKey)))
                    transferMatching(inventory, lastQuickMoveKey);
                lastQuickMoveKey = null;
            } else if (click == ClickType.QUICK_MOVE) {
                lastQuickMoveKey = null;
                ResourceHatchBulkTransfer.withSlotKey(inventory, logical, null, key -> {
                    if (!(key instanceof AEItemKey item)) return;
                    lastQuickMoveRevision = values[0];
                    lastQuickMoveSlot = logical;
                    lastQuickMoveInput = input;
                    lastQuickMoveKey = key;
                    lastQuickMoveTime = System.currentTimeMillis();
                    transferResourceItemsLocked(inventory, logical, item);
                });
            } else {
                clickResource(inventory, logical, input, (int) values[3], click, getPlayer());
            }
        });
        sync(getPlayer());
    }

    public HatchMode mode() {
        return tile.mode();
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return hatch.isValid() && tile.storage() == hatch && super.canInteractWith(player);
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
        if (!canInteractWith(player) || isResourceSlot(index)) return ItemStack.EMPTY;
        if (click == ClickType.QUICK_MOVE) {
            transferStackInSlot(player, index);
            sync(player);
            return ItemStack.EMPTY;
        }
        return super.slotClick(index, button, click, player);
    }

    private void clickResource(SlottedResourceStorage inventory, int slot, boolean input, int button, ClickType click, EntityPlayer player) {
        if (click == ClickType.QUICK_MOVE) {
            transferResourceItems(inventory, slot);
            return;
        }
        if (click == ClickType.PICKUP_ALL) {
            collectItems();
            return;
        }
        inventory.context().lock();
        try {
            switch (click) {
                case PICKUP -> {
                    if (button == 0 || button == 1)
                        setCarried(clickItem(inventory, slot, getCarried(), input, button == 1));
                }
                case SWAP -> {
                    if ((button >= 0 && button < 9) || button == 40) {
                        ItemStack old = player.inventory.getStackInSlot(button);
                        player.inventory.setInventorySlotContents(button, clickItem(inventory, slot, old, input, false));
                    }
                }
                case THROW -> {
                    if (getCarried().isEmpty() && (button == 0 || button == 1)) {
                        ItemStack dropped = takeItems(inventory, slot, button == 0 ? 1 : 64);
                        if (!dropped.isEmpty()) player.dropItem(dropped, true);
                    }
                }
                case CLONE -> {
                    if (player.capabilities.isCreativeMode && getCarried().isEmpty() && storedKey(inventory, slot) instanceof AEItemKey key)
                        setCarried(key.toStack(key.getMaxStackSize()));
                }
                case QUICK_CRAFT -> { /* Resource slots are not vanilla ItemStack drag targets. */ }
            }
        } finally {
            inventory.context().unlock();
        }
    }

    private void collectItems() {
        ItemStack carried = getCarried();
        AEItemKey key = AEItemKey.of(carried);
        if (key == null || GenericStack.isWrapped(carried)) return;
        collectItems(hatch.input(), key, carried);
        collectItems(hatch.output(), key, carried);
    }

    private void transferMatching(SlottedResourceStorage inventory, @Nullable AEKey key) {
        if (!(key instanceof AEItemKey)) return;
        for (int slot = 0; slot < inventory.size(); slot++)
            transferResourceItems(inventory, slot, key);
    }

    private void doubleClickResource(SlottedResourceStorage inventory, int slot, boolean input, int button) {
        if (GenericResourcePackageItem.isPackage(getCarried())) {
            setCarried(insertPackage(inventory, slot, getCarried()));
        } else {
            ResourceHatchBulkTransfer.doubleClick(inventory, slot, input, button, new BulkPlayerSlots(),
                new ResourceHatchBulkTransfer.HeldContainers() {
                    @Override
                    public boolean isItem(AEKey key) {
                        return key instanceof AEItemKey;
                    }

                    @Override
                    public boolean canInsertCursor() {
                        return !GenericStack.isWrapped(getCarried());
                    }

                    @Override
                    public boolean tryEmpty() {
                        if (ContainerItemStrategies.getEmptyingAction(getCarried()) == null) return false;
                        transferContainerItem(inventory, slot, InventoryAction.EMPTY_ENTIRE_ITEM, false);
                        return true;
                    }

                    @Override
                    public boolean tryFill(GenericStack selected) {
                        var context = ContainerItemStrategies.findCarriedContextForKey(selected.what(), getPlayer(), ContainerResourceHatch.this);
                        if (context == null || context.insert(selected.what(), selected.amount(), Actionable.SIMULATE) <= 0)
                            return false;
                        transferContainerItem(inventory, slot, InventoryAction.FILL_ENTIRE_ITEM, true);
                        return true;
                    }
                });
        }
        getPlayerInventory().markDirty();
        if (getPlayer() instanceof EntityPlayerMP player)
            player.connection.sendPacket(new SPacketSetSlot(-2, 40, player.inventory.getStackInSlot(40).copy()));
    }

    private ItemStack insertPackageFromPlayer(ItemStack packageStack) {
        GenericStack packaged = GenericResourcePackageItem.unwrap(packageStack);
        if (packaged == null) return packageStack;
        GenericStack remainder = HatchPackageTransfer.insert(hatch.input(), hatch.output(), packaged);
        if (remainder == packaged) return packageStack;
        return remainder == null ? ItemStack.EMPTY : GenericResourcePackageItem.wrap(remainder.what(), remainder.amount());
    }

    private void transferResourceItems(SlottedResourceStorage inventory, int slot) {
        transferResourceItems(inventory, slot, null);
    }

    private void transferResourceItems(SlottedResourceStorage inventory, int slot, @Nullable AEKey expected) {
        ResourceHatchBulkTransfer.withSlotKey(inventory, slot, expected, key -> {
            if (key instanceof AEItemKey item) transferResourceItemsLocked(inventory, slot, item);
        });
    }

    private void transferResourceItemsLocked(SlottedResourceStorage inventory, int slot, AEItemKey key) {
        inventory.context().requireLock();
        // The same expected key remains bound through extraction and exact compensation.
        for (int i = 0; i < 36; i++) {
            long amount = inventory.extract(slot, key, Math.min(64, key.getMaxStackSize()), Actionable.MODULATE);
            if (amount == 0) break;
            ItemStack moved = key.toStack((int) amount);
            mergeItemStack(moved, resourceSlots, inventorySlots.size(), true);
            if (!moved.isEmpty()) {
                long restored = inventory.insert(slot, key, moved.getCount(), Actionable.MODULATE);
                if (restored != moved.getCount())
                    throw new IllegalStateException("Hatch rejected its own untransferred items");
                break;
            }
        }
    }

    @Override
    public @NotNull ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (isClientSide() || !canInteractWith(player) || index < resourceSlots || index >= inventorySlots.size())
            return ItemStack.EMPTY;
        Slot source = inventorySlots.get(index);
        ItemStack stack = source.getStack();
        if (GenericResourcePackageItem.isPackage(stack)) {
            ItemStack remaining = insertPackageFromPlayer(stack);
            if (remaining != stack) {
                source.putStack(remaining);
                source.onSlotChanged();
                player.inventory.markDirty();
            }
            return ItemStack.EMPTY;
        }
        SlottedResourceStorage input = hatch.input();
        if (input == null) return ItemStack.EMPTY;
        input.context().lock();
        try {
            AEItemKey key = AEItemKey.of(stack);
            if (key != null && !GenericStack.isWrapped(stack)) {
                // The storage owns full-side target selection, including slots outside the window.
                long inserted = input.insert(key, stack.getCount(), Actionable.MODULATE);
                source.decrStackSize((int) inserted);
                source.onSlotChanged();
            }
        } finally {
            input.context().unlock();
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void doAction(EntityPlayerMP player, InventoryAction action, int index, long id) {
        if (!canUseServerWindow() || player != getPlayer()) return;
        if (isResourceSlot(index)) {
            boolean input = isInputSlot(index);
            int logical = logicalSlot(index);
            window.interact(id, input, logical, inventory -> {
                if (action == InventoryAction.CREATIVE_DUPLICATE) {
                    clickResource(inventory, logical, input, 2, ClickType.CLONE, player);
                } else if (action == InventoryAction.MOVE_REGION) {
                    for (int slot = 0; slot < inventory.size(); slot++) transferResourceItems(inventory, slot);
                } else {
                    boolean fill = action == InventoryAction.FILL_ITEM || action == InventoryAction.FILL_ENTIRE_ITEM;
                    boolean empty = action == InventoryAction.EMPTY_ITEM || action == InventoryAction.EMPTY_ENTIRE_ITEM;
                    if (fill || empty && input) transferContainerItem(inventory, logical, action, fill);
                }
            });
        } else {
            super.doAction(player, action, index, id);
        }
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

    private final class BulkPlayerSlots implements ResourceHatchBulkTransfer.PlayerSlots {
        @Override
        public int size() {
            return 38;
        }

        private int inventorySlot(int slot) {
            return slot == 37 ? 40 : slot - 1;
        }

        @Override
        public @Nullable GenericStack get(int slot) {
            ItemStack item = slot == 0 ? getCarried() : getPlayerInventory().getStackInSlot(inventorySlot(slot));
            var key = AEItemKey.of(item);
            return key == null ? null : new GenericStack(key, item.getCount());
        }

        @Override
        public void set(int slot, @Nullable GenericStack stack) {
            ItemStack item = stack == null ? ItemStack.EMPTY : ((AEItemKey) stack.what()).toStack((int) stack.amount());
            if (slot == 0) setCarried(item);
            else getPlayerInventory().setInventorySlotContents(inventorySlot(slot), item);
        }

        @Override
        public long limit(int slot, AEKey key) {
            return slot > 0 && isPlayerInventorySlotLocked(inventorySlot(slot)) ? 0 : ((AEItemKey) key).getMaxStackSize();
        }
    }
}
