package com.circulation.prototype_eng.energy;

import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.gui.EnergyHatchGuiHost;
import com.circulation.prototype_eng.block.EnergyHatchBlock;
import com.circulation.prototype_eng.api.storage.StorageContext;
import java.util.List;
/**
 * PM's tiered input/output/IO energy hatch, retaining separate machine and automation directions.
 */
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
    private boolean pendingConfiguration;
    private HatchMode mode;
    private EnergyBuffer buffer;
    private EnergyBuffer outputBuffer;
    @Nullable private String allocationLocation;
    private List<StorageContext> contexts;
    private ForgeEnergyPort port;
    private long maxInputRate;
    private long maxOutputRate;
    private boolean autoInput;
    private boolean autoOutput;

    /** Forge instantiates registered tile entities through this constructor when loading NBT. */
    @SuppressWarnings("unused")
    public EnergyHatchBlockEntity() { this(HatchMode.INPUT, HatchTier.LV1);
        pendingConfiguration = true;
    }

    public EnergyHatchBlockEntity(HatchMode mode, HatchTier tier) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.tier = Objects.requireNonNull(tier, "tier");
        createStorage();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (pendingConfiguration && world != null && world.getTileEntity(pos) == this
            && world.getBlockState(pos).getBlock() instanceof EnergyHatchBlock block
            && block.getTileEntity(world, pos) == this) {
            initializeConfiguration(block.mode(), block.tier());
        }
        bindSourceIdentities();
    }

    /** Physical labels are facts about the world-owned tile, not source access tokens. */
    protected @Nullable String allocationLocation() {
        return world != null && world.getTileEntity(pos) == this
            ? "prototype_eng:energy/" + world.provider.getDimension() + "/" + pos.getX() + "/" + pos.getY() + "/" + pos.getZ()
            : null;
    }

    private void bindSourceIdentities() {
        if (buffer == null || !buffer.isValid() || isInvalid()) return;
        var location = allocationLocation();
        if (location == null || location.equals(allocationLocation)) return;
        buffer.context().bindSourceIdentity(location + (mode.hasInput() ? "/input" : "/output"));
        if (outputBuffer != null && outputBuffer != buffer) outputBuffer.context().bindSourceIdentity(location + "/output");
        allocationLocation = location;
    }

    @Override
    public void setPos(@NotNull BlockPos position) {
        if (allocationLocation != null && !pos.equals(position)) invalidate();
        super.setPos(position);
    }

    @Override
    public void setWorld(@NotNull World nextWorld) {
        if (allocationLocation != null && world != nextWorld) invalidate();
        super.setWorld(nextWorld);
    }

    /**
     * Completes only an unsaved no-arg instance; explicit construction and NBT remain authoritative.
     */
    protected void initializeConfiguration(HatchMode mode, HatchTier tier) {
        if (!pendingConfiguration) {
            return;
        }
        invalidateStorage();
        this.mode = Objects.requireNonNull(mode);
        this.tier = Objects.requireNonNull(tier);
        createStorage();
        if (isInvalid()) {
            invalidateStorage();
        }
        pendingConfiguration = false;
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
        allocationLocation = null;
        buffer = new EnergyBuffer(HatchConfig.energyCapacity(tier), effectiveInputRate(), effectiveOutputRate(), this::requestStorageSave);
        outputBuffer = mode == HatchMode.MIXED
            ? new EnergyBuffer(HatchConfig.energyCapacity(tier), effectiveInputRate(), effectiveOutputRate(), this::requestStorageSave)
            : mode == HatchMode.OUTPUT ? buffer : null;
        contexts = mode == HatchMode.MIXED ? List.of(buffer.context(), outputBuffer.context()) : List.of(buffer.context());
        port = new ForgeEnergyPort(inputStorage(), outputStorage());
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

    /** Compatibility access to the input balance, or the sole output balance on output hatches. */
    @Override
    public EnergyBuffer storage() {
        bindSourceIdentities();
        return buffer;
    }

    @Override
    public @Nullable EnergyBuffer inputStorage() {
        bindSourceIdentities();
        return mode.hasInput() ? buffer : null;
    }

    @Override
    public @Nullable EnergyBuffer outputStorage() {
        bindSourceIdentities();
        return outputBuffer;
    }

    private void invalidateStorage() {
        locked(() -> {
            buffer.invalidate();
            if (outputBuffer != null && outputBuffer != buffer) outputBuffer.invalidate();
        });
    }

    private void locked(Runnable action) {
        // The input context is constructed before the independent output context.
        StorageContext.lockAll(contexts);
        try {
            action.run();
        } finally {
            for (int i = contexts.size() - 1; i >= 0; i--) contexts.get(i).unlockDeferred();
            for (var context : contexts) context.publishChanges();
        }
    }
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
        locked(() -> {
            buffer.setTransferLimits(effectiveInputRate(), effectiveOutputRate());
            if (outputBuffer != null && outputBuffer != buffer)
                outputBuffer.setTransferLimits(effectiveInputRate(), effectiveOutputRate());
        });
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
            bindSourceIdentities();
            if (capability == HatchCapabilities.ENERGY_INPUT && mode.hasInput()) {
                return HatchCapabilities.ENERGY_INPUT.cast(buffer);
            }
            if (capability == HatchCapabilities.ENERGY_OUTPUT && mode.hasOutput()) {
                return HatchCapabilities.ENERGY_OUTPUT.cast(outputBuffer);
            }
            if (capability == CapabilityEnergy.ENERGY) {
                return CapabilityEnergy.ENERGY.cast(port);
            }
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void saveAdditional(@NotNull NBTTagCompound tag) {
        super.saveAdditional(tag);
        tag.setInteger("Tier", tier.level());
        tag.setString("Mode", mode.name());
        locked(() -> {
            if (mode == HatchMode.MIXED) {
                tag.setLong("InputEnergy", buffer.savedEnergy());
                tag.setLong("OutputEnergy", outputBuffer.savedEnergy());
                tag.removeTag("Energy");
            } else {
                tag.setLong("Energy", buffer.savedEnergy());
                tag.removeTag("InputEnergy");
                tag.removeTag("OutputEnergy");
            }
        });
        tag.setLong("MaxInRate", maxInputRate);
        tag.setLong("MaxOutRate", maxOutputRate);
        tag.setBoolean("AutoInput", autoInput);
        tag.setBoolean("AutoOutput", autoOutput);
    }

    @Override
    public void loadTag(@NotNull NBTTagCompound tag) {
        pendingConfiguration = false;
        super.loadTag(tag);
        invalidateStorage();
        tier = tag.hasKey("Tier", 3) ? HatchTier.of(tag.getInteger("Tier")) : tier;
        mode = tag.hasKey("Mode", 8) ? HatchMode.valueOf(tag.getString("Mode")) : mode;
        maxInputRate = Math.max(0, tag.getLong("MaxInRate"));
        maxOutputRate = Math.max(0, tag.getLong("MaxOutRate"));
        autoInput = tag.getBoolean("AutoInput");
        autoOutput = tag.getBoolean("AutoOutput");
        createStorage();
        // PM reloads the current tier config, then clamps the saved balance if capacity was reduced.
        // Legacy mixed hatches had one Energy value. Keep it only on the input side, never duplicate it.
        long saved = mode == HatchMode.MIXED && tag.hasKey("InputEnergy", 4)
            ? tag.getLong("InputEnergy") : tag.getLong("Energy");
        buffer.restore(Math.clamp(saved, 0, buffer.capacity()));
        if (mode == HatchMode.MIXED)
            outputBuffer.restore(Math.clamp(tag.getLong("OutputEnergy"), 0, outputBuffer.capacity()));
        if (isInvalid()) {
            invalidateStorage();
        }
    }

    @Override
    protected void setRemoved() {
        invalidateStorage();
        flushStorageChanges();
        super.setRemoved();
    }

    @Override
    protected void onChunkUnloaded() {
        invalidateStorage();
        flushStorageChanges();
        super.onChunkUnloaded();
    }

    @Override
    protected void clearRemoved() {
        super.clearRemoved();
        if (!buffer.isValid()) {
            long saved = buffer.savedEnergy();
            long savedOutput = outputBuffer == null ? 0 : outputBuffer.savedEnergy();
            createStorage();
            buffer.restore(Math.clamp(saved, 0, buffer.capacity()));
            if (mode == HatchMode.MIXED)
                outputBuffer.restore(Math.clamp(savedOutput, 0, outputBuffer.capacity()));
        }
    }
    @Override public @NotNull NBTTagCompound getUpdateTag() { return writeToNBT(new NBTTagCompound()); }
    @Override public SPacketUpdateTileEntity getUpdatePacket() { return new SPacketUpdateTileEntity(pos, 0, getUpdateTag()); }
    @Override public void onDataPacket(@NotNull NetworkManager network, SPacketUpdateTileEntity packet) { readFromNBT(packet.getNbtCompound()); }
    @Override public void handleUpdateTag(@NotNull NBTTagCompound tag) { readFromNBT(tag); }
}
