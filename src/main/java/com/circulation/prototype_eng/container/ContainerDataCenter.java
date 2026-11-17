package com.circulation.prototype_eng.container;

import ae2.api.inventories.InternalInventory;
import com.circulation.prototype_eng.api.gui.ResearchGuiAccess;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.container.AEBaseContainer;
import ae2.container.SlotSemantics;
import ae2.container.guisync.GuiSync;
import ae2.container.slot.AppEngSlot;
import ae2.util.inv.AppEngInternalInventory;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.network.DataCenter;
import com.circulation.prototype_eng.api.network.MachineDataCenterStorage;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImport;
import com.circulation.prototype_eng.api.research.download.DataCenterTransferInventory;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadPreview;
import com.circulation.prototype_eng.api.research.download.ResearchItemImportPreview;
import com.circulation.prototype_eng.localization.DataCenterGuiText;
import com.circulation.prototype_eng.research.ServerResearch;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Native slots use the attached database inventory. Previews retain the server's frozen callback result.
 * Requires MachineDataCenterStorage; other storage implementations supply their own GUI bindings.
 */
public final class ContainerDataCenter extends AEBaseContainer {
    public static final int INPUT_X = 245, OUTPUT_X = 309, TRANSFER_Y = 99;
    public static final int PLAYER_X = 112, PLAYER_Y = 204;
    private final MachineControllerHost host;
    private final @Nullable MachineDataCenterStorage storage;
    private final @Nullable DataCenterTransferInventory transfers;
    private final @Nullable DataCenterTransferInventory.Adapter adapter;
    private final Runnable displayRead = this::capture;
    private @Nullable DataCenterResearchSnapshot contents;
    private @Nullable ResearchItemDownloadPreview download;
    private @Nullable ResearchItemImportPreview imported;
    private @Nullable ResourceLocation selectedResearch;
    private String teamId = "";
    private int generation;
    private long inventoryRevision = -1;
    private boolean captureQueued, previewQueued, previewQueried, actionPending;
    @GuiSync(0)
    public DataCenterResearchPacket stored = DataCenterResearchPacket.EMPTY;
    @GuiSync(1)
    public DataCenterResearchPacket target = DataCenterResearchPacket.EMPTY;
    @GuiSync(2)
    public @Nullable GenericStack preview;
    @GuiSync(3)
    public int level;
    @GuiSync(4)
    public int capacity;
    @GuiSync(5)
    public @Nullable NetworkWorkState state;
    @GuiSync(6)
    public boolean visible;
    @GuiSync(7)
    public boolean canRemove;
    @GuiSync(8)
    public boolean canTransfer;
    @GuiSync(9)
    public boolean slotsEditable;
    @GuiSync(10)
    public boolean savePending;
    @GuiSync(11)
    public boolean importMode;
    @GuiSync(12)
    public int teamLevelLoss;
    @GuiSync(13)
    public @Nullable DataCenterGuiText operationState;
    @GuiSync(14)
    public boolean transferPending;

    @SuppressWarnings("DataFlowIssue")
    public ContainerDataCenter(InventoryPlayer player, MachineControllerHost host) {
        super(player, host.blockEntity());
        this.host = host;
        setGuiTitle(host.type().displayName().text());
        InternalInventory inventory;
        if (isClientSide()) {
            storage = null;
            transfers = null;
            adapter = null;
            inventory = new AppEngInternalInventory(2);
        } else {
            storage = (MachineDataCenterStorage) ((DataCenter) host.machine()).dataCenterStorage();
            transfers = storage.transfers();
            adapter = transfers.forPlayer(player.player.getUniqueID());
            inventory = transfers;
        }
        for (int index = 0; index < 2; index++) {
            var slot = new AppEngSlot(inventory, index, index == 0 ? INPUT_X : OUTPUT_X, TRANSFER_Y) {
                @Override
                public boolean isItemValid(ItemStack stack) {
                    return slotNumber == DataCenterTransferInventory.INPUT && slotsEditable && super.isItemValid(stack);
                }

                @Override
                public boolean canTakeStack(EntityPlayer player) {
                    return slotsEditable && super.canTakeStack(player);
                }
            };
            addSlotToContainer(slot, index == 0 ? SlotSemantics.MACHINE_INPUT : SlotSemantics.MACHINE_OUTPUT);
        }
        addPlayerInventorySlots(PLAYER_X, PLAYER_Y);
        registerClientAction("mode", Boolean.class, value -> {
            if (value == null || !current() || importMode == value || actionPending) return;
            importMode = value;
            invalidatePreview();
            updateTarget();
        });
        registerClientAction("select", ResourceLocation.class, 256, id -> {
            if (id == null || !current() || !canTransfer || actionPending) return;
            var definition = ResearchRegistry.INSTANCE.require(id);
            if (definition.specialOnly()) return;
            selectedResearch = id;
            invalidatePreview();
            updateTarget();
        });
        registerClientAction("remove", ResourceLocation.class, 256, this::remove);
        registerClientAction("transfer", this::transfer);
        registerClientAction("overview", () -> {
            if (current() && !actionPending)
                ResearchGuiAccess.open((EntityPlayerMP) getPlayer(), host);
        });
    }

