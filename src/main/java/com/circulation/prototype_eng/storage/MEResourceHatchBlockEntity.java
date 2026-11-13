package com.circulation.prototype_eng.storage;

import ae2.api.AECapabilities;
import ae2.api.config.Actionable;
import ae2.api.networking.GridFlags;
import ae2.api.networking.GridHelper;
import ae2.api.networking.IGridNode;
import ae2.api.networking.IGridNodeListener;
import ae2.api.networking.IInWorldGridNodeHost;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.security.IActionHost;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.storage.StorageHelper;
import ae2.api.util.AEColor;
import ae2.block.IOwnerAwareTile;
import ae2.items.misc.GenericResourcePackageItem;
import ae2.tile.AEBaseTile;
import ae2.util.Platform;
import com.circulation.prototype_eng.MEAssemblyConfig;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceHatchGrouping;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.capabilities.Capability;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Objects;

/**
 * ME-only physical host. Recipe resources use the independent transactional storage contract.
 */
public class MEResourceHatchBlockEntity extends AEBaseTile implements IInWorldGridNodeHost, IActionHost, IOwnerAwareTile, ResourceHatchGrouping {
    private static final VarHandle STORAGE_DIRTY;
    private static final IGridNodeListener<MEResourceHatchBlockEntity> NODE_LISTENER = (owner, ignored) -> owner.requestStorageSave();

