package com.circulation.prototype_eng.api.research.station;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.ResearchStationController;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
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
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.circulation.prototype_eng.api.research.station.StationFixtures.Entry;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.OWNER;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.TYPE;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchStationPersistenceTest {
    private static final TestResources.Key RAW = new TestResources.Key(TYPE, "saved_raw");
    private static final TestResources.Key PRODUCT = new TestResources.Key(TYPE, "saved_product");
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt(location -> location.equals(TYPE.getId()) ? TYPE : null);

    private static ResearchDefinition definition(Function<RecipeStage, RequirementTransaction> action) {
        return definition(action, _ -> {
        });
    }

    private static ResearchDefinition definition(Function<RecipeStage, RequirementTransaction> action, Consumer<RecipeProcess> cancellation) {
        var observer = new RecipeRequirementType<>(id("saved_observer_type"), new RecipeRequirementSystem<Function<RecipeStage, RequirementTransaction>>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Function<RecipeStage, RequirementTransaction>> requirement) {
                return requirement.payload().apply(RecipeStage.START);
            }

            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Function<RecipeStage, RequirementTransaction>> requirement) {
                return requirement.payload().apply(RecipeStage.TICK);
            }

            @Override
            public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Function<RecipeStage, RequirementTransaction>> requirement) {
                return requirement.payload().apply(RecipeStage.END);
            }

            @Override
            public void cancel(RecipeProcess process, RecipeRequirement<Function<RecipeStage, RequirementTransaction>> requirement) {
                cancellation.accept(process);
            }
        }).requirement(id("saved_observer"), action);
        var stage = ResearchLevelStage.builder(1, 1).researchTier(2)
                                      .resourceFormula(ResearchResourceFormula.builder().constant(ComputingResourceFixtures.COMPUTE, 3).build())
                                      .materialPersistence("saved-station-v1", codec -> codec.resources(KEYS))
                                      .materialRequirement(RecipeRequirements.RESOURCES.requirement(id("saved_io"), new ResourceRequirement(
                                          List.of(new GenericStack(RAW, 2)), List.of(new GenericStack(RAW, 1)), List.of(), List.of(new GenericStack(PRODUCT, 2)), false)))
                                      .materialRequirement(observer).build();
        return ResearchDefinition.builder(id("research")).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage).build();
    }

    private static ResearchStations.Site site(StationFixtures fixture, Entry station) {
        var identity = fixture.identity(station);
        return new ResearchStations.Site(identity.identity(), identity.location());
    }

    private static Entry reload(StationFixtures fixture, Entry station, ResearchStations.Site site, NBTTagCompound saved) {
        return reload(fixture, station, site, saved, fixture.world);
    }

    private static Entry reload(StationFixtures fixture, Entry station, ResearchStations.Site site, NBTTagCompound saved, Object worldIdentity) {
        var host = new MachineLifecycleHost() {
            @Override
            public Object worldIdentity() {
                return worldIdentity;
            }

            @Override
            public MachineIdentity identity() {
                return site.location();
            }

            @Override
            public UUID placementPlayer() {
                return site.identity().ownerId();
            }

            @Override
            public World world() {
                throw new AssertionError("No game world in station persistence tests");
            }

            @Override
            public void requireServerThread() {
            }
        };
        var machine = station.assembly().restore(host, saved, new MachineLifecycleRegistry());
        return fixture.attach(machine, station.assembly(), station.role());
    }

    private static void put(Entry station, TestResources.Key key, long amount, boolean output) {
        assertEquals(amount, Objects.requireNonNull(output ? station.warehouse().output() : station.warehouse().input()).insert(key, amount, Actionable.MODULATE));
    }

    private static long amount(Entry station, TestResources.Key key, boolean output) {
        return Objects.requireNonNull(output ? station.warehouse().output() : station.warehouse().input()).amount(key);
    }

    private static ResearchStationWork.Context context(long time) {
        return new ResearchStationWork.Context(time, Set.of(OWNER), () -> true);
    }

    private static PlannedDeadline deadline() {
        return PlannedDeadline.after(Duration.ofSeconds(5));
    }

    @Test
    void cleanupRestorationCancelsTheSavedRealOwnerAfterATeamMoveWithoutAnyWorkLeaseOrMaterialGrant() {
        var phases = new ObjectArrayList<RecipeStage>();
        var cancelled = new AtomicReference<RecipeProcess>();
        int[] cancellationCalls = {0};
        try (var moving = new MovingTeamFixture(definition(phase -> {
            phases.add(phase);
            return RequirementTransaction.SUCCESS;
        }, process -> {
            cancellationCalls[0]++;
            cancelled.set(process);
        })); var lanes = new Lanes()) {
            var fixture = moving.fixture;
            var station = moving.station;
            put(station, RAW, 10, false);
            put(station, PRODUCT, 100, true);
            var original = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(ResearchStationWork.Status.WAITING, original.work.advance(capture, context(1), lanes.tasks, deadline()).status());
            var saved = original.work.snapshot();
            assertTrue(original.work.suspend());
            var restored = new State(fixture, original.queue);
            restored.work.restore(saved);
            moving.move();
            var newPermit = Objects.requireNonNull(fixture.roleOf(station.machine()).workPermit());
            assertEquals("new_team", newPermit.teamId());
            assertFalse(capture.current());
            assertNull(fixture.selector.restore("team", 2, restored.work.sites()));
            var cleanup = Objects.requireNonNull(fixture.selector.restoreForCleanup(restored.work.sites()));
            assertSame(station.machine(), cleanup.primary());
            assertTrue(restored.work.cancelRestored(cleanup));
            assertEquals(1, cancellationCalls[0]);
            assertSame(station.machine(), Objects.requireNonNull(cancelled.get()).owner());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK), phases);
            assertEquals(7, amount(station, RAW, false));
            assertEquals(100, amount(station, PRODUCT, true));
            assertEquals(100, newPermit.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, newPermit.requestLease(), "Cleanup did not take a station work lease");
            assertEquals(ResearchStationWork.Status.CANCELLED, restored.work.result().status());
            assertNull(restored.work.snapshot().segment());
            var view = new TeamResearchView(fixture.research, "team", Set.of(), Object2IntMaps.emptyMap(), Object2IntMaps.emptyMap(), Set.of(id("research")));
            var prune = restored.queue.preparePrune(view);
            prune.commit();
            prune.complete();
            assertEquals(0, restored.queue.size());
            assertEquals(0, restored.player.completedLevel(id("research")));
        }
    }

    @Test
    void cleanupRequiresEveryExactPhysicalSiteButCanCancelOnAReloadedUnformedOwnerWithoutNetworkPublication() {
        int[] cancelled = {0};
        try (var fixture = new StationFixtures(10, 100, 2, definition(_ -> RequirementTransaction.SUCCESS, _ -> cancelled[0]++)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, RAW, 10, false);
            put(station, PRODUCT, 100, true);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            state.work.advance(capture, context(1), lanes.tasks, deadline());
            var saved = state.work.snapshot();
            var stationTag = station.assembly().encode(station.machine());
            assertTrue(state.work.suspend());
            var cleanup = Objects.requireNonNull(fixture.selector.restoreForCleanup(state.work.sites()));
            Objects.requireNonNull(station.machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            assertFalse(cleanup.current());
            assertFalse(state.work.cancelRestored(cleanup));
            fixture.station(2, 0);
            assertNull(fixture.selector.restoreForCleanup(state.work.sites()), "Another station cannot replace the missing saved identity");
            assertEquals(0, cancelled[0]);
            assertEquals(saved.segment(), state.work.snapshot().segment());
            var replacement = reload(fixture, station, capture.sites().getFirst(), stationTag);
            Objects.requireNonNull(replacement.machine().lifecycle()).invalidateStructure();
            assertNull(fixture.roleOf(replacement.machine()).workPermit());
            var restoredCleanup = Objects.requireNonNull(fixture.selector.restoreForCleanup(state.work.sites()));
            assertSame(replacement.machine(), restoredCleanup.primary());
            assertTrue(restoredCleanup.hatches().resources().isEmpty(), "Cleanup uses the owner's actual unformed hatch bindings");
            assertTrue(state.work.cancelRestored(restoredCleanup));
            assertEquals(1, cancelled[0]);
            assertNull(state.work.key());
        }
    }

    @Test
    void throwingRestoredCancellationRetainsTheSameFaultedProcessAndNeverDecodesOrCancelsItAgain() {
        var failure = new IllegalStateException("restored cancellation failed");
        int[] calls = {0};
        var cancelled = new AtomicReference<RecipeProcess>();
        try (var fixture = new StationFixtures(10, 100, 2, definition(_ -> RequirementTransaction.SUCCESS, process -> {
            calls[0]++;
            cancelled.set(process);
            throw failure;
        })); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, RAW, 10, false);
            put(station, PRODUCT, 100, true);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            state.work.advance(capture, context(1), lanes.tasks, deadline());
            var saved = state.work.snapshot();
            assertTrue(state.work.suspend());
            var sources = Objects.requireNonNull(fixture.selector.restoreForCleanup(state.work.sites()));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> state.work.cancelRestored(sources)));
            var owned = Objects.requireNonNull(cancelled.get());
            assertSame(station.machine(), owned.owner());
            assertSame(owned.state(), state.work.materialState());
            assertEquals(RecipeProcess.Lifecycle.CANCELLED, owned.state().lifecycle());
            assertEquals(Objects.requireNonNull(saved.segment()).key(), state.work.key());
            assertThrows(IllegalStateException.class, state.work::snapshot);
            assertFalse(state.work.suspend());
            assertThrows(IllegalStateException.class, () -> state.work.restore(saved));
            assertFalse(state.work.resume(capture));
            assertEquals(ResearchStationWork.Status.FAILED, state.work.advance(capture, context(2), lanes.tasks, deadline()).status());
            assertFalse(state.work.cancelRestored(Objects.requireNonNull(fixture.selector.restoreForCleanup(state.work.sites()))));
            assertFalse(state.work.cancel(deadline()));
            assertEquals(1, calls[0]);
            assertSame(owned.state(), state.work.materialState());
            assertEquals(7, amount(station, RAW, false));
            assertEquals(100, amount(station, PRODUCT, true));
        }
    }

    @Test
    void restoredSitesRequireTheOriginalOwnersLocationsAndOneCurrentWorldSession() {
        try (var fixture = new StationFixtures(10, 2)) {
            var first = fixture.station(2, 0);
            var second = fixture.station(2, 0);
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            var sites = capture.sites();
            assertEquals(2, capture.machines().size());
            assertSame(capture.primary(), capture.machines().getFirst());
            var firstSite = sites.getFirst();
            var otherOwner = new ResearchStations.Site(new PersistentMachineIdentity(firstSite.identity().machineId(), new UUID(0, 999)), firstSite.location());
            assertNull(fixture.selector.restore("team", 2, List.of(otherOwner, sites.get(1))));
            var relocated = new ResearchStations.Site(firstSite.identity(), new MachineIdentity(firstSite.location().machineType(),
                firstSite.location().dimension(), firstSite.location().controllerPos().add(30, 0, 0)));
            assertNull(fixture.selector.restore("team", 2, List.of(relocated, sites.get(1))));
            assertThrows(IllegalArgumentException.class, () -> fixture.selector.restore("team", 2, List.of(firstSite, firstSite)));
            var crossDimension = new ResearchStations.Site(sites.get(1).identity(), new MachineIdentity(sites.get(1).location().machineType(),
                2, new BlockPos(40, 0, 0)));
            assertThrows(IllegalArgumentException.class, () -> fixture.selector.restore("team", 2, List.of(firstSite, crossDimension)));
            var secondSite = site(fixture, second);
            var saved = second.assembly().encode(second.machine());
            Objects.requireNonNull(second.machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            reload(fixture, second, secondSite, saved, new Object());
            fixture.refresh();
            assertNull(fixture.selector.restore("team", 2, sites));
            assertTrue(fixture.identity(first).unique());
        }
    }

    @Test
    void chunkReloadRestoresEveryOriginalStationAndItsPrimaryWithoutAddingOtherCandidates() {
        try (var fixture = new StationFixtures(10, 2)) {
            var first = fixture.station(2, 0);
            var second = fixture.station(2, 0);
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            var firstSite = site(fixture, first);
            var secondSite = site(fixture, second);
            var firstTag = first.assembly().encode(first.machine());
            var secondTag = second.assembly().encode(second.machine());
            Objects.requireNonNull(first.machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            Objects.requireNonNull(second.machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            var extra = fixture.station(2, 0);
            var newFirst = reload(fixture, first, firstSite, firstTag);
            fixture.nextTick();
            assertNull(fixture.selector.restore("team", 2, capture.sites()), "A partial reload cannot replace the missing station with another candidate");
            var newSecond = reload(fixture, second, secondSite, secondTag);
            fixture.refresh();
            var restored = Objects.requireNonNull(fixture.selector.restore("team", 2, capture.sites()));
            assertEquals(capture.sites(), restored.sites());
            assertEquals(2, restored.machines().size());
            assertFalse(restored.machines().contains(extra.machine()));
            assertSame(capture.primary() == first.machine() ? newFirst.machine() : newSecond.machine(), restored.primary());
            assertNotSame(capture.hatches(), restored.hatches());
            assertTrue(restored.current());
        }
    }

    @Test
    void blockedEndResumesOnTheReloadedOriginalStationWithoutRepayingZeroSpeedStartOrTick() {
        var phases = new ObjectArrayList<RecipeStage>();
        try (var fixture = new StationFixtures(10, 100, 2, definition(phase -> {
            phases.add(phase);
            return RequirementTransaction.SUCCESS;
        })); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, RAW, 10, false);
            put(station, PRODUCT, 100, true);
            station.machine().attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 0);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(ResearchStationWork.Status.WAITING, state.work.advance(capture, context(1), lanes.tasks, deadline()).status());
            assertEquals(7, amount(station, RAW, false));
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK), phases, "Full output rejects IO before the later END observer is acquired");
            var saved = state.work.snapshot();
            assertEquals(capture.sites(), Objects.requireNonNull(saved.segment()).sites());
            var stationTag = station.assembly().encode(station.machine());
            Objects.requireNonNull(station.machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            assertTrue(state.work.suspend(), "Healthy unattached material work can save after its controller unloads");
            assertNull(state.work.sources());
            assertNull(state.work.materialState());
            assertEquals(capture.sites(), state.work.sites());
            assertFalse(state.work.cancel(deadline()), "A suspended session still owns its real cancellation callbacks");
            assertNull(fixture.selector.restore("team", 2, capture.sites()));

            var replacement = reload(fixture, station, capture.sites().getFirst(), stationTag);
            put(replacement, RAW, 7, false);
            put(replacement, PRODUCT, 98, true);
            replacement.machine().attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 9);
            fixture.nextTick();
            var restoredSources = Objects.requireNonNull(fixture.selector.restore("team", 2, state.work.sites()));
            assertNotSame(capture.primary(), restoredSources.primary());
            assertEquals(capture.sites(), restoredSources.sites());
            assertTrue(state.work.resume(restoredSources));
            assertEquals(1, Objects.requireNonNull(state.work.materialState()).successfulTicks());
            assertEquals(0, Objects.requireNonNull(state.work.materialState()).progress());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK), phases, "Resume decodes the paid process without invoking any material phase");
            assertEquals(ResearchStationWork.Status.COMPLETED, state.work.advance(restoredSources, context(2), lanes.tasks, deadline()).status());
            assertEquals(7, amount(replacement, RAW, false));
            assertEquals(100, amount(replacement, PRODUCT, true));
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), phases);
            assertEquals(2, state.work.snapshot().lastGrantedTime());
            assertNull(state.work.snapshot().segment());
        }
    }

    @Test
    void restoringStoresTheTagDefensivelyAndOnlyDecodesAgainstTheExactRealCapture() {
        try (var fixture = new StationFixtures(10, 100, 2, definition(_ -> RequirementTransaction.SUCCESS)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            var other = fixture.station(2, 2);
            put(station, RAW, 10, false);
            put(station, PRODUCT, 100, true);
            fixture.refresh();
            var original = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            original.work.advance(capture, context(1), lanes.tasks, deadline());
            var snapshot = original.work.snapshot();
            var saved = Objects.requireNonNull(snapshot.segment());
            var borrowedTag = saved.processTag();
            var copied = new ResearchStationWork.SavedSegment(saved.key(), saved.sites(), borrowedTag);
            borrowedTag.setString("Revision", "corrupt_after_construction");
            copied.processTag().setString("Revision", "corrupt_after_access");
            assertEquals(saved.processTag(), copied.processTag());
            var resumed = new State(fixture, original.queue);
            resumed.work.restore(new ResearchStationWork.Saved(snapshot.lastGrantedTime(), copied));
            assertNull(resumed.work.sources());
            assertEquals(saved.key(), resumed.work.key());
            assertFalse(resumed.work.resume(Objects.requireNonNull(fixture.selector.capture("team", 2))));
            assertSame(other.machine(), Objects.requireNonNull(fixture.selector.restore("team", 2,
                List.of(site(fixture, other)))).primary());
            assertEquals(saved.processTag(), Objects.requireNonNull(resumed.work.snapshot().segment()).processTag());
            assertTrue(resumed.work.resume(Objects.requireNonNull(fixture.selector.restore("team", 2, saved.sites()))));
            assertEquals(1, Objects.requireNonNull(resumed.work.materialState()).successfulTicks());
            assertThrows(IllegalStateException.class, () -> resumed.work.restore(snapshot));
        }
    }

    @Test
    void savedProcessCanResumeAndCancelWithoutAnyCurrentNumericGrant() {
        var phases = new ObjectArrayList<RecipeStage>();
        try (var fixture = new StationFixtures(10, 0, 2, definition(phase -> {
            phases.add(phase);
            return RequirementTransaction.SUCCESS;
        }))) {
            var station = fixture.station(2, 0);
            put(station, RAW, 10, false);
            put(station, PRODUCT, 100, true);
            fixture.refresh();
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            var state = new State(fixture);
            var materials = fixture.research.materials(id("research"), 1);
            var process = RecipeProcess.materialCycle(station.machine(), materials.recipe(), capture.hatches(), 9);
            assertInstanceOf(ProcessResult.Blocked.class, process.tick());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK), phases);
            state.work.restore(new ResearchStationWork.Saved(-1,
                new ResearchStationWork.SavedSegment(state.queue.activeHead().orElseThrow().key(), capture.sites(), materials.encode(process))));
            assertEquals(0, capture.numericResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertFalse(state.work.cancel(deadline()));
            assertTrue(state.work.resume(capture));
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK), phases, "Resume needs no numeric grant and executes no END");
            assertTrue(state.work.cancel(deadline()));
            assertEquals(ResearchStationWork.Status.CANCELLED, state.work.result().status());
            assertEquals(7, amount(station, RAW, false));
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK), phases, "Cancellation does not acquire the unpaid END");
            assertNull(state.work.snapshot().segment());
            assertEquals(1, state.queue.size());
        }
    }

    @Test
    void savedQueueKeyAndDeferredCodecValidationNeverReplaceTheStoredSessionOnFailure() {
        try (var fixture = new StationFixtures(10, 100, 2, definition(_ -> RequirementTransaction.SUCCESS)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, RAW, 10, false);
            put(station, PRODUCT, 100, true);
            fixture.refresh();
            var original = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            original.work.advance(capture, context(1), lanes.tasks, deadline());
            var snapshot = original.work.snapshot();
            var saved = Objects.requireNonNull(snapshot.segment());
            var state = new State(fixture, original.queue);
            var incorrect = new TeamResearchQueue.Key(saved.key().researchId(), saved.key().targetLevel(), saved.key().queueEntryId() + 1);
            assertThrows(IllegalStateException.class, () -> state.work.restore(new ResearchStationWork.Saved(-1,
                new ResearchStationWork.SavedSegment(incorrect, saved.sites(), saved.processTag()))));
            assertNull(state.work.key());
            var corrupt = saved.processTag();
            corrupt.setString("Revision", "different");
            state.work.restore(new ResearchStationWork.Saved(-1, new ResearchStationWork.SavedSegment(saved.key(), saved.sites(), corrupt)));
            assertThrows(IllegalArgumentException.class, () -> state.work.resume(capture));
            assertNull(state.work.sources());
            assertEquals(corrupt, Objects.requireNonNull(state.work.snapshot().segment()).processTag());
            assertThrows(IllegalArgumentException.class, () -> new ResearchStationWork.Saved(-2, null));
        }
    }

    @Test
    void schedulerStopPreservesThePaidMaterialSessionAndItsExactBusyEndParticipants() {
        var fence = new AtomicReference<StorageFence>();
        var stationRef = new AtomicReference<Entry>();
        boolean[] block = {true};
        try (var fixture = new StationFixtures(10, 100, 2, definition(phase -> {
            if (phase == RecipeStage.END && block[0]) {
                fence.set(Objects.requireNonNull(StorageFence.tryAcquire(List.of(Objects.requireNonNull(stationRef.get().warehouse().output()).context()))));
            }
            return RequirementTransaction.SUCCESS;
        })); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            stationRef.set(station);
            put(station, RAW, 10, false);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            try {
                assertEquals(ResearchStationWork.Status.PENDING, state.work.advance(capture, context(1), lanes.tasks, deadline()).status());
                assertTrue(state.queue.busy());
                assertThrows(IllegalStateException.class, state.work::snapshot);
                assertFalse(state.work.suspend());
                state.work.requestStop(PlannedCancellationToken.Reason.EPOCH_CHANGED);
                block[0] = false;
                Objects.requireNonNull(fence.getAndSet(null)).release();
                assertEquals(ResearchStationWork.Status.WAITING, state.work.resolve(deadline()).status());
                assertEquals(7, amount(station, RAW, false));
                assertEquals(0, amount(station, PRODUCT, true));
                assertFalse(state.queue.busy());
                assertTrue(state.work.suspend());
                fixture.nextTick();
                var restored = Objects.requireNonNull(fixture.selector.restore("team", 2, state.work.sites()));
                assertEquals(ResearchStationWork.Status.COMPLETED, state.work.advance(restored, context(2), lanes.tasks, deadline()).status());
                assertEquals(7, amount(station, RAW, false));
            } finally {
                var remaining = fence.getAndSet(null);
                if (remaining != null) {
                    remaining.release();
                }
            }
        }
    }

    @Test
    void unknownAndFaultedMaterialSessionsCannotProduceAFalseSaveBoundary() {
        var failure = new IllegalStateException("unknown station END");
        boolean[] confirmed = {false};
        int[] completions = {0};
        try (var fixture = new StationFixtures(10, 100, 2, definition(phase -> phase == RecipeStage.END ? new RequirementTransaction() {
            @Override
            public ProcessResult result() {
                return ProcessResult.SUCCESS;
            }

            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
            }

            @Override
            public void complete() {
                completions[0]++;
                throw failure;
            }

            @Override
            public Resolution resolution() {
                return confirmed[0] ? Resolution.COMPLETED : Resolution.UNKNOWN;
            }
        } : RequirementTransaction.SUCCESS)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, RAW, 10, false);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> state.work.advance(capture, context(1), lanes.tasks, deadline())));
            assertThrows(IllegalStateException.class, state.work::snapshot);
            assertFalse(state.work.suspend());
            assertFalse(state.work.destroy(capture.sites().getFirst(), deadline()));
            confirmed[0] = true;
            assertTrue(state.work.destroy(capture.sites().getFirst(), deadline()));
            assertEquals(1, completions[0]);
            assertEquals(1, state.player.completedLevel(id("research")));
            assertNull(state.work.snapshot().segment());
        }
        try (var fixture = new StationFixtures(10, 100, 2, definition(_ -> {
            throw failure;
        })); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, RAW, 10, false);
            fixture.refresh();
            var state = new State(fixture);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> state.work.advance(Objects.requireNonNull(fixture.selector.capture("team", 2)), context(1), lanes.tasks, deadline())));
            assertFalse(state.work.pending());
            assertEquals(ResearchStationWork.Status.FAILED, state.work.result().status());
            assertThrows(IllegalStateException.class, state.work::snapshot);
            assertFalse(state.work.suspend());
            assertFalse(state.work.cancel(deadline()));
        }
    }

    private static final class State {
        private final TeamResearchQueue queue;
        private final PlayerResearchState player = new PlayerResearchState(OWNER);
        private final ResearchStationWork work;

        private State(StationFixtures fixture) {
            this(fixture, new TeamResearchQueue(fixture.research, "team"));
            queue.enqueue(id("research"), 1, OWNER, 0);
        }

        private State(StationFixtures fixture, TeamResearchQueue queue) {
            this.queue = queue;
            work = new ResearchStationWork(queue, new TeamOwnedResearch("team", fixture.research), Map.of(OWNER, player));
        }
    }

    private static final class Lanes implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private final ExecutorService first = Executors.newSingleThreadExecutor();
        private final ExecutorService second = Executors.newSingleThreadExecutor();
        private final PlannedTaskExecutor tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{first, second});

        @Override
        public void close() {
            first.close();
            second.close();
            workers.close();
        }
    }

    private static final class MovingTeamFixture implements AutoCloseable {
        private final StationFixtures fixture;
        private final NetworkRuntime network;
        private final Entry station;
        private String teamId = "team";

        private MovingTeamFixture(ResearchDefinition definition) {
            fixture = new StationFixtures(10, 100, 2, definition);
            network = new NetworkRuntime(fixture.identities, player -> OwnerTeamResolution.resolved(player, teamId,
                OwnerTeamType.SERVER, ObjectSets.singleton(player), Optional.empty()), fixture::requireOwner);
            network.beginTick(0);
            role(NetworkDeviceRole.NETWORK_CENTER);
            dataCenter();
            station = role(NetworkDeviceRole.RESEARCH_STATION);
            assertTrue(network.refresh());
        }

        private Entry role(NetworkDeviceRole role) {
            var supply = new Reference2DoubleOpenHashMap<NetworkResourceType>();
            if (role == NetworkDeviceRole.NETWORK_CENTER) {
                supply.put(ComputingResourceFixtures.COMPUTE, 100);
            }
            var component = MachineNetworkDevice.component(id("moving_role_" + fixture.positions), fixture.ownership,
                (machine, identity) -> new NetworkDeviceSnapshot(identity.identity(), role, 2, Objects.requireNonNull(machine.lifecycle()).formed(),
                    identity.loadOrder(), role == NetworkDeviceRole.NETWORK_CENTER ? 10 : 0, supply, NetworkResourceModifier.EMPTY, 0, 0), _ -> network);
            return attach(component, null, role == NetworkDeviceRole.RESEARCH_STATION);
        }

        private void dataCenter() {
            var storage = MachineDataCenterStorage.component(id("moving_storage"), fixture.ownership, 2, 10, fixture.research, _ -> {
            });
            var component = MachineNetworkDevice.capturedComponent(id("moving_data_role"), fixture.ownership,
                (machine, identity) -> Objects.requireNonNull(machine.components().get(storage)).networkCapture(identity,
                    Objects.requireNonNull(machine.lifecycle()).formed(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY), _ -> network);
            attach(component, storage, false);
        }

        private Entry attach(ComponentType<MachineInstance, MachineNetworkDevice> role, ComponentType<MachineInstance, MachineDataCenterStorage> storage, boolean station) {
            var typeBuilder = MachineType.builder(id("moving_machine_" + fixture.positions), GuiText.ResourceStorage, id("structure")).component(fixture.ownership).component(role);
            if (storage != null) {
                typeBuilder.component(storage);
            }
            var type = typeBuilder.build();
            var builder = MachineAssembly.builder(type, "moving-v1").component(fixture.ownership, MachineOwnership.CODEC).recreated(role);
            if (station) builder.instanceFactory(value -> new ResearchStationController(value, 2,
                Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 3), 0));
            if (storage != null) {
                builder.component(storage, MachineDataCenterStorage.CODEC);
            }
            var assembly = builder.build();
            return fixture.attach(assembly.create(fixture.host(type.id(), 0, OWNER, fixture.world), new MachineLifecycleRegistry()), assembly, role);
        }

        private void move() {
            network.endTick();
            teamId = "new_team";
            network.beginTick(1);
            assertTrue(network.refresh());
        }

        @Override
        public void close() {
            fixture.close();
            network.close();
        }
    }
}
