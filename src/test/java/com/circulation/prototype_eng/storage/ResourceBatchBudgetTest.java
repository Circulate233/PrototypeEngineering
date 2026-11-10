package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ResourceBatchBudgetTest {
    private static RecipeWorkBudget unit() {
        return new RecipeWorkBudget(1);
    }

    private static void drive(BooleanSupplier advance) {
        int calls = 0;
        while (!advance.getAsBoolean()) {
            assertTrue(++calls < 100_000, "Cursor did not progress");
        }
    }

    private static EnergyBuffer energy() {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.receiveEnergy(10, Actionable.MODULATE);
        return energy;
    }

    private static ResourceReservation take(EnergyBuffer energy, long amount) {
        return Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), amount).reservation());
    }

    private static ResourceBatch acquire(List<StorageContext> contexts, List<? extends ResourceReservation> tokens,
                                         List<? extends ReversibleTransaction> business) {
        var pending = ResourceBatch.beginAcquireSequential(contexts, tokens, business);
        drive(() -> pending.advance(unit()));
        return Objects.requireNonNull(pending.result());
    }

    private static void apply(ResourceBatch.Source source) {
        while (source.state() == ResourceBatch.SourceState.RESERVED) {
            assertTrue(source.applyWithinBudget(unit()));
        }
        assertEquals(ResourceBatch.SourceState.APPLIED, source.state());
    }

    private static void clean(ResourceBatch batch) {
        drive(() -> batch.rollbackWithinBudget(unit()));
        for (var source : batch.sources()) {
            drive(() -> source.resolveWithinBudget(unit()));
        }
        assertTrue(batch.readyToFinish());
        drive(() -> batch.finishWithinBudget(unit()));
        assertTrue(batch.finished());
    }

    @Test
    void manyActualBalancesAndBusinessParticipantsStageThenRefundWithOneUnitWithoutReplaying() {
        var energies = new ArrayList<EnergyBuffer>();
        var contexts = new ArrayList<StorageContext>();
        var tokens = new ArrayList<ResourceReservation>();
        var business = new ArrayList<Business>();
        for (int index = 0; index < 48; index++) {
            var energy = energy();
            energies.add(energy);
            contexts.add(energy.context());
            tokens.add(take(energy, 2));
            business.add(new Business());
        }
        var batch = acquire(contexts, tokens, business);
        for (var source : batch.sources()) {
            apply(source);
        }
        int yields = 0;
        while (!batch.stageWithinBudget(() -> true, unit())) {
            assertEquals(ResourceBatch.Decision.OPEN, batch.decision());
            assertTrue(batch.workYielded());
            assertTrue(++yields < 10_000);
        }
        assertTrue(yields > business.size());
        for (var transaction : business) {
            assertEquals(1, transaction.prepares);
            assertEquals(1, transaction.commits);
        }
        clean(batch);
        for (var energy : energies) {
            assertEquals(10, energy.storedEnergy());
        }
        for (var transaction : business) {
            assertEquals(1, transaction.rollbacks);
            assertEquals(0, transaction.completes);
        }
    }

    @Test
    void oneUnitVisitsOnlyOneTokenActionInALargeSharedPhysicalSource() {
        var energy = new EnergyBuffer(1000, () -> {
        });
        energy.receiveEnergy(1000, Actionable.MODULATE);
        var tokens = new ArrayList<ResourceReservation>();
        int[] actions = {0};
        for (int index = 0; index < 128; index++) {
            tokens.add(new Delegate(take(energy, 1)) {
                @Override
                public boolean canApply() {
                    actions[0]++;
                    return super.canApply();
                }

                @Override
                public void apply() {
                    actions[0]++;
                    super.apply();
                }

                @Override
                public void exposeApplied() {
                    actions[0]++;
                    super.exposeApplied();
                }

                @Override
                public void rollback() {
                    actions[0]++;
                    super.rollback();
                }
            });
        }
        var batch = acquire(List.of(energy.context()), tokens, List.of());
        assertEquals(1, batch.sources().size());
        var source = batch.sources().getFirst();
        while (source.state() == ResourceBatch.SourceState.RESERVED) {
            int before = actions[0];
            assertTrue(source.applyWithinBudget(unit()));
            assertTrue(actions[0] - before <= 1);
        }
        assertEquals(384, actions[0]);
        drive(() -> batch.stageWithinBudget(() -> true, unit()));
        drive(() -> batch.rollbackWithinBudget(unit()));
        boolean done;
        do {
            int before = actions[0];
            done = source.resolveWithinBudget(unit());
            assertTrue(actions[0] - before <= 1);
        } while (!done);
        assertEquals(512, actions[0]);
        drive(() -> batch.finishWithinBudget(unit()));
        assertEquals(1000, energy.storedEnergy());
    }

    @Test
    void partiallyCapturedSavepointAndAppendRetainOriginalSourceAndSuccessfulPrefix() {
        var energy = energy();
        var prefix = take(energy, 2);
        var batch = acquire(List.of(energy.context()), List.of(prefix), List.of());
        var source = batch.sources().getFirst();
        apply(source);
        drive(() -> batch.stageWithinBudget(() -> true, unit()));
        var mark = Objects.requireNonNull(batch.savepointWithinBudget(unit()));
        var additions = new ArrayList<ResourceReservation>();
        assertTrue(batch.acquireNextWithinBudget(() -> additions.add(take(energy, 3))));
        var extraBusiness = List.of(new Business());
        drive(() -> batch.appendWithinBudget(additions, extraBusiness, false, unit()));
        assertSame(source, batch.sources().getFirst());
        apply(source);
        drive(() -> batch.stageWithinBudget(() -> true, unit()));
        drive(() -> batch.rollbackToWithinBudget(mark, unit()));
        assertFalse(batch.finishRollbackToWithinBudget(mark, unit()));
        drive(() -> source.resolveWithinBudget(unit()));
        drive(() -> batch.finishRollbackToWithinBudget(mark, unit()));
        assertSame(source, batch.sources().getFirst());
        assertEquals(ResourceBatch.SourceState.APPLIED, source.state());
        batch.accessWithinBudget(() -> assertEquals(8, energy.storedEnergy()));
        assertEquals(1, extraBusiness.getFirst().rollbacks);
        drive(() -> batch.commitWithinBudget(() -> true, unit()));
        drive(() -> source.resolveWithinBudget(unit()));
        drive(() -> batch.finishWithinBudget(unit()));
        assertEquals(8, energy.storedEnergy());
    }

    @Test
    void rejectedFlatChildRefundsOnlyItsSubsetInsideTheOriginalUnregisteredWindow() {
        var energy = energy();
        var prefix = take(energy, 2);
        var batch = acquire(List.of(energy.context()), List.of(prefix), List.of());
        var source = batch.sources().getFirst();
        apply(source);
        drive(() -> batch.stageWithinBudget(() -> true, unit()));
        var outer = Objects.requireNonNull(batch.savepointWithinBudget(unit()));
        var failed = new ArrayList<ResourceReservation>();
        int[] applies = {0};
        assertTrue(batch.acquireNextWithinBudget(() -> failed.add(new Delegate(take(energy, 3)) {
            @Override
            public boolean supportsSequentialUse() {
                return false;
            }

            @Override
            public void apply() {
                applies[0]++;
                super.apply();
            }
        })));
        var childBusiness = List.of(new Business());
        drive(() -> batch.adoptRejectedWithinBudget(failed, childBusiness, unit()));
        assertTrue(batch.acquiringNext());
        assertEquals(ResourceBatch.Decision.OPEN, batch.decision());
        assertSame(source, batch.sources().getFirst());
        assertThrows(IllegalStateException.class, () -> batch.accessWithinBudget(() -> {
        }));
        assertThrows(IllegalStateException.class, () -> source.applyWithinBudget(unit()));
        drive(() -> batch.resolveBusinessWithinBudget(unit()));
        drive(() -> source.resolveWithinBudget(unit()));
        drive(() -> batch.finishRejectedWithinBudget(unit()));
        assertTrue(batch.acquiringNext());
        assertSame(source, batch.sources().getFirst());
        assertEquals(0, applies[0]);
        assertEquals(0, childBusiness.getFirst().prepares);
        assertEquals(0, childBusiness.getFirst().commits);
        assertEquals(1, childBusiness.getFirst().rollbacks);
        assertEquals(0, childBusiness.getFirst().completes);
        var accepted = new ArrayList<ResourceReservation>();
        assertTrue(batch.accessWithinBudget(() -> {
            assertEquals(8, energy.storedEnergy());
            accepted.add(take(energy, 4));
        }));
        List<ReversibleTransaction> noBusiness = List.of();
        drive(() -> batch.appendWithinBudget(accepted, noBusiness, false, unit()));
        apply(source);
        drive(() -> batch.stageWithinBudget(() -> true, unit()));
        batch.releaseSavepoint(outer);
        drive(() -> batch.commitWithinBudget(() -> true, unit()));
        drive(() -> source.resolveWithinBudget(unit()));
        drive(() -> batch.finishWithinBudget(unit()));
        assertEquals(4, energy.storedEnergy());
    }

    @Test
    void unknownEffectAfterThrowKeepsOriginalTokenAndNeverReplaysItBeforeExactProof() {
        var energy = energy();
        var delegate = take(energy, 4);
        boolean[] proof = {false};
        int[] calls = {0};
        var fault = new IllegalStateException("after compensation");
        ResourceReservation token = new Delegate(delegate) {
            @Override
            public void rollback() {
                calls[0]++;
                delegate.rollback();
                throw fault;
            }

            @Override
            public Resolution resolution() {
                return proof[0] ? delegate.resolution() : Resolution.UNKNOWN;
            }
        };
        var batch = acquire(List.of(energy.context()), List.of(token), List.of());
        var source = batch.sources().getFirst();
        apply(source);
        drive(() -> batch.rollbackWithinBudget(unit()));
        assertFalse(source.resolveWithinBudget(unit()));
        assertTrue(source.workYielded());
        assertFalse(source.resolveWithinBudget(unit()));
        assertTrue(source.workYielded());
        assertSame(fault, assertThrows(IllegalStateException.class, () -> source.resolveWithinBudget(unit())));
        assertFalse(source.resolveWithinBudget(unit()));
        assertFalse(source.workYielded());
        assertEquals(1, calls[0]);
        assertFalse(batch.readyToFinish());
        proof[0] = true;
        drive(() -> source.resolveWithinBudget(unit()));
        assertEquals(1, calls[0]);
        drive(() -> batch.finishWithinBudget(unit()));
        assertEquals(10, energy.storedEnergy());
    }

    @Test
    void fatalVmErrorPropagatesFromTheActualSourceCallbackEvenAfterTheLastUnitWasSpent() {
        var energy = energy();
        var delegate = take(energy, 4);
        var calls = new int[1];
        var fatal = new OutOfMemoryError("Synthetic fatal callback signal");
        ResourceReservation token = new Delegate(delegate) {
            @Override
            public void rollback() {
                calls[0]++;
                delegate.rollback();
                throw fatal;
            }
        };
        var batch = acquire(List.of(energy.context()), List.of(token), List.of());
        var source = batch.sources().getFirst();
        apply(source);
        drive(() -> batch.rollbackWithinBudget(unit()));
        assertFalse(source.resolveWithinBudget(unit()));
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> source.resolveWithinBudget(unit())));
        assertThrows(IllegalStateException.class, energy.context()::requireLock);
        assertEquals(1, calls[0]);
        assertFalse(batch.readyToFinish());
        drive(() -> source.resolveWithinBudget(unit()));
        assertEquals(1, calls[0]);
        drive(() -> batch.finishWithinBudget(unit()));
        assertEquals(10, energy.storedEnergy());
    }

    @Test
    void legacyBusinessCallbackStillOwnsAPhysicalBaseHoldAndProbeStageNeverCompletes() {
        var energy = energy();
        var business = new Business() {
            @Override
            public boolean prepareCommit() {
                assertThrows(IllegalStateException.class, energy.context()::unlockDeferred);
                energy.context().lock();
                energy.context().unlockDeferred();
                return super.prepareCommit();
            }
        };
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquireSequential(List.of(energy.context()), List.of(take(energy, 2)), List.of(business)));
        assertTrue(batch.sources().getFirst().apply());
        assertTrue(batch.stage(() -> true));
        assertEquals(ResourceBatch.Decision.OPEN, batch.decision());
        assertEquals(0, business.completes);
        assertTrue(batch.rollback());
        assertTrue(batch.sources().getFirst().resolve());
        batch.finish();
        assertEquals(10, energy.storedEnergy());
    }

    @Test
    void reversibleFailureIsQueriedButNeverRetriedAgainInsideTheSameLegacySettlement() {
        int[] calls = {0};
        boolean[] repair = {false};
        var fault = new IllegalStateException("reversible cleanup");
        ReversibleTransaction transaction = new ReversibleTransaction() {
            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
                calls[0]++;
                if (!repair[0]) {
                    throw fault;
                }
            }

            @Override
            public Resolution resolution() {
                return Resolution.REVERSIBLE;
            }
        };
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(), List.of(transaction)));
        assertSame(fault, assertThrows(IllegalStateException.class, batch::rollback));
        assertEquals(1, calls[0]);
        assertSame(fault, assertThrows(IllegalStateException.class, batch::resolveBusiness));
        assertEquals(2, calls[0]);
        repair[0] = true;
        assertTrue(batch.resolveBusiness());
        assertEquals(3, calls[0]);
        batch.finish();
    }

    @Test
    void cancellationDoesNotReturnResourceOwnershipUntilTheEnteredSourceActuallyExits() throws Exception {
        var energy = energy();
        var delegate = take(energy, 4);
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        ResourceReservation token = new Delegate(delegate) {
            @Override
            public void apply() {
                delegate.apply();
                entered.countDown();
                try {
                    assertTrue(proceed.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
        };
        var batch = acquire(List.of(energy.context()), List.of(token), List.of());
        var source = batch.sources().getFirst();
        @SuppressWarnings("resource") var worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            var running = worker.submit(() -> source.applyWithinBudget(new RecipeWorkBudget(100)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(batch.rollbackWithinBudget(unit()));
            assertFalse(batch.readyToFinish());
            assertFalse(batch.finished());
            assertThrows(IllegalStateException.class, () -> batch.finishWithinBudget(unit()));
            assertThrows(IllegalStateException.class, () -> source.resolveWithinBudget(unit()));
            proceed.countDown();
            assertFalse(running.get(5, TimeUnit.SECONDS));
            clean(batch);
            assertEquals(10, energy.storedEnergy());
        } finally {
            proceed.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void ownerAndAnotherSameDomainSourceCannotEnterBetweenASourceAdvancesShortPhysicalScopes() throws Exception {
        var energy = energy();
        var delegate = take(energy, 4);
        var captured = new StorageFence[1];
        var gap = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        ResourceReservation token = new Delegate(delegate) {
            @Override
            public boolean canApply() {
                captured[0] = context().requireFenceAccess();
                return super.canApply();
            }
        };
        var batch = acquire(List.of(energy.context()), List.of(token), List.of());
        var source = batch.sources().getFirst();
        var budget = new RecipeWorkBudget(100) {
            private int calls;

            @Override
            public boolean take() {
                if (++calls == 2) {
                    gap.countDown();
                    try {
                        assertTrue(proceed.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
                return super.take();
            }
        };
        @SuppressWarnings("resource") var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            var applying = workers.submit(() -> source.applyWithinBudget(budget));
            assertTrue(gap.await(5, TimeUnit.SECONDS));
            var fence = Objects.requireNonNull(captured[0]);
            assertFalse(fence.tryAccessWithinBudget(() -> fail("The source advance still owns its work gate")));
            assertThrows(IllegalStateException.class, fence::beginRelease);
            var started = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var other = workers.submit(() -> {
                started.countDown();
                fence.access(energy.context(), entered::countDown);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(1, entered.getCount());
            proceed.countDown();
            assertTrue(applying.get(5, TimeUnit.SECONDS));
            other.get(5, TimeUnit.SECONDS);
            clean(batch);
            assertEquals(10, energy.storedEnergy());
        } finally {
            proceed.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static class Business implements ReversibleTransaction {
        int prepares, commits, completes, rollbacks;

        @Override
        public boolean prepareCommit() {
            prepares++;
            return true;
        }

        @Override
        public void commit() {
            commits++;
        }

        @Override
        public void complete() {
            completes++;
        }

        @Override
        public void rollback() {
            rollbacks++;
        }
    }

    private static class Delegate implements ResourceReservation {
        final ResourceReservation delegate;

        Delegate(ResourceReservation delegate) {
            this.delegate = delegate;
        }

        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public boolean supportsSequentialUse() {
            return delegate.supportsSequentialUse();
        }

        @Override
        public void exposeApplied() {
            delegate.exposeApplied();
        }

        @Override
        public boolean canApply() {
            return delegate.canApply();
        }

        @Override
        public void apply() {
            delegate.apply();
        }

        @Override
        public void complete() {
            delegate.complete();
        }

        @Override
        public void rollback() {
            delegate.rollback();
        }

        @Override
        public Resolution resolution() {
            return delegate.resolution();
        }
    }
}
