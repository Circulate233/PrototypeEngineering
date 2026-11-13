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
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCommit;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskStorage;
import com.circulation.prototype_eng.research.DataCenterStorageNbt;
import com.circulation.prototype_eng.research.ResearchDataCenter;
import com.circulation.prototype_eng.research.disk.MinecraftDataCenterPersistence;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Persistent physical database attachment. Its version proof also requires the exact live component binding.
 */
public final class MachineDataCenterStorage implements ResearchDiskStorage, MachineLifecycleListener {
    public static final MachineComponentCodec<MachineDataCenterStorage> CODEC = new MachineComponentCodec<>() {
        @Override
        public NBTTagCompound encode(MachineInstance owner, MachineDataCenterStorage component) {
            component.requireOwner(owner);
            owner.requirePersistenceBoundary();
            return DataCenterStorageNbt.encodeState(Objects.requireNonNull(component.state).persistentState());
        }

        @Override
        public MachineDataCenterStorage restore(MachineInstance owner, MachineDataCenterStorage component, NBTTagCompound saved) {
            component.requireOwner(owner);
            owner.requirePersistenceBoundary();
            if (component.state != null) {
                throw new IllegalStateException("Data-center restore requires a fresh attachment");
            }
            var savedState = DataCenterStorageNbt.decodeState(saved, component.registry);
            var snapshot = savedState.contents();
            if (snapshot.level() != component.level || snapshot.capacity() != component.capacity) {
                throw new IllegalArgumentException("Saved data-center metadata differs from its declaration");
            }
            component.state = ResearchDataCenter.restore(savedState, component.registry, component::requireThread, component::changed);
            return component;
        }

        @Override
        public void validate(MachineInstance owner, MachineDataCenterStorage component) {
            component.requireOwner(owner);
        }
    };

    private final MachineInstance owner;
    private final ComponentType<MachineInstance, MachineOwnership> ownershipType;
    private final int level;
    private final int capacity;
    private final ResearchRegistry registry;
    private final Consumer<MachineInstance> dirty;
    private final MachineDataCenterPersistence persistence;
    private final ComponentType<MachineInstance, MachineDataCenterStorage> boundType;
    @Nullable
    private ResearchDataCenter state;

    private MachineDataCenterStorage(MachineInstance owner, ComponentType<MachineInstance, MachineDataCenterStorage> boundType,
                                     ComponentType<MachineInstance, MachineOwnership> ownership,
                                     int level, int capacity, ResearchRegistry registry, Consumer<MachineInstance> dirty,
                                     MachineDataCenterPersistence persistence) {
        this.owner = owner;
        this.boundType = boundType;
        ownershipType = ownership;
        this.level = level;
        this.capacity = capacity;
        this.registry = registry;
        this.dirty = dirty;
        this.persistence = persistence;
    }

    /**
     * Default native controller storage, using the same exact physical host as its persistence barrier.
     */
    public static ComponentType<MachineInstance, MachineDataCenterStorage> component(ResourceLocation id, int level, int capacity,
                                                                                     ResearchRegistry registry) {
        return component(id, level, capacity, registry, MinecraftDataCenterPersistence::markDirty);
    }

    /**
     * The host supplies its real persistence dirty callback; declare this component before its network role.
     */
    public static ComponentType<MachineInstance, MachineDataCenterStorage> component(ResourceLocation id, int level, int capacity,
                                                                                     ResearchRegistry registry, Consumer<MachineInstance> dirty) {
        return component(id, MachineOwnership.TYPE, level, capacity, registry, dirty);
    }

    public static ComponentType<MachineInstance, MachineDataCenterStorage> component(ResourceLocation id,
                                                                                     ComponentType<MachineInstance, MachineOwnership> ownership, int level, int capacity,
                                                                                     ResearchRegistry registry, Consumer<MachineInstance> dirty) {
        return component(id, ownership, level, capacity, registry, dirty, MinecraftDataCenterPersistence.INSTANCE);
    }

    /**
     * External hosts may supply their real writer and authoritative database readback.
     */
    public static ComponentType<MachineInstance, MachineDataCenterStorage> component(ResourceLocation id, int level, int capacity,
                                                                                     ResearchRegistry registry, Consumer<MachineInstance> dirty,
                                                                                     MachineDataCenterPersistence persistence) {
        return component(id, MachineOwnership.TYPE, level, capacity, registry, dirty, persistence);
    }

    public static ComponentType<MachineInstance, MachineDataCenterStorage> component(ResourceLocation id,
                                                                                     ComponentType<MachineInstance, MachineOwnership> ownership,
                                                                                     int level, int capacity, ResearchRegistry registry,
                                                                                     Consumer<MachineInstance> dirty, MachineDataCenterPersistence persistence) {
        if (level < 0 || capacity < 1) {
            throw new IllegalArgumentException("Invalid physical data-center metadata");
        }
        Objects.requireNonNull(registry);
        Objects.requireNonNull(dirty);
        Objects.requireNonNull(ownership);
        Objects.requireNonNull(persistence);
        return new ComponentType<>(id, (owner, type) -> new MachineDataCenterStorage(owner, type, ownership, level, capacity, registry, dirty, persistence),
            List.of(), List.of(ownership));
    }

