package com.circulation.prototype_eng.container;

import ae2.api.behaviors.ContainerItemStrategies;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.api.util.AEColor;
import ae2.container.AEBaseContainer;
import ae2.container.SlotSemantics;
import ae2.container.guisync.GuiSync;
import ae2.container.slot.AppEngSlot;
import ae2.container.slot.FakeSlot;
import ae2.helpers.InventoryAction;
import ae2.items.misc.GenericResourcePackageItem;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.localization.MEAssemblyText;
import com.circulation.prototype_eng.storage.HatchGuiInventory;
import com.circulation.prototype_eng.storage.MEResourceHatchBlockEntity;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * ME-only stock controls: no player path can transfer the actual input or output buffers.
 */
public final class ContainerMEResourceHatch extends AEBaseContainer {
    public static final int COLUMNS = 8;
    public static final int GRID_LEFT = 26;
    private static final String VIEW = "meView";
    private static final String MARK = "meMark";
    private static final String COLOR = "meColor";
    public final int guiHeight;
    public final int inputHeight;
    public final int outputTop;
    public final int outputHeight;
    private final MEResourceHatchBlockEntity tile;
    private final HatchMode mode;
    private final int displaySlots;
    @GuiSync(0)
    public MEResourceHatchWindowSnapshot snapshot = MEResourceHatchWindowSnapshot.EMPTY;
    @GuiSync(1)
    public AEColor color = AEColor.TRANSPARENT;
    private int inputRow, outputRow;
    private int requestedInputRow = -1, requestedOutputRow = -1;
    private long presentedMarkRevision = -1;
    private long presentedWindowRevision = -1;

    public ContainerMEResourceHatch(InventoryPlayer player, MEResourceHatchBlockEntity tile) {
        super(player, tile);
        this.tile = Objects.requireNonNull(tile);
        mode = tile.mode();
        boolean mixed = mode == HatchMode.MIXED;
        guiHeight = mixed ? 256 : 186;
        inputHeight = mixed ? 68 : 72;
        outputTop = mixed ? 98 : 24;
        outputHeight = mixed ? 68 : 72;
        int inputDisplays = mode.hasInput() ? MEResourceHatchWindowSnapshot.INPUT_CELLS * 2 : 0;
        displaySlots = inputDisplays + (mode.hasOutput() ? MEResourceHatchWindowSnapshot.OUTPUT_CELLS : 0);
        if (isServerSide()) capture();
        if (mode.hasInput()) {
            addDisplay(true, true);
            addDisplay(true, false);
        }
        if (mode.hasOutput()) addDisplay(false, false);
        addPlayerInventorySlots(27, guiHeight - 84);
        setGuiTitle(switch (mode) {
            case INPUT -> MEAssemblyText.InputAssembly.text();
            case OUTPUT -> MEAssemblyText.OutputAssembly.text();
            case MIXED -> MEAssemblyText.Interface.text();
        });
        registerClientAction(VIEW, long[].class, 64, this::receiveView);
        registerClientAction(MARK, long[].class, 128, this::receiveMark);
        registerClientAction(COLOR, Integer.class, value -> {
            if (canEdit() && value != null && value >= 0 && value < AEColor.VALUES.length) {
                tile.setResourceColor(AEColor.VALUES[value]);
                capture();
            }
        });
    }

    public static int rows(int total) {
        return total / COLUMNS + (total % COLUMNS == 0 ? 0 : 1);
    }

    public HatchMode mode() {
        return mode;
    }

