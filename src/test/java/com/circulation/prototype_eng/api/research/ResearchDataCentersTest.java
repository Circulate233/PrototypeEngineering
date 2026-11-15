package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.research.ResearchDataCenter;
import com.circulation.prototype_eng.research.ResearchEligibility;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ResearchDataCentersTest {
    static final UUID OWNER = new UUID(0, 1);
    static final ResourceLocation FIRST = id("first");
    static final ResourceLocation SECOND = id("second");

    private static ResearchDefinition definition(ResourceLocation id, int maxLevel) {
        return ResearchDefinition.builder(id).maxLevel(maxLevel).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                 .startCondition(_ -> fail("Database access ran a start condition"))
                                 .stage(ResearchLevelStage.builder(1, maxLevel).durationTicks(1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test_data_centers", path);
    }

    private static List<MachineInstance> orderedDataCenters(Fixture fixture) {
        var first = fixture.dataCenter();
        var second = fixture.dataCenter();
        return fixture.machineId(first).compareTo(fixture.machineId(second)) < 0 ? List.of(first, second) : List.of(second, first);
    }

    @Test
    void exactPublishedWinnerExposesItsExternalStorageAndRejectsForeignTargets() {
        try (var fixture = new Fixture()) {
            var pair = orderedDataCenters(fixture);
            var first = pair.getFirst();
            var loser = pair.getLast();
            assertNull(fixture.selector.capture("team", fixture.machineId(first)));
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(first)));
            assertSame(fixture.storage(first), capture.storage());
            assertEquals(OWNER, capture.ownerId());
            assertEquals(fixture.storage(first).snapshot(), capture.snapshot());
            assertTrue(capture.current());
            assertNull(fixture.selector.capture("other", fixture.machineId(first)));
            assertNull(fixture.selector.capture("team", fixture.machineId(loser)));
            assertNull(fixture.selector.capture("team", new UUID(0, 99)));
        }
    }

    @Test
    void writesInvalidateOriginalContentsAndFreshCaptureKeepsEarlierWrites() {
        try (var fixture = new Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var first = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            assertEquals(DataCenterStorage.Result.UPDATED, first.storage().downloadResearch(FIRST, 1, fixture.cloud(), first::current));
            assertFalse(first.current());
            assertTrue(first.snapshot().storedResearch().isEmpty());
            assertNull(fixture.selector.capture("team", fixture.machineId(machine)));
            fixture.refresh();
            var second = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            assertEquals(1, second.snapshot().storedResearch().getInt(FIRST));
            assertEquals(DataCenterStorage.Result.UPDATED, second.storage().downloadResearch(SECOND, 1, fixture.cloud(), second::current));
            assertFalse(second.current());
            fixture.refresh();
            var finalCapture = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            assertEquals(1, finalCapture.snapshot().storedResearch().getInt(FIRST));
            assertEquals(1, finalCapture.snapshot().storedResearch().getInt(SECOND));
        }
    }

    @Test
    void equalContentReplacementCannotReuseThePreviousComponentsPublication() {
        try (var fixture = new Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            var replacement = fixture.copyStorage(machine, capture.snapshot());
            machine.components().replaceValue(fixture.storageType, replacement);
            assertEquals(capture.snapshot(), replacement.snapshot());
            assertFalse(capture.current());
            assertNull(fixture.selector.capture("team", fixture.machineId(machine)));
            fixture.refresh();
            var fresh = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            assertSame(replacement, fresh.storage());
            assertTrue(fresh.current());
            assertFalse(capture.current());
        }
    }

    @Test
    void ambiguousStorageRejectsDuplicateInstancesAndDistinctBackends() {
        try (var fixture = new Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            var alias = new ComponentType<MachineInstance, DataCenterStorage>(id("storage_alias"), _ -> fixture.storage(machine), List.of());
            machine.components().put(alias, fixture.storage(machine));
            assertThrows(IllegalStateException.class, () -> fixture.selector.capture("team", fixture.machineId(machine)));
            assertThrows(IllegalStateException.class, capture::current);
            machine.components().replaceValue(alias, fixture.copyStorage(machine, capture.snapshot()));
            assertThrows(IllegalStateException.class, () -> fixture.selector.capture("team", fixture.machineId(machine)));
            machine.components().remove(alias);
            assertTrue(capture.current());
        }
    }

    @Test
    void duplicateRoleBindingAndMissingStorageAreExplicitlyRejected() {
        try (var fixture = new Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            var alias = new ComponentType<MachineInstance, MachineNetworkDevice>(id("role_alias"), _ -> fixture.role(machine), List.of());
            machine.components().put(alias, fixture.role(machine));
            assertThrows(IllegalStateException.class, () -> fixture.selector.capture("team", fixture.machineId(machine)));
            machine.components().remove(alias);
            machine.components().remove(fixture.storageType);
            assertFalse(capture.current());
            assertNull(fixture.selector.capture("team", fixture.machineId(machine)));
        }
    }

    @Test
    void removedRoleAndLostFormationCannotRetainOriginalAuthorization() {
        try (var fixture = new Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            var originalRole = fixture.role(machine);
            machine.components().remove(fixture.dataRole);
            assertFalse(capture.current());
            assertNull(fixture.selector.capture("team", fixture.machineId(machine)));
            machine.components().put(fixture.dataRole, originalRole);
            Objects.requireNonNull(machine.lifecycle()).invalidateStructure();
            assertFalse(capture.current());
            fixture.refresh();
            assertNull(fixture.selector.capture("team", fixture.machineId(machine)));
            fixture.form(machine);
            fixture.refresh();
            assertTrue(Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine))).current());
            assertFalse(capture.current());
        }
    }

    @Test
    void refreshedOwnerTeamAndSelectedWinnerInvalidateTheOldCapture() {
        try (var fixture = new Fixture()) {
            var pair = orderedDataCenters(fixture);
            var first = pair.getFirst();
            var second = pair.getLast();
            fixture.refresh();
            var original = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(first)));
            fixture.storage(second).downloadResearch(FIRST, 1, fixture.cloud());
            fixture.refresh();
            assertFalse(original.current());
            assertNull(fixture.selector.capture("team", fixture.machineId(first)));
            var selected = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(second)));
            fixture.teamId = "new";
            fixture.refresh();
            assertFalse(selected.current());
            assertNull(fixture.selector.capture("team", fixture.machineId(second)));
            assertTrue(Objects.requireNonNull(fixture.selector.capture("new", fixture.machineId(second))).current());
        }
    }

    @Test
    void copiedIdentityConflictAndRestoredGenerationRequireFreshCapture() {
        try (var fixture = new Fixture()) {
            var original = fixture.dataCenter();
            fixture.refresh();
            var machineId = fixture.machineId(original);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", machineId));
            var saved = fixture.dataAssembly.encode(original);
            var restored = fixture.attach(fixture.dataAssembly.restore(fixture.host(fixture.dataType.id()), saved, new MachineLifecycleRegistry()));
            assertEquals(machineId, fixture.machineId(restored));
            assertFalse(capture.current());
            fixture.refresh();
            assertNull(fixture.selector.capture("team", machineId));
            fixture.unload(original);
            fixture.refresh();
            var fresh = Objects.requireNonNull(fixture.selector.capture("team", machineId));
            assertSame(fixture.storage(restored), fresh.storage());
            assertTrue(fresh.current());
            assertFalse(capture.current());
        }
    }

    @Test
    void originalPhysicalBindingSurvivesItsWriteAndRecoversAfterSelectionAndTeamChanges() {
        try (var fixture = new Fixture()) {
            var pair = orderedDataCenters(fixture);
            var first = pair.getFirst();
            var second = pair.getLast();
            fixture.refresh();
            var original = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(first)));
            var target = original.target();
            original.storage().downloadResearch(FIRST, 1, fixture.cloud(), original::current);
            assertFalse(original.current());
            assertTrue(original.physicalCurrent());
            fixture.storage(second).downloadResearch(FIRST, 1, fixture.cloud());
            fixture.storage(second).downloadResearch(SECOND, 1, fixture.cloud());
            fixture.refresh();
            fixture.teamId = "new";
            fixture.refresh();
            assertNull(fixture.selector.capture("team", fixture.machineId(first)));
            var recovery = Objects.requireNonNull(fixture.selector.captureOriginal(target));
            assertSame(original.storage(), recovery.storage());
            assertTrue(recovery.current());
            first.components().remove(fixture.dataRole);
            assertNull(fixture.selector.capture("new", fixture.machineId(first)));
            assertTrue(Objects.requireNonNull(fixture.selector.captureOriginal(target)).current());
            fixture.unload(first);
            assertFalse(original.physicalCurrent());
            assertFalse(recovery.current());
            assertNull(fixture.selector.captureOriginal(target));
        }
    }

    @Test
    void duplicateIdentityAndMovedCopiesCannotResumeTheOriginalPhysicalTarget() {
        try (var fixture = new Fixture()) {
            var original = fixture.dataCenter();
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(original)));
            var target = capture.target();
            var saved = fixture.dataAssembly.encode(original);
            fixture.attach(fixture.dataAssembly.restore(fixture.host(fixture.dataType.id()), saved, new MachineLifecycleRegistry()));
            assertFalse(capture.physicalCurrent());
            assertNull(fixture.selector.captureOriginal(target));
            fixture.unload(original);
            assertNull(fixture.selector.captureOriginal(target));
        }
    }

    @Test
    void advertisedContentsMustBelongToTheActualInterfaceBackend() {
        try (var fixture = new Fixture()) {
            var machine = fixture.dataCenter();
            fixture.advertiseDifferentContents = true;
            fixture.refresh();
            assertFalse(Objects.requireNonNull(Objects.requireNonNull(fixture.network.plan()).teams().get("team").dataCenterResearch())
                               .storedResearch().isEmpty());
            assertTrue(fixture.storage(machine).snapshot().storedResearch().isEmpty());
            assertNull(fixture.selector.capture("team", fixture.machineId(machine)));
        }
    }

    @Test
    void captureAndProofRequireTheServerOwnerThread() {
        try (var fixture = new Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            var async = CompletableFuture.runAsync(capture::current);
            assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, async::join).getCause());
        }
    }

    /**
     * Real assembly/identity/publication fixture shared with the database operation integration tests.
     */
    static final class Fixture implements AutoCloseable {
        final Thread owner = Thread.currentThread();
        final Object worldToken = new Object();
        final ObjectArrayList<MachineInstance> machines = new ObjectArrayList<>();
        final ResearchRegistry research = new ResearchRegistry();
        final PlayerResearchState player = new PlayerResearchState(OWNER);
        final MachineIdentityRegistry identities = new MachineIdentityRegistry(this::requireOwner);
        final NetworkRuntime network;
        final ResearchDataCenters selector = new ResearchDataCenters(identities, this::requireOwner);
        final ComponentType<MachineInstance, MachineOwnership> ownership = new ComponentType<>(id("ownership"),
            machine -> new MachineOwnership(machine, _ -> identities), List.of());
        final ComponentType<MachineInstance, DataCenterStorage> storageType = new ComponentType<>(id("external_storage"),
            machine -> new ExternalStorage(this, machine), List.of(), Set.of(ownership));
        final ComponentType<MachineInstance, MachineNetworkDevice> dataRole;
        final MachineType dataType;
        final MachineAssembly dataAssembly;
        int positions;
        int dirtyWrites;
        String teamId = "team";
        boolean advertiseDifferentContents;
        @Nullable RuntimeException dirtyFailure;

        Fixture() {
            for (var definition : ObjectList.of(definition(FIRST, 3), definition(SECOND, 1)))
                research.register(definition);
            research.freeze();
            player.completeAtLeast(FIRST, 1);
            player.completeAtLeast(SECOND, 1);
            network = new NetworkRuntime(identities, this::membership, this::requireOwner);
            dataRole = MachineNetworkDevice.capturedComponent(id("data_role"), ownership, (machine, identity) -> {
                var storage = storage(machine);
                var captured = storage.capture();
                var contents = captured.snapshot();
                if (advertiseDifferentContents)
                    contents = new DataCenterResearchSnapshot(contents.dataCenterId(), contents.level(),
                        contents.capacity(), contents.revision(), Object2IntMaps.singleton(FIRST, 1));
                var snapshot = new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.DATA_CENTER, 1,
                    Objects.requireNonNull(machine.lifecycle()).formed(), identity.loadOrder(), Reference2DoubleMaps.emptyMap(),
                    NetworkResourceModifier.EMPTY, contents.capacity(), contents.storedResearch().size(), contents);
                return new NetworkRuntime.Capture(snapshot,
                    () -> machine.components().get(storageType) == storage && captured.current().getAsBoolean());
            }, _ -> network);
            dataType = MachineType.builder(id("data_machine"), GuiText.ResourceStorage, id("structure"))
                                  .component(ownership).component(storageType).component(dataRole).build();
            dataAssembly = MachineAssembly.builder(dataType, "data-v1").component(ownership, MachineOwnership.CODEC)
                                          .recreated(storageType).recreated(dataRole).build();
            var supply = new Reference2DoubleOpenHashMap<NetworkResourceType>();
            supply.put(ComputingResourceFixtures.COMPUTE, 100);
            var centerRole = MachineNetworkDevice.component(id("center_role"), ownership, (machine, identity) ->
                new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.NETWORK_CENTER, 1,
                    Objects.requireNonNull(machine.lifecycle()).formed(), identity.loadOrder(), 2, supply, NetworkResourceModifier.EMPTY, 0, 0), _ -> network);
            var centerType = MachineType.builder(id("center_machine"), GuiText.ResourceStorage, id("structure"))
                                        .component(ownership).component(centerRole).build();
            var assembly = MachineAssembly.builder(centerType, "center-v1").component(ownership, MachineOwnership.CODEC).recreated(centerRole).build();
            network.beginTick(0);
            attach(assembly.create(host(centerType.id()), new MachineLifecycleRegistry()));
        }

        MachineInstance dataCenter() {
            return attach(dataAssembly.create(host(dataType.id()), new MachineLifecycleRegistry()));
        }

        MachineInstance attach(MachineInstance machine) {
            form(machine);
            machines.add(machine);
            return machine;
        }

        void form(MachineInstance machine) {
            Objects.requireNonNull(machine.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
        }

        Host host(ResourceLocation type) {
            return new Host(worldToken, type, new BlockPos(positions++, 0, 0), OWNER);
        }

        UUID machineId(MachineInstance machine) {
            return Objects.requireNonNull(machine.components().get(ownership)).identity().machineId();
        }

        DataCenterStorage storage(MachineInstance machine) {
            return Objects.requireNonNull(machine.components().get(storageType));
        }

        MachineNetworkDevice role(MachineInstance machine) {
            return Objects.requireNonNull(machine.components().get(dataRole));
        }

        ExternalStorage copyStorage(MachineInstance machine, DataCenterResearchSnapshot saved) {
            var replacement = new ExternalStorage(this, machine);
            replacement.state = ResearchDataCenter.restore(saved, research, this::requireOwner, this::dirty);
            return replacement;
        }

        TeamResearchView cloud() {
            return new ResearchEligibility(research).calculateStructural(teamId, Set.of(OWNER), Map.of(OWNER, player), Object2IntMaps.emptyMap(), Set.of());
        }

        OwnerTeamResolution membership(UUID actor) {
            return OwnerTeamResolution.resolved(actor, teamId, OwnerTeamType.SERVER, ObjectSets.singleton(actor), null);
        }

        ResearchPermissionContext permission() {
            return new ResearchPermissionContext(OWNER, teamId, null, teamId, true, false, false);
        }

        ResearchWorld researchWorld(ResearchPermissionPolicy policy, Consumer<ResearchWorldSnapshot> writer) {
            var result = new ResearchWorld(research, policy, writer);
            result.restore(new ResearchWorldSnapshot(0, ObjectList.of(player.snapshot()), ObjectList.of()));
            return result;
        }

        void refresh() {
            assertTrue(network.refresh());
        }

        void unload(MachineInstance machine) {
            Objects.requireNonNull(machine.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }

        void requireOwner() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Not the test server owner");
        }

        void dirty() {
            dirtyWrites++;
            if (dirtyFailure != null) throw dirtyFailure;
        }

        @Override
        public void close() {
            machines.forEach(this::unload);
            network.close();
            identities.close();
        }
    }

    /**
     * A downstream interface implementation with the real backend's content and mutation semantics.
     */
    private static final class ExternalStorage implements DataCenterStorage, MachineLifecycleListener {
        private final Fixture fixture;
        private final MachineInstance machine;
        @Nullable
        private ResearchDataCenter state;

        private ExternalStorage(Fixture fixture, MachineInstance machine) {
            this.fixture = fixture;
            this.machine = machine;
        }

        @Override
        public void onLoaded(MachineLifecycleEvent.Loaded event) {
            assertSame(machine, event.context().machine());
            state = new ResearchDataCenter(fixture.machineId(machine), 1, 4, fixture.research, fixture::requireOwner, fixture::dirty);
        }

        @Override
        public DataCenterResearchSnapshot snapshot() {
            return Objects.requireNonNull(state).snapshot();
        }

        @Override
        public boolean current(DataCenterResearchSnapshot snapshot) {
            return Objects.requireNonNull(state).current(snapshot);
        }

        @Override
        public Result downloadResearch(ResourceLocation id, int level, TeamResearchView cloud, BooleanSupplier current) {
            return Objects.requireNonNull(state).downloadResearch(id, level, cloud, current);
        }

        @Override
        public Result storeFromDisk(Object2IntMap<ResourceLocation> entries, TeamResearchView cloud, BooleanSupplier current) {
            return Objects.requireNonNull(state).storeFromDisk(entries, cloud, current);
        }

        @Override
        public Result synchronizeLoopingResearch(TeamResearchView cloud, BooleanSupplier current) {
            return Objects.requireNonNull(state).synchronizeLoopingResearch(cloud, current);
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
            throw new AssertionError("Database tests do not load a game world");
        }

        @Override
        public void requireServerThread() {
        }
    }
}