    static {
        try {
            STORAGE_DIRTY = MethodHandles.lookup().findVarHandle(MEResourceHatchBlockEntity.class, "storageDirty", boolean.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private HatchMode mode;
    private int inputSlots;
    private @Nullable MEResourceHatch hatch;
    private @Nullable IManagedGridNode mainNode;
    private @Nullable NBTTagCompound nodeData;
    private @Nullable NBTTagCompound suspendedStorage;
    private volatile AEColor resourceColor = AEColor.TRANSPARENT;
    @SuppressWarnings("unused") // Worker writes and the server tick drains STORAGE_DIRTY.
    private boolean storageDirty;

    @SuppressWarnings("unused") // AE's native entity creation uses this public no-arg constructor.
    public MEResourceHatchBlockEntity() {
        this(HatchMode.INPUT, MEAssemblyConfig.inputSlots());
    }

    public MEResourceHatchBlockEntity(HatchMode mode, int inputSlots) {
        this.mode = Objects.requireNonNull(mode);
        if (inputSlots <= 0) throw new IllegalArgumentException("ME input slot count must be positive");
        this.inputSlots = inputSlots;
    }

    public HatchMode mode() {
        return mode;
    }

    public MEResourceHatch storage() {
        if (hatch == null) {
            hatch = suspendedStorage == null ? new MEResourceHatch(mode, inputSlots, this::requestStorageSave, this::resourceColor)
                : MEResourceHatch.load(suspendedStorage, this::requestStorageSave, this::resourceColor);
            suspendedStorage = null;
            if (isInvalid()) hatch.invalidate();
        }
        return hatch;
    }

    @Override
    public AEColor resourceColor() {
        return resourceColor;
    }

    @Override
    public void setResourceColor(AEColor color) {
        requireOwner();
        Objects.requireNonNull(color, "color");
        if (resourceColor != color) {
            resourceColor = color;
            markDirty();
        }
    }

    public boolean compareInputMark(int slot, long expectedRevision, @Nullable GenericStack next) {
        requireOwner();
        return storage().compareInputMark(slot, expectedRevision, next);
    }

    private void requireOwner() {
        if (world.isRemote || !((WorldServer) world).isCallingFromMinecraftThread())
            throw new IllegalStateException("ME assembly edit requires the server thread");
    }

    @Override
    public boolean canBeRotated() {
        return false;
    }

    private IManagedGridNode node() {
        if (mainNode == null) {
            mainNode = GridHelper.createManagedNode(this, NODE_LISTENER).setInWorldNode(true).setTagName("MeNode")
                                 .setFlags(GridFlags.REQUIRE_CHANNEL);
            if (nodeData != null) mainNode.loadFromNBT(nodeData);
            nodeData = null;
        }
        return mainNode;
    }

    @SuppressWarnings("unused") // Public AE integration hook for downstream ownership and node configuration.
    public IManagedGridNode getMainNode() {
        return node();
    }

    @Override
    public @Nullable IGridNode getGridNode(EnumFacing side) {
        return !isInvalid() && mainNode != null ? mainNode.getNode() : null;
    }

    @Override
    public @Nullable IGridNode getActionableNode() {
        return !isInvalid() && mainNode != null ? mainNode.getNode() : null;
    }

    @Override
    public void setOwner(EntityPlayer owner) {
        node().setOwningPlayer(owner);
    }

    @Override
    public void onReady() {
        super.onReady();
        if (world.isRemote) return;
        storage();
        var gridNode = node();
        if (!gridNode.isReady()) {
            gridNode.setVisualRepresentation(new ItemStack(world.getBlockState(pos).getBlock()));
            gridNode.create(world, pos);
        }
    }

    @Override
    protected void clearRemoved() {
        super.clearRemoved();
        remountStorage();
        scheduleInit();
    }

    private boolean remountStorage() {
        if (hatch != null && !hatch.isValid()) {
            if (hatch.hasReservations()) return false;
            var saved = new NBTTagCompound();
            hatch.save(saved);
            suspendedStorage = saved;
            hatch = null;

        }
        return true;
    }

    @Override
    public void update() {
        super.update();
        if (world.isRemote) return;
        if (!remountStorage()) return;
        if (mainNode != null && mainNode.isActive()) {
            var grid = mainNode.getGrid();
            if (grid != null) {
                var inventory = grid.getStorageService().getInventory();
                var energy = grid.getEnergyService();
                var source = IActionSource.ofMachine(this);
                storage().transfer(new MEResourceHatch.METransfer() {
                    @Override
                    public long insert(AEKey key, long amount) {
                        return StorageHelper.poweredInsert(energy, inventory, key, amount, source, Actionable.MODULATE);
                    }

                    @Override
                    public long extract(AEKey key, long amount) {
                        return StorageHelper.poweredExtraction(energy, inventory, key, amount, source, Actionable.MODULATE);
                    }
                });
            }
        }
        if ((boolean) STORAGE_DIRTY.getAndSet(this, false)) markDirty();
    }

    private void requestStorageSave() {
        STORAGE_DIRTY.setRelease(this, true);
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> capability, @Nullable EnumFacing side) {
        return !isInvalid() && (capability == HatchCapabilities.RESOURCE || capability == AECapabilities.IN_WORLD_GRID_NODE_HOST)
            || super.hasCapability(capability, side);
    }

    @Override
    public <T> @Nullable T getCapability(@NotNull Capability<T> capability, @Nullable EnumFacing side) {
        if (!isInvalid()) {
            if (capability == HatchCapabilities.RESOURCE) return HatchCapabilities.RESOURCE.cast(storage());
            if (capability == AECapabilities.IN_WORLD_GRID_NODE_HOST)
                return AECapabilities.IN_WORLD_GRID_NODE_HOST.cast(this);
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void saveAdditional(@NotNull NBTTagCompound tag) {
        super.saveAdditional(tag);
        storage().save(tag);
        tag.setString("ResourceColor", resourceColor.name());
        if (mainNode != null) mainNode.saveToNBT(tag);
        else if (nodeData != null && nodeData.hasKey("MeNode")) tag.setTag("MeNode", nodeData.getTag("MeNode").copy());
    }

    @Override
    public void loadTag(@NotNull NBTTagCompound tag) {
        if (hatch != null && (!hatch.isValid() || hatch.hasTransferReservation()))
            throw new IllegalStateException("Cannot reload an invalid or unresolved ME storage");
        var nextColor = tag.hasKey("ResourceColor") ? AEColor.valueOf(StrictNbt.requireString(tag, "ResourceColor")) : AEColor.TRANSPARENT;
        var next = MEResourceHatch.load(tag, this::requestStorageSave, this::resourceColor);
        if (hatch != null) {
            if (!hatch.retireIfIdle()) throw new IllegalStateException("Cannot reload unresolved ME storage");
        }
        super.loadTag(tag);
        mode = next.mode();
        resourceColor = nextColor;
        inputSlots = Math.max(1, StrictNbt.requireInt(tag, "InputSlots"));
        hatch = next;

        suspendedStorage = null;

        if (mainNode != null) mainNode.loadFromNBT(tag);
        else nodeData = tag.copy();
    }

    public NBTTagCompound saveForItemDrop() {
        var tag = new NBTTagCompound();
        storage().save(tag);
        tag.setString("ResourceColor", resourceColor.name());
        if (mode.hasInput()) tag.setTag("Input", new NBTTagList());
        if (mode.hasOutput()) tag.setTag("Output", new NBTTagList());
        return tag;
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops) {
        super.addAdditionalDrops(drops);
        if (world == null || world.isRemote) return;
        var removalWorld = world;
        var removalPosition = pos.toImmutable();
        var server = Objects.requireNonNull(world.getMinecraftServer());
        var contents = storage().removeForDrops(deferred -> {
            Runnable deliver = () -> {
                var delayed = new ObjectArrayList<ItemStack>();
                for (var stack : deferred) GenericResourcePackageItem.addDrop(stack.what(), stack.amount(), delayed);
                Platform.spawnDrops(removalWorld, removalPosition, delayed);
            };
            if (server.isCallingFromMinecraftThread()) deliver.run();
            else server.addScheduledTask(deliver);
        });
        for (var stack : contents) GenericResourcePackageItem.addDrop(stack.what(), stack.amount(), drops);
    }

    @Override
    protected void setRemoved() {
        suspend();
        super.setRemoved();
    }

    @Override
    protected void onChunkUnloaded() {
        suspend();
        super.onChunkUnloaded();
    }

    private void suspend() {

        if (mainNode != null) {
            var saved = new NBTTagCompound();
            mainNode.saveToNBT(saved);
            mainNode.destroy();
            mainNode = null;
            nodeData = saved;
        }
        if (hatch != null) hatch.invalidate();
        if (world != null && !world.isRemote && (boolean) STORAGE_DIRTY.getAndSet(this, false)) markDirty();
    }
}