    private void addDisplay(boolean input, boolean mark) {
        int count = input ? MEResourceHatchWindowSnapshot.INPUT_CELLS : MEResourceHatchWindowSnapshot.OUTPUT_CELLS;
        var inventory = new HatchGuiInventory(count, isClientSide(), cell -> input
            ? mark ? snapshot.mark(cell) : snapshot.actual(cell) : snapshot.output(cell));
        for (int cell = 0; cell < count; cell++) {
            var x = GRID_LEFT + cell % COLUMNS * 18 + 1;
            var y = (input ? 24 + cell / COLUMNS * 36 + (mark ? 0 : 18) : outputTop + cell / COLUMNS * 18) + 1;
            var slot = mark ? new FakeSlot(inventory, cell, x, y)
                : new AppEngSlot(inventory, cell, x, y) {
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
            addSlotToContainer(slot, mark ? SlotSemantics.CONFIG : SlotSemantics.STORAGE);
        }
    }

    public boolean isResourceSlot(int index) {
        return index >= 0 && index < displaySlots;
    }

    public boolean isInputSlot(int index) {
        return mode.hasInput() && index >= 0 && index < MEResourceHatchWindowSnapshot.INPUT_CELLS * 2;
    }

    public boolean isMarkSlot(int index) {
        return mode.hasInput() && index >= 0 && index < MEResourceHatchWindowSnapshot.INPUT_CELLS;
    }

    public int cell(int index) {
        return isInputSlot(index) ? index % MEResourceHatchWindowSnapshot.INPUT_CELLS
            : index - (mode.hasInput() ? MEResourceHatchWindowSnapshot.INPUT_CELLS * 2 : 0);
    }

    public int logicalInputSlot(int index) {
        int cell = cell(index);
        return isInputSlot(index) && cell >= 0 && cell < snapshot.inputs().size() ? snapshot.inputFirst() + cell : -1;
    }

    public void completeFrame() {
        presentedMarkRevision = snapshot.markRevision();
        presentedWindowRevision = snapshot.revision();
    }

    public boolean canEditPresented() {
        return presentedWindowRevision == snapshot.revision() && presentedMarkRevision == snapshot.markRevision();
    }

    public void requestView(int nextInputRow, int nextOutputRow) {
        if (requestedInputRow == nextInputRow && requestedOutputRow == nextOutputRow) return;
        requestedInputRow = nextInputRow;
        requestedOutputRow = nextOutputRow;
        sendClientAction(VIEW, new long[]{nextInputRow, nextOutputRow});
    }

    public void clickMark(int index, int button) {
        int logical = logicalInputSlot(index);
        if (!isMarkSlot(index) || logical < 0 || !canEditPresented()) return;
        sendClientAction(MARK, new long[]{presentedMarkRevision, logical, button == 1 ? 1 : 0, 0});
    }

    public void setTarget(long expectedRevision, int logical, long amount) {
        if (amount >= 0) sendClientAction(MARK, new long[]{expectedRevision, logical, 2, amount});
    }

    public void setColor(AEColor next) {
        sendClientAction(COLOR, next.ordinal());
    }

    private void receiveView(long[] values) {
        if (!canEdit() || values == null || values.length != 2 || values[0] < 0 || values[0] > Integer.MAX_VALUE
            || values[1] < 0 || values[1] > Integer.MAX_VALUE) return;
        inputRow = (int) values[0];
        outputRow = (int) values[1];
        capture();
    }

    private void receiveMark(long[] values) {
        if (!canEdit() || !mode.hasInput() || values == null || values.length != 4 || values[1] < 0
            || values[1] >= tile.storage().inputSlots()) return;
        int logical = (int) values[1];
        var view = tile.storage().inputView();
        if (view.revision() != values[0]) return;
        GenericStack next;
        if (values[2] == 2) {
            var previous = view.slots().get(logical).mark();
            if (previous == null || values[3] < 0) return;
            next = values[3] == 0 ? null : new GenericStack(previous.what(), values[3]);
        } else if (values[2] == 0 || values[2] == 1) {
            next = carriedMark((int) values[2]);
        } else return;
        var input = tile.storage().input();
        if (next != null && (next.amount() <= 0 || input == null || !input.isSupportedType(next.what().getType())))
            return;
        if (!tile.compareInputMark(logical, values[0], next)) {
            capture();
            if (getPlayer() instanceof EntityPlayerMP serverPlayer) syncInventoryActionState(serverPlayer);
            return;
        }
        capture();
    }

    private @Nullable GenericStack carriedMark(int button) {
        var carried = getCarried();
        if (carried.isEmpty()) return null;
        var packaged = GenericResourcePackageItem.unwrap(carried);
        if (packaged != null) return packaged;
        var wrapped = GenericStack.unwrapItemStack(carried);
        if (wrapped != null) return wrapped;
        var emptying = button == 1 ? ContainerItemStrategies.getEmptyingAction(carried) : null;
        if (emptying != null) return new GenericStack(emptying.what(), emptying.maxAmount());
        var key = AEItemKey.of(carried);
        return key == null ? null : new GenericStack(key, carried.getCount());
    }

    private boolean canEdit() {
        var player = getPlayer();
        return canInteractWith(player)
            && player.world.isBlockModifiable(player, tile.getPos());
    }

    private void capture() {
        var input = tile.storage().inputView();
        var output = tile.storage().outputView();
        inputRow = Math.clamp(inputRow, 0, Math.max(0, rows(input.slots().size()) - Math.max(1, inputHeight / 36)));
        outputRow = Math.clamp(outputRow, 0, Math.max(0, rows(output.stacks().size()) - Math.max(1, outputHeight / 18)));
        int inputFirst = Math.min(input.slots().size(), inputRow * COLUMNS);
        int outputFirst = Math.min(output.stacks().size(), outputRow * COLUMNS);
        var inputs = new ObjectArrayList<MEResourceHatchWindowSnapshot.Input>();
        for (int i = inputFirst; i < Math.min(input.slots().size(), inputFirst + MEResourceHatchWindowSnapshot.INPUT_CELLS); i++) {
            var value = input.slots().get(i);
            inputs.add(new MEResourceHatchWindowSnapshot.Input(value.mark(), value.actual()));
        }
        var outputs = output.stacks().subList(outputFirst,
            Math.min(output.stacks().size(), outputFirst + MEResourceHatchWindowSnapshot.OUTPUT_CELLS));
        var next = new MEResourceHatchWindowSnapshot(snapshot.revision(), input.revision(), inputFirst, input.slots().size(),
            outputFirst, output.stacks().size(), inputs, outputs);
        if (!next.equals(snapshot))
            snapshot = new MEResourceHatchWindowSnapshot(snapshot.revision() + 1, next.markRevision(),
                inputFirst, next.inputTotal(), outputFirst, next.outputTotal(), inputs, outputs);
        color = tile.resourceColor();
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide() && canInteractWith(getPlayer())) capture();
        super.broadcastChanges();
    }

