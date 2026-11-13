package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.LeaseStatus;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkBudgetRuntimeTest {
    private static final UUID OWNER = new UUID(1, 1);
    private static final ResourceLocation SOURCE = new ResourceLocation("test", "slot");
    private static final ResourceLocation MACHINE_TYPE = new ResourceLocation("test", "machine");

    private static NetworkRuntime.WorkPermit permit(NetworkRuntime.Registration registration) {
        return Objects.requireNonNull(registration.workPermit());
    }

    private static Reference2DoubleMap<NetworkResourceType> amount(long value) {
        return Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, value);
    }

    @Test
    void recapturesFailuresAndTeamReturnKeepOneTickBalanceAndOldCompensationCannotRefillNextTick() {
        try (var fixture = new Fixture()) {
            var runtime = fixture.runtime;
            runtime.beginTick(10);
            assertTrue(runtime.refresh());
            assertEquals(LeaseStatus.NOT_REQUIRED, permit(fixture.center).requestLease());
            var first = permit(fixture.first);
            assertEquals(LeaseStatus.GRANTED, first.requestLease());
            var spent = Objects.requireNonNull(first.reserve(SOURCE, 0, amount(6)));
            assertTrue(spent.commit());
            var pending = Objects.requireNonNull(first.reserve(SOURCE, 1, amount(2)));
            assertEquals(LeaseStatus.INSUFFICIENT_CAPACITY, permit(fixture.second).requestLease());
            assertEquals(10, first.numericResources().getDouble(ComputingResourceFixtures.COMPUTE));

            fixture.captureFailure = true;
            assertThrows(IllegalStateException.class, runtime::refresh);
            assertFalse(first.current());
            assertFalse(pending.commit());
            pending.rollback();
            fixture.captureFailure = false;
            assertTrue(runtime.refresh());
            var recaptured = permit(fixture.first);
            assertEquals(LeaseStatus.ALREADY_GRANTED, recaptured.requestLease());
            assertEquals(4, recaptured.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertNull(recaptured.reserve(SOURCE, 2, amount(5)));

            fixture.accepted = false;
            assertTrue(runtime.refresh());
            assertNull(fixture.first.workPermit());
            fixture.accepted = true;
            fixture.compute = 3;
            assertTrue(runtime.refresh());
            assertEquals(0, permit(fixture.first).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertNull(permit(fixture.first).reserve(SOURCE, 2, amount(1)));
            fixture.compute = 10;
            assertTrue(runtime.refresh());
            assertEquals(4, permit(fixture.first).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            fixture.compute = 100;
            assertTrue(runtime.refresh());
            var returned = permit(fixture.first);
            assertEquals(94, returned.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(100, returned.numericResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(LeaseStatus.INSUFFICIENT_CAPACITY, permit(fixture.second).requestLease());
            runtime.endTick();
            assertFalse(returned.current());
            assertEquals(LeaseStatus.EXPIRED, returned.requestLease());

            runtime.beginTick(11);
            assertTrue(runtime.refresh());
            var nextTick = permit(fixture.second);
            assertEquals(LeaseStatus.GRANTED, nextTick.requestLease());
            assertEquals(100, nextTick.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            spent.rollback();
            assertEquals(100, returned.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(100, nextTick.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }

    @Test
    void identityAbaAndChangedHostProofPreventOldReceiptCommitWithoutWaitingForAnotherCapture() {
        try (var fixture = new Fixture()) {
            var runtime = fixture.runtime;
            runtime.beginTick(0);
            assertTrue(runtime.refresh());
            var first = permit(fixture.first);
            var pending = Objects.requireNonNull(first.reserve(SOURCE, 0, amount(2)));
            var duplicate = fixture.identities.register(fixture.firstIdentity.identity(),
                new MachineIdentity(MACHINE_TYPE, 0, new BlockPos(99, 0, 0)), fixture.world, new Object());
            duplicate.close();
            assertTrue(fixture.firstIdentity.unique());
            assertFalse(first.current());
            assertFalse(pending.commit());
            pending.rollback();
            assertTrue(runtime.refresh());
            var current = permit(fixture.first);
            var invalidProof = Objects.requireNonNull(current.reserve(SOURCE, 1, amount(3)));
            fixture.centerProofValid = false;
            assertFalse(invalidProof.commit());
            assertNull(runtime.plan());
            invalidProof.rollback();
            fixture.centerProofValid = true;
            assertTrue(runtime.refresh());
            var afterProof = permit(fixture.first);
            var failedProof = Objects.requireNonNull(afterProof.reserve(SOURCE, 2, amount(4)));
            fixture.proofFailure = true;
            assertThrows(IllegalStateException.class, failedProof::commit);
            assertFalse(afterProof.current());
            assertNull(runtime.plan());
            failedProof.rollback();
            fixture.proofFailure = false;
            assertTrue(runtime.refresh());
            assertEquals(10, permit(fixture.first).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }

    @Test
    void schedulerMainPreparationsShareBudgetAndWorkersOnlyReadTheFinalPublication() {
        try (var fixture = new Fixture();
             var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            var runtime = fixture.runtime;
            var workerPermit = new NetworkRuntime.WorkPermit[1];
            var workerReceipt = new NetworkTeamBudget.Reservation[1];
            var preparations = new int[1];
            scheduler.submit(() -> {
                var receipt = Objects.requireNonNull(permit(fixture.first).reserve(SOURCE, 0, amount(6)));
                assertTrue(receipt.commit());
            }, ExecutionMode.MAIN_THREAD);
            scheduler.submit(() -> {
                var current = permit(fixture.first);
                assertEquals(LeaseStatus.ALREADY_GRANTED, current.requestLease());
                assertNull(current.reserve(SOURCE, 1, amount(6)));
                assertEquals(4, current.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            }, ExecutionMode.MAIN_THREAD);
            scheduler.submit(() -> {
                assertTrue(workerPermit[0].current());
                assertThrows(IllegalStateException.class, workerPermit[0]::requestLease);
                assertThrows(IllegalStateException.class, workerReceipt[0]::commit);
                assertThrows(IllegalStateException.class, runtime::endTick);
            }, ExecutionMode.CONCURRENT);
            runtime.beginTick(0);
            try {
                scheduler.tick(() -> {
                    assertTrue(runtime.refresh());
                    if (++preparations[0] == 3) {
                        workerPermit[0] = permit(fixture.first);
                        workerReceipt[0] = Objects.requireNonNull(workerPermit[0].reserve(SOURCE, 1, amount(4)));
                    }
                });
                assertEquals(3, preparations[0]);
                assertTrue(workerReceipt[0].commit());
                assertEquals(0, workerPermit[0].availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            } finally {
                runtime.endTick();
            }
            assertFalse(workerPermit[0].current());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Object world = new Object();
        private final MachineIdentityRegistry identities = new MachineIdentityRegistry(this::requireOwner);
        private final NetworkRuntime runtime;
        private final NetworkRuntime.Registration center;
        private final NetworkRuntime.Registration first;
        private final NetworkRuntime.Registration second;
        private final MachineIdentityRegistry.Registration firstIdentity;
        private long compute = 10;
        private boolean accepted = true;
        private boolean captureFailure;
        private boolean proofFailure;
        private boolean centerProofValid = true;

        private Fixture() {
            runtime = new NetworkRuntime(identities, ownerId -> accepted
                ? OwnerTeamResolution.resolved(ownerId, "team", OwnerTeamType.SERVER, ObjectSet.of(ownerId), Optional.empty())
                : OwnerTeamResolution.rejected(ownerId, OwnerTeamResolutionStatus.NO_TEAM, "owner left"), this::requireOwner);
            center = register(identity(1), NetworkDeviceRole.NETWORK_CENTER);
            firstIdentity = identity(2);
            first = register(firstIdentity, NetworkDeviceRole.DEVICE);
            second = register(identity(3), NetworkDeviceRole.DEVICE);
        }

        private MachineIdentityRegistry.Registration identity(int id) {
            return identities.register(new PersistentMachineIdentity(new UUID(0, id), OWNER),
                new MachineIdentity(MACHINE_TYPE, 0, new BlockPos(id, 0, 0)), world, new Object());
        }

        private NetworkRuntime.Registration register(MachineIdentityRegistry.Registration identity, NetworkDeviceRole role) {
            return runtime.register(identity, () -> {
                if (captureFailure) {
                    throw new IllegalStateException("capture failed");
                }
                var resources = new Reference2DoubleOpenHashMap<NetworkResourceType>();
                if (role == NetworkDeviceRole.NETWORK_CENTER) {
                    resources.put(ComputingResourceFixtures.COMPUTE, compute);
                }
                return new NetworkRuntime.Capture(new NetworkDeviceSnapshot(identity.identity(), role, 1, true,
                    identity.loadOrder(), role == NetworkDeviceRole.NETWORK_CENTER ? 1 : 0, resources, NetworkResourceModifier.EMPTY, 0, 0), () -> {
                    if (proofFailure) {
                        throw new IllegalStateException("proof failed");
                    }
                    return role != NetworkDeviceRole.NETWORK_CENTER || centerProofValid;
                });
            });
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Network runtime owner required");
            }
        }

        @Override
        public void close() {
            runtime.close();
            identities.close();
        }
    }
}
