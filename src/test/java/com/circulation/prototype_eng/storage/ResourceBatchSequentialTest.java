package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResourceBatchSequentialTest {
    private static final KeyType TYPE = new KeyType("batch_sequence");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key OUTPUT = new Key(TYPE, "output");

    @Test void segmentsReuseExactSourcesAndKeepBusinessProvisionalUntilOneFinalValidation() {
        var storage = buffer();
        storage.insert(MATERIAL, 100, Actionable.MODULATE);
        var input = observed(storage.reserveExtract(storage.snapshot(), MATERIAL, 100));
        var first = new Business("first", new ArrayList<>());
        var second = new Business("second", first.events);
        var batch = sequential(List.of(storage.context()), List.of(input), List.of(first));
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        assertEquals(ResourceBatch.Decision.OPEN, batch.decision());
        assertEquals(1, first.prepares);
        assertEquals(1, first.commits);
        assertEquals(0, first.validations);
        assertEquals(0, first.completions);
        assertThrows(IllegalStateException.class, () -> batch.stage(() -> true));
        var acquired = new ArrayList<ResourceReservation>();
        batch.acquireNext(() -> {
            assertEquals(1, first.commits, "The preceding segment's business effect is visible");
            batchContext(storage.context());
            acquired.add(observed(storage.reserveInsert(storage.snapshot(), OUTPUT, 75)));
        });
        batch.append(acquired, List.of(second));
        assertSame(source, batch.sources().getFirst());
        assertEquals("storage:" + storage.context().order(), source.affinity());
        assertEquals(ResourceBatch.SourceState.RESERVED, source.state());
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        assertEquals(1, input.applies);
        assertEquals(1, input.exposures);
        batch.access(() -> {
            assertEquals(ResourceReservation.Resolution.APPLIED, input.resolution());
            assertEquals(75, storage.availableAmount(OUTPUT));
        });
        assertEquals(1, first.commits);
        assertEquals(1, second.commits);
        assertEquals(0, second.validations);
        assertTrue(batch.commit(() -> true));
        assertEquals(1, first.prepares);
        assertEquals(1, second.prepares);
        assertEquals(1, first.validations);
        assertEquals(1, second.validations);
        assertEquals(1, first.completions);
        assertEquals(1, second.completions);
        assertTrue(source.resolve());
        assertTrue(batch.readyToFinish());
        batch.finish();
        assertEquals(new GenericStack(OUTPUT, 75), storage.getStack(0));
        assertEquals(0, storage.amount(MATERIAL));
        assertEquals(1, input.completions);
    }

    @Test void reverseSettlementRestoresThreeDependentSegmentsAndTheirOriginalSlot() {
        var storage = buffer();
        storage.insert(MATERIAL, 100, Actionable.MODULATE);
        var firstToken = observed(storage.reserveExtract(storage.snapshot(), MATERIAL, 100));
        var events = new ArrayList<String>();
        var first = new Business("first", events);
        var second = new Business("second", events);
        var third = new Business("third", events);
        var batch = sequential(List.of(storage.context()), List.of(firstToken), List.of(first));
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        var secondTokens = new ArrayList<ResourceReservation>();
        batch.acquireNext(() -> secondTokens.add(observed(storage.reserveInsert(storage.snapshot(), OUTPUT, 80))));
        batch.append(secondTokens, List.of(second));
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        var thirdTokens = new ArrayList<ResourceReservation>();
        batch.acquireNext(() -> thirdTokens.add(observed(storage.reserveExtract(storage.snapshot(), OUTPUT, 50))));
        batch.append(thirdTokens, List.of(third));
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        batch.access(() -> assertEquals(30, storage.availableAmount(OUTPUT)));
        events.clear();
        assertTrue(batch.rollback());
        assertEquals(List.of("rollback:third", "rollback:second", "rollback:first"), events);
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(new GenericStack(MATERIAL, 100), storage.getStack(0));
        assertEquals(0, storage.amount(OUTPUT));
        assertEquals(1, firstToken.rollbacks);
        assertEquals(0, first.validations);
        assertEquals(0, third.validations);
    }

    @Test void emptySegmentsRetainDeclaredDomainsAndCanIntroduceAStableSourceLater() throws Exception {
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        var batch = sequential(List.of(energy.context()), List.of(), List.of());
        assertTrue(batch.sources().isEmpty());
        assertTrue(batch.stage(() -> true));
        var entered = new CountDownLatch(1);
        var reader = new FutureTask<>(() -> { entered.countDown(); return energy.savedEnergy(); });
        Thread.ofPlatform().daemon().start(reader);
        await(entered);
        assertFalse(reader.isDone());
        batch.acquireNext(() -> assertEquals(100, energy.savedEnergy()));
        batch.append(List.of(), List.of());
        assertTrue(batch.stage(() -> true));
        var tokens = new ArrayList<ResourceReservation>();
        batch.acquireNext(() -> tokens.add(observed(energy.reserveExtractEnergy(energy.energySnapshot(), 60))));
        batch.append(tokens, List.of());
        assertEquals(1, batch.sources().size());
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        batch.acquireNext(() -> {});
        batch.append(List.of(), List.of());
        assertEquals(ResourceBatch.SourceState.APPLIED, source.state());
        assertTrue(batch.stage(() -> true));
        assertTrue(batch.commit(() -> true));
        assertTrue(source.resolve());
        assertFalse(reader.isDone());
        batch.finish();
        assertEquals(40, reader.get(5, TimeUnit.SECONDS));
    }

    @Test void unknownDependentRollbackStopsBeforeItsPredecessorAndLaterProofResumesInOrder() {
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        var input = observed(energy.reserveExtractEnergy(energy.energySnapshot(), 100));
        var batch = sequential(List.of(energy.context()), List.of(input), List.of());
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        boolean[] proof = {false};
        var fault = new IllegalStateException("dependent rollback acknowledgement unavailable");
        var tokens = new ArrayList<ObservedToken>();
        batch.acquireNext(() -> tokens.add(new ObservedToken(accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 100))) {
            @Override public void rollback() {
                if (!proof[0]) { rollbacks++; throw fault; }
                super.rollback();
            }
            @Override public Resolution resolution() { return proof[0] ? super.resolution() : Resolution.UNKNOWN; }
        }));
        batch.append(tokens, List.of());
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        assertTrue(batch.rollback());
        assertSame(fault, assertThrows(IllegalStateException.class, source::resolve));
        assertEquals(0, input.rollbacks);
        assertEquals(ResourceBatch.SourceState.CONFIRMATION_PENDING, source.state());
        assertFalse(source.resolve());
        assertEquals(1, tokens.getFirst().rollbacks);
        assertEquals(0, input.rollbacks);
        assertThrows(IllegalStateException.class, batch::finish);
        proof[0] = true;
        assertTrue(source.resolve());
        assertEquals(2, tokens.getFirst().rollbacks);
        assertEquals(1, input.rollbacks);
        batch.finish();
        assertEquals(100, energy.savedEnergy());
    }

    @Test void exactTerminalProofAfterAThrowAllowsPredecessorRollbackWithoutReplayingTheSuccessor() {
        var energy = new EnergyBuffer(100, () -> {});
        var output = observed(energy.reserveReceiveEnergy(energy.energySnapshot(), 100));
        var batch = sequential(List.of(energy.context()), List.of(output), List.of());
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        var fault = new IllegalStateException("acknowledgement failed after compensation");
        var tokens = new ArrayList<ObservedToken>();
        batch.acquireNext(() -> tokens.add(new ObservedToken(accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 60))) {
            @Override public void rollback() { super.rollback(); throw fault; }
        }));
        batch.append(tokens, List.of());
        assertTrue(source.apply());
        assertTrue(batch.rollback());
        assertSame(fault, assertThrows(IllegalStateException.class, source::resolve));
        assertEquals(ResourceBatch.SourceState.ROLLED_BACK, source.state());
        assertEquals(1, output.rollbacks);
        assertTrue(source.resolve());
        assertEquals(1, tokens.getFirst().rollbacks);
        batch.finish();
        assertEquals(0, energy.savedEnergy());
    }

    @Test void aFailedExposureKeepsBothAppliedAndPendingExactTokensForCompensation() {
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        var first = observed(energy.reserveExtractEnergy(energy.energySnapshot(), 30));
        var fault = new IllegalStateException("exposure failed before returning holds");
        var second = new ObservedToken(accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 40))) {
            @Override public void exposeApplied() { throw fault; }
        };
        var third = observed(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        var batch = sequential(List.of(), List.of(first, second, third), List.of());
        var source = batch.sources().getFirst();
        assertSame(fault, assertThrows(IllegalStateException.class, source::apply));
        assertEquals(ResourceBatch.SourceState.APPLY_FAILED, source.state());
        assertThrows(IllegalStateException.class, () -> batch.stage(() -> true));
        assertTrue(batch.rollback());
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(100, energy.savedEnergy());
        assertEquals(1, first.rollbacks);
        assertEquals(1, second.rollbacks);
        assertEquals(1, third.rollbacks);
        assertEquals(0, third.applies);
    }

    @Test void appendRejectionPreflightsAllIdentitiesDomainsAndCapabilitiesWithoutChangingExistingParticipants() {
        var energy = new EnergyBuffer(100, () -> {});
        var other = new EnergyBuffer(100, () -> {});
        var initial = observed(energy.reserveReceiveEnergy(energy.energySnapshot(), 10));
        var business = new Business("initial", new ArrayList<>());
        var batch = sequential(List.of(), List.of(initial), List.of(business));
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        var valid = new ArrayList<ObservedToken>();
        batch.acquireNext(() -> valid.add(observed(energy.reserveReceiveEnergy(energy.energySnapshot(), 20))));
        assertThrows(IllegalStateException.class, () -> batch.acquireNext(() -> fail("Acquisition must not replay")));
        assertThrows(IllegalStateException.class, () -> batch.commit(() -> true));
        var foreign = observed(other.reserveReceiveEnergy(other.energySnapshot(), 20));
        assertThrows(IllegalArgumentException.class, () -> batch.append(List.of(valid.getFirst(), foreign), List.of()));
        assertThrows(IllegalArgumentException.class, () -> batch.append(List.of(valid.getFirst(), initial), List.of()));
        assertThrows(IllegalArgumentException.class, () -> batch.append(List.of(valid.getFirst(), valid.getFirst()), List.of()));
        var unsupported = new ObservedToken(valid.getFirst()) {
            @Override public boolean supportsSequentialUse() { return false; }
            @Override public void exposeApplied() { fail("Capability must be checked before exposure"); }
        };
        assertThrows(IllegalArgumentException.class, () -> batch.append(List.of(unsupported), List.of()));
        assertThrows(IllegalArgumentException.class, () -> batch.append(valid, List.of(business)));
        assertSame(source, batch.sources().getFirst());
        assertEquals(ResourceBatch.SourceState.APPLIED, source.state());
        assertEquals(1, batch.sources().size());
        ResourceTransactions.rollback(List.of(foreign));
        batch.append(valid, List.of());
        assertTrue(source.apply());
        assertTrue(batch.rollback());
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(0, energy.savedEnergy());
    }

    @Test void unsupportedInitialTokensAreRejectedBeforeClaimAndBusyDeclaredDomainsKeepCallerOwnership() {
        var energy = new EnergyBuffer(100, () -> {});
        var token = observed(energy.reserveReceiveEnergy(energy.energySnapshot(), 20));
        var unsupported = new ObservedToken(token) {
            @Override public boolean supportsSequentialUse() { return false; }
        };
        assertThrows(IllegalArgumentException.class,
            () -> ResourceBatch.tryAcquireSequential(List.of(energy.context()), List.of(unsupported), List.of()));
        assertEquals(0, token.applies);
        var held = Objects.requireNonNull(StorageFence.tryAcquire(List.of(energy.context())));
        assertNull(ResourceBatch.tryAcquireSequential(List.of(energy.context()), List.of(), List.of()));
        held.release();
        ResourceTransactions.rollback(List.of(token));
        assertEquals(1, token.rollbacks);
    }

    @Test void throwingNextAcquisitionCanRegisterItsExactParticipantsWithoutReplayingAcquisition() {
        var energy = new EnergyBuffer(100, () -> {});
        var first = observed(energy.reserveReceiveEnergy(energy.energySnapshot(), 40));
        var batch = sequential(List.of(), List.of(first), List.of());
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        var acquired = new ArrayList<ObservedToken>();
        var fault = new IllegalStateException("next acquisition failed after reserving");
        assertSame(fault, assertThrows(IllegalStateException.class, () -> batch.acquireNext(() -> {
            acquired.add(observed(energy.reserveExtractEnergy(energy.energySnapshot(), 20)));
            throw fault;
        })));
        assertEquals(ResourceBatch.Decision.OPEN, batch.decision());
        batch.access(() -> assertEquals(ResourceReservation.Resolution.APPLIED, first.resolution()));
        assertFalse(batch.readyToFinish());
        assertThrows(IllegalStateException.class, batch::finish);
        assertThrows(IllegalStateException.class, batch::rollback);
        assertEquals(ResourceBatch.Decision.OPEN, batch.decision(), "Unregistered effects retain the acquisition window");
        assertThrows(IllegalStateException.class, () -> batch.acquireNext(() -> {}));
        batch.append(acquired, List.of());
        assertTrue(batch.rollback());
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(1, acquired.getFirst().rollbacks);
        assertEquals(0, energy.savedEnergy());
    }

    @Test void authorizationLossDuringAStagedCommitReversesPriorAndNewBusinessWithoutFinalValidation() {
        var events = new ArrayList<String>();
        var first = new Business("first", events);
        boolean[] authorized = {true};
        var second = new Business("second", events) {
            @Override public void commit() { super.commit(); authorized[0] = false; }
        };
        var batch = sequential(List.of(), List.of(), List.of(first));
        assertTrue(batch.stage(() -> true));
        batch.acquireNext(() -> {});
        batch.append(List.of(), List.of(second));
        assertFalse(batch.stage(() -> authorized[0]));
        assertEquals(ResourceBatch.Decision.ROLLBACK, batch.decision());
        assertTrue(batch.rollback());
        batch.finish();
        assertEquals(List.of("commit:first", "commit:second", "rollback:second", "rollback:first"), events);
        assertEquals(1, first.commits);
        assertEquals(1, second.commits);
        assertEquals(0, first.validations);
        assertEquals(0, second.validations);
    }

    @Test void finalValidationRejectionCompensatesAllPreviouslyStagedEffectsWithoutReplayingBusiness() {
        var energy = new EnergyBuffer(100, () -> {});
        var output = observed(energy.reserveReceiveEnergy(energy.energySnapshot(), 40));
        var transaction = new Business("stage", new ArrayList<>()) {
            @Override public boolean validateCommit() { super.validateCommit(); return false; }
        };
        var batch = sequential(List.of(), List.of(output), List.of(transaction));
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        assertEquals(0, transaction.validations);
        assertFalse(batch.commit(() -> true));
        assertEquals(1, transaction.validations);
        assertEquals(1, transaction.commits);
        assertTrue(batch.rollback());
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(0, energy.savedEnergy());
        assertEquals(0, transaction.completions);
    }

    @Test void finalCommitProcessesOnlyTheUnstagedSegmentAndValidatesEveryBusinessExactlyOnce() {
        var events = new ArrayList<String>();
        var first = new Business("first", events);
        var second = new Business("second", events);
        var batch = sequential(List.of(), List.of(), List.of(first));
        assertTrue(batch.stage(() -> true));
        batch.acquireNext(() -> {});
        batch.append(List.of(), List.of(second));
        assertTrue(batch.commit(() -> true));
        assertEquals(1, first.prepares);
        assertEquals(1, first.commits);
        assertEquals(1, second.prepares);
        assertEquals(1, second.commits);
        assertEquals(1, first.validations);
        assertEquals(1, second.validations);
        assertEquals(1, first.completions);
        assertEquals(1, second.completions);
        batch.finish();
    }

    @Test void anUnsupportedNextTokenIsRegisteredForOriginalSourceCleanupBeforeAnyFenceRelease() {
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        var input = observed(energy.reserveExtractEnergy(energy.energySnapshot(), 100));
        var batch = sequential(List.of(), List.of(input), List.of());
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        var tokens = new ArrayList<ObservedToken>();
        batch.acquireNext(() -> tokens.add(new ObservedToken(accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 100))) {
            @Override public boolean supportsSequentialUse() { return false; }
            @Override public void rollback() { context().requireFenceAccess(); super.rollback(); }
            @Override public void exposeApplied() { fail("Rejected acquisitions must never expose"); }
        }));
        assertFalse(tokens.getFirst().supportsSequentialUse());
        batch.appendForRollback(tokens, List.of());
        assertEquals(ResourceBatch.Decision.ROLLBACK, batch.decision());
        assertSame(source, batch.sources().getFirst());
        assertEquals(0, tokens.getFirst().rollbacks, "Registration does not call a source callback on the owner");
        assertFalse(source.apply());
        assertTrue(batch.rollback());
        assertThrows(IllegalStateException.class, batch::finish);
        assertTrue(source.resolve());
        assertEquals(0, tokens.getFirst().applies);
        assertEquals(1, tokens.getFirst().rollbacks);
        assertEquals(1, input.rollbacks);
        batch.finish();
        assertEquals(100, energy.savedEnergy());
    }

    @Test void unknownBusinessRollbackRetainsItsPredecessorAndPreventsSourceCompensationUntilConfirmed() {
        var energy = new EnergyBuffer(100, () -> {});
        var token = observed(energy.reserveReceiveEnergy(energy.energySnapshot(), 40));
        var events = new ArrayList<String>();
        var first = new Business("first", events);
        boolean[] proof = {false};
        var fault = new IllegalStateException("business compensation unknown");
        var second = new Business("second", events) {
            @Override public void rollback() { if (!proof[0]) { rollbacks++; throw fault; } super.rollback(); }
            @Override public Resolution resolution() { return proof[0] ? super.resolution() : Resolution.UNKNOWN; }
        };
        var batch = sequential(List.of(), List.of(token), List.of(first, second));
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        assertSame(fault, assertThrows(IllegalStateException.class, batch::rollback));
        assertEquals(0, first.rollbacks);
        assertFalse(source.resolve());
        assertEquals(ResourceBatch.SourceState.APPLIED, source.state());
        assertFalse(batch.resolveBusiness());
        assertEquals(0, token.rollbacks);
        proof[0] = true;
        assertTrue(batch.resolveBusiness());
        assertEquals(1, first.rollbacks);
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(0, energy.savedEnergy());
    }

    @Test void stagingAndAcquisitionRequireActualSourceExitAndOwnerAccessDoesNotOpenAnAppendWindow() throws Exception {
        var energy = new EnergyBuffer(100, () -> {});
        var entered = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var token = new ObservedToken(accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 40))) {
            @Override public void apply() { entered.countDown(); await(exit); super.apply(); }
        };
        var batch = sequential(List.of(), List.of(token), List.of());
        assertThrows(IllegalStateException.class, () -> batch.acquireNext(() -> {}));
        batch.access(() -> {});
        assertThrows(IllegalStateException.class, () -> batch.append(List.of(), List.of()));
        try (var lane = Executors.newSingleThreadExecutor()) {
            var running = lane.submit(batch.sources().getFirst()::apply);
            try {
                await(entered);
                assertThrows(IllegalStateException.class, () -> batch.stage(() -> true));
                assertThrows(IllegalStateException.class, () -> batch.acquireNext(() -> {}));
                assertThrows(IllegalStateException.class, () -> batch.access(() -> {}));
                assertFalse(batch.rollback());
            } finally { exit.countDown(); }
            assertTrue(running.get(5, TimeUnit.SECONDS));
            assertTrue(batch.rollback());
            assertTrue(lane.submit(batch.sources().getFirst()::resolve).get(5, TimeUnit.SECONDS));
        }
        batch.finish();
        assertEquals(0, energy.savedEnergy());
    }

    @Test void ordinaryBatchesKeepHoldsAndAttemptAllRollbackTokensDespiteAnUnknownSuccessor() {
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        var first = observed(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        boolean[] proof = {false};
        var fault = new IllegalStateException("ordinary successor unknown");
        var second = new ObservedToken(accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 30))) {
            @Override public void rollback() { if (!proof[0]) { rollbacks++; throw fault; } super.rollback(); }
            @Override public Resolution resolution() { return proof[0] ? super.resolution() : Resolution.UNKNOWN; }
        };
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(first, second), List.of()));
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertEquals(0, first.exposures);
        batch.access(() -> assertEquals(0, energy.energySnapshot().availableCapacity()));
        assertThrows(IllegalStateException.class, () -> batch.stage(() -> true));
        assertThrows(IllegalStateException.class, () -> batch.acquireNext(() -> {}));
        assertTrue(batch.rollback());
        assertSame(fault, assertThrows(IllegalStateException.class, source::resolve));
        assertEquals(1, first.rollbacks);
        proof[0] = true;
        assertTrue(source.resolve());
        assertEquals(1, first.rollbacks);
        batch.finish();
        assertEquals(100, energy.savedEnergy());
    }

    private static ResourceBatch sequential(List<StorageContext> declared, List<? extends ResourceReservation> tokens,
                                            List<? extends ReversibleTransaction> business) {
        return Objects.requireNonNull(ResourceBatch.tryAcquireSequential(declared, tokens, business));
    }

    private static void batchContext(StorageContext context) { context.requireFenceAccess(); }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100L);
        return new SlottedResourceBuffer(1, capacities, () -> {});
    }

    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }

    private static ObservedToken observed(ReservationResult result) { return new ObservedToken(accepted(result)); }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Source did not reach its boundary"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static class ObservedToken implements ResourceReservation {
        private final ResourceReservation delegate;
        int applies;
        int exposures;
        int completions;
        int rollbacks;

        private ObservedToken(ResourceReservation delegate) { this.delegate = delegate; }
        @Override public StorageContext context() { return delegate.context(); }
        @Override public boolean canApply() { return delegate.canApply(); }
        @Override public void apply() { applies++; delegate.apply(); }
        @Override public boolean supportsSequentialUse() { return delegate.supportsSequentialUse(); }
        @Override public void exposeApplied() { exposures++; delegate.exposeApplied(); }
        @Override public void complete() { completions++; delegate.complete(); }
        @Override public void rollback() { rollbacks++; delegate.rollback(); }
        @Override public Resolution resolution() { return delegate.resolution(); }
    }

    private static class Business implements ReversibleTransaction {
        private final String name;
        final List<String> events;
        int prepares;
        int commits;
        int validations;
        int completions;
        int rollbacks;
        private Resolution resolution = Resolution.REVERSIBLE;

        private Business(String name, List<String> events) { this.name = name; this.events = events; }
        @Override public boolean prepareCommit() { prepares++; return true; }
        @Override public void commit() { commits++; events.add("commit:" + name); }
        @Override public boolean validateCommit() { validations++; return true; }
        @Override public void complete() { completions++; resolution = Resolution.COMPLETED; }
        @Override public void rollback() { rollbacks++; events.add("rollback:" + name); resolution = Resolution.ROLLED_BACK; }
        @Override public Resolution resolution() { return resolution; }
    }
}
