package com.circulation.prototype_eng.api.research.station;

import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;

import static com.circulation.prototype_eng.api.research.station.StationFixtures.OWNER;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchWorld;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.UUID;


@SuppressWarnings("DataFlowIssue") // Test setup establishes successful admission or capture before reading the result.
class ResearchWorldStationTest {
    @Test
    void queueActionsReorderExactEntriesAndRejectAnotherTeamsActor() {
        var registry = new ResearchRegistry();
        for (var name : List.of("a", "b", "c"))
            registry.register(ResearchDefinition.builder(id(name)).maxLevel(1)
                                                .deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(ResearchLevelStage.builder(1, 1).durationTicks(4).researchTier(1)
                                                                                                                                  .resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze();
        var world = new ResearchWorld(registry, ResearchPermissionPolicy.DEFAULT, _ -> {
        });
        var member = OwnerTeamResolution.resolved(OWNER, "team", OwnerTeamType.SERVER, ObjectSets.singleton(OWNER), Optional.empty());
        var permission = new ResearchPermissionContext(OWNER, "team", null, "team", true, false, false);
        var environment = new TeamResearchStart.Environment(1, 1, 1, Reference2DoubleMaps.emptyMap());
        for (var name : List.of("a", "b", "c"))
            world.start(member, permission, id(name), 1, environment, 0, Set.of(OWNER), () -> true);
        var rows = world.snapshot().teams().getFirst().queue().entries();
        long selected = rows.get(2).key().queueEntryId();
        assertTrue(world.manageQueue(member, permission, selected, ResearchStationView.Action.CURRENT, 1, Set.of(OWNER), _ -> null, () -> true));
        assertEquals(id("c"), world.snapshot().teams().getFirst().queue().activeHead().researchId());
        assertTrue(world.manageQueue(member, permission, selected, ResearchStationView.Action.DOWN, 2, Set.of(OWNER), _ -> null, () -> true));
        assertEquals(id("a"), world.snapshot().teams().getFirst().queue().activeHead().researchId());
        var outsider = new UUID(0, 10);
        var other = OwnerTeamResolution.resolved(outsider, "other", OwnerTeamType.SERVER, ObjectSets.singleton(outsider), Optional.empty());
        var denied = new ResearchPermissionContext(outsider, "other", null, "team", false, false, false);
        assertFalse(world.manageQueue(other, denied, selected, ResearchStationView.Action.REMOVE, 3, Set.of(outsider), _ -> null, () -> true));
        assertEquals(3, world.snapshot().teams().getFirst().queue().entries().size());
    }

    @Test
    void worldCheckpointRetainsRemovedResearchAndResumesItAfterReload() {
        try (var fixture = new StationFixtures(1, 100, 2, ResearchStationWorkTest.definition(4, 40, 0))) {
            fixture.poweredStation(0);
            fixture.refresh();
            var membership = OwnerTeamResolution.resolved(OWNER, "team", OwnerTeamType.SERVER, ObjectSets.singleton(OWNER), Optional.empty());
            var permission = new ResearchPermissionContext(OWNER, "team", null, "team", true, false, false);
            var world = new ResearchWorld(fixture.research, ResearchPermissionPolicy.DEFAULT, _ -> {
            });
            assertEquals(TeamResearchStart.Status.STARTED, world.start(membership, permission, id("research"), 1,
                fixture.selector.admission("team").environment(), 0, Set.of(OWNER), () -> true).status());
            var deadline = PlannedDeadline.after(Duration.ofSeconds(5));
            var tick = world.prepareTick(1, Map.of(OWNER, membership), Set.of(OWNER), _ -> null, () -> true, fixture.selector, fixture::refresh, deadline);
            assertTrue(tick.advance(deadline));
            var head = world.snapshot().teams().getFirst().queue().activeHead();
            assertTrue(world.manageQueue(membership, permission, head.queueEntryId(), ResearchStationView.Action.REMOVE, 2, Set.of(OWNER), _ -> null, () -> true));
            var saved = world.snapshot();
            assertEquals(0, saved.teams().getFirst().queue().entries().size());
            assertEquals(1, saved.teams().getFirst().queue().progress().getFirst().elapsedTicks());
            var restored = new ResearchWorld(fixture.research, ResearchPermissionPolicy.DEFAULT, _ -> {
            });
            restored.restore(saved);
            assertEquals(TeamResearchStart.Status.STARTED, restored.start(membership, permission, id("research"), 1,
                fixture.selector.admission("team").environment(), 3, Set.of(OWNER), () -> true).status());
            fixture.nextTick();
            deadline = PlannedDeadline.after(Duration.ofSeconds(5));
            assertTrue(restored.prepareTick(4, Map.of(OWNER, membership), Set.of(OWNER), _ -> null, () -> true,
                fixture.selector, fixture::refresh, deadline).advance(deadline));
            assertEquals(2, restored.snapshot().teams().getFirst().queue().progress().getFirst().elapsedTicks());
        }
    }
}
