package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
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
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskData;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImport;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.research.DataCenterStorageNbt;
import com.circulation.prototype_eng.research.ResearchEligibility;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineDataCenterStorageTest {
    private static NetworkResourceRegistry resources() {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        return resources;
    }

    private static ResearchRegistry research(NetworkResourceRegistry resources) {
        var registry = new ResearchRegistry();
        registry.register(definition("loop", 3));
        registry.register(definition("other", 1));
        registry.freeze(resources);
        return registry;
    }

    private static ResearchDefinition definition(String name, int max) {
        return ResearchDefinition.builder(id(name)).maxLevel(max).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                 .stage(ResearchLevelStage.builder(1, max).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build();
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("machine_data_test", name);
    }

    @Test
    void selectionCarriesOnlyTheActualWinnerAndContentChangesInvalidateOldPermitsBeforeRefresh() {
        try (var fixture = new Fixture()) {
            var first = fixture.dataCenter();
            var second = fixture.dataCenter();
            fixture.player.completeAtLeast(id("loop"), 1);
            fixture.player.completeAtLeast(id("other"), 1);
            assertEquals(DataCenterStorage.Result.UPDATED, fixture.storage(first).downloadResearch(id("loop"), 1, fixture.cloud()));
            assertEquals(DataCenterStorage.Result.UPDATED, fixture.storage(second).downloadResearch(id("other"), 1, fixture.cloud()));
            assertTrue(fixture.network.refresh());
            var team = Objects.requireNonNull(fixture.network.plan()).teams().get("team");
            assertEquals(fixture.storage(first).snapshot(), team.dataCenterResearch(), "Equal-size data centers retain the earlier load");
            assertFalse(Objects.requireNonNull(team.dataCenterResearch()).storedResearch().containsKey(id("other")));
            var old = Objects.requireNonNull(fixture.role(first).workPermit());
            var receipt = Objects.requireNonNull(old.reserve(id("work"), 0, Object2LongMaps.emptyMap()));
            fixture.player.completeAtLeast(id("loop"), 3);
            assertEquals(DataCenterStorage.Result.UPDATED, fixture.storage(first).synchronizeLoopingResearch(fixture.cloud()));
            assertFalse(receipt.commit());
            receipt.rollback();
            assertNull(fixture.role(first).workPermit(), "An unchanged physical structure cannot grant from stale database contents");
            assertTrue(fixture.network.refresh());
            assertEquals(3, Objects.requireNonNull(Objects.requireNonNull(fixture.network.plan()).teams().get("team").dataCenterResearch())
                                   .storedResearch().getInt(id("loop")));
            assertEquals(DataCenterStorage.Result.UPDATED, fixture.storage(second).downloadResearch(id("loop"), 1, fixture.cloud()));
            assertNull(fixture.role(first).workPermit(), "The losing database's changed selection count also invalidates publication");
            assertTrue(fixture.network.refresh());
            var selected = Objects.requireNonNull(fixture.network.plan()).teams().get("team").dataCenterResearch();
            assertEquals(fixture.storage(second).snapshot(), selected, "A full database with more distinct research wins without merging");
        }
    }

    @Test
    void copiedMachineSavesRestoreRealContentsButNeedUniqueIdentityAndAFreshPublication() {
        try (var fixture = new Fixture()) {
            var original = fixture.dataCenter();
            fixture.player.completeAtLeast(id("loop"), 2);
            fixture.storage(original).downloadResearch(id("loop"), 2, fixture.cloud());
            assertTrue(fixture.network.refresh());
            var old = Objects.requireNonNull(fixture.role(original).workPermit());
            var proof = fixture.storage(original).capture();
            var saved = fixture.dataAssembly.encode(original);
            var restored = fixture.attach(fixture.dataAssembly.restore(fixture.host(fixture.dataType.id()), saved, new MachineLifecycleRegistry()));
            assertEquals(proof.snapshot().storedResearch(), fixture.storage(restored).snapshot().storedResearch());
            assertFalse(fixture.storage(restored).current(proof.snapshot()));
            assertTrue(fixture.network.refresh());
            assertNull(fixture.role(original).workPermit());
            assertNull(fixture.role(restored).workPermit());
            fixture.unload(original);
            assertFalse(proof.current().getAsBoolean());
            assertFalse(old.current());
            assertNull(fixture.role(restored).workPermit());
            assertTrue(fixture.network.refresh());
            assertNotNull(fixture.role(restored).workPermit());
            assertEquals(2, Objects.requireNonNull(Objects.requireNonNull(fixture.network.plan()).teams().get("team").dataCenterResearch())
                                   .storedResearch().getInt(id("loop")));
            assertTrue(fixture.dirty > 0);
        }
    }

    @Test
    void diskPersistenceSavesCurrentAtomicStateAndCodecRestoresItsReceiptAfterAnUnknownSave() {
        try (var fixture = new Fixture()) {
            var machine = fixture.dataCenter();
            var storage = fixture.storage(machine);
            var data = new ResearchDiskData(fixture.research, 1, Object2IntMaps.singleton(id("loop"), 2));
            var commit = Objects.requireNonNull(ResearchDiskImport.prepare(new UUID(0, 80), fixture.player.playerUuid(), "team", data,
                storage.snapshot(), fixture.cloud()).commit());
            var before = storage.capture();
            assertEquals(DataCenterStorage.Result.UPDATED, storage.commitDisk(commit, fixture.cloud(), before.current()));
            assertFalse(before.current().getAsBoolean());
            fixture.diskFailure = new IllegalStateException("save outcome unknown");
            assertSame(fixture.diskFailure, assertThrows(IllegalStateException.class, () -> storage.persistDisk(commit, () -> true)));
            assertEquals(commit, storage.receipt(commit.operationId()));
            assertEquals(commit, DataCenterStorageNbt.decodeState(Objects.requireNonNull(fixture.diskSaved), fixture.research).receipts().get(commit.operationId()));
            fixture.diskFailure = null;
            fixture.player.completeAtLeast(id("loop"), 3);
            fixture.player.completeAtLeast(id("other"), 1);
            storage.synchronizeLoopingResearch(fixture.cloud());
            storage.downloadResearch(id("other"), 1, fixture.cloud());
            int saves = fixture.diskSaveCalls;
            assertThrows(IllegalStateException.class, () -> storage.persistDisk(commit, () -> false));
            assertEquals(saves, fixture.diskSaveCalls);
            storage.persistDisk(commit, () -> true);
            var persisted = DataCenterStorageNbt.decodeState(Objects.requireNonNull(fixture.diskSaved), fixture.research);
            assertEquals(3, persisted.contents().storedResearch().getInt(id("loop")));
            assertEquals(1, persisted.contents().storedResearch().getInt(id("other")));
            var saved = fixture.dataAssembly.encode(machine);
            var restored = fixture.attach(fixture.dataAssembly.restore(fixture.host(fixture.dataType.id()), saved, new MachineLifecycleRegistry()));
            assertEquals(commit, fixture.storage(restored).receipt(commit.operationId()));
        }
    }

    @Test
    void cleanupResavesMissingReceiptAfterFailureAndKeepsLaterOrdinaryDownloads() {
        try (var fixture = new Fixture()) {
            var storage = fixture.storage(fixture.dataCenter());
            var data = new ResearchDiskData(fixture.research, 1, Object2IntMaps.singleton(id("loop"), 2));
            var commit = Objects.requireNonNull(ResearchDiskImport.prepare(new UUID(0, 81), fixture.player.playerUuid(), "team", data,
                storage.snapshot(), fixture.cloud()).commit());
            storage.commitDisk(commit, fixture.cloud(), () -> true);
            storage.persistDisk(commit, () -> true);
            fixture.player.completeAtLeast(id("other"), 1);
            storage.downloadResearch(id("other"), 1, fixture.cloud());
            fixture.diskFailure = new IllegalStateException("cleanup save outcome unknown");
            assertThrows(IllegalStateException.class, () -> storage.clearDiskReceipt(commit, () -> true));
            assertNull(storage.receipt(commit.operationId()));
            fixture.diskFailure = null;
            int saves = fixture.diskSaveCalls;
            storage.clearDiskReceipt(commit, () -> true);
            assertEquals(saves + 1, fixture.diskSaveCalls);
            var persisted = DataCenterStorageNbt.decodeState(Objects.requireNonNull(fixture.diskSaved), fixture.research);
            assertTrue(persisted.receipts().isEmpty());
            assertEquals(1, persisted.contents().storedResearch().getInt(id("other")));
        }
    }

    @Test
    void convenienceSnapshotSourcesRecheckActualResourceConfigurationBeforeIssuingNewWork() {
        try (var fixture = new Fixture()) {
            assertTrue(fixture.network.refresh());
            var component = Objects.requireNonNull(fixture.center.components().get(fixture.centerRole));
            var before = Objects.requireNonNull(component.workPermit());
            fixture.supply.put(NetworkResources.COMPUTE, 50);
            assertNull(component.workPermit());
            assertEquals(NetworkTeamBudget.LeaseStatus.EXPIRED, before.requestLease());
            assertTrue(fixture.network.refresh());
            assertEquals(20, Objects.requireNonNull(component.workPermit()).numericResources().getLong(NetworkResources.COMPUTE),
                "Same-tick recapture retains the first accepted budget even when supply changes");
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Thread ownerThread = Thread.currentThread();
        private final Object world = new Object();
        private final List<MachineInstance> machines = new ObjectArrayList<>();
        private final NetworkResourceRegistry resources = resources();
        private final ResearchRegistry research = research(resources);
        private final PlayerResearchState player = new PlayerResearchState(new UUID(0, 1));
        private final MachineIdentityRegistry identities = new MachineIdentityRegistry(this::requireOwner);
        private final NetworkRuntime network = new NetworkRuntime(identities, resources,
            owner -> OwnerTeamResolution.resolved(owner, "team", OwnerTeamType.SERVER, ObjectSets.singleton(owner), Optional.empty()), this::requireOwner);
        private final ComponentType<MachineInstance, MachineOwnership> ownership = new ComponentType<>(id("ownership"),
            owner -> new MachineOwnership(owner, _ -> identities), List.of());
        private final ComponentType<MachineInstance, MachineDataCenterStorage> storage = MachineDataCenterStorage.component(id("storage"),
            ownership, 1, 2, research, _ -> this.dirty++, this::persistDisk);
        private final ComponentType<MachineInstance, MachineNetworkDevice> dataRole = MachineNetworkDevice.capturedComponent(id("data_role"),
            ownership, (owner, identity) -> Objects.requireNonNull(owner.components().get(storage)).networkCapture(identity,
                Objects.requireNonNull(owner.lifecycle()).formed(), Object2LongMaps.emptyMap(), NetworkResourceModifier.EMPTY), _ -> network);
        private final Object2LongOpenHashMap<NetworkResourceType> supply = new Object2LongOpenHashMap<>();
        private final ComponentType<MachineInstance, MachineNetworkDevice> centerRole = MachineNetworkDevice.component(id("center_role"),
            ownership, (owner, identity) -> new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.NETWORK_CENTER, 1,
                Objects.requireNonNull(owner.lifecycle()).formed(), identity.loadOrder(), NetworkResourceModifier.copyResources(supply),
                NetworkResourceModifier.EMPTY, 0, 0), _ -> network);
        private final MachineType dataType = MachineType.builder(id("data_machine"), GuiText.ResourceStorage, id("structure"))
                                                        .component(ownership).component(storage).component(dataRole).build();
        private final MachineAssembly dataAssembly = MachineAssembly.builder(dataType, "data-v1").component(ownership, MachineOwnership.CODEC)
                                                                    .component(storage, MachineDataCenterStorage.CODEC).recreated(dataRole).build();
        private final MachineInstance center;
        private int dirty;
        private int diskSaveCalls;
        @Nullable
        private NBTTagCompound diskSaved;
        @Nullable
        private RuntimeException diskFailure;
        private int positions;

        private Fixture() {
            supply.put(NetworkResources.THREAD, 5);
            supply.put(NetworkResources.COMPUTE, 20);
            network.beginTick(0);
            var type = MachineType.builder(id("center_machine"), GuiText.ResourceStorage, id("structure"))
                                  .component(ownership).component(centerRole).build();
            var assembly = MachineAssembly.builder(type, "center-v1").component(ownership, MachineOwnership.CODEC).recreated(centerRole).build();
            center = attach(assembly.create(host(type.id()), new MachineLifecycleRegistry()));
        }

        private MachineInstance dataCenter() {
            return attach(dataAssembly.create(host(dataType.id()), new MachineLifecycleRegistry()));
        }

        private MachineInstance attach(MachineInstance machine) {
            Objects.requireNonNull(machine.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
            machines.add(machine);
            return machine;
        }

        private Host host(ResourceLocation type) {
            return new Host(world, type, new BlockPos(positions++, 0, 0), player.playerUuid());
        }

        private MachineDataCenterStorage storage(MachineInstance machine) {
            return Objects.requireNonNull(machine.components().get(storage));
        }

        private MachineNetworkDevice role(MachineInstance machine) {
            return Objects.requireNonNull(machine.components().get(dataRole));
        }

        private TeamResearchView cloud() {
            return new ResearchEligibility(research).calculateStructural("team", List.of(player.playerUuid()),
                Map.of(player.playerUuid(), player), Object2IntMaps.emptyMap(), Set.of());
        }

        private void unload(MachineInstance machine) {
            Objects.requireNonNull(machine.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }

        private void persistDisk(MachineInstance machine, ResourceLocation storageType, ResourceLocation ownershipType,
                                 NBTTagCompound expected, BooleanSupplier current) {
            requireOwner();
            assertEquals(storage.id(), storageType);
            assertEquals(ownership.id(), ownershipType);
            assertTrue(current.getAsBoolean());
            assertEquals(expected, MachineDataCenterStorage.CODEC.encode(machine, storage(machine)));
            diskSaved = expected.copy();
            diskSaveCalls++;
            if (diskFailure != null) throw diskFailure;
        }

        private void requireOwner() {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException("Not the test server owner");
            }
        }

        @Override
        public void close() {
            machines.forEach(this::unload);
            network.close();
            identities.close();
        }
    }

    private record Host(Object worldIdentity, ResourceLocation type, BlockPos position,
                        UUID placementPlayer) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(type, 0, position);
        }

        @Override
        public World world() {
            throw new AssertionError("Storage tests do not load a Minecraft world");
        }

        @Override
        public void requireServerThread() {
        }
    }
}
