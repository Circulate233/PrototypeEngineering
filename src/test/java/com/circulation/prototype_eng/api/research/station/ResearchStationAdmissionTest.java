package com.circulation.prototype_eng.api.research.station;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.network.MachineDataCenterStorage;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchStartCondition;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.circulation.prototype_eng.api.research.station.StationFixtures.Entry;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.OWNER;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.TYPE;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchStationAdmissionTest {
    private static final TestResources.Key MATERIAL = new TestResources.Key(TYPE, "admission_material");

    private static ResearchDefinition definition(int tier, ResearchStartCondition condition) {
        return ResearchDefinition.builder(id("research")).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                 .startCondition(condition).stage(ResearchLevelStage.builder(1, 1).researchTier(tier)
                                                                                    .resourceFormula(ResearchResourceFormula.builder().constant(ComputingResourceFixtures.COMPUTE, 10).build())
                                                                                    .materialRequirement(RecipeRequirements.RESOURCES.requirement(id("admission_io"), new ResourceRequirement(
                                                                                        List.of(new GenericStack(MATERIAL, 5)), List.of(), List.of(), List.of(), false))).build()).build();
    }

    private static TeamResearchQueue queue(StationFixtures fixture) {
        return new TeamResearchQueue(fixture.research, "team");
    }

    private static TeamResearchStart.Result start(StationFixtures fixture, TeamResearchQueue queue, ResearchStations.Admission admission) {
        var view = new TeamResearchView(fixture.research, "team", Set.of(OWNER), Object2IntMaps.emptyMap(), Object2IntMaps.emptyMap(), Set.of());
        var actor = new ResearchPermissionContext(OWNER, "team", null, "team", false, false, false);
        var start = new TeamResearchStart(fixture.research, ResearchPermissionPolicy.DEFAULT);
        return start.start(new TeamResearchStart.Request(queue, view, actor, id("research"), 1, admission.environment(), 1, admission::current));
    }

    @Test
    void samePublicationNumericConsumptionInsideTheConditionRejectsTheOriginalBalanceWithoutReplayingTheCallback() {
        var permits = new NetworkRuntime.WorkPermit[1];
        int[] callbacks = {0};
        var definition = definition(2, context -> {
            callbacks[0]++;
            assertEquals(70, context.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            var consumption = Objects.requireNonNull(Objects.requireNonNull(permits[0]).reserve(id("condition_numeric_consumption"), Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 70)));
            assertTrue(consumption.commit());
            return true;
        });
        try (var fixture = new StationFixtures(1, 100, 2, definition)) {
            var station = fixture.station(2, 0);
            assertEquals(17, Objects.requireNonNull(station.warehouse().input()).insert(MATERIAL, 17, Actionable.MODULATE));
            fixture.refresh();
            var permit = Objects.requireNonNull(fixture.roleOf(fixture.entries.getFirst().machine()).workPermit());
            permits[0] = permit;
            var earlier = Objects.requireNonNull(permit.reserve(id("earlier_numeric_consumption"), Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 30)));
            assertTrue(earlier.commit());
            var publication = fixture.network.plan();
            var captured = fixture.selector.admission("team");
            var queue = queue(fixture);
            assertTrue(captured.current());
            assertEquals(TeamResearchStart.Status.STALE_CONTEXT, start(fixture, queue, captured).status());
            assertSame(publication, fixture.network.plan(), "Budget consumption keeps the same network publication");
            assertTrue(permit.current());
            assertFalse(captured.current());
            assertEquals(70, captured.environment().availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(0, permit.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(TeamResearchStart.Status.STALE_CONTEXT, start(fixture, queue, captured).status());
            assertEquals(1, callbacks[0]);
            assertEquals(0, queue.size());
            assertEquals(17, Objects.requireNonNull(station.warehouse().input()).amount(MATERIAL));
            assertEquals(10, station.energy().storedEnergy());
            assertEquals(100, permit.numericResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, Objects.requireNonNull(fixture.roleOf(station.machine()).workPermit()).requestLease());
        }
    }

    @Test
    void actualMissingStationDataCenterAndCenterProduceTheirNormalAdmissionDenials() {
        try (var fixture = new StationFixtures(1, 100, 2, definition(2, ResearchStartCondition.ALWAYS_ALLOWED))) {
            fixture.refresh();
            var queue = queue(fixture);
            var noStation = fixture.selector.admission("team");
            assertTrue(noStation.current());
            assertEquals(3, noStation.environment().networkCenterLevel());
            assertEquals(2, noStation.environment().dataCenterLevel());
            assertEquals(0, noStation.environment().highestResearchStationLevel());
            assertEquals(TeamResearchStart.Status.STATION_LEVEL_INSUFFICIENT, start(fixture, queue, noStation).status());
            fixture.station(1, 0);
            fixture.refresh();
            assertEquals(TeamResearchStart.Status.STATION_LEVEL_INSUFFICIENT, start(fixture, queue, fixture.selector.admission("team")).status());
            var data = fixture.entries.stream().filter(entry -> entry.machine().components().assemblyBindings().stream()
                                                                     .anyMatch(binding -> binding.component() instanceof MachineDataCenterStorage)).findFirst().orElseThrow();
            Objects.requireNonNull(data.machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            fixture.refresh();
            var noData = fixture.selector.admission("team");
            assertEquals(0, noData.environment().dataCenterLevel());
            assertEquals(1, noData.environment().highestResearchStationLevel());
            assertEquals(TeamResearchStart.Status.DATA_CENTER_LEVEL_INSUFFICIENT, start(fixture, queue, noData).status());
            Objects.requireNonNull(fixture.entries.getFirst().machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            fixture.refresh();
            var noCenter = fixture.selector.admission("team");
            assertTrue(noCenter.current());
            assertEquals(0, noCenter.environment().networkCenterLevel());
            assertEquals(0, noCenter.environment().dataCenterLevel());
            assertEquals(0, noCenter.environment().highestResearchStationLevel());
            assertTrue(noCenter.environment().availableResources().isEmpty());
            assertEquals(TeamResearchStart.Status.NO_ACTIVE_CENTER, start(fixture, queue, noCenter).status());
            assertEquals(0, queue.size());
        }
    }

    @Test
    void highestStationIsAcrossDimensionsAndRequiresTheActualLevelAuthorizedDevicePermit() {
        try (var fixture = new StationFixtures(1, 100, 3, definition(3, ResearchStartCondition.ALWAYS_ALLOWED))) {
            fixture.station(2, 0);
            fixture.station(3, 9);
            var excessive = fixture.station(4, -3);
            fixture.role(NetworkDeviceRole.RESEARCH_STATION, 3, 4, new UUID(0, 100), fixture.world);
            var unformed = fixture.station(3, -5);
            Objects.requireNonNull(unformed.machine().lifecycle()).invalidateStructure();
            fixture.refresh();
            assertEquals(NetworkWorkState.CENTER_LEVEL_INSUFFICIENT, fixture.roleOf(excessive.machine()).state());
            assertNull(fixture.roleOf(excessive.machine()).workPermit());
            var captured = fixture.selector.admission("team");
            assertTrue(captured.current());
            assertEquals(3, captured.environment().networkCenterLevel());
            assertEquals(3, captured.environment().dataCenterLevel());
            assertEquals(3, captured.environment().highestResearchStationLevel());
            assertEquals(TeamResearchStart.Status.STARTED, start(fixture, queue(fixture), captured).status());
            assertEquals(0, Objects.requireNonNull(fixture.selector.capture("team", 2)).dimension(), "Admission never advances the work dimension rotation");
        }
    }

    @Test
    void captureAndAdmissionPreserveMaterialEnergyAndNumericBalanceWithoutTakingStationLeases() {
        int[] callbacks = {0};
        var definition = definition(2, context -> {
            callbacks[0]++;
            return OWNER.equals(context.playerId()) && context.networkCenterLevel() == 3
                && context.availableResources().getDouble(ComputingResourceFixtures.COMPUTE) == 70;
        });
        try (var fixture = new StationFixtures(1, 100, 2, definition)) {
            var first = fixture.station(2, -1);
            var second = fixture.station(2, 3);
            assertEquals(17, Objects.requireNonNull(first.warehouse().input()).insert(MATERIAL, 17, Actionable.MODULATE));
            fixture.refresh();
            var centerPermit = Objects.requireNonNull(fixture.roleOf(fixture.entries.getFirst().machine()).workPermit());
            var priorWork = Objects.requireNonNull(centerPermit.reserve(id("ordinary_before_admission"), Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 30)));
            assertTrue(priorWork.commit());
            var captured = fixture.selector.admission("team");
            assertEquals(70, captured.environment().availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(100, centerPermit.numericResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertTrue(fixture.selector.admission("team").current());
            assertEquals(TeamResearchStart.Status.STARTED, start(fixture, queue(fixture), captured).status());
            assertEquals(1, callbacks[0]);
            assertEquals(17, Objects.requireNonNull(first.warehouse().input()).amount(MATERIAL));
            assertEquals(10, first.energy().storedEnergy());
            assertEquals(10, second.energy().storedEnergy());
            assertEquals(70, centerPermit.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            var firstPermit = Objects.requireNonNull(fixture.roleOf(first.machine()).workPermit());
            var secondPermit = Objects.requireNonNull(fixture.roleOf(second.machine()).workPermit());
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, firstPermit.requestLease());
            assertEquals(NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY, secondPermit.requestLease());
            assertEquals(-1, Objects.requireNonNull(fixture.selector.capture("team", 2)).dimension());
        }
    }

    @Test
    void sourceMutationInsideTheRealStartConditionCannotSubstituteANewPublicationToAdmit() {
        var fixtureRef = new StationFixtures[1];
        var replacedSource = new Entry[1];
        int[] callbacks = {0};
        var definition = definition(2, _ -> {
            callbacks[0]++;
            Objects.requireNonNull(replacedSource[0].machine().lifecycle()).invalidateStructure();
            fixtureRef[0].refresh();
            return true;
        });
        try (var fixture = new StationFixtures(1, 100, 2, definition)) {
            fixtureRef[0] = fixture;
            replacedSource[0] = fixture.station(2, 0);
            fixture.station(2, 4);
            fixture.refresh();
            var captured = fixture.selector.admission("team");
            var queue = queue(fixture);
            assertTrue(captured.current());
            assertEquals(TeamResearchStart.Status.STALE_CONTEXT, start(fixture, queue, captured).status());
            assertFalse(captured.current());
            assertEquals(2, fixture.selector.admission("team").environment().highestResearchStationLevel(), "A fresh eligible station exists, but cannot replace the original callback proof");
            assertEquals(0, queue.size());
            assertEquals(1, callbacks[0]);
        }
    }

    @Test
    void selectedDataCenterAndOriginalPublicationRemainFrozenWhenPhysicalLevelsOrTicksChange() {
        try (var fixture = new StationFixtures(1, 1)) {
            fixture.station(3, 0);
            fixture.refresh();
            var original = fixture.selector.admission("team");
            assertEquals(1, original.environment().dataCenterLevel());
            fixture.dataCenter(2);
            fixture.refresh();
            assertFalse(original.current());
            assertEquals(1, original.environment().dataCenterLevel());
            var replaced = fixture.selector.admission("team");
            assertEquals(2, replaced.environment().dataCenterLevel());
            fixture.nextTick();
            assertFalse(replaced.current());
            assertTrue(fixture.selector.admission("team").current());
        }
    }

    @Test
    void absentCenterSnapshotDetectsANewRegisteredCenterThroughTheCapturedIdentityRevision() {
        try (var fixture = new StationFixtures(1, 2)) {
            Objects.requireNonNull(fixture.entries.getFirst().machine().lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            fixture.refresh();
            var absent = fixture.selector.admission("team");
            assertTrue(absent.current());
            fixture.role(NetworkDeviceRole.NETWORK_CENTER, 3, 0, OWNER, fixture.world);
            fixture.refresh();
            assertFalse(absent.current());
            assertEquals(0, absent.environment().networkCenterLevel());
            assertEquals(3, fixture.selector.admission("team").environment().networkCenterLevel());
        }
    }
}
