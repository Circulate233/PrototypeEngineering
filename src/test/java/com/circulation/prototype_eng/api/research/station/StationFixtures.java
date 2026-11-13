package com.circulation.prototype_eng.api.research.station;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.ResearchStationController;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.network.MachineDataCenterStorage;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class StationFixtures implements AutoCloseable {
    static final UUID OWNER = new UUID(0, 1);
    static final TestResources.KeyType TYPE = new TestResources.KeyType("station_material");
    final Thread owner = Thread.currentThread();
    final Object world = new Object();
    final NetworkResourceRegistry resources = new NetworkResourceRegistry();
    final ResearchRegistry research = new ResearchRegistry();
    final MachineIdentityRegistry identities = new MachineIdentityRegistry(this::requireOwner);
    final MachineLifecycleRegistry listeners = new MachineLifecycleRegistry();
    final NetworkRuntime network;
    final ResearchStations selector = new ResearchStations(identities, this::requireOwner);
    final ComponentType<MachineInstance, MachineOwnership> ownership = new ComponentType<>(id("ownership"), machine -> new MachineOwnership(machine, _ -> identities), List.of());
    final ObjectArrayList<Entry> entries = new ObjectArrayList<>();
    final long threads;
    final long compute;
    int positions;
    long tick;

    StationFixtures(long threads, int dataCenterTier) {
        this(threads, 100, dataCenterTier, defaultDefinition());
    }

    @SuppressWarnings("unused")
        // Shared station consumer tests provide full material requirements and numeric supply here.
    StationFixtures(long threads, long compute, int dataCenterTier, ResearchDefinition definition) {
        this.threads = threads;
        this.compute = compute;
        resources.freeze();
        research.register(definition);
        research.freeze(resources);
        network = new NetworkRuntime(identities, resources, player -> OwnerTeamResolution.resolved(player, player.equals(OWNER) ? "team" : "other",
            OwnerTeamType.SERVER, ObjectSets.singleton(player), Optional.empty()), this::requireOwner);
        network.beginTick(0);
        role(NetworkDeviceRole.NETWORK_CENTER, 3, 0, OWNER, world);
        dataCenter(dataCenterTier);
    }

    static ResourceLocation id(String name) {
        return new ResourceLocation("research_stations_test", name);
    }

    static ResearchDefinition defaultDefinition() {
        return ResearchDefinition.builder(id("research")).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                 .stage(ResearchLevelStage.builder(1, 1).researchTier(2).resourceFormula(ResearchResourceFormula.builder().constant(NetworkResources.COMPUTE, 10).build()).build()).build();
    }

    Entry station(int level, int dimension) {
        return role(NetworkDeviceRole.RESEARCH_STATION, level, dimension, OWNER, world);
    }

    Entry station(int level, long rate, long energyPerTick) {
        return role(NetworkDeviceRole.RESEARCH_STATION, level, 0, OWNER, world, rate, energyPerTick);
    }

    Entry role(NetworkDeviceRole role, int level, int dimension, UUID player, Object worldIdentity) {
        return role(role, level, dimension, player, worldIdentity, 3, 0);
    }

    private Entry role(NetworkDeviceRole role, int level, int dimension, UUID player, Object worldIdentity, long rate, long energyPerTick) {
        var vector = new Object2LongOpenHashMap<NetworkResourceType>();
        if (role == NetworkDeviceRole.NETWORK_CENTER) {
            vector.put(NetworkResources.THREAD, threads);
            vector.put(NetworkResources.COMPUTE, compute);
        }
        var attached = MachineNetworkDevice.component(id("role_" + positions), ownership,
            (machine, identity) -> new NetworkDeviceSnapshot(identity.identity(), role, level, Objects.requireNonNull(machine.lifecycle()).formed(),
                identity.loadOrder(), vector, NetworkResourceModifier.EMPTY, 0, 0), _ -> network);
        var type = MachineType.builder(id("machine_" + positions), GuiText.ResourceStorage, id("structure")).component(ownership).component(attached).build();
        var builder = MachineAssembly.builder(type, "v1").component(ownership, MachineOwnership.CODEC).recreated(attached);
        if (role == NetworkDeviceRole.RESEARCH_STATION)
            builder.instanceFactory(value -> new ResearchStationController(value, level,
                Object2LongMaps.singleton(NetworkResources.COMPUTE, rate), energyPerTick));
        var assembly = builder.build();
        return attach(assembly.create(host(type.id(), dimension, player, worldIdentity), listeners), assembly, attached);
    }

    void dataCenter(int level) {
        var storage = MachineDataCenterStorage.component(id("storage_" + positions), ownership, level, 10, research, _ -> {
        });
        var role = MachineNetworkDevice.capturedComponent(id("data_role_" + positions), ownership,
            (machine, identity) -> Objects.requireNonNull(machine.components().get(storage)).networkCapture(identity,
                Objects.requireNonNull(machine.lifecycle()).formed(), Object2LongMaps.emptyMap(), NetworkResourceModifier.EMPTY), _ -> network);
        var type = MachineType.builder(id("data_" + positions), GuiText.ResourceStorage, id("structure")).component(ownership).component(storage).component(role).build();
        var assembly = MachineAssembly.builder(type, "v1").component(ownership, MachineOwnership.CODEC).component(storage, MachineDataCenterStorage.CODEC).recreated(role).build();
        attach(assembly.create(host(type.id(), 0, OWNER, world), listeners), assembly, role);
    }

    Entry attach(MachineInstance machine, MachineAssembly assembly, ComponentType<MachineInstance, MachineNetworkDevice> role) {
        var hatch = new BufferedResourceHatch(HatchMode.MIXED, 1, Reference2LongMaps.singleton(TYPE, 100L), () -> {
        });
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.receiveEnergy(10, Actionable.MODULATE);
        Objects.requireNonNull(machine.lifecycle()).form(new MachineHatches(List.of(hatch), List.of(energy), List.of(energy)));
        var entry = new Entry(machine, assembly, role, hatch, energy);
        entries.add(entry);
        return entry;
    }

    MachineIdentityRegistry.Registration identity(Entry entry) {
        return Objects.requireNonNull(Objects.requireNonNull(entry.machine.components().get(ownership)).registration());
    }

    MachineNetworkDevice roleOf(MachineInstance machine) {
        return entries.stream().filter(entry -> entry.machine == machine).findFirst().map(entry -> Objects.requireNonNull(machine.components().get(entry.role))).orElseThrow();
    }

    Host host(ResourceLocation type, int dimension, UUID player, Object worldIdentity) {
        return new Host(worldIdentity, type, dimension, new BlockPos(positions++, 0, 0), player);
    }

    void refresh() {
        assertTrue(network.refresh());
    }

    void advanceTick() {
        network.endTick();
        network.beginTick(++tick);
    }

    void nextTick() {
        advanceTick();
        refresh();
    }

    void replaceHatches(Entry entry, MachineHatches hatches) {
        entry.machine.components().replaceValue(MachineHatches.TYPE, hatches);
    }

    void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Not owner");
        }
    }

    @Override
    public void close() {
        entries.forEach(entry -> Objects.requireNonNull(entry.machine.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
        network.close();
        identities.close();
    }

    record Entry(MachineInstance machine, MachineAssembly assembly,
                 ComponentType<MachineInstance, MachineNetworkDevice> role,
                 BufferedResourceHatch warehouse, EnergyBuffer energy) {
    }

    private record Host(Object worldIdentity, ResourceLocation type, int dimension, BlockPos position,
                        UUID placementPlayer) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(type, dimension, position);
        }

        @Override
        public World world() {
            throw new AssertionError("No game world in station tests");
        }

        @Override
        public void requireServerThread() {
        }
    }
}
