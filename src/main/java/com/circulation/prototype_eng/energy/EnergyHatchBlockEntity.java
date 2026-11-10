package com.circulation.prototype_eng.energy;

import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.gui.EnergyHatchGuiHost;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.storage.HatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ReconfigurableHatch;
import com.circulation.prototype_eng.block.EnergyHatchBlock;
import com.circulation.prototype_eng.storage.HatchAutoTransfer;
import com.circulation.prototype_eng.storage.HatchConfigurationNbt;
import com.circulation.prototype_eng.storage.HatchConfigurationRequest;
import com.circulation.prototype_eng.storage.HatchReplacement;
import com.circulation.prototype_eng.storage.HatchStorageChanges;
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

/**
 * PM's tiered input/output/IO energy hatch, retaining separate machine and automation directions.
 */
public class EnergyHatchBlockEntity extends AEBaseTile implements EnergyHatchGuiHost, ReconfigurableHatch {
    private static final VarHandle STORAGE_DIRTY;

    static {
        try {
            STORAGE_DIRTY = MethodHandles.lookup().findVarHandle(EnergyHatchBlockEntity.class, "storageDirty", boolean.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final HatchConfigurationRequest configurationRequest = new HatchConfigurationRequest();
    private final HatchAutoTransfer autoTransfer = new HatchAutoTransfer();
    @SuppressWarnings("unused") // Worker writes and server-tick drains use STORAGE_DIRTY.
    private boolean storageDirty;
    private HatchTier tier;
    private boolean pendingConfiguration;
    private HatchMode mode;
    private EnergyBuffer buffer;
    private EnergyBuffer outputBuffer;
    @Nullable
    private String allocationLocation;
    private EnergyHatchStorage balances;
    private EnergyHatchConfiguration effective;
    private long observedTargetRevision = -1;
    private boolean configurationNotification;
    private ForgeEnergyPort port;
    private long maxInputRate = -1;
    private long maxOutputRate = -1;
    private boolean autoInput;
    private boolean autoOutput;

    /**
     * Forge instantiates registered tile entities through this constructor when loading NBT.
     */
    @SuppressWarnings("unused")
    public EnergyHatchBlockEntity() {
        this(HatchMode.INPUT, HatchTier.LV1);
        pendingConfiguration = true;
    }

    public EnergyHatchBlockEntity(HatchMode mode, HatchTier tier) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.tier = Objects.requireNonNull(tier, "tier");
        effective = HatchConfig.energyConfiguration(tier, mode);
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
        observeConfigurationTarget();
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
        if (outputBuffer != null && outputBuffer != buffer)
            outputBuffer.context().bindSourceIdentity(location + "/output");
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
        effective = HatchConfig.energyConfiguration(tier, mode);
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
        installStorage(new EnergyHatchStorage(effective, maxInputRate, maxOutputRate, 0, 0, this::requestStorageSave));
    }

    private void installStorage(EnergyHatchStorage next) {
        balances = next;
        buffer = next.primary();
        outputBuffer = next.output();
        allocationLocation = null;
        port = new ForgeEnergyPort(next.input(), next.output());
    }

    private void requestStorageSave() {
        STORAGE_DIRTY.setRelease(this, true);
    }

    @Override
    public void update() {
        super.update();
        observeConfigurationTarget();
        queueConfiguration();
        if ((mode.hasInput() && autoInput || mode.hasOutput() && autoOutput)
            && world != null && !world.isRemote && !isInvalid()
            && world.isBlockLoaded(pos) && world.getTileEntity(pos) == this)
            autoTransfer.tickEnergy(this);
        flushStorageChanges();
        notifyConfiguration();
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
        balances.invalidate();
    }

    @Override
    public EnergyHatchConfiguration effectiveConfiguration() {
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
        if (!(configuration instanceof EnergyHatchConfiguration energy) || energy.mode() != mode)
            throw new IllegalArgumentException("FE profile must preserve physical hatch direction");
        configurationRequest.accept(energy, effective);
        observedTargetRevision = HatchConfig.targetRevision();
        queueConfiguration(boundary);
        return configurationRequest.status();
    }

    private void observeConfigurationTarget() {
        if (world != null && !world.isRemote && !isInvalid() && observedTargetRevision != HatchConfig.targetRevision())
            requestConfiguration(HatchConfig.energyConfiguration(tier, mode));
    }

    /** Addon hosts may supply an owner scheduler while preserving the public request admission checks. */
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

    private HatchReplacement applyConfiguration() {
        var target = (EnergyHatchConfiguration) Objects.requireNonNull(configurationRequest.target());
        var old = balances;
        var location = allocationLocation();
        return old.replace(target, maxInputRate, maxOutputRate, location, this::requestStorageSave,
            () -> balances == old && configurationRequest.target() == target && !isInvalid(), candidate -> {
                var nextPort = new ForgeEnergyPort(candidate.input(), candidate.output());
                return () -> {
                    balances = candidate;
                    buffer = candidate.primary();
                    outputBuffer = candidate.output();
                    port = nextPort;
                    effective = target;
                    allocationLocation = location;
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

    public HatchTier tier() {
        return tier;
    }

    public HatchMode mode() {
        return mode;
    }

    public long maxInputRate() {
        return maxInputRate;
    }

    public long maxOutputRate() {
        return maxOutputRate;
    }

    public boolean autoInput() {
        return autoInput;
    }

    public boolean autoOutput() {
        return autoOutput;
    }

    @SuppressWarnings("unused") // Public native transfer query.
    public long effectiveInputRate() {
        return Objects.requireNonNull(effective.input() == null ? effective.output() : effective.input()).effectiveRate(maxInputRate);
    }

    @SuppressWarnings("unused") // Public native transfer query.
    public long effectiveOutputRate() {
        return Objects.requireNonNull(effective.output() == null ? effective.input() : effective.output()).effectiveRate(maxOutputRate);
    }

    /**
     * Minus one removes the user limit; zero prohibits transfer. The configured ceiling still applies.
     */
    public void setMaxInputRate(long value) {
        if (value < -1) throw new IllegalArgumentException("Input rate must be -1 or nonnegative");
        balances.updateRates(value, maxOutputRate);
        maxInputRate = value;
        settingsChanged();
    }

    public void setMaxOutputRate(long value) {
        if (value < -1) throw new IllegalArgumentException("Output rate must be -1 or nonnegative");
        balances.updateRates(maxInputRate, value);
        maxOutputRate = value;
        settingsChanged();
    }

    public void setAutoInput(boolean value) {
        autoInput = value;
        settingsChanged();
    }

    public void setAutoOutput(boolean value) {
        autoOutput = value;
        settingsChanged();
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
        tag.setTag("EffectiveProfile", HatchConfigurationNbt.energy(effective));
        var saved = balances.savedBalances();
        if (mode == HatchMode.MIXED) {
            tag.setLong("InputEnergy", saved[0]);
            tag.setLong("OutputEnergy", saved[1]);
            tag.removeTag("Energy");
        } else {
            tag.setLong("Energy", saved[mode.hasInput() ? 0 : 1]);
            tag.removeTag("InputEnergy");
            tag.removeTag("OutputEnergy");
        }
        tag.setLong("MaxInRate", maxInputRate);
        tag.setLong("MaxOutRate", maxOutputRate);
        tag.setBoolean("AutoInput", autoInput);
        tag.setBoolean("AutoOutput", autoOutput);
    }

    @Override
    public void loadTag(@NotNull NBTTagCompound tag) {
        if (hasTransferReservation())
            throw new IllegalStateException("Cannot reload energy hatch with an unresolved external transfer");
        boolean modern = tag.hasKey("EffectiveProfile");
        var nextTier = modern || tag.hasKey("Tier") ? HatchTier.of(StrictNbt.requireInt(tag, "Tier")) : tier;
        var nextMode = modern || tag.hasKey("Mode") ? HatchMode.valueOf(StrictNbt.requireString(tag, "Mode")) : mode;
        if (modern) {
            StrictNbt.requireLong(tag, "MaxInRate");
            StrictNbt.requireLong(tag, "MaxOutRate");
        }
        boolean nextAutoInput = modern ? StrictNbt.requireBoolean(tag, "AutoInput") : tag.getBoolean("AutoInput");
        boolean nextAutoOutput = modern ? StrictNbt.requireBoolean(tag, "AutoOutput") : tag.getBoolean("AutoOutput");
        long nextInputRate = HatchConfigurationNbt.energyRate(tag, "MaxInRate");
        long nextOutputRate = HatchConfigurationNbt.energyRate(tag, "MaxOutRate");
        if (nextMode == HatchMode.MIXED) {
            if (tag.hasKey("InputEnergy") && tag.hasKey("Energy"))
                throw new IllegalArgumentException("Ambiguous mixed hatch input balance");
            if (tag.hasKey("EffectiveProfile")) {
                StrictNbt.requireLong(tag, "InputEnergy");
                StrictNbt.requireLong(tag, "OutputEnergy");
            }
        } else {
            if (tag.hasKey("InputEnergy") || tag.hasKey("OutputEnergy"))
                throw new IllegalArgumentException("Saved FE sides differ from physical hatch direction");
            if (tag.hasKey("EffectiveProfile")) StrictNbt.requireLong(tag, "Energy");
        }
        // Legacy mixed Energy belongs only to input; a missing output balance is zero.
        long primary = HatchConfigurationNbt.nonnegative(tag,
            nextMode == HatchMode.MIXED && tag.hasKey("InputEnergy") ? "InputEnergy" : "Energy");
        long inputEnergy = nextMode.hasInput() ? primary : 0;
        long outputEnergy = nextMode == HatchMode.MIXED ? HatchConfigurationNbt.nonnegative(tag, "OutputEnergy")
            : nextMode.hasOutput() ? primary : 0;
        EnergyHatchConfiguration profile;
        if (tag.hasKey("EffectiveProfile")) {
            profile = HatchConfigurationNbt.energy(StrictNbt.requireCompound(tag, "EffectiveProfile"), nextMode);
        } else {
            var defaults = HatchConfig.energyConfiguration(nextTier, nextMode);
            var in = defaults.input();
            var out = defaults.output();
            profile = new EnergyHatchConfiguration(in == null ? null : new EnergyHatchConfiguration.Side(Math.max(in.capacity(), inputEnergy), in.transfer()),
                out == null ? null : new EnergyHatchConfiguration.Side(Math.max(out.capacity(), outputEnergy), out.transfer()));
        }
        var changes = new HatchStorageChanges(this::requestStorageSave);
        var candidate = new EnergyHatchStorage(profile, nextInputRate, nextOutputRate, inputEnergy, outputEnergy, changes);
        if (isInvalid()) candidate.invalidate();
        invalidateStorage();
        super.loadTag(tag);
        pendingConfiguration = false;
        tier = nextTier;
        mode = nextMode;
        effective = profile;
        maxInputRate = nextInputRate;
        maxOutputRate = nextOutputRate;
        autoInput = nextAutoInput;
        autoOutput = nextAutoOutput;
        installStorage(candidate);
        configurationRequest.reset();
        observedTargetRevision = -1;
        changes.enable();
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
        if (hasTransferReservation()) return;
        if (!buffer.isValid()) {
            var saved = new NBTTagCompound();
            saveAdditional(saved);
            loadTag(saved);
        }
    }

    private boolean hasTransferReservation() {
        return buffer != null && buffer.hasTransferReservation()
            || outputBuffer != null && outputBuffer != buffer && outputBuffer.hasTransferReservation();
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
