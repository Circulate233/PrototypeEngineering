package com.circulation.prototype_eng.storage;

import ae2.api.AECapabilities;
import ae2.api.behaviors.GenericInternalInventoryAdapters;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.gui.ResourceHatchGuiHost;
import com.circulation.prototype_eng.api.storage.FluidHatchSettings;

public class ResourceHatchBlockEntity extends AEBaseTile implements ResourceHatchGuiHost, FluidHatchSettings {
    private static final VarHandle STORAGE_DIRTY;

    static {
        try {
            STORAGE_DIRTY = MethodHandles.lookup().findVarHandle(ResourceHatchBlockEntity.class, "storageDirty", boolean.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SuppressWarnings("unused") // Worker writes and server-tick drains use STORAGE_DIRTY.
    private boolean storageDirty;
    private final Reference2ObjectMap<Capability<?>, Object> adapters = new Reference2ObjectOpenHashMap<>();
    private HatchMode mode;
    private HatchTier tier;
    private int slots;
    private Reference2LongMap<AEKeyType> capacities;
    private BufferedResourceHatch hatch;
    private HatchInventoryPort port;
    private HatchFluidHandler fluids;
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
        slots = HatchConfig.slotsPerSide(tier, mode);
    }

    @Override
    public AEBaseTile blockEntity() {
        return this;
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
            capacities = capacities == null ? HatchConfig.resourceCapacities(tier) : capacities;
            hatch = new BufferedResourceHatch(mode, slots, capacities, this::requestStorageSave);
            port = new HatchInventoryPort(hatch, true, true);
            fluids = new HatchFluidHandler(hatch, this);
            if (isInvalid()) {
                hatch.invalidate();
            }
        }
        return hatch;
    }

    private void requestStorageSave() {
        STORAGE_DIRTY.setRelease(this, true);
    }

    @Override
    public void update() {
        super.update();
        flushStorageChanges();
    }

    private void flushStorageChanges() {
        if (world != null && !world.isRemote && (boolean) STORAGE_DIRTY.getAndSet(this, false)) {
            markDirty();
        }
    }

    public HatchMode mode() {
        return mode;
    }

    public HatchTier tier() { return tier; }

    @SuppressWarnings("unused") // Public downstream layout query; the default GUI reads each side's actual size.
    public int slotsPerSide() {
        return slots;
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
                adapter = GenericInternalInventoryAdapters.getCapability(port, capability);
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
        tag.setString("Mode", mode.name());
        tag.setInteger("Tier", tier.level());
        tag.setInteger("Slots", slots);
        tag.setLong("MaxFluidInRate", maxFluidInputRate);
        tag.setLong("MaxFluidOutRate", maxFluidOutputRate);
        storage().save(tag);
        var limits = new NBTTagCompound();
        capacities.forEach((type, capacity) -> limits.setLong(type.getId().toString(), capacity));
        tag.setTag("Capacities", limits);
    }

    @Override
    public void loadTag(@NotNull NBTTagCompound tag) {
        super.loadTag(tag);
        maxFluidInputRate = Math.max(0, tag.getLong("MaxFluidInRate"));
        maxFluidOutputRate = Math.max(0, tag.getLong("MaxFluidOutRate"));
        if (hatch != null) {
            hatch.invalidate();
        }
        hatch = null;
        adapters.clear();
        if (tag.hasKey("Mode", 8)) {
            mode = HatchMode.valueOf(tag.getString("Mode"));
        }
        tier = tag.hasKey("Tier", 3) ? HatchTier.of(tag.getInteger("Tier")) : tier;
        if (tag.hasKey("Slots", 3)) {
            slots = tag.getInteger("Slots");
            if (slots < 1 || slots > 81) {
                throw new IllegalArgumentException("Invalid resource hatch slot count: " + slots);
            }
        }
        var saved = HatchConfig.resourceCapacities(tier);
        NBTTagCompound limits = tag.getCompoundTag("Capacities");
        for (String id : limits.getKeySet()) {
            AEKeyType type = AEKeyTypes.get(new ResourceLocation(id));
            long capacity = limits.getLong(id);
            if (capacity <= 0) {
                throw new IllegalArgumentException("Invalid saved hatch capacity for " + id);
            }
            if (type != null) {
                saved.put(type, capacity);
            }
        }
        capacities = saved;
        storage().load(tag);
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
        if (hatch != null && !hatch.isValid()) {
            // A reattached tile gets new views; previously returned capability references stay invalid.
            var saved = new NBTTagCompound();
            saveAdditional(saved);
            loadTag(saved);
        }
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
    public void onDataPacket(@NotNull NetworkManager network, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    @Override
    public void handleUpdateTag(@NotNull NBTTagCompound tag) {
        readFromNBT(tag);
    }
}
