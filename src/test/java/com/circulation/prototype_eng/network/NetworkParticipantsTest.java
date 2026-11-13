package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkParticipant;
import com.circulation.prototype_eng.api.network.NetworkParticipant.Snapshot;
import com.circulation.prototype_eng.api.network.NetworkParticipants;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static com.circulation.prototype_eng.api.network.NetworkParticipant.Cancellation.CANCELLED;
import static com.circulation.prototype_eng.api.network.NetworkParticipant.Cancellation.RETRYABLE;
import static com.circulation.prototype_eng.api.network.NetworkParticipant.Cancellation.UNKNOWN;
import static com.circulation.prototype_eng.api.network.NetworkParticipants.PlanStatus.ACCEPTED;
import static com.circulation.prototype_eng.api.network.NetworkParticipants.PlanStatus.DUPLICATE_PLAN;
import static com.circulation.prototype_eng.api.network.NetworkParticipants.PlanStatus.SOURCE_UNSUPPORTED;
import static com.circulation.prototype_eng.api.network.NetworkParticipants.PlanStatus.STALE_SNAPSHOT;
import static com.circulation.prototype_eng.api.network.NetworkParticipants.WorkResult.ALREADY_CANCELLED;
import static com.circulation.prototype_eng.api.network.NetworkParticipants.WorkResult.ALREADY_COMMITTED;
import static com.circulation.prototype_eng.api.network.NetworkParticipants.WorkResult.CLEANUP_PENDING;
import static com.circulation.prototype_eng.api.network.NetworkParticipants.WorkResult.COMMITTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkParticipantsTest {
    private static final UUID CENTER_OWNER = new UUID(1, 1);
    private static final UUID EXTERNAL_OWNER = new UUID(1, 2);
    private static final UUID EXTERNAL = new UUID(0, 100);
    private static final ResourceLocation TYPE = new ResourceLocation("test", "machine");
    private static final ResourceLocation WORK = new ResourceLocation("test", "work");
    private static final ResourceLocation SOURCE = new ResourceLocation("test", "source");

    private static Reference2DoubleMap<NetworkResourceType> amounts(long compute, long qubit) {
        var result = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        if (compute > 0) result.put(ComputingResourceFixtures.COMPUTE, compute);
        if (qubit > 0) result.put(ComputingResourceFixtures.QUBIT, qubit);
        return result;
    }

    @Test
    void defaultRefreshContributesRealSupplyAndWorkUsesDeclaredSourcesAndSharedLease() {
        try (var fixture = new Fixture()) {
            fixture.start(10);
            assertEquals(10, fixture.available());
            var snapshot = fixture.snapshot();
            assertEquals(SOURCE_UNSUPPORTED, fixture.registration.plan(new UUID(5, 0), snapshot, WORK, WORK).status());
            assertEquals(10, fixture.available());
            var first = fixture.plan(1);
            var second = fixture.plan(2);
            assertEquals(4, fixture.available());
            assertEquals(NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY, fixture.otherPermit().requestLease());
            assertEquals(COMMITTED, first.commit());
            assertEquals(ALREADY_COMMITTED, first.commit());
            assertEquals(ALREADY_COMMITTED, first.cancel());
            assertEquals(COMMITTED, second.commit());
            assertEquals(2, fixture.participant.effects);
            assertEquals(2, fixture.participant.commits);
            assertEquals(SOURCE, Objects.requireNonNull(fixture.participant.lastCommand.materialSource()).id());
            assertEquals(DUPLICATE_PLAN, fixture.registration.plan(new UUID(5, 1), snapshot, WORK, SOURCE).status());
        }
    }

    @Test
    void completeVectorRejectsWithoutPartialSpendAndContributionReceivesCenterModifierOnce() {
        try (var fixture = new Fixture()) {
            fixture.modifier = NetworkResourceModifier.builder().multiplierPpm(ComputingResourceFixtures.COMPUTE, 1_000_000).build();
            fixture.participant.cost = amounts(3, 50);
            fixture.start(0);
            assertEquals(20, fixture.available());
            var rejected = fixture.registration.plan(new UUID(5, 0), fixture.snapshot(), WORK, null);
            assertEquals(NetworkParticipants.PlanStatus.RESOURCE_DENIED, rejected.status());
            assertEquals(20, fixture.available());
            assertEquals(0, fixture.participant.commits);
            assertTrue(fixture.runtime.participants().pendingWork().isEmpty());
        }
    }

    @Test
    void rejectedCommitRefundsAndChangedAuthorityAfterCallbackCompensatesItsEffects() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var rejected = fixture.plan(1);
            fixture.participant.reject = true;
            assertEquals(NetworkParticipants.WorkResult.CANCELLED, rejected.commit());
            assertEquals(0, fixture.participant.effects);
            assertEquals(0, fixture.participant.cancels);
            assertEquals(10, fixture.available());
            fixture.participant.reject = false;
            var invalidated = fixture.plan(2);
            var original = fixture.centerPermit();
            fixture.participant.duringCommit = () -> fixture.participant.proof = false;
            assertEquals(NetworkParticipants.WorkResult.CANCELLED, invalidated.commit());
            assertEquals(0, fixture.participant.effects);
            assertEquals(1, fixture.participant.cancels);
            assertEquals(10, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertNull(fixture.runtime.plan());
        }
    }

    @Test
    void refreshCancelsOldUncommittedPlansWithoutRefillingCommittedWorkAndCompensationUsesOldBook() {
        try (var fixture = new Fixture()) {
            fixture.start(3);
            var originalPermit = fixture.centerPermit();
            var completed = fixture.plan(1);
            assertEquals(COMMITTED, completed.commit());
            var pending = fixture.plan(2);
            var oldSnapshot = fixture.snapshot();
            assertTrue(fixture.runtime.refresh());
            assertEquals(NetworkParticipants.WorkState.CANCELLED, pending.state());
            assertEquals(STALE_SNAPSHOT, fixture.registration.plan(new UUID(5, 3), oldSnapshot, WORK, null).status());
            assertEquals(7, fixture.available());
            fixture.runtime.endTick();
            fixture.start(4);
            assertEquals(10, fixture.available());
            assertEquals(NetworkParticipants.WorkResult.CANCELLED, completed.compensate());
            assertEquals(ALREADY_CANCELLED, completed.compensate());
            assertEquals(10, originalPermit.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(10, fixture.available());
        }
    }

    @Test
    void ownerTeamChangeAndDeviceInactivityRevokeWorkBeforeCallbacks() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var work = fixture.plan(1);
            fixture.otherTeam = true;
            assertEquals(NetworkParticipants.WorkResult.CANCELLED, work.commit());
            assertEquals(0, fixture.participant.commits);
            assertNull(fixture.runtime.plan());
            assertTrue(fixture.runtime.refresh());
            assertEquals(NetworkParticipants.PlanStatus.OWNER_TEAM_REJECTED,
                fixture.registration.plan(new UUID(5, 2), fixture.snapshot(), WORK, null).status());
            fixture.otherTeam = false;
            assertTrue(fixture.runtime.refresh());
            var next = fixture.plan(3);
            fixture.participant.state = NetworkParticipant.State.INACTIVE;
            assertEquals(NetworkParticipants.WorkResult.CANCELLED, next.commit());
            assertTrue(fixture.runtime.refresh());
            assertNull(fixture.registration.snapshot());
            assertEquals(0, fixture.participant.commits);
        }
    }

    @Test
    void lostSupplierProofRevokesOrdinaryMachineReceiptsToo() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var ordinary = Objects.requireNonNull(fixture.centerPermit().reserve(WORK,
                Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 8)));
            fixture.participant.proof = false;
            assertFalse(ordinary.commit());
            assertNull(fixture.runtime.plan());
            ordinary.rollback();
            fixture.participant.proof = true;
            assertTrue(fixture.runtime.refresh());
            assertEquals(10, fixture.available());
        }
    }

    @Test
    void unknownCancellationRetainsOriginalBudgetAcrossTickAndQueriesBeforeRetry() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var original = fixture.centerPermit();
            var work = fixture.plan(1);
            fixture.participant.cancellation = UNKNOWN;
            assertEquals(CLEANUP_PENDING, work.cancel());
            assertEquals(7, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(CLEANUP_PENDING, work.cancel());
            assertEquals(1, fixture.participant.cancels);
            fixture.runtime.endTick();
            fixture.runtime.beginTick(1);
            assertFalse(fixture.runtime.refresh());
            assertEquals(List.of(work), fixture.runtime.participants().pendingWork());
            fixture.participant.resolution = CANCELLED;
            assertTrue(fixture.runtime.refresh());
            assertEquals(1, fixture.participant.cancels);
            assertEquals(10, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(10, fixture.available());
        }
    }

    @Test
    void throwingCommitAndCancellationKeepEvidenceAndNeverReplayCommit() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var work = fixture.plan(1);
            var original = fixture.centerPermit();
            fixture.participant.commitFailure = new IllegalStateException("partial external commit");
            fixture.participant.cancelFailure = new IllegalStateException("unknown external cancellation");
            assertSame(fixture.participant.commitFailure, assertThrows(IllegalStateException.class, work::commit));
            assertSame(fixture.participant.commitFailure, work.failure());
            assertEquals(1, fixture.participant.effects);
            assertNull(fixture.runtime.plan());
            assertEquals(7, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(CLEANUP_PENDING, work.commit());
            assertEquals(1, fixture.participant.commits);
            assertEquals(1, fixture.participant.cancels);
            fixture.participant.cancelFailure = null;
            fixture.participant.resolution = RETRYABLE;
            assertEquals(NetworkParticipants.WorkResult.CANCELLED, work.cancel());
            assertEquals(0, fixture.participant.effects);
            assertEquals(2, fixture.participant.cancels);
            assertEquals(1, fixture.participant.commits);
            assertTrue(fixture.runtime.refresh());
            assertEquals(10, fixture.available());
        }
    }

    @Test
    void exactOldHandleCannotDetachReplacementAndUnknownCleanupRemainsOwned() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var old = fixture.registration;
            var oldParticipant = fixture.participant;
            var work = fixture.plan(1);
            oldParticipant.cancellation = UNKNOWN;
            old.close();
            assertEquals(List.of(work), fixture.runtime.participants().pendingWork());
            fixture.participant = new Participant();
            fixture.registration = fixture.runtime.participants().register(fixture.participant);
            assertFalse(fixture.runtime.refresh());
            oldParticipant.resolution = CANCELLED;
            assertTrue(fixture.runtime.refresh());
            var published = fixture.runtime.plan();
            old.close();
            assertSame(published, fixture.runtime.plan());
            assertEquals(COMMITTED, fixture.plan(2).commit());
            assertEquals(1, fixture.participant.effects);
        }
    }

    @Test
    @SuppressWarnings("resource")
        // Registration is expected to fail before returning a handle.
    void closeAttemptsEveryCleanupAndRetainsUnresolvedWorkForAnExplicitRetry() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var first = fixture.plan(1);
            var second = fixture.plan(2);
            fixture.participant.cancelFailure = new IllegalStateException("unresolved device");
            assertThrows(IllegalStateException.class, fixture.runtime::close);
            assertEquals(2, fixture.participant.cancels);
            assertEquals(2, fixture.runtime.participants().pendingWork().size());
            assertThrows(IllegalStateException.class, () -> fixture.runtime.participants().register(new Participant()));
            assertThrows(IllegalStateException.class, fixture.runtime::close);
            assertEquals(2, fixture.participant.cancels);
            fixture.participant.resolution = CANCELLED;
            fixture.runtime.close();
            assertEquals(NetworkParticipants.WorkState.CANCELLED, first.state());
            assertEquals(NetworkParticipants.WorkState.CANCELLED, second.state());
            assertTrue(fixture.runtime.participants().pendingWork().isEmpty());
        }
    }

    @Test
    void successfulCloseStillSettlesLaterExplicitCompensationAgainstOriginalBookOnlyOnce() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var original = fixture.centerPermit();
            var work = fixture.plan(1);
            assertEquals(COMMITTED, work.commit());
            fixture.runtime.close();
            assertThrows(IllegalStateException.class, () -> fixture.runtime.beginTick(1));
            fixture.participant.cancellation = UNKNOWN;
            assertEquals(CLEANUP_PENDING, work.compensate());
            assertEquals(7, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertThrows(IllegalStateException.class, fixture.runtime::close);
            assertEquals(List.of(work), fixture.runtime.participants().pendingWork());
            assertEquals(1, fixture.participant.cancels);
            fixture.participant.completeCancellation(work.command());
            fixture.runtime.close();
            assertEquals(NetworkParticipants.WorkState.CANCELLED, work.state());
            assertEquals(0, fixture.participant.effects);
            assertEquals(10, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            fixture.runtime.close();
            assertEquals(ALREADY_CANCELLED, work.compensate());
            assertEquals(10, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(1, fixture.participant.cancels);
            assertTrue(fixture.runtime.participants().pendingWork().isEmpty());
        }
    }

    @Test
    void firstProofFailureRecordsOriginalFaultAndImmediatelyCancelsUncommittedWork() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var original = fixture.centerPermit();
            var work = fixture.plan(1);
            var failure = new IllegalStateException("first proof failed");
            fixture.participant.proofFailure = failure;
            assertSame(failure, assertThrows(IllegalStateException.class, work::commit));
            assertSame(failure, work.failure());
            assertEquals(NetworkParticipants.WorkState.CANCELLED, work.state());
            assertEquals(0, fixture.participant.commits);
            assertEquals(1, fixture.participant.cancels);
            assertEquals(10, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertNull(fixture.runtime.plan());
            assertTrue(fixture.runtime.participants().pendingWork().isEmpty());
            assertEquals(ALREADY_CANCELLED, work.commit());
            fixture.participant.proofFailure = null;
        }
    }

    @Test
    void firstOwnerResolutionFailureWithUnknownCancelRetainsOriginalBudgetUntilConfirmed() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var original = fixture.centerPermit();
            var work = fixture.plan(1);
            var failure = new IllegalStateException("first owner resolution failed");
            fixture.ownerFailure = failure;
            fixture.participant.cancellation = UNKNOWN;
            assertSame(failure, assertThrows(IllegalStateException.class, work::commit));
            assertSame(failure, work.failure());
            assertEquals(NetworkParticipants.WorkState.CANCELLING, work.state());
            assertEquals(0, fixture.participant.commits);
            assertEquals(1, fixture.participant.cancels);
            assertEquals(7, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(List.of(work), fixture.runtime.participants().pendingWork());
            assertEquals(CLEANUP_PENDING, work.commit());
            assertEquals(1, fixture.participant.cancels);
            fixture.ownerFailure = null;
            fixture.participant.completeCancellation(work.command());
            assertTrue(fixture.runtime.participants().retryCleanup());
            assertEquals(10, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(ALREADY_CANCELLED, work.cancel());
            assertEquals(0, fixture.participant.commits);
        }
    }

    @Test
    void firstProofAndCleanupExceptionsRetainBothFailuresWithoutReplayingCommit() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var original = fixture.centerPermit();
            var work = fixture.plan(1);
            var failure = new IllegalStateException("proof failure");
            var cleanup = new IllegalStateException("cleanup failure");
            fixture.participant.proofFailure = failure;
            fixture.participant.cancelFailure = cleanup;
            assertSame(failure, assertThrows(IllegalStateException.class, work::commit));
            assertSame(failure, work.failure());
            assertEquals(1, failure.getSuppressed().length);
            assertSame(cleanup, failure.getSuppressed()[0]);
            assertEquals(0, fixture.participant.commits);
            assertEquals(7, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(CLEANUP_PENDING, work.commit());
            assertEquals(1, fixture.participant.cancels);
            fixture.participant.proofFailure = null;
            fixture.participant.cancelFailure = null;
            fixture.participant.resolution = RETRYABLE;
            assertEquals(NetworkParticipants.WorkResult.CANCELLED, work.cancel());
            assertEquals(10, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(0, fixture.participant.commits);
            assertEquals(2, fixture.participant.cancels);
        }
    }

    @Test
    void snapshotsAreWorkerValuesButWorkAndRegistryOperationsRequireOwner() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            var snapshot = fixture.snapshot();
            var work = fixture.plan(1);
            var worker = new FutureTask<>(() -> {
                assertEquals(8, snapshot.resources().getDouble(ComputingResourceFixtures.COMPUTE));
                assertThrows(IllegalStateException.class, work::commit);
                assertThrows(IllegalStateException.class, fixture.registration::close);
                assertThrows(IllegalStateException.class, fixture.runtime.participants()::retryCleanup);
                return true;
            });
            Thread.ofPlatform().start(worker);
            assertTrue(worker.get(5, TimeUnit.SECONDS));
            assertEquals(COMMITTED, work.commit());
        }
    }

    @Test
    void repeatedVersionCannotHideChangedCapabilitiesAndFailureRevokesPriorPublication() {
        try (var fixture = new Fixture()) {
            fixture.start(0);
            fixture.participant.versionStep = 0;
            assertTrue(fixture.runtime.refresh());
            fixture.participant.supply = 9;
            assertThrows(IllegalArgumentException.class, fixture.runtime::refresh);
            assertNull(fixture.runtime.plan());
            fixture.participant.versionStep = 1;
            assertTrue(fixture.runtime.refresh());
            assertEquals(11, fixture.available());
            fixture.runtime.endTick();
            fixture.start(1);
            assertEquals(11, fixture.available());
        }
    }

    private static final class Participant implements NetworkParticipant {
        private final ObjectOpenHashSet<UUID> applied = new ObjectOpenHashSet<>();
        private State state = State.ACTIVE;
        private long version;
        private long versionStep = 1;
        private long supply = 8;
        private boolean proof = true;
        private Reference2DoubleMap<NetworkResourceType> cost = amounts(3, 1);
        private Cancellation cancellation = CANCELLED;
        private Cancellation resolution = UNKNOWN;
        private RuntimeException commitFailure;
        private RuntimeException cancelFailure;
        private RuntimeException proofFailure;
        private boolean reject;
        private Runnable duringCommit = () -> {
        };
        private int effects;
        private int commits;
        private int cancels;
        private Command lastCommand;

        @Override
        public UUID participantId() {
            return EXTERNAL;
        }

        @Override
        public UUID ownerId() {
            return EXTERNAL_OWNER;
        }

        @Override
        public Kind kind() {
            return Kind.EXTERNAL_MACHINE;
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public Capture capture(long tick, long epoch) {
            version += versionStep;
            var snapshot = new Snapshot(EXTERNAL, EXTERNAL_OWNER, kind(), tick, epoch, version, amounts(supply, 4),
                List.of(new WorkType(WORK, cost)), List.of(new MaterialSource(SOURCE, "external-source")));
            return new Capture(snapshot, () -> {
                if (proofFailure != null) throw proofFailure;
                return proof;
            });
        }

        @Override
        public Commit commit(Command command) {
            lastCommand = command;
            commits++;
            if (reject) return Commit.REJECTED;
            applied.add(command.planId());
            effects = applied.size();
            duringCommit.run();
            if (commitFailure != null) throw commitFailure;
            return Commit.COMMITTED;
        }

        @Override
        public Cancellation cancel(Command command) {
            lastCommand = command;
            cancels++;
            if (cancelFailure != null) throw cancelFailure;
            if (cancellation == CANCELLED) applied.remove(command.planId());
            effects = applied.size();
            return cancellation;
        }

        @Override
        public Cancellation cancellationState(Command command) {
            lastCommand = command;
            return resolution;
        }

        private void completeCancellation(Command command) {
            applied.remove(command.planId());
            effects = applied.size();
            resolution = CANCELLED;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Thread ownerThread = Thread.currentThread();
        private final MachineIdentityRegistry identities = new MachineIdentityRegistry(this::requireOwner);
        private final NetworkRuntime runtime;
        private final NetworkRuntime.Registration center;
        private final NetworkRuntime.Registration other;
        private Participant participant = new Participant();
        private NetworkParticipants.Registration registration;
        private NetworkResourceModifier modifier = NetworkResourceModifier.EMPTY;
        private boolean otherTeam;
        private RuntimeException ownerFailure;

        private Fixture() {
            runtime = new NetworkRuntime(identities, this::resolve, this::requireOwner);
            center = device(1, NetworkDeviceRole.NETWORK_CENTER);
            other = device(2, NetworkDeviceRole.DEVICE);
            registration = runtime.participants().register(participant);
        }

        private OwnerTeamResolution resolve(UUID owner) {
            if (owner.equals(EXTERNAL_OWNER) && ownerFailure != null) throw ownerFailure;
            if (otherTeam && owner.equals(EXTERNAL_OWNER)) {
                return OwnerTeamResolution.resolved(owner, "other", OwnerTeamType.SERVER, ObjectSet.of(owner), Optional.empty());
            }
            if (!owner.equals(CENTER_OWNER) && !owner.equals(EXTERNAL_OWNER)) {
                return OwnerTeamResolution.rejected(owner, OwnerTeamResolutionStatus.OWNER_UNKNOWN, "unknown owner");
            }
            return OwnerTeamResolution.resolved(owner, "team", OwnerTeamType.SERVER,
                otherTeam ? ObjectSet.of(CENTER_OWNER) : ObjectSet.of(CENTER_OWNER, EXTERNAL_OWNER), Optional.empty());
        }

        private NetworkRuntime.Registration device(int id, NetworkDeviceRole role) {
            var identity = identities.register(new PersistentMachineIdentity(new UUID(0, id), CENTER_OWNER),
                new MachineIdentity(TYPE, 0, new BlockPos(id, 0, 0)), this, new Object());
            return runtime.register(identity, () -> {
                var supply = new Reference2DoubleOpenHashMap<NetworkResourceType>();
                if (role == NetworkDeviceRole.NETWORK_CENTER) {
                    supply.put(ComputingResourceFixtures.COMPUTE, 2);
                    supply.put(ComputingResourceFixtures.QUBIT, 2);
                }
                return new NetworkRuntime.Capture(new NetworkDeviceSnapshot(identity.identity(), role, 1, true,
                    identity.loadOrder(), role == NetworkDeviceRole.NETWORK_CENTER ? 1 : 0, supply, modifier, 0, 0), () -> true);
            });
        }

        private void start(long tick) {
            runtime.beginTick(tick);
            assertTrue(runtime.refresh());
        }

        private Snapshot snapshot() {
            return Objects.requireNonNull(registration.snapshot());
        }

        private NetworkParticipants.Work plan(long id) {
            var result = registration.plan(new UUID(5, id), snapshot(), WORK, SOURCE);
            assertEquals(ACCEPTED, result.status());
            return Objects.requireNonNull(result.work());
        }

        private NetworkRuntime.WorkPermit centerPermit() {
            return Objects.requireNonNull(center.workPermit());
        }

        private NetworkRuntime.WorkPermit otherPermit() {
            return Objects.requireNonNull(other.workPermit());
        }

        private double available() {
            return centerPermit().availableResources().getDouble(ComputingResourceFixtures.COMPUTE);
        }

        private void requireOwner() {
            if (Thread.currentThread() != ownerThread) throw new IllegalStateException("owner thread required");
        }

        @Override
        public void close() {
            runtime.close();
            identities.close();
        }
    }
}