    public void changeMode() {
        sendClientAction("mode", !importMode);
    }

    /**
     * Called by the research picker when that screen is implemented.
     */
    @SuppressWarnings("unused")
    public void selectResearch(ResourceLocation id) {
        sendClientAction("select", id);
    }

    public void openResearchOverview() {
        sendClientAction("overview");
    }

    public void selectFromOverview(ResourceLocation id) {
        selectedResearch = id;
        invalidatePreview();
        updateTarget();
    }

    public void removeResearch(ResourceLocation id) {
        sendClientAction("remove", id);
    }

    public void performTransfer() {
        sendClientAction("transfer");
    }

    private boolean current() {
        return getPlayer().openContainer == this && canInteractWith(getPlayer());
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return super.canInteractWith(player) && (isClientSide()
            || host.machine() instanceof DataCenter database && database.dataCenterStorage() == storage);
    }

    @Override
    public @NotNull ItemStack slotClick(int slotId, int dragType, ClickType type, EntityPlayer player) {
        if (isServerSide()) captureAccess();
        return super.slotClick(slotId, dragType, type, player);
    }

    @Override
    public @NotNull ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (isServerSide()) captureAccess();
        return super.transferStackInSlot(player, index);
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void onSlotChange(Slot slot) {
        super.onSlotChange(slot);
        if (isServerSide() && slot.slotNumber < 2) transfers.sendChangeNotification(slot.slotNumber);
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void broadcastChanges() {
        if (isServerSide() && !captureQueued && current()) {
            captureQueued = true;
            var device = host.machine().networkDevice();
            if (device == null || !device.readDisplayAtEndTick(displayRead)) host.readGuiAtBoundary(displayRead);
        }
        super.broadcastChanges();
    }

    private void capture() {
        try {
            if (!current()) return;
            captureAccess();
            capturePreview();
            super.broadcastChanges();
        } finally {
            captureQueued = false;
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private void captureAccess() {
        var view = ServerResearch.dataCenterView((EntityPlayerMP) getPlayer(), host);
        if (view == null) {
            canRemove = canTransfer = slotsEditable = false;
            return;
        }
        var next = view.contents();
        transferPending = transfers.locked() || actionPending;
        boolean transfer = view.canTransfer() && view.state() == NetworkWorkState.ACTIVE_DATA_CENTER && !transferPending;
        long revision = transfers.revision();
        if (contents != next || inventoryRevision != revision || visible != view.visible() || canTransfer != transfer || !teamId.equals(view.teamId()))
            invalidatePreview();
        inventoryRevision = revision;
        if (contents != next || visible != view.visible()) {
            stored = view.visible() ? DataCenterResearchPacket.capture(next.storedResearch()) : DataCenterResearchPacket.EMPTY;
            contents = next;
        }
        teamId = view.teamId();
        level = next.level();
        capacity = next.capacity();
        state = view.state();
        visible = view.visible();
        savePending = view.savePending();
        canRemove = view.canRemove() && !actionPending;
        canTransfer = transfer;
        slotsEditable = view.canTransfer() && !transferPending;
        if (!visible) target = DataCenterResearchPacket.EMPTY;
        else if (!importMode && target.entries().isEmpty() && selectedResearch != null) updateTarget();
    }

    private void invalidatePreview() {
        generation++;
        download = null;
        imported = null;
        preview = null;
        previewQueried = false;
        operationState = null;
        teamLevelLoss = 0;
        if (importMode) target = DataCenterResearchPacket.EMPTY;
    }

    private void updateTarget() {
        target = importMode || selectedResearch == null || !visible ? DataCenterResearchPacket.EMPTY
            : new DataCenterResearchPacket(List.of(DataCenterResearchPacket.entry(selectedResearch, 1)));
    }

    @SuppressWarnings("DataFlowIssue")
    private void capturePreview() {
        if (!canTransfer || previewQueued || transfers.getStackInSlot(0).isEmpty()
            || !transfers.getStackInSlot(1).isEmpty() || !importMode && selectedResearch == null) return;
        if (imported != null && !imported.current().getAsBoolean() || download != null && !download.current().getAsBoolean())
            invalidatePreview();
        if (previewQueried) return;
        previewQueued = true;
        int request = generation;
        if (importMode) {
            ServerResearch.requestItemImportPreview((EntityPlayerMP) getPlayer(), teamId, contents.dataCenterId(), 0,
                adapter, adapter, () -> current() && generation == request, candidate -> {
                    previewQueued = false;
                    if (!current() || generation != request) return;
                    previewQueried = true;
                    imported = candidate;
                    if (candidate == null) return;
                    target = DataCenterResearchPacket.capture(candidate.output().data().entries());
                    if (candidate.ready()) {
                        var output = new ItemStack(candidate.output().output());
                        preview = new GenericStack(AEItemKey.of(output), candidate.output().amount());
                    } else
                        operationState = candidate.preparation().result().status() == ResearchDiskImport.Status.DISK_TIER_TOO_HIGH
                            ? DataCenterGuiText.TierTooLow : storageState(candidate.preparation().result().storageResult());
                    super.broadcastChanges();
                });
        } else {
            ServerResearch.requestItemDownloadPreview((EntityPlayerMP) getPlayer(), teamId, contents.dataCenterId(), selectedResearch,
                0, adapter, adapter, () -> current() && generation == request, candidate -> {
                    previewQueued = false;
                    if (!current() || generation != request) return;
                    previewQueried = true;
                    download = candidate;
                    if (candidate == null) return;
                    target = new DataCenterResearchPacket(List.of(DataCenterResearchPacket.entry(selectedResearch, candidate.output().researchLevel())));
                    teamLevelLoss = candidate.teamLevelLoss();
                    if (candidate.ready()) {
                        var output = candidate.output().output();
                        preview = new GenericStack(AEItemKey.of(output), output.getCount());
                    } else operationState = switch (candidate.sources().status()) {
                        case DATA_CENTER_LEVEL_TOO_LOW -> DataCenterGuiText.TierTooLow;
                        case SOURCE_BUSY -> DataCenterGuiText.Busy;
                        case SOURCE_CHANGED -> DataCenterGuiText.Changed;
                        default -> DataCenterGuiText.ResearchUnavailable;
                    };
                    super.broadcastChanges();
                });
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private void transfer() {
        if (!current() || actionPending) return;
        captureAccess();
        if (!canTransfer || preview == null) return;
        actionPending = true;
        preview = null;
        var databaseId = contents.dataCenterId();
        if (importMode) {
            ServerResearch.requestItemImport((EntityPlayerMP) getPlayer(), teamId, databaseId, imported, UUID.randomUUID(),
                adapter, adapter, this::current, result -> {
                    actionPending = false;
                    invalidatePreview();
                    operationState = switch (result.status()) {
                        case COMPLETED -> DataCenterGuiText.Completed;
                        case PENDING, BUSY -> DataCenterGuiText.Busy;
                        case PERMISSION_DENIED -> DataCenterGuiText.ReadOnly;
                        case DISK_REJECTED -> result.precheck() == null ? DataCenterGuiText.ResearchUnavailable
                            : storageState(result.precheck().storageResult());
                        case NO_RECIPE, MISSING_DISK, OUTPUT_BLOCKED -> null;
                        default -> DataCenterGuiText.Changed;
                    };
                });
        } else {
            ServerResearch.requestItemDownload((EntityPlayerMP) getPlayer(), teamId, databaseId, download, UUID.randomUUID(),
                adapter, adapter, this::current, result -> {
                    actionPending = false;
                    invalidatePreview();
                    operationState = switch (result.status()) {
                        case COMPLETED -> DataCenterGuiText.Completed;
                        case PENDING, BUSY -> DataCenterGuiText.Busy;
                        case PERMISSION_DENIED -> DataCenterGuiText.ReadOnly;
                        case SOURCE_REJECTED -> DataCenterGuiText.ResearchUnavailable;
                        case NO_EVENT, EVENT_REJECTED, MISSING_MATERIAL, OUTPUT_BLOCKED -> null;
                        default -> DataCenterGuiText.Changed;
                    };
                });
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private void remove(ResourceLocation id) {
        if (id == null || !current() || actionPending) return;
        captureAccess();
        if (!canRemove || contents.storedResearch().getInt(id) == 0) return;
        actionPending = true;
        ServerResearch.requestDataCenterRemove((EntityPlayerMP) getPlayer(), teamId, contents.dataCenterId(), id,
            this::current, result -> {
                actionPending = false;
                invalidatePreview();
                operationState = switch (result.status()) {
                    case COMPLETED -> storageState(result.storageResult());
                    case PERMISSION_DENIED -> DataCenterGuiText.ReadOnly;
                    case SAVE_PENDING -> DataCenterGuiText.SavePending;
                    default -> DataCenterGuiText.Changed;
                };
            });
    }

    private static @Nullable DataCenterGuiText storageState(@Nullable DataCenterStorage.Result result) {
        return switch (result) {
            case UPDATED, UNCHANGED -> null;
            case DATA_CENTER_LEVEL_TOO_LOW -> DataCenterGuiText.TierTooLow;
            case CAPACITY_FULL -> DataCenterGuiText.CapacityFull;
            case MAX_LEVEL_REACHED -> DataCenterGuiText.MaxLevelReached;
            case MUTEX_BLOCKED -> DataCenterGuiText.MutexBlocked;
            case BUSY -> DataCenterGuiText.Busy;
            case STALE_CONTEXT -> DataCenterGuiText.Changed;
            case null, default -> DataCenterGuiText.ResearchUnavailable;
        };
    }
}
