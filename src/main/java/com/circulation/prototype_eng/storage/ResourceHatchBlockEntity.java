package com.circulation.prototype_eng.storage;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.AECapabilities;
import ae2.api.behaviors.GenericInternalInventoryAdapters;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.api.util.AEColor;
import ae2.items.misc.GenericResourcePackageItem;
import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.gui.ResourceHatchGuiHost;
import com.circulation.prototype_eng.api.storage.FluidHatchSettings;
import com.circulation.prototype_eng.api.storage.HatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ReconfigurableHatch;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.api.storage.ResourceHatchGrouping;
import com.circulation.prototype_eng.api.storage.ResourceHatchSettings;
import com.circulation.prototype_eng.api.storage.ResourceInputGroup;
import com.circulation.prototype_eng.structure.tool.BuilderDropRecovery;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;

public class ResourceHatchBlockEntity extends AEBaseTile implements ResourceHatchGrouping, ResourceHatchGuiHost, FluidHatchSettings, ResourceHatchSettings, ReconfigurableHatch {
    private static final VarHandle STORAGE_DIRTY;

    static {
        try {
            STORAGE_DIRTY = MethodHandles.lookup().findVarHandle(ResourceHatchBlockEntity.class, "storageDirty", boolean.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final Reference2ObjectMap<Capability<?>, Object> adapters = new Reference2ObjectOpenHashMap<>();
    private final HatchConfigurationRequest configurationRequest = new HatchConfigurationRequest();
    private final HatchAutoTransfer autoTransfer = new HatchAutoTransfer();
    @SuppressWarnings("unused") // Worker writes and server-tick drains use STORAGE_DIRTY.
    private boolean storageDirty;
    private HatchMode mode;
    private HatchTier tier;
    private ResourceHatchConfiguration effective;
    private long observedTargetRevision = -1;
    private boolean configurationNotification;
    private BufferedResourceHatch hatch;
    private HatchInventoryPort port;
    private HatchFluidHandler fluids;
    private volatile AEColor resourceColor = AEColor.TRANSPARENT;
    private boolean autoInput;
    private boolean autoOutput;
    private volatile long maxFluidInputRate;
    private volatile long maxFluidOutputRate;

    public ResourceHatchBlockEntity() {
        this(HatchMode.INPUT);
    }

    public ResourceHatchBlockEntity(HatchMode mode) {
        this(mode, HatchTier.LV1);
    }

    public ResourceHatchBlockEntity(HatchMode mode, HatchTier tier) {
        this.mode = mode;
        this.tier = tier;
    }

    private static void addResourceDrops(List<GenericStack> contents, List<ItemStack> drops, World world, BlockPos position) {
        for (int index = 0; index < contents.size(); index++) {
            var stack = contents.get(index);
// AEItemKey's list cap and amount+stackSize-1 estimate must not discard a large long
            // balance or later keys. Use AE's native package before either limit can be exceeded.
            if (stack.what() instanceof AEItemKey item
                && stack.amount() > (long) Math.max(1, item.getMaxStackSize()) * Math.max(0, 500 - drops.size()))
                GenericResourcePackageItem.addDrop(stack.what(), stack.amount(), drops);
            else stack.what().addDrops(stack.amount(), drops, world, position);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        observeConfigurationTarget();
    }

    @Override
    public AEBaseTile blockEntity() {
        return this;
    }

    @Override
    public AEColor resourceColor() {
        return resourceColor;
    }

    @Override
    public void setResourceColor(AEColor color) {
        if (world instanceof WorldServer serverWorld && !serverWorld.isCallingFromMinecraftThread())
            throw new IllegalStateException("Resource group changes require the server owner");
        resourceColor = color;
        if (hatch != null) hatch.setInputGroup(new ResourceInputGroup(color));
        markDirty();
    }

    @Override
    public boolean canBeRotated() {
        return false;
    }

    /**
     * Resolve registered key types only once the tile actually enters the world.
     */
    public BufferedResourceHatch storage() {
        if (hatch == null) {

            effective = effective == null ? HatchConfig.resourceConfiguration(tier, mode) : effective;
            hatch = new BufferedResourceHatch(effective, this::requestStorageSave);
            port = new HatchInventoryPort(hatch, true, true);
            fluids = new HatchFluidHandler(hatch, this);
            if (isInvalid()) {
                hatch.invalidate();
            }
        }
        hatch.setInputGroup(new ResourceInputGroup(resourceColor));
        return hatch;
    }

    private void requestStorageSave() {
        STORAGE_DIRTY.setRelease(this, true);
    }

    @Override
    public void update() {
        super.update();
        observeConfigurationTarget();
        queueConfiguration();
        if ((mode.hasInput() && autoInput || mode.hasOutput() && autoOutput) && !world.isRemote)
            autoTransfer.tickResources(this);
        flushStorageChanges();
        notifyConfiguration();
    }

    private void flushStorageChanges() {
        if (world != null && !world.isRemote && (boolean) STORAGE_DIRTY.getAndSet(this, false)) {
            markDirty();
        }
    }

    public HatchMode mode() {
        return mode;
    }

    public HatchTier tier() {
        return tier;
    }

    @SuppressWarnings({"unused", "DataFlowIssue"})
    // Public downstream layout query; the default GUI reads each side's actual size.
    public int slotsPerSide() {
        if (effective == null) return HatchConfig.slotsPerSide(tier, mode);
        return (effective.input() == null ? effective.output() : effective.input()).slots();
    }

    @Override
    public ResourceHatchConfiguration effectiveConfiguration() {
        storage();
        return effective;
    }

    @Override
    public HatchReconfigurationStatus configurationStatus() {
        return configurationRequest.status();
    }

    @Override
    public HatchReconfigurationStatus requestConfiguration(HatchConfiguration configuration) {
        var boundary = configurationBoundary();
        if (boundary != null) boundary.requireOwner();
        if (!(configuration instanceof ResourceHatchConfiguration resource) || resource.mode() != mode)
            throw new IllegalArgumentException("Resource profile must preserve physical hatch direction");
        configurationRequest.accept(resource, effectiveConfiguration());
        observedTargetRevision = HatchConfig.targetRevision();
        queueConfiguration(boundary);
        return configurationRequest.status();
    }

    private void observeConfigurationTarget() {
        if (world != null && !world.isRemote && !isInvalid() && observedTargetRevision != HatchConfig.targetRevision())
            requestConfiguration(HatchConfig.resourceConfiguration(tier, mode));
    }

    /**
     * Addon hosts may supply an owner scheduler while preserving the public request admission checks.
     */
    protected @Nullable HatchConfigurationRequest.Boundary configurationBoundary() {
        return world == null ? null : HatchConfigurationRequest.boundary(this);
    }

    private void queueConfiguration() {
        if (isInvalid() || world != null && world.isRemote) return;
        queueConfiguration(configurationBoundary());
    }

    private void queueConfiguration(@Nullable HatchConfigurationRequest.Boundary boundary) {
        configurationRequest.queue(boundary, this::applyConfiguration, () -> {
            configurationNotification = true;
            requestStorageSave();
            notifyConfiguration();
        });
    }

    @SuppressWarnings("DataFlowIssue")
    private HatchReplacement applyConfiguration() {
        var target = (ResourceHatchConfiguration) configurationRequest.target();
        var old = storage();
        return old.replace(target, this::requestStorageSave,
            () -> hatch == old && configurationRequest.target() == target && !isInvalid(), candidate -> {
                var nextPort = new HatchInventoryPort(candidate, true, true);
                var nextFluids = new HatchFluidHandler(candidate, this);
                return () -> {
                    candidate.setInputGroup(new ResourceInputGroup(resourceColor));
                    hatch = candidate;
                    effective = target;
                    port = nextPort;
                    fluids = nextFluids;
                    adapters.clear();

                };
            });
    }

    private void notifyConfiguration() {
        if (!configurationNotification || world == null || world.isRemote) return;
        markDirty();
        var state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state, 3);
        configurationNotification = false;
    }

    @Override
    public boolean autoInput() {
        return autoInput;
    }

    @Override
    public boolean autoOutput() {
        return autoOutput;
    }

    @Override
    public void setAutoInput(boolean value) {
        if (autoInput != value) {
            autoInput = value;
            markDirty();
        }
    }

    @Override
    public void setAutoOutput(boolean value) {
        if (autoOutput != value) {
            autoOutput = value;
            markDirty();
        }
    }

    @Override
    public long maxFluidInputRate() {
        return maxFluidInputRate;
    }

    @Override
    public long maxFluidOutputRate() {
        return maxFluidOutputRate;
    }

    @Override
    public void setMaxFluidInputRate(long value) {
        long next = Math.max(0, value);
        if (next != maxFluidInputRate) {
            maxFluidInputRate = next;
            markDirty();
        }
    }

    @Override
    public void setMaxFluidOutputRate(long value) {
        long next = Math.max(0, value);
        if (next != maxFluidOutputRate) {
            maxFluidOutputRate = next;
            markDirty();
        }
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> capability, @Nullable EnumFacing side) {
        if (!isInvalid() && (capability == HatchCapabilities.RESOURCE
            || capability == AECapabilities.ME_STORAGE || capability == AECapabilities.GENERIC_INTERNAL_INV
            || capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY
            || GenericInternalInventoryAdapters.hasAdapter(capability))) {
            return true;
        }
        return super.hasCapability(capability, side);
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getCapability(@NotNull Capability<T> capability, @Nullable EnumFacing side) {
        if (!isInvalid()) {
            BufferedResourceHatch storage = storage();
            if (capability == HatchCapabilities.RESOURCE) {
                return HatchCapabilities.RESOURCE.cast(storage);
            }
            if (capability == AECapabilities.ME_STORAGE) {
                return AECapabilities.ME_STORAGE.cast(storage.automation());
            }
            if (capability == AECapabilities.GENERIC_INTERNAL_INV) {
                return AECapabilities.GENERIC_INTERNAL_INV.cast(port);
            }
            if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
                return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(fluids);
            }
            Object adapter = adapters.get(capability);
            if (adapter == null && GenericInternalInventoryAdapters.hasAdapter(capability)) {
                adapter = capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
                    ? new HatchItemHandler(port) : GenericInternalInventoryAdapters.getCapability(port, capability);
                if (adapter != null) {
                    adapters.put(capability, adapter);
                }
            }
            if (adapter != null) {
                return (T) adapter;
            }
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void saveAdditional(@NotNull NBTTagCompound tag) {
        super.saveAdditional(tag);
        tag.setString("ResourceColor", resourceColor.name());
        tag.setString("Mode", mode.name());
        tag.setInteger("Tier", tier.level());
        tag.setTag("EffectiveProfile", HatchConfigurationNbt.resource(effectiveConfiguration()));
        tag.removeTag("Slots");
        tag.removeTag("Capacities");
        tag.setBoolean("AutoInput", autoInput);
        tag.setBoolean("AutoOutput", autoOutput);
        tag.setLong("MaxFluidInRate", maxFluidInputRate);
        tag.setLong("MaxFluidOutRate", maxFluidOutputRate);
        storage().save(tag);
    }

    /**
     * Only settings travel with the hatch item; AE's additional-drop hook owns stored resources.
     */
    public NBTTagCompound saveForItemDrop() {
        var tag = new NBTTagCompound();
        saveAdditional(tag);
        if (mode.hasInput()) tag.setTag("Input", new NBTTagList());
        if (mode.hasOutput()) tag.setTag("Output", new NBTTagList());
        return tag;
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void addAdditionalDrops(List<ItemStack> drops) {
        super.addAdditionalDrops(drops);
        if (world == null || world.isRemote) return;
        var removalWorld = world;
        var removalPosition = pos.toImmutable();
        var server = removalWorld.getMinecraftServer();
        var destination = BuilderDropRecovery.destination(removalWorld, removalPosition);
        var contents = storage().removeForDrops(deferred -> {
            Runnable deliver = () -> {
                var delayed = new ObjectArrayList<ItemStack>();
                addResourceDrops(deferred, delayed, removalWorld, removalPosition);
                destination.accept(delayed);
            };
            if (server.isCallingFromMinecraftThread()) deliver.run();
            else server.addScheduledTask(deliver);
        });
        addResourceDrops(contents, drops, removalWorld, removalPosition);
    }

    @Override
    public void loadTag(@NotNull NBTTagCompound tag) {
        if (hasTransferReservation())
            throw new IllegalStateException("Cannot reload resource hatch with an unresolved external transfer");
        var nextColor = tag.hasKey("ResourceColor") ? AEColor.valueOf(tag.getString("ResourceColor")) : AEColor.TRANSPARENT;
        boolean modern = tag.hasKey("EffectiveProfile");
        var nextMode = modern || tag.hasKey("Mode") ? HatchMode.valueOf(tag.getString("Mode")) : mode;
        var nextTier = modern || tag.hasKey("Tier") ? HatchTier.of(tag.getInteger("Tier")) : tier;
        if (modern) {
            if (!nextMode.hasInput() && tag.hasKey("Input") || !nextMode.hasOutput() && tag.hasKey("Output"))
                throw new IllegalArgumentException("Saved resource sides differ from physical hatch direction");
        }
        boolean nextAutoInput = tag.getBoolean("AutoInput");
        boolean nextAutoOutput = tag.getBoolean("AutoOutput");
        ResourceHatchConfiguration profile;
        if (tag.hasKey("EffectiveProfile")) {
            profile = HatchConfigurationNbt.resource(tag.getCompoundTag("EffectiveProfile"), nextMode);
            if (nextMode.hasInput()) tag.getTagList("Input", 10);
            if (nextMode.hasOutput()) tag.getTagList("Output", 10);
        } else if (tag.hasKey("Slots") || tag.hasKey("Capacities")) {
            var side = HatchConfigurationNbt.resourceSide(tag);
            profile = new ResourceHatchConfiguration(nextMode.hasInput() ? side : null, nextMode.hasOutput() ? side : null);
        } else {
            profile = HatchConfig.resourceConfiguration(nextTier, nextMode);
        }
        long nextInputRate = HatchConfigurationNbt.nonnegative(tag, "MaxFluidInRate");
        long nextOutputRate = HatchConfigurationNbt.nonnegative(tag, "MaxFluidOutRate");
        var changes = new HatchStorageChanges(this::requestStorageSave);
        var candidate = new BufferedResourceHatch(profile, changes);
        candidate.load(tag); // Both sides validate before the old live instance is retired.
        var nextPort = new HatchInventoryPort(candidate, true, true);
        var nextFluids = new HatchFluidHandler(candidate, this);
        if (isInvalid()) candidate.invalidate();
        if (hatch != null) hatch.invalidate();
        super.loadTag(tag);

        mode = nextMode;
        tier = nextTier;
        effective = profile;
        resourceColor = nextColor;
        candidate.setInputGroup(new ResourceInputGroup(nextColor));
        hatch = candidate;
        port = nextPort;
        fluids = nextFluids;

        adapters.clear();
        autoInput = nextAutoInput;
        autoOutput = nextAutoOutput;
        maxFluidInputRate = nextInputRate;
        maxFluidOutputRate = nextOutputRate;
        configurationRequest.reset();
        observedTargetRevision = -1;
        changes.enable();
    }

    @Override
    protected void setRemoved() {

        if (hatch != null) {
            hatch.invalidate();
        }
        flushStorageChanges();
        adapters.clear();
        super.setRemoved();
    }

    @Override
    protected void onChunkUnloaded() {

        if (hatch != null) {
            hatch.invalidate();
        }
        flushStorageChanges();
        adapters.clear();
        super.onChunkUnloaded();
    }

    @Override
    protected void clearRemoved() {
        super.clearRemoved();
        if (hasTransferReservation()) return;
        if (hatch != null && !hatch.isValid()) {
            // A reattached tile gets new views; previously returned capability references stay invalid.
            var saved = new NBTTagCompound();
            saveAdditional(saved);
            loadTag(saved);
        }
    }

    private boolean hasTransferReservation() {
        return hatch != null && hatch.hasTransferReservation();
    }

    @Override
    public @NotNull NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void onDataPacket(@NotNull NetworkManager network, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    @Override
    public void handleUpdateTag(@NotNull NBTTagCompound tag) {
        readFromNBT(tag);
    }
}