    @Override
    public void onLoaded(MachineLifecycleEvent.Loaded event) {
        requireThread();
        var identity = Objects.requireNonNull(owner.components().get(ownershipType)).identity();
        if (state == null) {
            state = new ResearchDataCenter(identity.machineId(), level, capacity, registry, this::requireThread, this::changed);
        } else if (!state.snapshot().dataCenterId().equals(identity.machineId())) {
            throw new IllegalArgumentException("Saved data-center storage has another controller identity");
        }
    }

    @Override
    public DataCenterResearchSnapshot snapshot() {
        requireThread();
        return Objects.requireNonNull(state, "Data-center storage is not loaded").snapshot();
    }

    @Override
    public boolean current(DataCenterResearchSnapshot snapshot) {
        requireThread();
        return live() && Objects.requireNonNull(state).current(snapshot);
    }

    @Override
    public Result downloadResearch(ResourceLocation id, int requestedLevel, TeamResearchView cloud, BooleanSupplier current) {
        requireLive();
        return Objects.requireNonNull(state).downloadResearch(id, requestedLevel, cloud, () -> current.getAsBoolean() && live());
    }

    @Override
    public Result storeFromDisk(Object2IntMap<ResourceLocation> entries, TeamResearchView cloud, BooleanSupplier current) {
        requireLive();
        return Objects.requireNonNull(state).storeFromDisk(entries, cloud, () -> current.getAsBoolean() && live());
    }

    @Override
    public Result synchronizeLoopingResearch(TeamResearchView cloud, BooleanSupplier current) {
        requireLive();
        return Objects.requireNonNull(state).synchronizeLoopingResearch(cloud, () -> current.getAsBoolean() && live());
    }


    @Override
    public boolean diskPersistenceAvailable() {
        requireLive();
        return persistence != MinecraftDataCenterPersistence.INSTANCE || MinecraftDataCenterPersistence.available(owner);
    }

    @Override
    public Result commitDisk(ResearchDiskCommit commit, TeamResearchView cloud, BooleanSupplier current) {
        requireLive();
        if (!diskPersistenceAvailable()) throw new IllegalStateException("Data-center disk persistence is unavailable");
        return Objects.requireNonNull(state).commitDisk(commit, cloud, () -> current.getAsBoolean() && live());
    }

    @Override
    @Nullable
    public ResearchDiskCommit receipt(UUID operationId) {
        requireLive();
        return Objects.requireNonNull(state).receipt(operationId);
    }

    @Override
    public void persistDisk(ResearchDiskCommit commit, BooleanSupplier physicalCurrent) {
        requireLive();
        if (!commit.equals(Objects.requireNonNull(state).receipt(commit.operationId())))
            throw new IllegalStateException("Database import receipt is missing or conflicting");
        persistCurrent(physicalCurrent);
    }

    @Override
    public void clearDiskReceipt(ResearchDiskCommit commit, BooleanSupplier physicalCurrent) {
        requireLive();
        Objects.requireNonNull(state).removeDiskReceipt(commit, () -> physicalCurrent.getAsBoolean() && live());
        persistCurrent(physicalCurrent);
    }

    private void persistCurrent(BooleanSupplier physicalCurrent) {
        requireLive();
        owner.requirePersistenceBoundary();
        if (!diskPersistenceAvailable() || !Objects.requireNonNull(physicalCurrent).getAsBoolean())
            throw new IllegalStateException("Original database persistence binding is unavailable");
        var backend = Objects.requireNonNull(state);
        var captured = backend.persistentState();
        var expected = DataCenterStorageNbt.encodeState(captured);
        BooleanSupplier current = () -> physicalCurrent.getAsBoolean() && live() && backend.persistentState() == captured;
        persistence.persistAndVerify(owner, boundType.id(), ownershipType.id(), expected, current);
        requireLive();
        if (!current.getAsBoolean()) throw new IllegalStateException("Database changed during its persistence barrier");
    }

    /**
     * Combine with MachineNetworkDevice.capturedComponent so physical and content proofs are both retained.
     */
    public NetworkRuntime.Capture networkCapture(MachineIdentityRegistry.Registration identity, boolean formed,
                                                 Reference2DoubleMap<NetworkResourceType> resources, NetworkResourceModifier modifier) {
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
        return owner.components().get(boundType) == this
            && Objects.requireNonNull(owner.lifecycle()).schedulingAllowed();
    }

    private void requireLive() {
        requireThread();
        if (!live()) {
            throw new IllegalStateException("Data-center storage attachment is not current");
        }
    }

    private void requireThread() {
        Objects.requireNonNull(owner.lifecycle()).host().requireServerThread();
    }

    private void changed() {
        dirty.accept(owner);
    }

    private void requireOwner(MachineInstance candidate) {
        if (candidate != owner) {
            throw new IllegalArgumentException("Data-center storage belongs to another machine");
        }
    }
}
