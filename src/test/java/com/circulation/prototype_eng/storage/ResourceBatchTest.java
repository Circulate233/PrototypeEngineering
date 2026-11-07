package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResourceBatchTest {
    private static final KeyType TYPE = new KeyType("batch_resource");
    private static final Key MATERIAL = new Key(TYPE, "material");

    @Test void independentSourceLanesKeepResourceAndEnergyInvisibleUntilTheOwnerFinishes() throws Exception {
        var notifications = new ConcurrentLinkedQueue<Thread>();
        var material = buffer(() -> notifications.add(Thread.currentThread()));
        var energy = new EnergyBuffer(1000, () -> notifications.add(Thread.currentThread()));
        material.insert(MATERIAL, 100, Actionable.MODULATE);
        energy.receiveEnergy(1000, Actionable.MODULATE);
        var bothEntered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var input = new ObservedToken(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 40))) {
            @Override public void apply() { bothEntered.countDown(); await(release); super.apply(); }
        };
        var power = new ObservedToken(accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 250))) {
            @Override public void apply() { bothEntered.countDown(); await(release); super.apply(); }
        };
        notifications.clear();
        int[] business = {0};
        var batch = batch(List.of(input, power), () -> {
            assertEquals(60, material.amount(MATERIAL));
            assertEquals(750, energy.savedEnergy());
            business[0] = 1;
        }, () -> business[0] = 2, () -> business[0] = 0);
        var outsideEntered = new CountDownLatch(1);
        var outside = new FutureTask<>(() -> {
            outsideEntered.countDown();
            return List.of(material.amount(MATERIAL), energy.savedEnergy());
        });
        Thread.ofPlatform().daemon().start(outside);
        await(outsideEntered);
        try (var firstLane = Executors.newSingleThreadExecutor(); var secondLane = Executors.newSingleThreadExecutor()) {
            var first = firstLane.submit(batch.sources().get(0)::apply);
            var second = secondLane.submit(batch.sources().get(1)::apply);
            try {
                await(bothEntered);
                assertFalse(outside.isDone());
                assertFalse(batch.rollback(), "A running source must not be mistaken for an exited action");
            } finally { release.countDown(); }
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(second.get(5, TimeUnit.SECONDS));
            assertEquals(ResourceBatch.Decision.ROLLBACK, batch.decision());
            assertTrue(batch.rollback());
            assertTrue(firstLane.submit(batch.sources().get(0)::resolve).get(5, TimeUnit.SECONDS));
            assertTrue(secondLane.submit(batch.sources().get(1)::resolve).get(5, TimeUnit.SECONDS));
            assertTrue(notifications.isEmpty());
            assertFalse(outside.isDone());
            batch.finish();
        } finally { release.countDown(); }
        assertEquals(List.of(100L, 1000L), outside.get(5, TimeUnit.SECONDS));
        assertEquals(0, business[0]);
        assertEquals(2, notifications.size());
        assertTrue(notifications.stream().allMatch(thread -> thread == Thread.currentThread()));

        notifications.clear();
        var committed = batch(List.of(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 40)),
            accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 250))), () -> {
                assertEquals(60, material.amount(MATERIAL));
                assertEquals(750, energy.savedEnergy());
                business[0] = 1;
            }, () -> business[0] = 2, () -> business[0] = 0);
        notifications.clear();
        for (var source : committed.sources()) { assertTrue(source.apply()); }
        assertTrue(committed.commit(() -> true));
        assertEquals(2, business[0]);
        assertThrows(IllegalStateException.class, committed::finish);
        for (var source : committed.sources()) { assertTrue(source.resolve()); }
        assertTrue(notifications.isEmpty());
        committed.finish();
        assertEquals(60, material.amount(MATERIAL));
        assertEquals(750, energy.savedEnergy());
        assertThrows(IllegalStateException.class, committed::rollback);
    }

    @Test void laterBusinessFailureReversesAllAcquisitionsAndOnlyThenReleasesEverySource() {
        var material = buffer(() -> {});
        material.insert(MATERIAL, 100, Actionable.MODULATE);
        var input = accepted(material.reserveExtract(material.snapshot(), MATERIAL, 40));
        var energy = new EnergyBuffer(1000, () -> {});
        energy.receiveEnergy(1000, Actionable.MODULATE);
        var power = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 250));
        int[] business = {0, 0};
        var failure = new IllegalStateException("later operation commit");
        var batch = batch(List.of(input, power), () -> {
            business[0] = 1;
            business[1] = 2;
            throw failure;
        }, () -> fail("Cannot complete a failed batch"), () -> { business[1] = 0; business[0] = 0; });
        for (var source : batch.sources()) { assertTrue(source.apply()); }
        assertSame(failure, assertThrows(IllegalStateException.class, () -> batch.commit(() -> true)));
        assertThrows(IllegalStateException.class, () -> batch.sources().getFirst().resolve());
        assertTrue(batch.rollback());
        assertArrayEquals(new int[] {0, 0}, business);
        for (var source : batch.sources()) { assertTrue(source.resolve()); }
        batch.finish();
        assertEquals(100, material.amount(MATERIAL));
        assertEquals(1000, energy.savedEnergy());
        assertTrue(ResourceTransactions.commit(List.of(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 100)))));
    }

    @Test void authorizationLossAfterBusinessCommitStillReversesTheExactAppliedResources() {
        var material = buffer(() -> {});
        material.insert(MATERIAL, 100, Actionable.MODULATE);
        boolean[] permitted = {true};
        int[] business = {0};
        var batch = batch(List.of(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 30))),
            () -> { business[0] = 1; permitted[0] = false; }, () -> fail("Lost permission cannot complete"), () -> business[0] = 0);
        assertTrue(batch.sources().getFirst().apply());
        assertFalse(batch.commit(() -> permitted[0]));
        assertTrue(batch.rollback());
        assertTrue(batch.sources().getFirst().resolve());
        batch.finish();
        assertEquals(0, business[0]);
        assertEquals(100, material.amount(MATERIAL));
    }

    @Test void rejectedAndPartlyAppliedSourcesRetainAllTokensForCompensation() {
        var material = buffer(() -> {});
        material.insert(MATERIAL, 100, Actionable.MODULATE);
        var first = new ObservedToken(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 20)));
        var failure = new IllegalArgumentException("later apply failed atomically");
        var second = new ObservedToken(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 30))) {
            @Override public void apply() { throw failure; }
        };
        int[] rollback = {0};
        var batch = batch(List.of(first, second), () -> fail("Partial source cannot commit"), () -> {}, () -> rollback[0]++);
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> batch.sources().getFirst().apply()));
        assertEquals(ResourceBatch.SourceState.APPLY_FAILED, batch.sources().getFirst().state());
        assertTrue(batch.rollback());
        assertTrue(batch.sources().getFirst().resolve());
        batch.finish();
        assertEquals(1, first.rollbacks);
        assertEquals(1, second.rollbacks);
        assertEquals(1, rollback[0]);
        assertEquals(100, material.amount(MATERIAL));

        var rejected = new ObservedToken(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 10))) {
            @Override public boolean canApply() { return false; }
            @Override public void apply() { fail("Rejected source must never apply"); }
        };
        var retry = batch(List.of(rejected), () -> {}, () -> {}, () -> {});
        assertFalse(retry.sources().getFirst().apply());
        assertThrows(IllegalStateException.class, () -> retry.commit(() -> true));
        assertTrue(retry.rollback());
        assertTrue(retry.sources().getFirst().resolve());
        retry.finish();
        assertEquals(100, material.amount(MATERIAL));
    }

    @Test void unknownRollbackOutcomeStaysPendingUntilAnExactProofPermitsRetryWithoutReplayingOthers() {
        var material = buffer(() -> {});
        material.insert(MATERIAL, 100, Actionable.MODULATE);
        var first = new ObservedToken(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 20)));
        boolean[] proof = {false};
        var failure = new IllegalStateException("rollback acknowledgement unavailable");
        var second = new ObservedToken(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 30))) {
            @Override public void rollback() {
                if (!proof[0]) { rollbacks++; throw failure; }
                super.rollback();
            }
            @Override public Resolution resolution() { return proof[0] ? super.resolution() : Resolution.UNKNOWN; }
        };
        var batch = batch(List.of(first, second), () -> {}, () -> {}, () -> {});
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.rollback());
        assertSame(failure, assertThrows(IllegalStateException.class, source::resolve));
        assertEquals(ResourceBatch.SourceState.CONFIRMATION_PENDING, source.state());
        assertFalse(source.resolve(), "Unknown acknowledgement is not in-flight and does not permit blind replay");
        assertEquals(1, first.rollbacks);
        assertEquals(1, second.rollbacks);
        assertThrows(IllegalStateException.class, batch::finish);
        proof[0] = true;
        assertTrue(source.resolve());
        assertEquals(1, first.rollbacks);
        assertEquals(2, second.rollbacks);
        batch.finish();
        assertEquals(100, material.amount(MATERIAL));
    }

    @Test void completionFaultAfterMutationIsConfirmedAndCannotBecomeRollback() {
        var material = buffer(() -> {});
        material.insert(MATERIAL, 100, Actionable.MODULATE);
        var first = new ObservedToken(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 20)));
        var failure = new IllegalStateException("completion acknowledgement failed after mutation");
        var second = new ObservedToken(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 30))) {
            @Override public void complete() { super.complete(); throw failure; }
        };
        var batch = batch(List.of(first, second), () -> {}, () -> {}, () -> fail("Completion is irrevocable"));
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.commit(() -> true));
        assertSame(failure, assertThrows(IllegalStateException.class, source::resolve));
        assertEquals(ResourceBatch.SourceState.COMPLETED, source.state());
        assertTrue(source.resolve());
        assertEquals(1, first.completions);
        assertEquals(1, second.completions);
        assertThrows(IllegalStateException.class, batch::rollback);
        batch.finish();
        assertEquals(50, material.amount(MATERIAL));
    }

    @Test void busyClaimLeavesOwnershipWithTheCallerAndEmptyBusinessFailureRemainsFenced() {
        var material = buffer(() -> {});
        material.insert(MATERIAL, 100, Actionable.MODULATE);
        var first = accepted(material.reserveExtract(material.snapshot(), MATERIAL, 20));
        var second = accepted(material.reserveExtract(material.snapshot(), MATERIAL, 30));
        var batch = batch(List.of(first), () -> {}, () -> {}, () -> {});
        assertNull(ResourceBatch.tryAcquire(List.of(second), () -> {}, () -> {}, () -> fail("Claim did not transfer ownership")));
        assertTrue(batch.rollback());
        assertTrue(batch.sources().getFirst().resolve());
        batch.finish();
        ResourceTransactions.rollback(List.of(second));
        assertEquals(100, material.amount(MATERIAL));

        int[] calls = {0};
        var failure = new IllegalStateException("business cleanup outcome unknown");
        var empty = batch(List.of(), () -> {}, () -> {}, () -> { calls[0]++; throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, empty::rollback));
        assertFalse(empty.rollback());
        assertEquals(1, calls[0]);
        assertThrows(IllegalStateException.class, empty::finish);
        assertThrows(IllegalStateException.class, () -> empty.commit(() -> true));
    }

    @Test void rejectedForeignLockAndNestedScopeDoNotStrandAnUnexecutedRollbackOrRelease() {
        var material = buffer(() -> {});
        material.insert(MATERIAL, 100, Actionable.MODULATE);
        int[] cleanupCalls = {0};
        var batch = batch(List.of(accepted(material.reserveExtract(material.snapshot(), MATERIAL, 30))),
            () -> fail("This batch is cancelled"), () -> {}, () -> cleanupCalls[0]++);
        var other = new StorageContext(() -> {});
        other.lock();
        try { assertThrows(IllegalStateException.class, batch::rollback); }
        finally { other.unlock(); }
        var otherFence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(other)));
        otherFence.access(() -> assertThrows(IllegalStateException.class, batch::rollback));
        assertTrue(batch.rollback());
        assertEquals(1, cleanupCalls[0]);
        assertTrue(batch.sources().getFirst().resolve());
        otherFence.access(() -> assertThrows(IllegalStateException.class, batch::finish));
        otherFence.release();
        other.lock();
        try { assertThrows(IllegalStateException.class, batch::finish); }
        finally { other.unlock(); }
        batch.finish();
        assertEquals(100, material.amount(MATERIAL));
    }

    private static SlottedResourceBuffer buffer(Runnable onChanged) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100L);
        return new SlottedResourceBuffer(2, capacities, onChanged);
    }

    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }

    private static ResourceBatch batch(List<? extends ResourceReservation> tokens, Runnable commit, Runnable complete, Runnable rollback) {
        return Objects.requireNonNull(ResourceBatch.tryAcquire(tokens, commit, complete, rollback));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Test action did not reach its barrier"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static class ObservedToken implements ResourceReservation {
        private final ResourceReservation delegate;
        int rollbacks;
        int completions;

        private ObservedToken(ResourceReservation delegate) { this.delegate = delegate; }
        @Override public StorageContext context() { return delegate.context(); }
        @Override public boolean canApply() { return delegate.canApply(); }
        @Override public void apply() { delegate.apply(); }
        @Override public void complete() { completions++; delegate.complete(); }
        @Override public void rollback() { rollbacks++; delegate.rollback(); }
        @Override public Resolution resolution() { return delegate.resolution(); }
    }
}
