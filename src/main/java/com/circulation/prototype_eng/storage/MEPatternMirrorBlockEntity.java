package com.circulation.prototype_eng.storage;

import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceInputGroup;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A stable physical port with a revocable cached binding to one loaded pattern assembly.
 * The binding stores no inventory. Old machine captures retain only their original source generation.
 */
public final class MEPatternMirrorBlockEntity extends AEBaseTile implements ResourceHatch {
    private final List<ResourceHatch> unlinkedPartitions = List.of(this);
    private int sourceDimension;
    private long sourcePosition;
    private boolean bound;
    private volatile boolean loaded;
    private volatile @Nullable MEPatternMirrorHatch binding;
    private @Nullable MEPatternMirrorLinks.Subscription subscription;

    public void bindSource(int dimension, long position) {
        if (bound && sourceDimension == dimension && sourcePosition == position) return;
        disconnect();
        sourceDimension = dimension;
        sourcePosition = position;
        bound = true;
        if (loaded) connect();
        markDirty();
    }

    private void connect() {
        subscription = MEPatternMirrorLinks.forWorld(world).subscribe(world.provider.getDimension(), sourceDimension,
            sourcePosition, this::sourceChanged);
    }

    private void sourceChanged(@Nullable MEPatternAssemblyStorage source) {
        var previous = binding;
        if (previous != null) previous.revoke();
        binding = source == null ? null : new MEPatternMirrorHatch(source);
    }

    private void disconnect() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    @Override
    public void onReady() {
        super.onReady();
        if (world.isRemote) return;
        disconnect();
        loaded = true;
        if (bound) connect();
    }

    @Override
    protected void clearRemoved() {
        super.clearRemoved();
        scheduleInit();
    }

    @Override
    protected void setRemoved() {
        loaded = false;
        disconnect();
        super.setRemoved();
    }

    @Override
    protected void onChunkUnloaded() {
        loaded = false;
        disconnect();
        super.onChunkUnloaded();
    }

    @Override
    public boolean canBeRotated() {
        return false;
    }

    @Override
    public boolean isValid() {
        return loaded;
    }

    @Override
    public ResourceInputGroup inputGroup() {
        var current = binding;
        return current == null ? ResourceInputGroup.TRANSPARENT : current.inputGroup();
    }

    @Override
    public List<? extends ResourceHatch> inputPartitions() {
        var current = binding;
        return current == null ? unlinkedPartitions : current.inputPartitions();
    }

    @Override
    public @Nullable ResourceStorage input() {
        var current = binding;
        return current == null ? null : current.input();
    }

    @Override
    public @Nullable ResourceStorage output() {
        var current = binding;
        return current == null ? null : current.output();
    }

    @Override
    public @Nullable Object inputStorageIdentity() {
        var current = binding;
        return current == null ? null : current.inputStorageIdentity();
    }

    @Override
    public @Nullable Object outputStorageIdentity() {
        var current = binding;
        return current == null ? null : current.outputStorageIdentity();
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> capability, @Nullable EnumFacing side) {
        return capability == HatchCapabilities.RESOURCE || super.hasCapability(capability, side);
    }

    @Override
    public <T> @Nullable T getCapability(@NotNull Capability<T> capability, @Nullable EnumFacing side) {
        return capability == HatchCapabilities.RESOURCE ? HatchCapabilities.RESOURCE.cast(this) : super.getCapability(capability, side);
    }

    @Override
    public void saveAdditional(@NotNull NBTTagCompound tag) {
        super.saveAdditional(tag);
        if (!bound) return;
        tag.setInteger("SourceDimension", sourceDimension);
        tag.setLong("SourcePosition", sourcePosition);
    }

    @Override
    public void loadTag(@NotNull NBTTagCompound tag) {
        disconnect();
        super.loadTag(tag);
        bound = tag.hasKey("SourcePosition");
        sourceDimension = tag.getInteger("SourceDimension");
        sourcePosition = tag.getLong("SourcePosition");
        if (loaded && bound) connect();
    }
}
