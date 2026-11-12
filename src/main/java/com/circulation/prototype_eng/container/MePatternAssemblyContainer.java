package com.circulation.prototype_eng.container;

import ae2.api.behaviors.ContainerItemStrategies;
import ae2.api.config.Actionable;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.inventories.InternalInventory;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.storage.IPatternAccessTermContainerHost;
import ae2.api.util.AEColor;
import ae2.container.AEBaseContainer;
import ae2.container.SlotSemantics;
import ae2.container.guisync.GuiSync;
import ae2.container.me.patternaccess.IPatternAccess;
import ae2.container.slot.AppEngSlot;
import ae2.container.slot.RestrictedInputSlot;
import ae2.crafting.pattern.AEProcessingPattern;
import ae2.core.gui.PatternContainerGuiReturnContext;
import ae2.helpers.InventoryAction;
import ae2.items.misc.GenericResourcePackageItem;
import com.circulation.prototype_eng.localization.MeAssemblyText;
import com.circulation.prototype_eng.storage.HatchGuiInventory;
import com.circulation.prototype_eng.storage.MePatternAssemblyBlockEntity;
import com.circulation.prototype_eng.storage.UnboundedResourceBuffer;
import com.circulation.prototype_eng.util.ExternalEnumValues;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import org.jetbrains.annotations.NotNull;
import java.util.Objects;
import java.util.function.LongUnaryOperator;

/** Versioned native pattern slots and manual catalyst transfers; ME input/output caches stay read-only. */
public final class MePatternAssemblyContainer extends AEBaseContainer {
    public static final int COLUMNS = 3;
    private static final int[] COUNTS = {18, 12, 30};
    private static final int DISPLAY_END = 36 + 18 + 12 + 30;
    private static final String VIEW = "patternView", CLICK = "patternClick", ACTION = "patternAction", COLOR = "patternColor";
    private final MePatternAssemblyBlockEntity tile;
    private final int[] rows = new int[3];
    private final int[] requestedRows = {-1, -1, -1};
    private int page;
    private boolean nativePatternAction;
    private long presentedRevision = -1;
    @GuiSync(0)
    public MePatternAssemblySnapshot snapshot = MePatternAssemblySnapshot.EMPTY;
    @GuiSync(1)
    public AEColor color = AEColor.TRANSPARENT;

