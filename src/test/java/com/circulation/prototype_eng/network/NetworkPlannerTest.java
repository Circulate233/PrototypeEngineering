package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.network.NetworkContribution;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkPlanner;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.team.OwnerTeamResolver;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPlannerTest {
    private static final UUID OWNER = uuid(100);
    private static final UUID OTHER_OWNER = uuid(200);
    private static final NetworkResourceType COMPUTE = ComputingResourceFixtures.network(new ResourceLocation("test", "compute"));

    private static OwnerTeamResolver teams() {
        return owner -> OwnerTeamResolution.resolved(owner, owner.equals(OWNER) ? "team" : "other",
            OwnerTeamType.SERVER, ObjectSet.of(owner), null);
    }

    private static NetworkDeviceSnapshot device(long id, NetworkDeviceRole role, int level, boolean formed,
                                                long order, double amount, int research) {
        return new NetworkDeviceSnapshot(identity(id), role, level, formed, order, resources(amount),
            NetworkResourceModifier.EMPTY, role == NetworkDeviceRole.DATA_CENTER ? 10 : 0, research);
    }

    private static Reference2DoubleMap<NetworkResourceType> resources(double amount) {
        var result = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        result.put(COMPUTE, amount);
        return result;
    }

    private static PersistentMachineIdentity identity(long id) {
        return new PersistentMachineIdentity(uuid(id), OWNER);
    }

    private static UUID uuid(long id) {
        return new UUID(0, id);
    }

    @Test
    void selectsCentersAndDataCentersUsingPersistentIdentityForTiesIncludingFullStorage() {
        var devices = List.of(
            device(1, NetworkDeviceRole.NETWORK_CENTER, 1, true, 1, 0, 0),
            device(2, NetworkDeviceRole.NETWORK_CENTER, 2, true, 10, 0, 0),
            device(3, NetworkDeviceRole.NETWORK_CENTER, 2, true, 5, 0, 0),
            device(4, NetworkDeviceRole.DATA_CENTER, 1, true, 0, 0, 10),
            device(5, NetworkDeviceRole.DATA_CENTER, 2, true, 3, 0, 2),
            device(6, NetworkDeviceRole.DATA_CENTER, 2, true, 8, 0, 10),
            device(7, NetworkDeviceRole.DATA_CENTER, 2, true, 7, 0, 10),
            device(8, NetworkDeviceRole.DATA_CENTER, 3, true, 0, 0, 10),
            device(9, NetworkDeviceRole.COMPUTER, 3, true, 0, 100, 0),
            device(10, NetworkDeviceRole.RESEARCH_STATION, 1, false, 0, 100, 0),
            device(11, NetworkDeviceRole.DEVICE, 0, true, 0, 100, 0));
        var plan = NetworkPlanner.plan(NetworkPlanner.capture(1, devices, List.of(), teams()));
        var team = plan.teams().get("team");
        assertEquals(uuid(2), team.centerId());
        assertEquals(uuid(6), team.dataCenterId());
        assertEquals(NetworkWorkState.SAME_TYPE_CONFLICT, plan.workStates().get(uuid(1)));
        assertEquals(NetworkWorkState.SAME_TYPE_CONFLICT, plan.workStates().get(uuid(3)));
        assertEquals(NetworkWorkState.SAME_TYPE_CONFLICT, plan.workStates().get(uuid(7)));
        assertEquals(NetworkWorkState.CENTER_LEVEL_INSUFFICIENT, plan.workStates().get(uuid(8)));
        assertEquals(NetworkWorkState.CENTER_LEVEL_INSUFFICIENT, plan.workStates().get(uuid(9)));
        assertEquals(NetworkWorkState.UNFORMED, plan.workStates().get(uuid(10)));
        assertEquals(NetworkWorkState.ACTIVE_DEVICE, plan.workStates().get(uuid(11)));
        assertEquals(100, team.resourcesPerTick().getDouble(COMPUTE));
    }

    @Test
    void sumsSelectedCenterEligibleDevicesAndCurrentTeamParticipantsBeforeOneModifierApplication() {
        var modifier = NetworkResourceModifier.builder().flat(COMPUTE, 3).multiplierPpm(COMPUTE, 500_000).build();
        var center = new NetworkDeviceSnapshot(identity(1), NetworkDeviceRole.NETWORK_CENTER, 2, true, 0,
            resources(2), modifier, 0, 0);
        var devices = List.of(center,
            device(2, NetworkDeviceRole.COMPUTER, 2, true, 1, 4, 0),
            device(3, NetworkDeviceRole.COMPUTER, 3, true, 2, 1_000, 0),
            device(4, NetworkDeviceRole.COMPUTER, 0, false, 3, 1_000, 0),
            device(5, NetworkDeviceRole.NETWORK_CENTER, 1, true, 4, 1_000, 0),
            device(6, NetworkDeviceRole.DATA_CENTER, 0, true, 5, 1_000, 0),
            device(7, NetworkDeviceRole.RESEARCH_STATION, 1, true, 6, 1_000, 0));
        var contributions = List.of(new NetworkContribution(uuid(20), OWNER, 4, 1, resources(5)),
            new NetworkContribution(uuid(21), OTHER_OWNER, 4, 1, resources(1_000)));
        var plan = NetworkPlanner.plan(NetworkPlanner.capture(4, devices, contributions, teams()));
        assertEquals(3_021, plan.teams().get("team").resourcesPerTick().getDouble(COMPUTE));
        assertEquals(uuid(6), plan.teams().get("team").dataCenterId());
        assertEquals(NetworkWorkState.ACTIVE_DATA_CENTER, plan.workStates().get(uuid(6)));
        assertFalse(plan.teams().containsKey("other"));
    }

    @Test
    void captureResolvesEachOwnerOnceAndWorkerPlanningUsesOnlyFrozenInput() throws Exception {
        var ownerThread = Thread.currentThread();
        var calls = new Object2IntOpenHashMap<UUID>();
        OwnerTeamResolver resolver = owner -> {
            assertSame(ownerThread, Thread.currentThread());
            calls.addTo(owner, 1);
            return teams().resolve(owner);
        };
        var quantities = resources(2);
        var devices = new ObjectArrayList<NetworkDeviceSnapshot>();
        devices.add(new NetworkDeviceSnapshot(identity(1), NetworkDeviceRole.NETWORK_CENTER, 1, true, 0,
            quantities, NetworkResourceModifier.EMPTY, 0, 0));
        devices.add(device(2, NetworkDeviceRole.COMPUTER, 1, true, 1, 3, 0));
        var captured = NetworkPlanner.capture(1, devices,
            List.of(new NetworkContribution(uuid(30), OWNER, 1, 0, resources(4))), resolver);
        var worker = new FutureTask<>(() -> NetworkPlanner.plan(captured));
        Thread.ofPlatform().start(worker);
        var plan = worker.get(5, TimeUnit.SECONDS);
        assertEquals(9, plan.teams().get("team").resourcesPerTick().getDouble(COMPUTE));
        assertEquals(1, calls.getInt(OWNER));
        assertEquals(1, calls.size());
        assertEquals(1, plan.epoch());
    }


    @Test
    void noCenterAndRejectedOwnerKeepDistinctStatesAndDoNotCreateCapacity() {
        var orphan = device(1, NetworkDeviceRole.COMPUTER, 0, true, 0, 100, 0);
        var unformed = device(2, NetworkDeviceRole.DATA_CENTER, 0, false, 1, 100, 0);
        var rejected = new NetworkDeviceSnapshot(new PersistentMachineIdentity(uuid(3), OTHER_OWNER),
            NetworkDeviceRole.COMPUTER, 0, false, 2, resources(100), NetworkResourceModifier.EMPTY, 0, 0);
        OwnerTeamResolver resolver = owner -> owner.equals(OTHER_OWNER)
            ? OwnerTeamResolution.rejected(owner, OwnerTeamResolutionStatus.NO_TEAM, "No current team") : teams().resolve(owner);
        var plan = NetworkPlanner.plan(NetworkPlanner.capture(1, List.of(orphan, unformed, rejected),
            List.of(new NetworkContribution(uuid(30), OWNER, 1, 0, resources(500))), resolver));
        assertEquals(NetworkWorkState.NO_ACTIVE_CENTER, plan.workStates().get(uuid(1)));
        assertEquals(NetworkWorkState.UNFORMED, plan.workStates().get(uuid(2)));
        assertEquals(NetworkWorkState.OWNER_TEAM_REJECTED, plan.workStates().get(uuid(3)));
        assertFalse(plan.teamIds().containsKey(uuid(3)));
        assertTrue(plan.teams().isEmpty());
    }


    @Test
    void unmodifiedSupplyAcceptsDoubleMaxWhileAccumulationRejectsOverflow() {
        var center = device(1, NetworkDeviceRole.NETWORK_CENTER, 1, true, 0, Double.MAX_VALUE, 0);
        var computer = device(2, NetworkDeviceRole.COMPUTER, 1, true, 1, Double.MAX_VALUE, 0);
        var overflow = NetworkPlanner.capture(1, List.of(center, computer), List.of(), teams());
        assertThrows(ArithmeticException.class, () -> NetworkPlanner.plan(overflow));
        var unmodified = NetworkPlanner.capture(1, List.of(center), List.of(), teams());
        assertEquals(Double.MAX_VALUE, NetworkPlanner.plan(unmodified).teams().get("team").resourcesPerTick().getDouble(COMPUTE));
    }
}
