package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.persistence.MachineComponentCodec;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.research.DataCenterStorageNbt;
import com.circulation.prototype_eng.research.ResearchDataCenter;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Persistent physical database attachment. Its version proof also requires the exact live component binding. */
public final class MachineDataCenterStorage implements DataCenterStorage, MachineLifecycleListener {
    public static final MachineComponentCodec<MachineDataCenterStorage> CODEC = new MachineComponentCodec<>() {
        @Override public NBTTagCompound encode(MachineInstance owner, MachineDataCenterStorage component) {
            component.requireOwner(owner);
            owner.requirePersistenceBoundary();
            return DataCenterStorageNbt.encode(component.snapshot());
        }

        @Override public MachineDataCenterStorage restore(MachineInstance owner, MachineDataCenterStorage component, NBTTagCompound saved) {
            component.requireOwner(owner);
            owner.requirePersistenceBoundary();
            if (component.loadAttempted || component.state != null) { throw new IllegalStateException("Data-center restore requires a fresh attachment"); }
            var snapshot = DataCenterStorageNbt.decode(saved, component.registry);
            if (snapshot.level() != component.level || snapshot.capacity() != component.capacity) {
                throw new IllegalArgumentException("Saved data-center metadata differs from its declaration");
            }
            component.state = ResearchDataCenter.restore(snapshot, component.registry, component::requireThread, component::changed);
            return component;
        }

        @Override public void validate(MachineInstance owner, MachineDataCenterStorage component) { component.requireOwner(owner); }
    };

    private final MachineInstance owner;
    private final ComponentType<MachineInstance, MachineOwnership> ownershipType;
    private final int level;
    private final int capacity;
    private final ResearchRegistry registry;
    private final Consumer<MachineInstance> dirty;
    @Nullable private ComponentType<MachineInstance, ?> boundType;
    @Nullable private ResearchDataCenter state;
    private boolean loadAttempted;

    /** The host supplies its real persistence dirty callback; declare this component before its network role. */
    public static ComponentType<MachineInstance, MachineDataCenterStorage> component(ResourceLocation id, int level, int capacity,
            ResearchRegistry registry, Consumer<MachineInstance> dirty) {
        return component(id, MachineOwnership.TYPE, level, capacity, registry, dirty);
    }

    public static ComponentType<MachineInstance, MachineDataCenterStorage> component(ResourceLocation id,
            ComponentType<MachineInstance, MachineOwnership> ownership, int level, int capacity,
            ResearchRegistry registry, Consumer<MachineInstance> dirty) {
        if (level < 0 || capacity < 1) { throw new IllegalArgumentException("Invalid physical data-center metadata"); }
        Objects.requireNonNull(registry);
        Objects.requireNonNull(dirty);
        Objects.requireNonNull(ownership);
        return new ComponentType<>(id, owner -> new MachineDataCenterStorage(owner, ownership, level, capacity, registry, dirty),
            List.of(), List.of(ownership));
    }

    private MachineDataCenterStorage(MachineInstance owner, ComponentType<MachineInstance, MachineOwnership> ownership,
            int level, int capacity, ResearchRegistry registry, Consumer<MachineInstance> dirty) {
        this.owner = owner;
        ownershipType = ownership;
        this.level = level;
        this.capacity = capacity;
        this.registry = registry;
        this.dirty = dirty;
    }

    @Override public void onLoaded(MachineLifecycleEvent.Loaded event) {
        requireOwner(event.context().machine());
        requireThread();
        if (loadAttempted) { throw new IllegalStateException("Data-center storage cannot load twice"); }
        loadAttempted = true;
        for (var binding : owner.components().assemblyBindings()) {
            if (binding.component() == this) {
                if (boundType != null) { throw new IllegalStateException("Data-center storage has multiple bindings"); }
                boundType = binding.type();
            }
        }
        Objects.requireNonNull(boundType, "Data-center storage is not installed");
        var identity = Objects.requireNonNull(owner.components().get(ownershipType)).identity();
        if (state == null) { state = new ResearchDataCenter(identity.machineId(), level, capacity, registry, this::requireThread, this::changed); }
        else if (!state.snapshot().dataCenterId().equals(identity.machineId())) {
            throw new IllegalArgumentException("Saved data-center storage has another controller identity");
        }
    }

    @Override public DataCenterResearchSnapshot snapshot() { requireThread(); return Objects.requireNonNull(state, "Data-center storage is not loaded").snapshot(); }

    @Override public boolean current(DataCenterResearchSnapshot snapshot) {
        requireThread();
        return live() && Objects.requireNonNull(state).current(snapshot);
    }

    @Override public Result downloadResearch(ResourceLocation id, int requestedLevel, TeamResearchView cloud) {
        requireLive();
        return Objects.requireNonNull(state).downloadResearch(id, requestedLevel, cloud);
    }

    @Override public Result storeFromDisk(Object2IntMap<ResourceLocation> entries, TeamResearchView cloud) {
        requireLive();
        return Objects.requireNonNull(state).storeFromDisk(entries, cloud);
    }

    @Override public Result synchronizeLoopingResearch(TeamResearchView cloud) {
        requireLive();
        return Objects.requireNonNull(state).synchronizeLoopingResearch(cloud);
    }

    /** Combine with MachineNetworkDevice.capturedComponent so physical and content proofs are both retained. */
    public NetworkRuntime.Capture networkCapture(MachineIdentityRegistry.Registration identity, boolean formed,
            Object2LongMap<NetworkResourceType> resources, NetworkResourceModifier modifier) {
        requireLive();
        if (identity.runtimeIdentity() != owner || !identity.identity().machineId().equals(snapshot().dataCenterId())) {
            throw new IllegalArgumentException("Data-center capture has foreign ownership");
        }
        var captured = capture();
        var snapshot = new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.DATA_CENTER, level, formed,
            identity.loadOrder(), resources, modifier, capacity, captured.snapshot().storedResearch().size(), captured.snapshot());
        return new NetworkRuntime.Capture(snapshot, captured.current());
    }

    private boolean live() {
        return boundType != null && owner.components().get(boundType) == this
            && Objects.requireNonNull(owner.lifecycle()).schedulingAllowed();
    }

    private void requireLive() {
        requireThread();
        if (!live()) { throw new IllegalStateException("Data-center storage attachment is not current"); }
    }

    private void requireThread() { Objects.requireNonNull(owner.lifecycle()).host().requireServerThread(); }
    private void changed() { dirty.accept(owner); }
    private void requireOwner(MachineInstance candidate) {
        if (candidate != owner) { throw new IllegalArgumentException("Data-center storage belongs to another machine"); }
    }
}
