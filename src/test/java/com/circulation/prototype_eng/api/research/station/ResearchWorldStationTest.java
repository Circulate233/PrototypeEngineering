package com.circulation.prototype_eng.api.research.station;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchStartCondition;
import com.circulation.prototype_eng.api.research.ResearchWorld;
import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.research.ResearchWorldNbt;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.circulation.prototype_eng.api.research.station.StationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchWorldStationTest {
    private static final TestResources.Key RAW = new TestResources.Key(TYPE, "world_raw");
    private static final TestResources.Key PRODUCT = new TestResources.Key(TYPE, "world_product");
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt(location -> location.equals(TYPE.getId()) ? TYPE : null);
    private static final ResourceLocation RESEARCH = id("research");

    @Test void authoritativeAdmissionRunsItsConditionOnceAndEachTickSavesOneCompleteMaterialSegmentAndGrant() {
        var observations = new Observations();
        var conditionCalls = new AtomicInteger();
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        try (var fixture = new StationFixtures(1, 100, 2, definition(10, observations, context -> {
            conditionCalls.incrementAndGet();
            return OWNER.equals(context.playerId());
        })); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, RAW, 30, false); charge(station, 100);
            var world = world(fixture, writes::add);
            var stranger = new UUID(0, 99);
            var denied = world.start(membership("team"), new ResearchPermissionContext(stranger, "team", null, "team", true, false, false),
                RESEARCH, 1, environment(), 0, Set.of(OWNER), () -> true);
            assertEquals(TeamResearchStart.Status.PERMISSION_DENIED, denied.status());
            assertEquals(ResearchPermissionPolicy.Decision.NOT_TEAM_MEMBER, denied.denial());
            denied = world.start(membership("team"), new ResearchPermissionContext(OWNER, "other", null, "team", true, false, false),
                RESEARCH, 1, environment(), 0, Set.of(OWNER), () -> true);
            assertEquals(ResearchPermissionPolicy.Decision.TARGET_TEAM_MISMATCH, denied.denial());
            assertEquals(0, conditionCalls.get());
            assertEquals(30, amount(station, RAW, false));
            assertTrue(team(world.snapshot(), "team").queue().entries().isEmpty());
            assertEquals(TeamResearchStart.Status.STARTED, start(world, 0).status());
            int admissions = writes.size();
            for (int time = 1; time <= 4; time++) {
                var execution = prepare(world, fixture, lanes, time, membership("team"));
                assertTrue(execution.advance(deadline()));
                assertTrue(execution.finished());
                assertEquals(admissions + time, writes.size());
                var saved = writes.getLast();
                assertEquals(world.snapshot(), saved);
                assertEquals(time, saved.serverTime());
                assertEquals(time, team(saved, "team").stationWork().lastGrantedTime());
                assertNull(team(saved, "team").stationWork().segment());
                assertEquals(30 - time * 3L, amount(station, RAW, false));
                assertEquals(time * 2L, amount(station, PRODUCT, true));
                assertEquals(100 - time * 4L, station.energy().storedEnergy());
                assertEquals(time < 4 ? time * 3L : 0, team(saved, "team").queue().resourceProgress().getLong(NetworkResources.COMPUTE));
                assertEquals(time == 4 ? 1 : 0, playerLevel(saved));
                fixture.advanceTick();
            }
            assertTrue(team(world.snapshot(), "team").queue().entries().isEmpty());
            assertEquals(1, conditionCalls.get());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END,
                RecipeStage.START, RecipeStage.TICK, RecipeStage.END, RecipeStage.START, RecipeStage.TICK, RecipeStage.END,
                RecipeStage.START, RecipeStage.TICK, RecipeStage.END), observations.phases);
        }
    }

    @Test void blockedEndWorldNbtRestoresOriginalMachineIdentityOnANewRuntimeAndOnlyRunsEnd() {
        var observations = new Observations();
        var definition = definition(3, observations, ResearchStartCondition.ALWAYS_ALLOWED);
        ResearchWorldSnapshot checkpoint;
        NBTTagCompound machineTag;
        ResearchStations.Site site;
        try (var original = new StationFixtures(1, 100, 2, definition); var lanes = new Lanes()) {
            var station = original.station(2, 0);
            put(station, RAW, 10, false); put(station, PRODUCT, 100, true); charge(station, 100);
            var writes = new ObjectArrayList<ResearchWorldSnapshot>();
            var world = world(original, writes::add);
            assertEquals(TeamResearchStart.Status.STARTED, start(world, 0).status());
            assertTrue(prepare(world, original, lanes, 1, membership("team")).advance(deadline()));
            checkpoint = world.snapshot();
            var saved = Objects.requireNonNull(team(checkpoint, "team").stationWork().segment());
            site = saved.sites().getFirst();
            assertEquals(1, saved.processTag().getCompoundTag("State").getLong("SuccessfulTicks"));
            assertEquals(7, amount(station, RAW, false));
            assertEquals(95, station.energy().storedEnergy());
            assertTrue(team(checkpoint, "team").queue().resourceProgress().isEmpty());
            assertEquals(0, playerLevel(checkpoint));
            assertEquals(checkpoint, writes.getLast());
            var codec = new ResearchWorldNbt(original.research, original.resources, KEYS);
            checkpoint = codec.decode(codec.encode(checkpoint));
            machineTag = station.assembly().encode(station.machine());
        }
        try (var replacement = new StationFixtures(1, 100, 2, definition); var lanes = new Lanes()) {
            var template = replacement.station(2, 0);
            Objects.requireNonNull(template.machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            var station = reload(replacement, template, site, machineTag);
            assertEquals(site.identity(), replacement.identity(station).identity());
            assertEquals(site.location(), replacement.identity(station).location());
            put(station, RAW, 7, false); put(station, PRODUCT, 98, true); charge(station, 95);
            var writes = new ObjectArrayList<ResearchWorldSnapshot>();
            var world = world(replacement, writes::add);
            world.restore(checkpoint);
            assertTrue(prepare(world, replacement, lanes, 2, membership("team")).advance(deadline()));
            var completed = world.snapshot();
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END, RecipeStage.END), observations.phases);
            assertEquals(7, amount(station, RAW, false));
            assertEquals(100, amount(station, PRODUCT, true));
            assertEquals(96, station.energy().storedEnergy());
            assertEquals(1, playerLevel(completed));
            assertNull(team(completed, "team").stationWork().segment());
            assertEquals(2, team(completed, "team").stationWork().lastGrantedTime());
            assertEquals(completed, writes.getLast());
        }
    }

    @Test void failedWorldWriteRetainsItsSnapshotAndOriginalExecutionRetriesOnlyTheWrite() {
        var observations = new Observations();
        var reject = new AtomicBoolean();
        var attempts = new ObjectArrayList<ResearchWorldSnapshot>();
        var failure = new IllegalArgumentException("world station checkpoint write failed");
        try (var fixture = new StationFixtures(1, 100, 2, definition(6, observations, ResearchStartCondition.ALWAYS_ALLOWED)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0); put(station, RAW, 10, false); charge(station, 100);
            var world = world(fixture, snapshot -> { attempts.add(snapshot); if (reject.get()) { throw failure; } });
            start(world, 0); reject.set(true);
            var execution = prepare(world, fixture, lanes, 1, membership("team"));
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> execution.advance(deadline())));
            assertTrue(world.savePending()); assertFalse(execution.finished());
            var pending = attempts.getLast();
            assertEquals(3, team(pending, "team").queue().resourceProgress().getLong(NetworkResources.COMPUTE));
            assertEquals(7, amount(station, RAW, false));
            assertEquals(2, amount(station, PRODUCT, true));
            assertEquals(96, station.energy().storedEnergy());
            assertThrows(IllegalStateException.class, world::snapshot);
            assertThrows(IllegalStateException.class, () -> prepare(world, fixture, lanes, 2, membership("team")));
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> execution.advance(deadline())));
            assertSame(pending, attempts.getLast());
            reject.set(false);
            assertTrue(execution.advance(deadline()));
            assertSame(pending, attempts.getLast());
            assertFalse(world.savePending());
            assertEquals(pending, world.snapshot());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), observations.phases);
            assertEquals(7, amount(station, RAW, false));
            fixture.advanceTick();
            assertTrue(prepare(world, fixture, lanes, 2, membership("team")).advance(deadline()));
            assertEquals(4, amount(station, RAW, false));
            assertEquals(4, amount(station, PRODUCT, true));
            assertEquals(1, playerLevel(world.snapshot()));
        }
    }

    @Test void unknownCompletionDefersCheckpointAndBlocksANewTickUntilTheOldExecutionConfirms() {
        var unknown = new UnknownEnd();
        var observations = new Observations(unknown::acquire);
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var refreshes = new AtomicInteger();
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, observations, ResearchStartCondition.ALWAYS_ALLOWED)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0); put(station, RAW, 10, false); charge(station, 100);
            var world = world(fixture, writes::add); start(world, 0);
            int admissionWrites = writes.size();
            var execution = world.prepareTick(1, Map.of(OWNER, membership("team")), Set.of(OWNER), _ -> null, () -> true,
                fixture.selector, () -> { refreshes.incrementAndGet(); fixture.refresh(); }, lanes.tasks, deadline());
            assertSame(unknown.failure, assertThrows(IllegalStateException.class, () -> execution.advance(deadline())));
            int capturedRefreshes = refreshes.get();
            assertFalse(execution.finished());
            assertTrue(world.busy());
            assertThrows(IllegalStateException.class, world::snapshot);
            assertThrows(IllegalStateException.class, () -> prepare(world, fixture, lanes, 2, membership("team")));
            assertEquals(TeamResearchStart.Status.QUEUE_BUSY, start(world, 2).status());
            world.checkpoint(2);
            assertEquals(admissionWrites, writes.size());
            assertFalse(world.savePending());
            assertFalse(execution.advance(deadline()));
            assertEquals(capturedRefreshes, refreshes.get());
            assertEquals(1, unknown.completions.get());
            unknown.confirmed.set(true);
            execution.awaitExit();
            assertTrue(execution.advance(deadline()));
            assertEquals(capturedRefreshes, refreshes.get());
            assertEquals(admissionWrites + 1, writes.size());
            var saved = world.snapshot();
            assertEquals(1, saved.serverTime());
            assertEquals(saved, writes.getLast());
            assertEquals(1, playerLevel(saved));
            assertTrue(team(saved, "team").queue().entries().isEmpty());
            assertEquals(7, amount(station, RAW, false));
            assertEquals(2, amount(station, PRODUCT, true));
            assertEquals(1, unknown.completions.get());
        }
    }

    @Test void blacklistingCancelsAnAlreadyPaidBlockedSegmentWithoutRefundAndNewAdmissionCanProgress() {
        var observations = new Observations();
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, observations, ResearchStartCondition.ALWAYS_ALLOWED)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0); put(station, RAW, 10, false); put(station, PRODUCT, 100, true); charge(station, 100);
            var world = world(fixture, _ -> { });
            var original = Objects.requireNonNull(start(world, 0).entry());
            assertTrue(prepare(world, fixture, lanes, 1, membership("team")).advance(deadline()));
            assertNotNull(team(world.snapshot(), "team").stationWork().segment());
            assertEquals(ResearchPermissionPolicy.Decision.ALLOWED, world.setBlacklisted(membership("team"), permission(), RESEARCH, true, 1, () -> true));
            fixture.advanceTick();
            assertTrue(prepare(world, fixture, lanes, 2, membership("team")).advance(deadline()));
            var cancelled = world.snapshot();
            assertTrue(team(cancelled, "team").queue().entries().isEmpty());
            assertNull(team(cancelled, "team").stationWork().segment());
            assertEquals(1, observations.cancellations.get());
            assertEquals(7, amount(station, RAW, false));
            assertEquals(95, station.energy().storedEnergy());
            assertEquals(0, playerLevel(cancelled));
            assertEquals(ResearchPermissionPolicy.Decision.ALLOWED, world.setBlacklisted(membership("team"), permission(), RESEARCH, false, 2, () -> true));
            var future = Objects.requireNonNull(start(world, 2).entry());
            assertTrue(future.key().queueEntryId() > original.key().queueEntryId());
            assertEquals(2, Objects.requireNonNull(station.warehouse().output()).extract(PRODUCT, 2, Actionable.MODULATE));
            fixture.advanceTick();
            assertTrue(prepare(world, fixture, lanes, 3, membership("team")).advance(deadline()));
            assertEquals(4, amount(station, RAW, false));
            assertEquals(91, station.energy().storedEnergy());
            assertEquals(100, amount(station, PRODUCT, true));
            assertEquals(1, playerLevel(world.snapshot()));
        }
    }

    @Test void membershipMigrationCancelsThePaidOldTeamBeforeRejoinedFutureWorkUsesItsStations() {
        var observations = new Observations();
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, observations, ResearchStartCondition.ALWAYS_ALLOWED)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0); put(station, RAW, 10, false); put(station, PRODUCT, 100, true); charge(station, 100);
            var world = world(fixture, _ -> { }); start(world, 0);
            assertTrue(prepare(world, fixture, lanes, 1, membership("team")).advance(deadline()));
            fixture.advanceTick();
            assertTrue(prepare(world, fixture, lanes, 2, membership("migrated")).advance(deadline()));
            var migrated = world.snapshot();
            assertTrue(team(migrated, "team").knownMembers().isEmpty());
            assertTrue(team(migrated, "team").queue().entries().isEmpty());
            assertNull(team(migrated, "team").stationWork().segment());
            assertEquals(Set.of(OWNER), team(migrated, "migrated").knownMembers());
            assertEquals(1, observations.cancellations.get());
            assertEquals(7, amount(station, RAW, false));
            assertEquals(95, station.energy().storedEnergy());
            assertEquals(0, playerLevel(migrated));
            assertEquals(TeamResearchStart.Status.STARTED, start(world, 2).status());
            assertEquals(2, Objects.requireNonNull(station.warehouse().output()).extract(PRODUCT, 2, Actionable.MODULATE));
            fixture.advanceTick();
            assertTrue(prepare(world, fixture, lanes, 3, membership("team")).advance(deadline()));
            assertEquals(4, amount(station, RAW, false));
            assertEquals(91, station.energy().storedEnergy());
            assertEquals(1, playerLevel(world.snapshot()));
            assertTrue(team(world.snapshot(), "team").queue().entries().isEmpty());
        }
    }

    @Test void closeRetainsUnknownOldExecutionAndOnlySavesAfterItsRealCompletionConfirms() {
        var unknown = new UnknownEnd();
        var observations = new Observations(unknown::acquire);
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, observations, ResearchStartCondition.ALWAYS_ALLOWED)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0); put(station, RAW, 10, false); charge(station, 100);
            var world = world(fixture, writes::add); start(world, 0);
            var execution = prepare(world, fixture, lanes, 1, membership("team"));
            assertSame(unknown.failure, assertThrows(IllegalStateException.class, () -> execution.advance(deadline())));
            int count = writes.size();
            assertTrue(assertThrows(IllegalStateException.class, () -> world.close(2)).getMessage().contains("unconfirmed"));
            assertFalse(execution.finished());
            assertThrows(IllegalStateException.class, world::snapshot);
            assertEquals(TeamResearchStart.Status.QUEUE_BUSY, start(world, 2).status());
            assertEquals(count, writes.size());
            assertEquals(1, unknown.completions.get());
            unknown.confirmed.set(true);
            world.close(2);
            assertTrue(execution.finished());
            var finalSave = writes.getLast();
            assertEquals(2, finalSave.serverTime());
            assertEquals(1, playerLevel(finalSave));
            assertTrue(team(finalSave, "team").queue().entries().isEmpty());
            assertNull(team(finalSave, "team").stationWork().segment());
            assertEquals(7, amount(station, RAW, false));
            assertEquals(2, amount(station, PRODUCT, true));
            assertEquals(1, unknown.completions.get());
            count = writes.size(); world.close(3); assertEquals(count, writes.size());
            assertThrows(IllegalStateException.class, () -> world.checkpoint(3));
        }
    }

    @Test void restoredPaidWorkCancelsAfterItsOwnerMigratesEvenThoughTheOldTeamHasNoWorkPermit() {
        var observations = new Observations();
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, observations, ResearchStartCondition.ALWAYS_ALLOWED));
             var migrated = new NetworkRuntime(fixture.identities, fixture.resources, _ -> membership("migrated"), fixture::requireOwner);
             var lanes = new Lanes()) {
            var original = fixture.station(2, 0);
            put(original, RAW, 10, false); put(original, PRODUCT, 100, true); charge(original, 100);
            var world = world(fixture, _ -> { }); start(world, 0);
            assertTrue(prepare(world, fixture, lanes, 1, membership("team")).advance(deadline()));
            var codec = new ResearchWorldNbt(fixture.research, fixture.resources, KEYS);
            var checkpoint = codec.decode(codec.encode(world.snapshot()));
            var saved = Objects.requireNonNull(team(checkpoint, "team").stationWork().segment());
            var site = saved.sites().getFirst();
            var machineTag = original.assembly().encode(original.machine());
            Objects.requireNonNull(original.machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            migrated.beginTick(1);
            var attached = MachineNetworkDevice.component(original.role().id(), fixture.ownership,
                (machine, identity) -> new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.RESEARCH_STATION, 2,
                    Objects.requireNonNull(machine.lifecycle()).formed(), identity.loadOrder(), Object2LongMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0),
                _ -> migrated);
            var type = MachineType.builder(site.location().machineType(), GuiText.ResourceStorage, id("structure"))
                .component(fixture.ownership).component(attached).build();
            var assembly = MachineAssembly.builder(type, "v1").component(fixture.ownership, MachineOwnership.CODEC).recreated(attached).build();
            var replacement = fixture.attach(assembly.restore(host(fixture, site), machineTag, new MachineLifecycleRegistry()), assembly, attached);
            put(replacement, RAW, 7, false); put(replacement, PRODUCT, 100, true); charge(replacement, 95);
            assertTrue(migrated.refresh()); fixture.refresh();
            assertEquals("migrated", Objects.requireNonNull(migrated.plan()).teamIds().get(site.identity().machineId()));
            assertEquals(NetworkWorkState.NO_ACTIVE_CENTER, fixture.roleOf(replacement.machine()).state());
            assertNull(fixture.roleOf(replacement.machine()).workPermit());
            assertNull(fixture.selector.restore("team", 2, saved.sites()));
            assertNotNull(fixture.selector.restoreForCleanup(saved.sites()));
            var writes = new ObjectArrayList<ResearchWorldSnapshot>();
            var restoredWorld = world(fixture, writes::add); restoredWorld.restore(checkpoint);
            var execution = restoredWorld.prepareTick(2, Map.of(OWNER, membership("migrated")), Set.of(OWNER), _ -> null, () -> true,
                fixture.selector, () -> { fixture.refresh(); assertTrue(migrated.refresh()); }, lanes.tasks, deadline());
            assertTrue(execution.advance(deadline()));
            var cancelled = restoredWorld.snapshot();
            assertFalse(restoredWorld.busy()); assertFalse(restoredWorld.faulted());
            assertTrue(team(cancelled, "team").knownMembers().isEmpty());
            assertTrue(team(cancelled, "team").queue().entries().isEmpty());
            assertNull(team(cancelled, "team").stationWork().segment());
            assertTrue(team(cancelled, "team").queue().resourceProgress().isEmpty());
            assertEquals(0, playerLevel(cancelled));
            assertEquals(1, observations.cancellations.get());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), observations.phases);
            assertEquals(7, amount(replacement, RAW, false));
            assertEquals(100, amount(replacement, PRODUCT, true));
            assertEquals(95, replacement.energy().storedEnergy());
            assertEquals(cancelled, writes.getLast());
            assertEquals(site.identity(), fixture.identity(replacement).identity());
        }
    }

    @Test void settledAcquisitionAndCommitExceptionsReleaseExecutionOwnershipAndRetainTheOriginalFatalCause() {
        for (boolean duringCommit : new boolean[]{false, true}) {
            var failure = new IllegalStateException(duringCommit ? "original settled END commit failure" : "original settled END acquisition failure");
            var endCallbacks = new AtomicInteger();
            var commits = new AtomicInteger();
            var observations = new Observations(phase -> {
                if (phase != RecipeStage.END) { return RequirementTransaction.SUCCESS; }
                endCallbacks.incrementAndGet();
                if (!duringCommit) { throw failure; }
                return new RequirementTransaction() {
                    @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                    @Override public void commit() { commits.incrementAndGet(); throw failure; }
                    @Override public void rollback() { }
                };
            });
            var writes = new ObjectArrayList<ResearchWorldSnapshot>();
            try (var fixture = new StationFixtures(1, 100, 2, definition(3, observations, ResearchStartCondition.ALWAYS_ALLOWED)); var lanes = new Lanes()) {
                var station = fixture.station(2, 0); put(station, RAW, 10, false); charge(station, 100);
                var world = world(fixture, writes::add); start(world, 0);
                int admissionWrites = writes.size();
                var execution = prepare(world, fixture, lanes, 1, membership("team"));
                assertSame(failure, assertThrows(IllegalStateException.class, () -> execution.advance(deadline())));
                assertTrue(world.faulted()); assertFalse(world.busy()); assertTrue(execution.finished());
                assertFalse(world.savePending());
                assertEquals(admissionWrites, writes.size());
                assertEquals(1, endCallbacks.get());
                assertEquals(duringCommit ? 1 : 0, commits.get());
                var retained = Objects.requireNonNull(observations.lastProcess.get());
                assertFalse(retained.persistenceReady());
                assertEquals(1, retained.state().successfulTicks());
                assertEquals(0, observations.cancellations.get());
                assertEquals(7, amount(station, RAW, false));
                assertEquals(0, amount(station, PRODUCT, true));
                assertEquals(95, station.energy().storedEnergy());
                assertFatal(failure, world::snapshot);
                assertFatal(failure, () -> prepare(world, fixture, lanes, 2, membership("team")));
                assertFatal(failure, () -> start(world, 2));
                assertFatal(failure, () -> world.player(OWNER));
                assertFatal(failure, () -> world.checkpoint(2));
                assertFatal(failure, () -> world.close(2));
                assertTrue(execution.advance(deadline()));
                assertEquals(1, endCallbacks.get());
                assertSame(retained, observations.lastProcess.get());
                assertEquals(admissionWrites, writes.size());
                // Unload is an actual lifecycle transition; it cannot enter while the old RUNNING gate is retained.
                assertTrue(Objects.requireNonNull(station.machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
                assertFalse(fixture.identity(station).current());
            }
        }
    }

    @Test void expectedProcessFailureCleansItsPaidPartialSegmentAndSavesWithoutFaultingTheWorld() {
        var reject = new AtomicBoolean(true);
        var rejected = new ProcessResult.Failure("expected external END rejection");
        var observations = new Observations(phase -> phase == RecipeStage.END && reject.get() ? new RequirementTransaction() {
            @Override public ProcessResult result() { return rejected; }
            @Override public void commit() { }
            @Override public void rollback() { }
        } : RequirementTransaction.SUCCESS);
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, observations, ResearchStartCondition.ALWAYS_ALLOWED)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0); put(station, RAW, 10, false); charge(station, 100);
            var world = world(fixture, writes::add);
            var original = Objects.requireNonNull(start(world, 0).entry());
            var execution = prepare(world, fixture, lanes, 1, membership("team"));
            assertTrue(execution.advance(deadline()));
            assertTrue(execution.finished()); assertFalse(world.busy()); assertFalse(world.faulted());
            var failedProcess = Objects.requireNonNull(observations.lastProcess.get());
            assertSame(rejected, failedProcess.state().result());
            assertEquals(RecipeProcess.Lifecycle.FAILED, failedProcess.state().lifecycle());
            var saved = world.snapshot();
            assertEquals(List.of(original), team(saved, "team").queue().entries());
            assertNull(team(saved, "team").stationWork().segment());
            assertTrue(team(saved, "team").queue().resourceProgress().isEmpty());
            assertEquals(0, playerLevel(saved));
            assertEquals(7, amount(station, RAW, false));
            assertEquals(0, amount(station, PRODUCT, true));
            assertEquals(95, station.energy().storedEnergy());
            assertEquals(1, observations.cancellations.get());
            assertEquals(saved, writes.getLast());
            Objects.requireNonNull(station.machine().lifecycle()).requirePersistenceBoundary();
            world.checkpoint(1);
            reject.set(false); fixture.advanceTick();
            assertTrue(prepare(world, fixture, lanes, 2, membership("team")).advance(deadline()));
            assertNotSame(failedProcess, observations.lastProcess.get());
            assertEquals(4, amount(station, RAW, false));
            assertEquals(2, amount(station, PRODUCT, true));
            assertEquals(91, station.energy().storedEnergy());
            assertEquals(1, playerLevel(world.snapshot()));
            assertFalse(world.faulted());
            world.close(2);
        }
    }

    @Test void partialMembershipUniverseUnavailablePausesBeforeMissingTrackedPeersAreValidated() {
        var observations = new Observations();
        var writes = new ObjectArrayList<ResearchWorldSnapshot>();
        var second = new UUID(0, 2);
        var missing = new UUID(0, 3);
        var members = Set.of(OWNER, second, missing);
        var accepted = OwnerTeamResolution.resolved(OWNER, "team", OwnerTeamType.SERVER, new ObjectOpenHashSet<>(members), Optional.empty());
        var unavailable = OwnerTeamResolution.rejected(second, OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE, "membership universe temporarily unavailable");
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, observations, ResearchStartCondition.ALWAYS_ALLOWED)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0); put(station, RAW, 10, false); charge(station, 100);
            var world = world(fixture, writes::add);
            assertEquals(TeamResearchStart.Status.STARTED, world.start(accepted, permission(), RESEARCH, 1, environment(), 0, Set.of(OWNER), () -> true).status());
            var before = world.snapshot();
            int count = writes.size();
            var execution = world.prepareTick(1, Map.of(OWNER, accepted, second, unavailable), Set.of(OWNER), _ -> null, () -> true,
                fixture.selector, fixture::refresh, lanes.tasks, deadline());
            assertTrue(execution.advance(deadline()));
            assertTrue(execution.finished()); assertFalse(world.busy()); assertFalse(world.faulted());
            assertEquals(before, world.snapshot());
            assertEquals(0, world.serverTime());
            assertEquals(members, team(world.snapshot(), "team").knownMembers());
            assertEquals(count, writes.size());
            assertTrue(observations.phases.isEmpty());
            assertEquals(10, amount(station, RAW, false));
            assertEquals(0, amount(station, PRODUCT, true));
            assertEquals(100, station.energy().storedEnergy());
        }
    }

    private static void assertFatal(Throwable cause, Runnable operation) {
        var rejected = assertThrows(IllegalStateException.class, operation::run);
        assertSame(cause, rejected.getCause());
        assertFalse(rejected.getMessage().contains("unconfirmed"));
    }

    private static ResearchDefinition definition(long demand, Observations observations, ResearchStartCondition condition) {
        var stage = ResearchLevelStage.builder(1, 1).researchTier(2)
            .resourceFormula(ResearchResourceFormula.builder().constant(NetworkResources.COMPUTE, demand, 3).build())
            .materialPersistence("world-station-v1", builder -> builder.resources(KEYS))
            .materialRequirement(observations.requirement())
            .materialRequirement(RecipeRequirements.RESOURCES.requirement(id("world_io"), new ResourceRequirement(
                List.of(new GenericStack(RAW, 2)), List.of(new GenericStack(RAW, 1)), List.of(), List.of(new GenericStack(PRODUCT, 2)), false)))
            .materialRequirement(RecipeRequirements.ENERGY.requirement(id("world_energy"), new EnergyRequirement(3, 2, 0, 1, false))).build();
        return ResearchDefinition.builder(RESEARCH).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
            .startCondition(condition).stage(stage).build();
    }
    private static ResearchWorld world(StationFixtures fixture, Consumer<ResearchWorldSnapshot> writer) {
        return new ResearchWorld(fixture.research, fixture.resources, ResearchPermissionPolicy.DEFAULT, writer);
    }
    private static TeamResearchStart.Result start(ResearchWorld world, long time) {
        return world.start(membership("team"), permission(), RESEARCH, 1, environment(), time, Set.of(OWNER), () -> true);
    }
    private static PlannedExecution prepare(ResearchWorld world, StationFixtures fixture, Lanes lanes, long time, OwnerTeamResolution membership) {
        return world.prepareTick(time, Map.of(OWNER, membership), Set.of(OWNER), _ -> null, () -> true, fixture.selector, fixture::refresh, lanes.tasks, deadline());
    }
    private static ResearchPermissionContext permission() { return new ResearchPermissionContext(OWNER, "team", null, "team", true, false, false); }
    private static TeamResearchStart.Environment environment() { return new TeamResearchStart.Environment(3, 2, 2, Object2LongMaps.emptyMap()); }
    private static OwnerTeamResolution membership(String team) {
        return OwnerTeamResolution.resolved(OWNER, team, OwnerTeamType.SERVER, new ObjectOpenHashSet<>(Set.of(OWNER)), Optional.empty());
    }
    private static ResearchWorldSnapshot.Team team(ResearchWorldSnapshot snapshot, String id) {
        return snapshot.teams().stream().filter(team -> team.teamId().equals(id)).findFirst().orElseThrow();
    }
    private static int playerLevel(ResearchWorldSnapshot snapshot) {
        return snapshot.players().stream().filter(player -> player.playerUuid().equals(OWNER)).findFirst().orElseThrow().completedLevels().getInt(RESEARCH);
    }
    private static PlannedDeadline deadline() { return PlannedDeadline.after(Duration.ofSeconds(5)); }
    private static void put(Entry station, TestResources.Key key, long count, boolean output) {
        var storage = Objects.requireNonNull(output ? station.warehouse().output() : station.warehouse().input());
        assertEquals(count, storage.insert(key, count, Actionable.MODULATE));
    }
    private static long amount(Entry station, TestResources.Key key, boolean output) {
        return Objects.requireNonNull(output ? station.warehouse().output() : station.warehouse().input()).amount(key);
    }
    private static void charge(Entry station, long amount) {
        station.energy().extractEnergy(100, Actionable.MODULATE);
        assertEquals(amount, station.energy().receiveEnergy(amount, Actionable.MODULATE));
    }
    private static Entry reload(StationFixtures fixture, Entry template, ResearchStations.Site site, NBTTagCompound machineTag) {
        var machine = template.assembly().restore(host(fixture, site), machineTag, new MachineLifecycleRegistry());
        return fixture.attach(machine, template.assembly(), template.role());
    }
    private static MachineLifecycleHost host(StationFixtures fixture, ResearchStations.Site site) {
        return new MachineLifecycleHost() {
            @Override public Object worldIdentity() { return fixture.world; }
            @Override public MachineIdentity identity() { return site.location(); }
            @Override public UUID placementPlayer() { return site.identity().ownerId(); }
            @Override public World world() { throw new AssertionError("No game world in research world station tests"); }
            @Override public void requireServerThread() { }
        };
    }

    /** External Java requirement, with real reversible END completion rather than synthetic storage tokens. */
    private static final class Observations {
        private final ObjectArrayList<RecipeStage> phases = new ObjectArrayList<>();
        private final AtomicInteger cancellations = new AtomicInteger();
        private final AtomicReference<RecipeProcess> lastProcess = new AtomicReference<>();
        private final Function<RecipeStage, RequirementTransaction> action;
        private Observations() { this(_ -> RequirementTransaction.SUCCESS); }
        private Observations(Function<RecipeStage, RequirementTransaction> action) { this.action = action; }
        private RecipeRequirement<Observations> requirement() {
            var type = new RecipeRequirementType<>(new ResourceLocation("aaa_world_station_test", "observation"), new RecipeRequirementSystem<Observations>() {
                @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Observations> requirement) {
                    requirement.payload().lastProcess.set(process);
                    return requirement.payload().acquire(RecipeStage.START);
                }
                @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Observations> requirement) {
                    requirement.payload().lastProcess.set(process);
                    return requirement.payload().acquire(RecipeStage.TICK);
                }
                @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Observations> requirement) {
                    requirement.payload().lastProcess.set(process);
                    return requirement.payload().acquire(RecipeStage.END);
                }
                @Override public void cancel(RecipeProcess process, RecipeRequirement<Observations> requirement) {
                    requirement.payload().cancellations.incrementAndGet();
                }
            });
            return type.requirement(id("world_observer"), this);
        }
        private RequirementTransaction acquire(RecipeStage phase) { phases.add(phase); return action.apply(phase); }
    }

    private static final class UnknownEnd {
        private final IllegalStateException failure = new IllegalStateException("unconfirmed original world END participant");
        private final AtomicBoolean confirmed = new AtomicBoolean();
        private final AtomicInteger completions = new AtomicInteger();
        private RequirementTransaction acquire(RecipeStage phase) {
            if (phase != RecipeStage.END) { return RequirementTransaction.SUCCESS; }
            return new RequirementTransaction() {
                @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                @Override public void commit() { }
                @Override public void rollback() { }
                @Override public void complete() { completions.incrementAndGet(); throw failure; }
                @Override public Resolution resolution() { return confirmed.get() ? Resolution.COMPLETED : Resolution.UNKNOWN; }
            };
        }
    }

    private static final class Lanes implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private final ExecutorService first = Executors.newSingleThreadExecutor();
        private final ExecutorService second = Executors.newSingleThreadExecutor();
        private final PlannedTaskExecutor tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{first, second});
        @Override public void close() { first.close(); second.close(); workers.close(); }
    }
}
