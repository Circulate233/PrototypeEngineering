package com.circulation.prototype_eng.energy;

import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.gui.EnergyHatchGuiHost;

/** PM's tiered input/output/IO energy hatch, retaining separate machine and automation directions. */
public class EnergyHatchBlockEntity extends AEBaseTile implements EnergyHatchGuiHost {
    private static final VarHandle STORAGE_DIRTY;

    static {
        try {
            STORAGE_DIRTY = MethodHandles.lookup().findVarHandle(EnergyHatchBlockEntity.class, "storageDirty", boolean.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SuppressWarnings("unused") // Worker writes and server-tick drains use STORAGE_DIRTY.
    private boolean storageDirty;
    private HatchTier tier;
    private HatchMode mode;
    private EnergyBuffer buffer;
    private ForgeEnergyPort port;
    private long maxInputRate;
    private long maxOutputRate;
    private boolean autoInput;
    private boolean autoOutput;

    /** Forge instantiates registered tile entities through this constructor when loading NBT. */
    @SuppressWarnings("unused")
    public EnergyHatchBlockEntity() { this(HatchMode.INPUT, HatchTier.LV1); }

    public EnergyHatchBlockEntity(HatchMode mode, HatchTier tier) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.tier = Objects.requireNonNull(tier, "tier");
        createStorage();
    }

    @Override
    public AEBaseTile blockEntity() {
        return this;
    }

    @Override
    public boolean canBeRotated() {
        return false;
    }

    private void createStorage() {
        // Physical directions are enforced by capability routing, not by disabling the
        // reverse machine operation on the shared balance as PM's backend did.
        buffer = new EnergyBuffer(HatchConfig.energyCapacity(tier), effectiveInputRate(), effectiveOutputRate(), this::requestStorageSave);
        port = new ForgeEnergyPort(buffer, mode);
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

    public EnergyBuffer storage() { return buffer; }
    public HatchTier tier() { return tier; }
    public HatchMode mode() { return mode; }
    public long maxInputRate() { return maxInputRate; }
    public long maxOutputRate() { return maxOutputRate; }
    public boolean autoInput() { return autoInput; }
    public boolean autoOutput() { return autoOutput; }
    public long effectiveInputRate() { return effectiveRate(maxInputRate); }
    public long effectiveOutputRate() { return effectiveRate(maxOutputRate); }

    private long effectiveRate(long requested) {
        long maximum = HatchConfig.energyTransfer(tier);
        return requested == 0 ? maximum : Math.min(requested, maximum);
    }

    /** Zero restores the tier default; positive user limits cannot exceed the tier, matching PM. */
    public void setMaxInputRate(long value) { maxInputRate = Math.max(0, value); updateTransferLimits(); settingsChanged(); }
    public void setMaxOutputRate(long value) { maxOutputRate = Math.max(0, value); updateTransferLimits(); settingsChanged(); }
    public void setAutoInput(boolean value) { autoInput = value; settingsChanged(); }
    public void setAutoOutput(boolean value) { autoOutput = value; settingsChanged(); }

    private void updateTransferLimits() {
        buffer.setTransferLimits(effectiveInputRate(), effectiveOutputRate());
    }

    private void settingsChanged() {
        markDirty();
        // GuiSync sends settings to every viewer without replacing their live storage views.
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> capability, @Nullable EnumFacing side) {
        return !isInvalid() && (capability == CapabilityEnergy.ENERGY
                || capability == HatchCapabilities.ENERGY_INPUT && mode.hasInput()
                || capability == HatchCapabilities.ENERGY_OUTPUT && mode.hasOutput())
                || super.hasCapability(capability, side);
    }

    @Override
    @Nullable
    public <T> T getCapability(@NotNull Capability<T> capability, @Nullable EnumFacing side) {
        if (!isInvalid()) {
            if (capability == HatchCapabilities.ENERGY_INPUT && mode.hasInput()) { return HatchCapabilities.ENERGY_INPUT.cast(buffer); }
            if (capability == HatchCapabilities.ENERGY_OUTPUT && mode.hasOutput()) { return HatchCapabilities.ENERGY_OUTPUT.cast(buffer); }
            if (capability == CapabilityEnergy.ENERGY) { return CapabilityEnergy.ENERGY.cast(port); }
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void saveAdditional(@NotNull NBTTagCompound tag) {
        super.saveAdditional(tag);
        tag.setInteger("Tier", tier.level());
        tag.setString("Mode", mode.name());
        tag.setLong("Energy", buffer.savedEnergy());
        tag.setLong("MaxInRate", maxInputRate);
        tag.setLong("MaxOutRate", maxOutputRate);
        tag.setBoolean("AutoInput", autoInput);
        tag.setBoolean("AutoOutput", autoOutput);
    }

    @Override
    public void loadTag(@NotNull NBTTagCompound tag) {
        super.loadTag(tag);
        buffer.invalidate();
        tier = tag.hasKey("Tier", 3) ? HatchTier.of(tag.getInteger("Tier")) : tier;
        mode = tag.hasKey("Mode", 8) ? HatchMode.valueOf(tag.getString("Mode")) : mode;
        maxInputRate = Math.max(0, tag.getLong("MaxInRate"));
        maxOutputRate = Math.max(0, tag.getLong("MaxOutRate"));
        autoInput = tag.getBoolean("AutoInput");
        autoOutput = tag.getBoolean("AutoOutput");
        createStorage();
        // PM reloads the current tier config, then clamps the saved balance if capacity was reduced.
        buffer.restore(Math.clamp(tag.getLong("Energy"), 0, buffer.capacity()));
        if (isInvalid()) {
            buffer.invalidate();
        }
    }

    @Override
    protected void setRemoved() {
        buffer.invalidate();
        flushStorageChanges();
        super.setRemoved();
    }

    @Override
    protected void onChunkUnloaded() {
        buffer.invalidate();
        flushStorageChanges();
        super.onChunkUnloaded();
    }

    @Override
    protected void clearRemoved() {
        super.clearRemoved();
        if (!buffer.isValid()) {
            long saved = buffer.savedEnergy();
            createStorage();
            buffer.restore(Math.clamp(saved, 0, buffer.capacity()));
        }
    }
    @Override public @NotNull NBTTagCompound getUpdateTag() { return writeToNBT(new NBTTagCompound()); }
    @Override public SPacketUpdateTileEntity getUpdatePacket() { return new SPacketUpdateTileEntity(pos, 0, getUpdateTag()); }
    @Override public void onDataPacket(@NotNull NetworkManager network, SPacketUpdateTileEntity packet) { readFromNBT(packet.getNbtCompound()); }
    @Override public void handleUpdateTag(@NotNull NBTTagCompound tag) { readFromNBT(tag); }
}
