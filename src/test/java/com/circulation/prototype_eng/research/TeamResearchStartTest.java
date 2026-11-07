package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchStartCondition;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TeamResearchStartTest {
    private static final UUID MEMBER = new UUID(2, 1);
    private static final UUID OPERATOR = new UUID(2, 2);
    private static final ResourceLocation RESEARCH = new ResourceLocation("test", "research");

    @Test void realRequesterAndResourcesAdmitEvenWhenTheAnonymousPreviewRejected() {
        var calls = new int[1];
        var setup = setup(0, context -> {
            if (context.playerId() == null) { return false; }
            calls[0]++;
            return context.playerId().equals(MEMBER) && context.availableResources().getLong(NetworkResources.COMPUTE) == 15;
        });
        assertFalse(setup.view.isStartable(RESEARCH));
        var supply = new Object2LongOpenHashMap<NetworkResourceType>();
        supply.put(NetworkResources.COMPUTE, 15);
        var result = setup.start(ResearchPermissionPolicy.DEFAULT).start(setup.request(member(),
            new TeamResearchStart.Environment(0, 0, 0, supply), () -> true));
        assertEquals(TeamResearchStart.Status.STARTED, result.status());
        assertNotNull(result.entry());
        assertEquals(MEMBER, result.entry().requestedBy());
        assertEquals(1, calls[0]);
        assertEquals(1, setup.queue.size());
        assertEquals(15, supply.getLong(NetworkResources.COMPUTE));
        assertTrue(setup.queue.resourceProgressSnapshot().isEmpty());
    }

    @Test void aPlainMemberCanStartButForeignTeamCannotInvokeTheCondition() {
        var calls = new int[1];
        var setup = setup(0, context -> { if (context.playerId() != null) { calls[0]++; } return true; });
        var start = setup.start(ResearchPermissionPolicy.DEFAULT);
        var foreign = new ResearchPermissionContext(OPERATOR, "other", null, "team", true, true, false);
        var rejected = start.start(setup.request(foreign, empty(), () -> true));
        assertEquals(ResearchPermissionPolicy.Decision.TARGET_TEAM_MISMATCH, rejected.denial());
        assertEquals(0, calls[0]);
        assertEquals(0, setup.queue.size());
        assertEquals(TeamResearchStart.Status.STARTED, start.start(setup.request(member(), empty(), () -> true)).status());
        assertEquals(1, calls[0]);
    }

    @Test void tieredAdmissionRequiresTheSelectedDataCenterAndAStationAtTheResearchTier() {
        var setup = setup(2, ResearchStartCondition.ALWAYS_ALLOWED);
        var start = setup.start(ResearchPermissionPolicy.DEFAULT);
        assertEquals(TeamResearchStart.Status.NO_ACTIVE_CENTER, start.start(setup.request(member(), empty(), () -> true)).status());
        assertEquals(TeamResearchStart.Status.DATA_CENTER_LEVEL_INSUFFICIENT,
            start.start(setup.request(member(), environment(3, 1, 3), () -> true)).status());
        assertEquals(TeamResearchStart.Status.STATION_LEVEL_INSUFFICIENT,
            start.start(setup.request(member(), environment(3, 2, 1), () -> true)).status());
        var operator = new ResearchPermissionContext(OPERATOR, "other", null, "team", false, false, true);
        assertEquals(TeamResearchStart.Status.STARTED,
            start.start(setup.request(operator, environment(3, 2, 2), () -> true)).status());
        assertEquals(1, setup.queue.size());
    }

    @Test void customPermissionAndCallbackFailuresDoNotBecomeSuccessfulAdmission() {
        var conditionFailure = new IllegalStateException("condition failed");
        var setup = setup(0, context -> { if (context.playerId() != null) { throw conditionFailure; } return true; });
        var denied = setup.start((_, _) -> ResearchPermissionPolicy.Decision.NOT_TEAM_LEADER);
        assertEquals(TeamResearchStart.Status.PERMISSION_DENIED, denied.start(setup.request(member(), empty(), () -> true)).status());
        var start = setup.start(ResearchPermissionPolicy.DEFAULT);
        assertSame(conditionFailure, assertThrows(IllegalStateException.class,
            () -> start.start(setup.request(member(), empty(), () -> true))));
        assertEquals(0, setup.queue.size());
        assertFalse(setup.queue.busy());
    }

    @Test void authorityInvalidatedByAConditionCannotAppendTheStaleRequest() {
        var current = new boolean[] { true };
        var setup = setup(0, context -> { if (context.playerId() != null) { current[0] = false; } return true; });
        var result = setup.start(ResearchPermissionPolicy.DEFAULT).start(setup.request(member(), empty(), () -> current[0]));
        assertEquals(TeamResearchStart.Status.STALE_CONTEXT, result.status());
        assertEquals(0, setup.queue.size());
    }

    @Test void callbacksCannotHideAnInterveningQueueMutationOrReenterAdmission() {
        var callback = new Runnable[1];
        var setup = setup(0, context -> { if (context.playerId() != null) { callback[0].run(); } return true; });
        var start = setup.start(ResearchPermissionPolicy.DEFAULT);
        var request = setup.request(member(), empty(), () -> true);
        callback[0] = () -> setup.queue.enqueue(RESEARCH, 1, MEMBER, 1);
        assertEquals(TeamResearchStart.Status.STALE_CONTEXT, start.start(request).status());
        assertEquals(1, setup.queue.size());
        setup.queue.removeUnstarted(setup.queue.activeHead().orElseThrow().key());
        callback[0] = () -> start.start(request);
        assertThrows(IllegalStateException.class, () -> start.start(request));
        assertEquals(0, setup.queue.size());
    }

    @Test void duplicateAndBusyQueueAreNormalResultsWithoutConsumingAnything() {
        var calls = new int[1];
        var setup = setup(0, context -> { if (context.playerId() != null) { calls[0]++; } return true; });
        var start = setup.start(ResearchPermissionPolicy.DEFAULT);
        var request = setup.request(member(), empty(), () -> true);
        assertEquals(TeamResearchStart.Status.STARTED, start.start(request).status());
        assertEquals(TeamResearchStart.Status.DUPLICATE_TARGET, start.start(request).status());
        var pending = setup.queue.preparePrune(setup.view);
        assertEquals(TeamResearchStart.Status.QUEUE_BUSY, start.start(request).status());
        pending.rollback();
        assertEquals(1, setup.queue.size());
        assertEquals(1, calls[0]);
    }

    private static Setup setup(int tier, ResearchStartCondition condition) {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(RESEARCH).maxLevel(1)
            .deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).startCondition(condition)
            .stage(ResearchLevelStage.builder(1, 1).researchTier(tier).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze(resources);
        var view = new ResearchEligibility(registry).calculate("team", List.of(MEMBER), Map.of(MEMBER, new PlayerResearchState(MEMBER)),
            Object2IntMaps.emptyMap(), 0, Set.of(), Object2LongMaps.emptyMap());
        return new Setup(registry, resources, new TeamResearchQueue(registry, resources, "team"), view);
    }

    private static ResearchPermissionContext member() { return new ResearchPermissionContext(MEMBER, "team", null, "team", false, false, false); }
    private static TeamResearchStart.Environment empty() { return environment(0, 0, 0); }
    private static TeamResearchStart.Environment environment(int center, int dataCenter, int station) {
        Object2LongMap<NetworkResourceType> resources = Object2LongMaps.emptyMap();
        return new TeamResearchStart.Environment(center, dataCenter, station, resources);
    }

    private record Setup(ResearchRegistry registry, NetworkResourceRegistry resources, TeamResearchQueue queue, TeamResearchView view) {
        TeamResearchStart start(ResearchPermissionPolicy policy) { return new TeamResearchStart(registry, resources, policy); }
        TeamResearchStart.Request request(ResearchPermissionContext permission, TeamResearchStart.Environment environment, BooleanSupplier current) {
            return new TeamResearchStart.Request(queue, view, permission, RESEARCH, 1, environment, 2, current);
        }
    }
}
