package com.circulation.prototype_eng.network;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.NetworkCenterController;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolver;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRuntimeTest {
    private static final UUID OWNER = new UUID(1, 1);
    private static final ResourceLocation TYPE = new ResourceLocation("test", "machine");

    private static NetworkRuntime runtime(MachineIdentityRegistry identities,
                                          OwnerTeamResolver teams, Runnable ownerCheck) {
        var runtime = new NetworkRuntime(identities, teams, ownerCheck);
        runtime.beginTick(0);
        return runtime;
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
            registration.loadOrder(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0);
    }

    private static OwnerTeamResolution team(UUID owner) {
        return OwnerTeamResolution.resolved(owner, "team", OwnerTeamType.SERVER, ObjectSet.of(owner), Optional.empty());
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
        // Successful refresh publishes the plan and all three active clients' permits.
    void activeCenterPaysOncePerTickAndAnUnpoweredCenterYieldsToTheNextCandidate() {
        try (var identities = new MachineIdentityRegistry(() -> {
        });
             var runtime = runtime(identities, NetworkRuntimeTest::team, () -> {
             })) {
            var type = MachineType.builder(TYPE, GuiText.ResourceStorage, TYPE).build();
            var high = new NetworkCenterController(type, 2, 2, 4);
            var low = new NetworkCenterController(type, 1, 1, 3);
            var highPower = new EnergyBuffer(100, () -> {
            });
            var lowPower = new EnergyBuffer(100, () -> {
            });
            highPower.receiveEnergy(7, Actionable.MODULATE);
            lowPower.receiveEnergy(20, Actionable.MODULATE);
            high.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(highPower), List.of()));
            low.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(lowPower), List.of()));
            var world = new Object();
            var highIdentity = identity(identities, world, 1, 1);
            var lowIdentity = identity(identities, world, 2, 2);
            var valid = new boolean[]{true};
            var highRole = runtime.register(highIdentity, () -> new NetworkRuntime.Capture(
                new NetworkDeviceSnapshot(highIdentity.identity(), NetworkDeviceRole.NETWORK_CENTER, 2, true,
                    highIdentity.loadOrder(), high.providedConcurrency(), high.networkResources(), NetworkResourceModifier.EMPTY, 0, 0), () -> valid[0], high::reservePower));
            var lowRole = runtime.register(lowIdentity, () -> new NetworkRuntime.Capture(
                new NetworkDeviceSnapshot(lowIdentity.identity(), NetworkDeviceRole.NETWORK_CENTER, 1, true,
                    lowIdentity.loadOrder(), low.providedConcurrency(), low.networkResources(), NetworkResourceModifier.EMPTY, 0, 0), () -> true, low::reservePower));
            var clients = new NetworkRuntime.Registration[3];
            for (int i = 0; i < clients.length; i++) {
                clients[i] = runtime.register(identity(identities, world, i + 3, i + 3), registration -> new NetworkRuntime.Capture(
                    new NetworkDeviceSnapshot(registration.identity(), NetworkDeviceRole.DEVICE, 0, true,
                        registration.loadOrder(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0), () -> true));
            }
            assertTrue(runtime.refresh());
            assertTrue(highRole.allowsNetworkWork());
            assertEquals(3, highPower.storedEnergy());
            assertEquals(20, lowPower.storedEnergy());
            assertEquals(2, runtime.plan().teams().get("team").concurrencyPerTick());
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, clients[0].workPermit().requestLease());
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, clients[1].workPermit().requestLease());
            assertEquals(NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY, clients[2].workPermit().requestLease());
            assertTrue(runtime.refresh());
            assertEquals(3, highPower.storedEnergy());
            assertEquals(NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY, clients[2].workPermit().requestLease());
            runtime.endTick();
            runtime.beginTick(1);
            assertTrue(runtime.refresh());
            assertFalse(highRole.allowsNetworkWork());
            assertTrue(lowRole.allowsNetworkWork());
            assertEquals(17, lowPower.storedEnergy());
            assertEquals(1, runtime.plan().teams().get("team").concurrencyPerTick());
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, clients[0].workPermit().requestLease());
            assertEquals(NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY, clients[1].workPermit().requestLease());
            highPower.receiveEnergy(10, Actionable.MODULATE);
            runtime.endTick();
            runtime.beginTick(2);
            valid[0] = false;
            assertFalse(runtime.refresh());
            assertEquals(13, highPower.storedEnergy());
            valid[0] = true;
            assertTrue(runtime.refresh());
            assertEquals(9, highPower.storedEnergy());
        }
    }

    @Test
    void duplicateArrivalAndDepartureCannotReauthorizeAnOlderPublishedBatch() {
        try (var identities = new MachineIdentityRegistry(() -> {
        });
             var runtime = runtime(identities, NetworkRuntimeTest::team, () -> {
             })) {
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

    @Test
    void lateOldUnloadCannotRevokeOrRemoveAReplacementBatch() {
        try (var identities = new MachineIdentityRegistry(() -> {
        });
             var runtime = runtime(identities, NetworkRuntimeTest::team, () -> {
             })) {
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

    @Test
    void failuresInvalidProofsAndExplicitRevocationCannotLeavePartialOrOldAuthorization() {
        try (var identities = new MachineIdentityRegistry(() -> {
        })) {
            var failure = new IllegalStateException("team capture failed");
            var fail = new boolean[1];
            try (var runtime = runtime(identities, owner -> {
                if (fail[0]) {
                    throw failure;
                }
                return team(owner);
            }, () -> {
            })) {
                var registration = identity(identities, new Object(), 1, 1);
                var valid = new boolean[]{true};
                var revokeInProof = new boolean[1];
                var device = runtime.register(registration, () -> new NetworkRuntime.Capture(snapshot(registration), () -> {
                    if (revokeInProof[0]) {
                        runtime.revoke();
                    }
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

    @Test
    void mutationDuringCaptureAndForeignIdentityAreRejectedWithoutPublishing() {
        try (var identities = new MachineIdentityRegistry(() -> {
        });
             var foreignIdentities = new MachineIdentityRegistry(() -> {
             })) {
            var world = new Object();
            var original = identity(identities, world, 1, 1);
            var mutate = new boolean[1];
            try (var runtime = runtime(identities, owner -> {
                if (mutate[0]) {
                    try (var ignored = identity(identities, world, 1, 2)) {
                        assertFalse(original.unique());
                    }
                }
                return team(owner);
            }, () -> {
            })) {
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

    @Test
    void workerReadsPublishedPermissionButCannotRefreshAndSessionClosurePermanentlyRevokes() throws Exception {
        var owner = Thread.currentThread();
        Runnable ownerCheck = () -> {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Not owner");
            }
        };
        try (var identities = new MachineIdentityRegistry(ownerCheck)) {
            var runtime = runtime(identities, NetworkRuntimeTest::team, ownerCheck);
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

}
