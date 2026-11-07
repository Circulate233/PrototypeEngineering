package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkContribution;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolver;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NetworkRuntimeTest {
    private static final UUID OWNER = new UUID(1, 1);
    private static final ResourceLocation TYPE = new ResourceLocation("test", "machine");

    @Test void duplicateArrivalAndDepartureCannotReauthorizeAnOlderPublishedBatch() {
        try (var identities = new MachineIdentityRegistry(() -> { });
             var runtime = runtime(identities, resources(), NetworkRuntimeTest::team, () -> { })) {
            var world = new Object();
            var original = identity(identities, world, 1, 1);
            var device = runtime.register(original, () -> capture(original));
            assertTrue(runtime.refresh());
            assertTrue(device.allowsNetworkWork());
            var duplicate = identity(identities, world, 1, 2);
            assertNull(runtime.plan());
            assertFalse(device.allowsNetworkWork());
            duplicate.close();
            assertTrue(original.unique());
            assertNull(runtime.plan());
            assertFalse(device.allowsNetworkWork());
            assertTrue(runtime.refresh());
            assertTrue(device.allowsNetworkWork());
        }
    }

    @Test void lateOldUnloadCannotRevokeOrRemoveAReplacementBatch() {
        try (var identities = new MachineIdentityRegistry(() -> { });
             var runtime = runtime(identities, resources(), NetworkRuntimeTest::team, () -> { })) {
            var world = new Object();
            var oldIdentity = identity(identities, world, 1, 1);
            var old = runtime.register(oldIdentity, () -> capture(oldIdentity));
            assertTrue(runtime.refresh());
            var replacementIdentity = identity(identities, world, 2, 1);
            var replacement = runtime.register(replacementIdentity, () -> capture(replacementIdentity));
            assertTrue(runtime.refresh());
            var plan = runtime.plan();
            old.close();
            oldIdentity.close();
            assertSame(plan, runtime.plan());
            assertTrue(replacement.allowsNetworkWork());
            assertFalse(old.allowsNetworkWork());
            replacement.close();
            assertNull(runtime.plan());
            assertFalse(replacement.allowsNetworkWork());
        }
    }

    @Test void failuresInvalidProofsAndExplicitRevocationCannotLeavePartialOrOldAuthorization() {
        try (var identities = new MachineIdentityRegistry(() -> { })) {
            var failure = new IllegalStateException("team capture failed");
            var fail = new boolean[1];
            try (var runtime = runtime(identities, resources(), owner -> {
                if (fail[0]) { throw failure; }
                return team(owner);
            }, () -> { })) {
                var registration = identity(identities, new Object(), 1, 1);
                var valid = new boolean[] {true};
                var revokeInProof = new boolean[1];
                var device = runtime.register(registration, () -> new NetworkRuntime.Capture(snapshot(registration), () -> {
                    if (revokeInProof[0]) { runtime.revoke(); }
                    return valid[0];
                }));
                assertTrue(runtime.refresh());
                fail[0] = true;
                assertSame(failure, assertThrows(IllegalStateException.class, runtime::refresh));
                assertNull(runtime.plan());
                assertFalse(device.allowsNetworkWork());
                fail[0] = false;
                valid[0] = false;
                assertFalse(runtime.refresh());
                assertFalse(device.allowsNetworkWork());
                valid[0] = true;
                assertTrue(runtime.refresh());
                revokeInProof[0] = true;
                assertFalse(runtime.refresh());
                assertFalse(device.allowsNetworkWork());
                assertNull(runtime.plan());
            }
        }
    }

    @Test void mutationDuringCaptureAndForeignIdentityAreRejectedWithoutPublishing() {
        try (var identities = new MachineIdentityRegistry(() -> { });
             var foreignIdentities = new MachineIdentityRegistry(() -> { })) {
            var world = new Object();
            var original = identity(identities, world, 1, 1);
            var mutate = new boolean[1];
            try (var runtime = runtime(identities, resources(), owner -> {
                if (mutate[0]) {
                    try (var ignored = identity(identities, world, 1, 2)) { assertFalse(original.unique()); }
                }
                return team(owner);
            }, () -> { })) {
                var device = runtime.register(original, () -> capture(original));
                var foreign = identity(foreignIdentities, world, 3, 3);
                assertThrows(IllegalArgumentException.class, () -> runtime.register(foreign, () -> capture(foreign)));
                assertTrue(runtime.refresh());
                mutate[0] = true;
                assertFalse(runtime.refresh());
                assertTrue(original.unique());
                assertFalse(device.allowsNetworkWork());
                assertNull(runtime.plan());
            }
        }
    }

    @Test void workerReadsPublishedPermissionButCannotRefreshAndSessionClosurePermanentlyRevokes() throws Exception {
        var owner = Thread.currentThread();
        Runnable ownerCheck = () -> {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("Not owner"); }
        };
        try (var identities = new MachineIdentityRegistry(ownerCheck)) {
            var runtime = runtime(identities, resources(), NetworkRuntimeTest::team, ownerCheck);
            var original = identity(identities, new Object(), 1, 1);
            var device = runtime.register(original, () -> capture(original));
            assertTrue(runtime.refresh());
            var worker = new FutureTask<>(() -> {
                assertTrue(device.allowsNetworkWork());
                assertNotNull(runtime.plan());
                assertThrows(IllegalStateException.class, runtime::refresh);
                assertThrows(IllegalStateException.class, runtime::revoke);
                assertThrows(IllegalStateException.class, device::close);
                return true;
            });
            Thread.ofPlatform().start(worker);
            assertTrue(worker.get(5, TimeUnit.SECONDS));
            runtime.close();
            device.close();
            runtime.close();
            assertNull(runtime.plan());
            assertFalse(device.allowsNetworkWork());
            assertThrows(IllegalStateException.class, runtime::refresh);
            assertThrows(IllegalStateException.class, () -> runtime.register(original, () -> capture(original)));
        }
    }

    @Test void capturedSupplyModifiersAndParticipantsMustUseRegisteredMetadataBeforeReauthorizing() {
        var resources = new NetworkResourceRegistry();
        var registered = resources.register(new NetworkResourceType(new ResourceLocation("test", "supply"), false, 7));
        resources.freeze();
        // A loader may reconstruct an equal record; it must not need the exact registration object.
        var equalCopy = new NetworkResourceType(registered.id(), false, 7);
        var supplied = new NetworkResourceType[] {equalCopy};
        var modifier = new NetworkResourceModifier[] {NetworkResourceModifier.EMPTY};
        try (var identities = new MachineIdentityRegistry(() -> { });
             var runtime = runtime(identities, resources, NetworkRuntimeTest::team, () -> { })) {
            var identity = identity(identities, new Object(), 1, 1);
            var device = runtime.register(identity, () -> new NetworkRuntime.Capture(new NetworkDeviceSnapshot(
                identity.identity(), NetworkDeviceRole.NETWORK_CENTER, 1, true, identity.loadOrder(),
                Object2LongMaps.singleton(supplied[0], 14), modifier[0], 0, 0), () -> true));
            for (var invalid : List.of(new NetworkResourceType(registered.id(), false, 8),
                new NetworkResourceType(registered.id(), true, 7),
                new NetworkResourceType(new ResourceLocation("test", "unknown"), false, 7))) {
                assertTrue(runtime.refresh());
                assertTrue(device.allowsNetworkWork());
                var plan = runtime.plan();
                assertNotNull(plan);
                // Device totals may exceed the per-item limit.
                assertEquals(14, plan.teams().get("team").resourcesPerTick().getLong(registered));
                supplied[0] = invalid;
                assertThrows(IllegalArgumentException.class, runtime::refresh);
                assertNull(runtime.plan());
                assertFalse(device.allowsNetworkWork());
                supplied[0] = equalCopy;
            }
            var unknown = new NetworkResourceType(new ResourceLocation("test", "modifier_only"), false, 0);
            for (var invalidModifier : List.of(NetworkResourceModifier.builder().flat(unknown, 1).build(),
                NetworkResourceModifier.builder().multiplierPpm(unknown, 1).build())) {
                assertTrue(runtime.refresh());
                modifier[0] = invalidModifier;
                assertThrows(IllegalArgumentException.class, runtime::refresh);
                assertFalse(device.allowsNetworkWork());
                modifier[0] = NetworkResourceModifier.EMPTY;
            }
            assertTrue(runtime.refresh());
            assertThrows(IllegalArgumentException.class, () -> runtime.refresh(epoch -> List.of(new NetworkContribution(
                new UUID(2, 2), OWNER, epoch, 0, Object2LongMaps.singleton(unknown, 1)))));
            assertNull(runtime.plan());
            assertFalse(device.allowsNetworkWork());
            assertTrue(runtime.refresh());
            assertTrue(device.allowsNetworkWork());
        }
    }

    private static NetworkRuntime runtime(MachineIdentityRegistry identities, NetworkResourceRegistry resources,
            OwnerTeamResolver teams, Runnable ownerCheck) {
        var runtime = new NetworkRuntime(identities, resources, teams, ownerCheck);
        runtime.beginTick(0);
        return runtime;
    }

    private static NetworkResourceRegistry resources() {
        var registry = new NetworkResourceRegistry();
        registry.freeze();
        return registry;
    }

    private static MachineIdentityRegistry.Registration identity(MachineIdentityRegistry registry, Object world, long id, int x) {
        return registry.register(new PersistentMachineIdentity(new UUID(0, id), OWNER),
            new MachineIdentity(TYPE, 0, new BlockPos(x, 0, 0)), world, new Object());
    }

    private static NetworkRuntime.Capture capture(MachineIdentityRegistry.Registration registration) {
        return new NetworkRuntime.Capture(snapshot(registration), () -> true);
    }

    private static NetworkDeviceSnapshot snapshot(MachineIdentityRegistry.Registration registration) {
        return new NetworkDeviceSnapshot(registration.identity(), NetworkDeviceRole.NETWORK_CENTER, 1, true,
            registration.loadOrder(), Object2LongMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0);
    }

    private static OwnerTeamResolution team(UUID owner) {
        return OwnerTeamResolution.resolved(owner, "team", OwnerTeamType.SERVER, ObjectSet.of(owner), Optional.empty());
    }
}