    @Override
    public @NotNull ItemStack slotClick(int index, int button, @NotNull ClickType click, @NotNull EntityPlayer player) {
        return isResourceSlot(index) ? ItemStack.EMPTY : super.slotClick(index, button, click, player);
    }

    @Override
    public void doAction(EntityPlayerMP player, InventoryAction action, int index, long id) {
        if (!isResourceSlot(index)) super.doAction(player, action, index, id);
    }

    @Override
    public void swapSlotContents(int a, int b) {
        if (!isResourceSlot(a) && !isResourceSlot(b)) super.swapSlotContents(a, b);
    }

    @Override
    public boolean canDragIntoSlot(@NotNull Slot slot) {
        return !isResourceSlot(slot.slotNumber) && super.canDragIntoSlot(slot);
    }

    @Override
    public boolean canMergeSlot(@NotNull ItemStack stack, @NotNull Slot slot) {
        return !isResourceSlot(slot.slotNumber) && super.canMergeSlot(stack, slot);
    }

    @Override
    public @NotNull ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (isResourceSlot(index) || index < 0 || index >= inventorySlots.size()) return ItemStack.EMPTY;
        Slot slot = inventorySlots.get(index);
        var source = slot.getStack();
        if (source.isEmpty()) return ItemStack.EMPTY;
        int playerStart = displaySlots;
        int hotbarStart = playerStart + 27;
        mergeItemStack(source, index < hotbarStart ? hotbarStart : playerStart,
            index < hotbarStart ? inventorySlots.size() : hotbarStart, false);
        slot.onSlotChanged();
        return ItemStack.EMPTY;
    }
}