    public MePatternAssemblyContainer(InventoryPlayer player, MePatternAssemblyBlockEntity tile) {
        super(player, tile);
        this.tile = Objects.requireNonNull(tile);
        PatternContainerGuiReturnContext.initializeContainer(this);
        if (isServerSide()) capture();
        var patterns = new PatternPageInventory();
        for (int cell = 0; cell < 36; cell++) {
            var slot = new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.PROVIDER_PATTERN, patterns, cell,
                102 + cell % 9 * 18, 25 + cell / 9 * 18);
            slot.setStackLimit(1);
            slot.setNotDraggable();
            addSlotToContainer(slot, SlotSemantics.ENCODED_PATTERN);
        }
        for (int kind = 0; kind < 3; kind++) addBuffer(kind);
        addPlayerInventorySlots(101, 112);
        setGuiTitle(MeAssemblyText.PatternAssembly.text());
        registerClientAction(VIEW, long[].class, 96, this::receiveView);
        registerClientAction(CLICK, long[].class, 160, this::receiveClick);
        registerClientAction(ACTION, long[].class, 128, this::receiveAction);
        registerClientAction(COLOR, long[].class, 64, values -> {
            if (!canEdit() || values == null || values.length != 2 || values[0] != tile.patternRevision()
                || values[1] < 0 || values[1] >= AEColor.VALUES.length) return;
            if (!tile.setInputColor(AEColor.VALUES[(int) values[1]])) {
                capture();
                if (getPlayer() instanceof EntityPlayerMP serverPlayer) syncInventoryActionState(serverPlayer);
                return;
            }
            capture();
        });
    }

    @Override
    protected boolean canInteractiveDistance(@NotNull EntityPlayer player, @NotNull TileEntity tileEntityHost) {
        if (!hasExternalGuiReturn()) return super.canInteractiveDistance(player, tileEntityHost);
        if (!(getReturnToContainerOverride() instanceof AEBaseContainer terminal) || !(terminal instanceof IPatternAccess)
            || terminal == this || terminal.getPlayer() != player || !terminal.canInteractWith(player)
            || !(terminal.getTarget() instanceof IPatternAccessTermContainerHost host) || !host.getLinkStatus().connected()
            || !tile.hasWorld() || !tile.getWorld().isBlockLoaded(tile.getPos())
            || tile.getWorld().getTileEntity(tile.getPos()) != tile) return false;
        var terminalNode = host.getGridNode();
        var assemblyNode = tile.getActionableNode();
        return terminalNode != null && terminalNode.isActive() && assemblyNode != null && assemblyNode.isActive()
            && terminalNode.grid() == assemblyNode.grid();
    }

    private void addBuffer(int kind) {
        var display = new HatchGuiInventory(COUNTS[kind], isClientSide(), cell -> snapshot.side(kind).stack(cell));
        for (int cell = 0; cell < display.size(); cell++) {
            var slot = new AppEngSlot(display, cell, left(kind) + cell % 3 * 18 + 1, top(kind) + cell / 3 * 18 + 1) {
                @Override
                public boolean isItemValid(@NotNull ItemStack stack) {
                    return false;
                }

                @Override
                public boolean canTakeStack(@NotNull EntityPlayer player) {
                    return false;
                }

                @Override
                public @NotNull ItemStack decrStackSize(int amount) {
                    return ItemStack.EMPTY;
                }
            };
            slot.setNotDraggable();
            addSlotToContainer(slot, SlotSemantics.STORAGE);
        }
    }

    public static int left(int kind) {
        return kind == 2 ? 267 : 26;
    }

    public static int top(int kind) {
        return kind == 1 ? 134 : 24;
    }

    public static int height(int kind) {
        return switch (kind) {
            case 0 -> 105;
            case 1 -> 54;
            case 2 -> 164;
            default -> throw new IllegalArgumentException("Invalid pattern buffer");
        };
    }

    public boolean isPatternSlot(int index) {
        return index >= 0 && index < 36;
    }

    public boolean isResourceSlot(int index) {
        return index >= 36 && index < DISPLAY_END;
    }

    public int kind(int index) {
        return index < 54 ? 0 : index < 66 ? 1 : 2;
    }

    public int cell(int index) {
        return index - (kind(index) == 0 ? 36 : kind(index) == 1 ? 54 : 66);
    }

    public void completeFrame() {
        presentedRevision = snapshot.revision();
    }

    public void requestView(int input, int catalyst, int output) {
        if (requestedRows[0] == input && requestedRows[1] == catalyst && requestedRows[2] == output) return;
        requestedRows[0] = input;
        requestedRows[1] = catalyst;
        requestedRows[2] = output;
        sendClientAction(VIEW, new long[]{input, catalyst, output});
    }

    public void click(int index, int button, ClickType type) {
        if (presentedRevision != snapshot.revision()) return;
        sendClientAction(CLICK, new long[]{snapshot.revision(), snapshot.patternRevision(), snapshot.page(), index, button, type.ordinal()});
    }

    public void action(int kind, int value) {
        if (presentedRevision != snapshot.revision()) return;
        sendClientAction(ACTION, new long[]{snapshot.patternRevision(), snapshot.page(), kind, value});
    }

    public void setColor(AEColor next) {
        Objects.requireNonNull(next, "next");
        if (presentedRevision == snapshot.revision())
            sendClientAction(COLOR, new long[]{snapshot.patternRevision(), next.ordinal()});
    }

    private void receiveView(long[] values) {
        if (!canEdit() || values == null || values.length != 3) return;
        for (int kind = 0; kind < 3; kind++) {
            if (values[kind] < 0 || values[kind] > Integer.MAX_VALUE) return;
        }
        for (int kind = 0; kind < 3; kind++) rows[kind] = (int) values[kind];
        capture();
    }

    private void receiveAction(long[] values) {
        if (!canEdit() || values == null || values.length != 4 || values[0] != tile.patternRevision()
            || values[1] != page || values[2] < 0 || values[2] > 5) return;
        boolean changed = switch ((int) values[2]) {
            case 0 -> {
                page = Math.floorMod(values[3], tile.patternPages());
                yield true;
            }
            case 1 -> tile.setMode(values[0], MePatternAssemblyBlockEntity.Mode.VALUES[(tile.mode().ordinal() + 1) % 5]);
            case 2 -> tile.returnInputsToMe(values[0]);
            case 3, 4 -> (values[3] == 2 || values[3] == 3 || values[3] == 5 || values[3] == 8)
                && tile.modifyPatterns(values[0], (int) values[3], values[2] == 4);
            case 5 -> values[3] >= 0 && values[3] < MePatternAssemblyBlockEntity.Mode.VALUES.length
                && tile.setMode(values[0], MePatternAssemblyBlockEntity.Mode.VALUES[(int) values[3]]);
            default -> false;
        };
        if (changed) capture();
    }

    private void receiveClick(long[] values) {
        if (!canEdit() || values == null || values.length != 6 || values[3] < 0 || values[3] >= inventorySlots.size()
            || values[4] < 0 || values[4] > 40 || values[5] < 0 || values[5] >= ExternalEnumValues.CLICK_TYPES.length) return;
        int index = (int) values[3];
        ClickType type = ExternalEnumValues.CLICK_TYPES[(int) values[5]];
        if (isResourceSlot(index)) {
            if (kind(index) == 1 && values[0] == snapshot.revision()) catalystClick(index, (int) values[4], type);
        } else if (values[1] == tile.patternRevision() && values[2] == page && type != ClickType.QUICK_CRAFT) {
            nativePatternAction = true;
            try {
                super.slotClick(index, (int) values[4], type, getPlayer());
            } finally {
                nativePatternAction = false;
            }
        }
        capture();
        if (getPlayer() instanceof EntityPlayerMP serverPlayer) syncInventoryActionState(serverPlayer);
    }

    private void catalystClick(int index, int button, ClickType type) {
        if (button > 1 || type != ClickType.PICKUP && type != ClickType.QUICK_MOVE) return;
        var buffer = tile.catalystStorage();
        var context = buffer.context();
        if (!context.tryOrdinaryLock()) return;
        try {
            if (!context.current(snapshot.catalyst().revision())) return;
            var selected = snapshot.catalyst().stack(cell(index));
            var carried = getCarried();
            var emptying = ContainerItemStrategies.getEmptyingAction(carried);
            if (type == ClickType.QUICK_MOVE) {
                if (button == 1 && emptying != null) emptyCatalystContainer(buffer, true);
                else if (selected != null && !(selected.what() instanceof AEItemKey) && !carried.isEmpty())
                    fillCatalystContainer(buffer, selected.what(), true);
                else if (selected != null && selected.what() instanceof AEItemKey key) transferCatalystItems(buffer, key);
                return;
            }
            var packaged = GenericResourcePackageItem.unwrap(carried);
            if (packaged != null) {
                if (packaged.amount() <= 0) return;
                long accepted = buffer.insert(packaged.what(), packaged.amount(), Actionable.SIMULATE);
                if (accepted > 0) transfer(buffer, packaged.what(), accepted, true, amount -> {
                    long remainder = packaged.amount() - amount;
                    var next = remainder == 0 ? ItemStack.EMPTY : GenericResourcePackageItem.wrap(packaged.what(), remainder);
                    if (remainder > 0 && next.isEmpty()) return 0;
                    setCarried(next);
                    return amount;
                });
                return;
            }
            if (button == 1 && emptying != null) {
                emptyCatalystContainer(buffer, false);
            } else if (button == 0 && selected != null && !(selected.what() instanceof AEItemKey) && !carried.isEmpty()
                && ContainerItemStrategies.findCarriedContextForKey(selected.what(), getPlayer(), this) != null) {
                fillCatalystContainer(buffer, selected.what(), false);
            } else if (!carried.isEmpty() && !GenericStack.isWrapped(carried)) {
                var key = AEItemKey.of(carried);
                if (key == null) return;
                long accepted = buffer.insert(key, button == 1 ? 1 : carried.getCount(), Actionable.SIMULATE);
                if (accepted > 0) transfer(buffer, key, accepted, true, amount -> {
                    carried.shrink((int) amount);
                    setCarried(carried);
                    return amount;
                });
            } else if (carried.isEmpty() && selected != null && selected.what() instanceof AEItemKey key) {
                long requested = Math.min(key.getMaxStackSize(),
                    button == 1 ? selected.amount() / 2 + selected.amount() % 2 : selected.amount());
                long offered = buffer.extract(key, requested, Actionable.SIMULATE);
                if (offered > 0) transfer(buffer, key, offered, false, amount -> {
                    setCarried(key.toStack((int) amount));
                    return amount;
                });
            }
        } finally {
            context.unlock();
        }
    }

    private void transferCatalystItems(UnboundedResourceBuffer buffer, AEItemKey key) {
        for (int stack = 0; stack < 36; stack++) {
            long offered = buffer.extract(key, key.getMaxStackSize(), Actionable.SIMULATE);
            if (offered == 0) break;
            long moved = transfer(buffer, key, offered, false, amount -> {
                var item = key.toStack((int) amount);
                mergeItemStack(item, DISPLAY_END, inventorySlots.size(), true);
                return amount - item.getCount();
            });
            if (moved != offered) break;
        }
    }

    private void emptyCatalystContainer(UnboundedResourceBuffer buffer, boolean all) {
        var carried = ContainerItemStrategies.findCarriedContext(null, getPlayer(), this);
        if (carried == null) return;
        var content = carried.getExtractableContent();
        if (content == null) return;
        for (int i = 0; i < (all ? 256 : 1); i++) {
            long offered = Math.min(carried.extract(content.what(), all ? Long.MAX_VALUE : content.what().getAmountPerUnit(), Actionable.SIMULATE),
                buffer.insert(content.what(), content.amount(), Actionable.SIMULATE));
            if (offered <= 0) break;
            long accepted = transfer(buffer, content.what(), offered, true,
                amount -> carried.extract(content.what(), amount, Actionable.MODULATE));
            if (accepted > 0) carried.playEmptySound(getPlayer(), content.what());
            if (accepted != offered) break;
        }
    }

    private void fillCatalystContainer(UnboundedResourceBuffer buffer, AEKey key, boolean all) {
        var carried = ContainerItemStrategies.findCarriedContextForKey(key, getPlayer(), this);
        if (carried == null) return;
        for (int i = 0; i < (all ? 256 : 1); i++) {
            long offered = Math.min(buffer.extract(key, all ? Long.MAX_VALUE : key.getAmountPerUnit(), Actionable.SIMULATE),
                carried.insert(key, all ? Long.MAX_VALUE : key.getAmountPerUnit(), Actionable.SIMULATE));
            if (offered <= 0) break;
            long accepted = transfer(buffer, key, offered, false, amount -> carried.insert(key, amount, Actionable.MODULATE));
            if (accepted > 0) carried.playFillSound(getPlayer(), key);
            if (accepted != offered) break;
        }
    }

    private static long transfer(UnboundedResourceBuffer buffer, AEKey key, long offered, boolean insert, LongUnaryOperator external) {
        var receipt = buffer.reserveTransfer(key, offered, insert);
        if (receipt == null) return 0;
        try {
            long actual = external.applyAsLong(receipt.amount());
            if (actual < 0 || actual > receipt.amount()) throw new IllegalStateException("Unknown catalyst transfer result");
            receipt.complete(actual);
            return actual;
        } catch (RuntimeException | Error failure) {
            try {
                buffer.context().invalidate();
            } catch (RuntimeException | Error invalidation) {
                if (invalidation != failure) failure.addSuppressed(invalidation);
            }
            throw failure;
        }
    }

    private boolean canEdit() {
        var player = getPlayer();
        var server = player.world.getMinecraftServer();
        return isServerSide() && server != null && server.isCallingFromMinecraftThread() && player.openContainer == this
            && canInteractWith(player) && !tile.isInvalid() && player.world.getTileEntity(tile.getPos()) == tile
            && player.world.isBlockModifiable(player, tile.getPos());
    }

    private void capture() {
        page = Math.clamp(page, 0, tile.patternPages() - 1);
        var patterns = new ObjectArrayList<MePatternAssemblySnapshot.Entry>(36);
        for (int cell = 0; cell < 36; cell++) patterns.add(new MePatternAssemblySnapshot.Entry(
            GenericStack.fromItemStack(tile.patterns().getStackInSlot(page * 36 + cell))));
        var input = side(tile.inputView(), 0);
        var catalyst = side(tile.catalystView(), 1);
        var output = side(tile.outputView(), 2);
        var next = new MePatternAssemblySnapshot(snapshot.revision(), tile.patternRevision(), page, tile.patternPages(), tile.mode().ordinal(),
            patterns, input, catalyst, output);
        if (!next.equals(snapshot)) snapshot = new MePatternAssemblySnapshot(snapshot.revision() + 1, next.patternRevision(), page,
            next.pages(), next.mode(), patterns, input, catalyst, output);
        color = tile.inputColor();
    }

    private MePatternAssemblySnapshot.Side side(MePatternAssemblyBlockEntity.BufferView view, int kind) {
        int total = view.stacks().size() + (kind == 1 ? 1 : 0);
        rows[kind] = Math.clamp(rows[kind], 0, Math.max(0, rowCount(total) - height(kind) / 18));
        int first = Math.min(total, rows[kind] * 3);
        var entries = new ObjectArrayList<MePatternAssemblySnapshot.Entry>();
        for (int cell = first; cell < Math.min(total, first + COUNTS[kind]); cell++)
            entries.add(new MePatternAssemblySnapshot.Entry(cell < view.stacks().size() ? view.stacks().get(cell) : null));
        return new MePatternAssemblySnapshot.Side(view.revision(), first, total, entries);
    }

    public static int rowCount(int total) {
        return total / 3 + (total % 3 == 0 ? 0 : 1);
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide() && canInteractWith(getPlayer())) capture();
        super.broadcastChanges();
    }

    @Override
    public @NotNull ItemStack slotClick(int index, int button, @NotNull ClickType type, @NotNull EntityPlayer player) {
        return index >= 0 && index < DISPLAY_END ? ItemStack.EMPTY : super.slotClick(index, button, type, player);
    }

    @Override
    public void doAction(EntityPlayerMP player, InventoryAction action, int index, long id) {
        if (index >= DISPLAY_END) super.doAction(player, action, index, id);
    }

    @Override
    public void swapSlotContents(int a, int b) {
        if (a >= DISPLAY_END && b >= DISPLAY_END) super.swapSlotContents(a, b);
    }

    @Override
    public boolean canDragIntoSlot(@NotNull Slot slot) {
        return slot.slotNumber >= DISPLAY_END && super.canDragIntoSlot(slot);
    }

    @Override
    public boolean canMergeSlot(@NotNull ItemStack stack, @NotNull Slot slot) {
        return (!isResourceSlot(slot.slotNumber) && (nativePatternAction || !isPatternSlot(slot.slotNumber)))
            && super.canMergeSlot(stack, slot);
    }

    @Override
    public void onSlotChange(Slot slot) {
        if (nativePatternAction && isPatternSlot(slot.slotNumber)) {
            var inventory = tile.patterns();
            int logical = page * 36 + slot.slotNumber;
            inventory.setItemDirect(logical, inventory.getStackInSlot(logical).copy());
        }
        super.onSlotChange(slot);
    }

    @Override
    public @NotNull ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (!nativePatternAction || isResourceSlot(index) || index < 0 || index >= inventorySlots.size()) return ItemStack.EMPTY;
        var slot = inventorySlots.get(index);
        var source = slot.getStack();
        if (source.isEmpty()) return ItemStack.EMPTY;
        if (isPatternSlot(index)) mergeItemStack(source, DISPLAY_END, inventorySlots.size(), true);
        else if (PatternDetailsHelper.isEncodedPattern(source)) mergeItemStack(source, 0, 36, false);
        else {
            var buffer = tile.catalystStorage();
            var context = buffer.context();
            if (!context.tryOrdinaryLock()) return ItemStack.EMPTY;
            try {
                var packaged = GenericResourcePackageItem.unwrap(source);
                AEKey key = packaged == null ? AEItemKey.of(source) : packaged.what();
                long offered = packaged == null ? source.getCount() : packaged.amount();
                if (key == null || offered <= 0 || GenericStack.isWrapped(source) && packaged == null) return ItemStack.EMPTY;
                long accepted = buffer.insert(key, offered, Actionable.SIMULATE);
                if (accepted > 0) transfer(buffer, key, accepted, true, amount -> {
                    if (packaged != null) {
                        long remaining = offered - amount;
                        var next = remaining == 0 ? ItemStack.EMPTY : GenericResourcePackageItem.wrap(key, remaining);
                        if (remaining > 0 && next.isEmpty()) return 0;
                        slot.putStack(next);
                    } else source.shrink((int) amount);
                    return amount;
                });
            } finally {
                context.unlock();
            }
        }
        slot.onSlotChanged();
        return ItemStack.EMPTY;
    }

    private final class PatternPageInventory implements InternalInventory {
        @Override
        public int size() {
            return 36;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            Objects.checkIndex(slot, 36);
            if (isServerSide()) return tile.patterns().getStackInSlot(page * 36 + slot);
            var stack = snapshot.pattern(slot);
            return stack != null && stack.what() instanceof AEItemKey key ? key.toStack((int) stack.amount()) : ItemStack.EMPTY;
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return isClientSide() ? PatternDetailsHelper.decodePattern(stack, getPlayer().world) instanceof AEProcessingPattern
                : nativePatternAction && tile.patterns().isItemValid(page * 36 + Objects.checkIndex(slot, 36), stack);
        }

        @Override
        public void setItemDirect(int slot, @NotNull ItemStack stack) {
            if (isServerSide() && nativePatternAction) tile.patterns().setItemDirect(page * 36 + Objects.checkIndex(slot, 36), stack);
        }
    }
}
