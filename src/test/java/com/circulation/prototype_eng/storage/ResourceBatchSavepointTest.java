package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceBatchSavepointTest {
    private static final KeyType FIRST = new KeyType("savepoint_first");
    private static final KeyType SECOND = new KeyType("savepoint_second");
    private static final Key MATERIAL = new Key(FIRST, "material");
    private static final Key REJECTED = new Key(SECOND, "rejected");
    private static final Key ACCEPTED = new Key(SECOND, "accepted");

    private static Token appendEnergy(ResourceBatch batch, EnergyBuffer energy, long amount, boolean receive) {
        var tokens = new ArrayList<Token>();
        batch.acquireNext(() -> tokens.add(token(receive ? energy.reserveReceiveEnergy(energy.energySnapshot(), amount)
            : energy.reserveExtractEnergy(energy.energySnapshot(), amount))));
        batch.append(tokens, List.of());
        return tokens.getFirst();
    }

    private static void applyAndStage(ResourceBatch batch, ResourceBatch.Source source) {
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
    }

    private static ResourceBatch batch(List<StorageContext> domains, List<? extends ResourceReservation> tokens,
                                       List<? extends ReversibleTransaction> business) {
        return Objects.requireNonNull(ResourceBatch.tryAcquireSequential(domains, tokens, business));
    }

    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }

    private static Token token(ReservationResult result) {
        return new Token(accepted(result));
    }

    private static EnergyBuffer energy() {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.receiveEnergy(100, Actionable.MODULATE);
        return energy;
    }

    private static SlottedResourceBuffer storage() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST, 100L);
        capacities.put(SECOND, 100L);
        return new SlottedResourceBuffer(1, capacities, () -> {
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Source did not reach its actual exit boundary");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void aFullSlotCandidateCanBeRefundedAndReplacedWhileTheOldPrefixRemainsApplied() {
        var storage = storage();
        storage.insert(MATERIAL, 100, Actionable.MODULATE);
        var prefix = token(storage.reserveExtract(storage.snapshot(), MATERIAL, 20));
        var prefixBusiness = new Business();
        var batch = batch(List.of(storage.context()), List.of(prefix), List.of(prefixBusiness));
        var source = batch.sources().getFirst();
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        var mark = batch.savepoint();
        var candidateBusiness = new Business();
        var candidateInput = new ArrayList<Token>();
        batch.acquireNext(() -> candidateInput.add(token(storage.reserveExtract(storage.snapshot(), MATERIAL, 80))));
        batch.append(candidateInput, List.of(candidateBusiness));
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        var candidateOutput = new ArrayList<Token>();
        batch.acquireNext(() -> candidateOutput.add(token(storage.reserveInsert(storage.snapshot(), REJECTED, 100))));
        batch.append(candidateOutput, List.of());
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        batch.access(() -> assertEquals(new GenericStack(REJECTED, 100), storage.getStack(0)));
        assertTrue(batch.rollbackTo(mark));
        assertTrue(batch.rewinding());
        assertEquals(ResourceBatch.Decision.OPEN, batch.decision());
        assertEquals(1, candidateBusiness.rollbacks);
        assertEquals(0, prefixBusiness.rollbacks);
        assertFalse(batch.finishRollbackTo(mark));
        assertTrue(source.resolve());
        assertTrue(batch.finishRollbackTo(mark));
        assertFalse(batch.rewinding());
        assertSame(source, batch.sources().getFirst());
        assertEquals(ResourceBatch.SourceState.APPLIED, source.state());
        assertEquals(0, prefix.rollbacks);
        batch.access(() -> {
            assertEquals(new GenericStack(MATERIAL, 80), storage.getStack(0));
            assertEquals(ResourceReservation.Resolution.APPLIED, prefix.resolution());
        });
        var alternative = new ArrayList<Token>();
        batch.acquireNext(() -> alternative.add(token(storage.reserveExtract(storage.snapshot(), MATERIAL, 80))));
        batch.append(alternative, List.of());
        assertTrue(source.apply());
        assertTrue(batch.stage(() -> true));
        var alternativeOutput = new ArrayList<Token>();
        batch.acquireNext(() -> alternativeOutput.add(token(storage.reserveInsert(storage.snapshot(), ACCEPTED, 100))));
        batch.append(alternativeOutput, List.of());
        assertTrue(source.apply());
        assertTrue(batch.commit(() -> true));
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(new GenericStack(ACCEPTED, 100), storage.getStack(0));
        assertEquals(1, prefix.applies);
        assertEquals(1, prefix.completions);
        assertEquals(1, prefixBusiness.commits);
        assertEquals(1, prefixBusiness.completions);
        assertEquals(1, candidateInput.getFirst().rollbacks);
        assertEquals(1, candidateOutput.getFirst().rollbacks);
        assertThrows(IllegalStateException.class, () -> batch.rollbackTo(mark));
    }

    @Test
    void nestedRollbackConsumesOnlyItsTargetAndDescendantsAndReleaseRequiresTheStackTop() {
        var energy = energy();
        var prefix = token(energy.reserveExtractEnergy(energy.energySnapshot(), 10));
        var batch = batch(List.of(energy.context()), List.of(prefix), List.of());
        var source = batch.sources().getFirst();
        applyAndStage(batch, source);
        var outer = batch.savepoint();
        var first = appendEnergy(batch, energy, 20, false);
        applyAndStage(batch, source);
        var inner = batch.savepoint();
        var second = appendEnergy(batch, energy, 30, false);
        applyAndStage(batch, source);
        assertThrows(IllegalStateException.class, () -> batch.releaseSavepoint(outer));
        assertTrue(batch.rollbackTo(inner));
        assertTrue(source.resolve());
        assertTrue(batch.finishRollbackTo(inner));
        batch.access(() -> assertEquals(70, energy.savedEnergy()));
        assertEquals(0, first.rollbacks);
        assertEquals(1, second.rollbacks);
        assertThrows(IllegalStateException.class, () -> batch.releaseSavepoint(inner));
        var descendant = batch.savepoint();
        var replacement = appendEnergy(batch, energy, 30, true);
        applyAndStage(batch, source);
        assertTrue(batch.rollbackTo(outer));
        assertTrue(source.resolve());
        assertTrue(batch.finishRollbackTo(outer));
        assertThrows(IllegalStateException.class, () -> batch.rollbackTo(descendant));
        batch.access(() -> assertEquals(90, energy.savedEnergy()));
        assertEquals(1, first.rollbacks);
        assertEquals(1, replacement.rollbacks);
        assertEquals(0, prefix.rollbacks);
        var accepted = batch.savepoint();
        batch.releaseSavepoint(accepted);
        assertThrows(IllegalStateException.class, () -> batch.releaseSavepoint(accepted));
        assertTrue(batch.commit(() -> true));
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(90, energy.savedEnergy());
    }

    @Test
    void unknownSuffixTokenStopsBeforeItsEarlierSuffixAndPrefixUntilExactProofResumesTheRewind() {
        var energy = energy();
        var prefix = token(energy.reserveExtractEnergy(energy.energySnapshot(), 40));
        var batch = batch(List.of(), List.of(prefix), List.of());
        var source = batch.sources().getFirst();
        applyAndStage(batch, source);
        var mark = batch.savepoint();
        var earlier = appendEnergy(batch, energy, 40, true);
        applyAndStage(batch, source);
        boolean[] proof = {false};
        var fault = new IllegalStateException("suffix outcome unknown");
        var later = new ArrayList<Token>();
        batch.acquireNext(() -> later.add(new Token(accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 30))) {
            @Override
            public void rollback() {
                if (!proof[0]) {
                    rollbacks++;
                    throw fault;
                }
                super.rollback();
            }

            @Override
            public Resolution resolution() {
                return proof[0] ? super.resolution() : Resolution.UNKNOWN;
            }
        }));
        batch.append(later, List.of());
        applyAndStage(batch, source);
        assertTrue(batch.rollbackTo(mark));
        assertSame(fault, assertThrows(IllegalStateException.class, source::resolve));
        assertFalse(source.resolve());
        assertFalse(batch.finishRollbackTo(mark));
        assertEquals(ResourceBatch.SourceState.CONFIRMATION_PENDING, source.state());
        assertEquals(0, earlier.rollbacks);
        assertEquals(0, prefix.rollbacks);
        assertFalse(batch.readyToFinish());
        assertThrows(IllegalStateException.class, batch::finish);
        proof[0] = true;
        assertTrue(source.resolve());
        assertTrue(batch.finishRollbackTo(mark));
        assertEquals(1, earlier.rollbacks);
        assertEquals(2, later.getFirst().rollbacks);
        assertEquals(0, prefix.rollbacks);
        assertTrue(batch.commit(() -> true));
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(60, energy.savedEnergy());
    }

    @Test
    void aSuffixThrowAfterMutationIsConfirmedAndNotReplayedWhenThePrefixCompletes() {
        var energy = energy();
        var prefix = token(energy.reserveExtractEnergy(energy.energySnapshot(), 40));
        var batch = batch(List.of(), List.of(prefix), List.of());
        var source = batch.sources().getFirst();
        applyAndStage(batch, source);
        var mark = batch.savepoint();
        var fault = new IllegalStateException("suffix acknowledgement failed after mutation");
        var suffix = new ArrayList<Token>();
        batch.acquireNext(() -> suffix.add(new Token(accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 30))) {
            @Override
            public void rollback() {
                super.rollback();
                throw fault;
            }
        }));
        batch.append(suffix, List.of());
        applyAndStage(batch, source);
        assertTrue(batch.rollbackTo(mark));
        assertSame(fault, assertThrows(IllegalStateException.class, source::resolve));
        assertTrue(source.resolve());
        assertTrue(batch.finishRollbackTo(mark));
        assertEquals(1, suffix.getFirst().rollbacks);
        assertTrue(batch.commit(() -> true));
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(60, energy.savedEnergy());
        assertEquals(1, prefix.completions);
    }

    @Test
    void unknownBusinessSuffixKeepsEverySourceAndEarlierBusinessUntouchedUntilConfirmed() {
        var energy = energy();
        var prefixToken = token(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        var prefixBusiness = new Business();
        var batch = batch(List.of(), List.of(prefixToken), List.of(prefixBusiness));
        var source = batch.sources().getFirst();
        applyAndStage(batch, source);
        var mark = batch.savepoint();
        boolean[] proof = {false};
        var fault = new IllegalStateException("business suffix outcome unknown");
        var earlier = new Business();
        var later = new Business() {
            @Override
            public void rollback() {
                if (!proof[0]) {
                    rollbacks++;
                    throw fault;
                }
                super.rollback();
            }

            @Override
            public Resolution resolution() {
                return proof[0] ? super.resolution() : Resolution.UNKNOWN;
            }
        };
        var suffix = new ArrayList<Token>();
        batch.acquireNext(() -> suffix.add(token(energy.reserveExtractEnergy(energy.energySnapshot(), 30))));
        batch.append(suffix, List.of(earlier, later));
        applyAndStage(batch, source);
        assertSame(fault, assertThrows(IllegalStateException.class, () -> batch.rollbackTo(mark)));
        assertTrue(batch.rewinding());
        assertEquals(ResourceBatch.Decision.OPEN, batch.decision());
        assertFalse(batch.resolveBusiness());
        assertFalse(source.resolve());
        assertEquals(ResourceBatch.SourceState.APPLIED, source.state());
        assertEquals(0, suffix.getFirst().rollbacks);
        assertEquals(0, earlier.rollbacks);
        assertEquals(0, prefixBusiness.rollbacks);
        assertFalse(batch.finishRollbackTo(mark));
        proof[0] = true;
        assertTrue(batch.resolveBusiness());
        assertEquals(1, earlier.rollbacks);
        assertTrue(source.resolve());
        assertTrue(batch.finishRollbackTo(mark));
        assertTrue(batch.commit(() -> true));
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(80, energy.savedEnergy());
        assertEquals(1, prefixBusiness.commits);
        assertEquals(1, prefixBusiness.completions);
    }

    @Test
    void globalCancellationExpandsAnUnknownRewindWithoutReplayingAnyConfirmedSuffix() {
        var energy = energy();
        var prefix = token(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        var prefixBusiness = new Business();
        var batch = batch(List.of(), List.of(prefix), List.of(prefixBusiness));
        var source = batch.sources().getFirst();
        applyAndStage(batch, source);
        var mark = batch.savepoint();
        var suffixBusiness = new Business();
        boolean[] proof = {false};
        var fault = new IllegalStateException("unknown suffix during cancellation");
        var suffix = new ArrayList<Token>();
        batch.acquireNext(() -> suffix.add(new Token(accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 30))) {
            @Override
            public void rollback() {
                if (!proof[0]) {
                    rollbacks++;
                    throw fault;
                }
                super.rollback();
            }

            @Override
            public Resolution resolution() {
                return proof[0] ? super.resolution() : Resolution.UNKNOWN;
            }
        }));
        batch.append(suffix, List.of(suffixBusiness));
        applyAndStage(batch, source);
        assertTrue(batch.rollbackTo(mark));
        assertSame(fault, assertThrows(IllegalStateException.class, source::resolve));
        assertTrue(batch.rollback());
        assertFalse(batch.rewinding());
        assertEquals(ResourceBatch.Decision.ROLLBACK, batch.decision());
        assertEquals(1, suffixBusiness.rollbacks);
        assertEquals(1, prefixBusiness.rollbacks);
        assertFalse(source.resolve());
        assertEquals(0, prefix.rollbacks);
        assertThrows(IllegalStateException.class, () -> batch.finishRollbackTo(mark));
        proof[0] = true;
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(100, energy.savedEnergy());
        assertEquals(1, prefix.rollbacks);
        assertEquals(2, suffix.getFirst().rollbacks);
        assertEquals(1, suffixBusiness.rollbacks);
    }

    @Test
    void cancellingAfterSuffixConfirmationStillRefundsThePrefixAndSkipsTheConfirmedToken() {
        var energy = energy();
        var prefix = token(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        var batch = batch(List.of(), List.of(prefix), List.of());
        var source = batch.sources().getFirst();
        applyAndStage(batch, source);
        var mark = batch.savepoint();
        var suffix = appendEnergy(batch, energy, 30, false);
        applyAndStage(batch, source);
        assertTrue(batch.rollbackTo(mark));
        assertTrue(source.resolve());
        assertEquals(ResourceBatch.SourceState.ROLLED_BACK, source.state());
        assertTrue(batch.rollback());
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(100, energy.savedEnergy());
        assertEquals(1, suffix.rollbacks);
        assertEquals(1, prefix.rollbacks);
    }

    @Test
    void aSourceIntroducedAfterTheMarkMustActuallyExitAndRemainsReusableAfterItsSuffixIsTrimmed() throws Exception {
        var energy = energy();
        var introduced = new EnergyBuffer(100, () -> {
        });
        var prefix = token(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        var batch = batch(List.of(introduced.context()), List.of(prefix), List.of());
        var prefixSource = batch.sources().getFirst();
        applyAndStage(batch, prefixSource);
        var mark = batch.savepoint();
        var entered = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var suffix = new ArrayList<Token>();
        batch.acquireNext(() -> suffix.add(new Token(accepted(introduced.reserveReceiveEnergy(introduced.energySnapshot(), 50))) {
            @Override
            public void apply() {
                super.apply();
                entered.countDown();
                await(exit);
            }
        }));
        batch.append(suffix, List.of());
        var source = batch.sources().getLast();
        String affinity = source.affinity();
        try (var lane = Executors.newSingleThreadExecutor()) {
            var applying = lane.submit(source::apply);
            try {
                await(entered);
                assertFalse(batch.rollbackTo(mark));
                assertTrue(batch.rewinding());
                assertFalse(batch.finishRollbackTo(mark));
                assertFalse(batch.resolveBusiness());
                assertThrows(IllegalStateException.class, () -> batch.stage(() -> true));
                assertThrows(IllegalStateException.class, () -> batch.commit(() -> true));
                assertThrows(IllegalStateException.class, () -> batch.acquireNext(() -> {
                }));
            } finally {
                exit.countDown();
            }
            assertTrue(applying.get(5, TimeUnit.SECONDS));
            assertTrue(batch.resolveBusiness());
            assertTrue(lane.submit(source::resolve).get(5, TimeUnit.SECONDS));
            assertTrue(batch.finishRollbackTo(mark));
            assertSame(source, batch.sources().getLast());
            assertEquals(affinity, source.affinity());
            assertEquals(ResourceBatch.SourceState.APPLIED, source.state());
            var replacement = appendEnergy(batch, introduced, 60, true);
            assertTrue(lane.submit(source::apply).get(5, TimeUnit.SECONDS));
            assertTrue(batch.commit(() -> true));
            assertTrue(prefixSource.resolve());
            assertTrue(lane.submit(source::resolve).get(5, TimeUnit.SECONDS));
            batch.finish();
            assertEquals(1, replacement.applies);
            assertEquals(1, replacement.completions);
        }
        assertEquals(60, introduced.savedEnergy());
        assertEquals(1, suffix.getFirst().applies);
        assertEquals(1, suffix.getFirst().rollbacks);
    }

    @Test
    void releasingASuccessfulCandidateMarkKeepsEveryEffectReversibleForOuterCancellation() {
        var energy = energy();
        var prefix = token(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        var batch = batch(List.of(), List.of(prefix), List.of());
        var source = batch.sources().getFirst();
        applyAndStage(batch, source);
        var mark = batch.savepoint();
        var suffixBusiness = new Business();
        var suffix = new ArrayList<Token>();
        batch.acquireNext(() -> suffix.add(token(energy.reserveExtractEnergy(energy.energySnapshot(), 30))));
        batch.append(suffix, List.of(suffixBusiness));
        applyAndStage(batch, source);
        batch.releaseSavepoint(mark);
        assertEquals(0, prefix.completions);
        assertEquals(0, suffix.getFirst().completions);
        assertEquals(0, suffixBusiness.completions);
        assertThrows(IllegalStateException.class, () -> batch.rollbackTo(mark));
        assertTrue(batch.rollback());
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(100, energy.savedEnergy());
        assertEquals(1, suffix.getFirst().rollbacks);
        assertEquals(1, prefix.rollbacks);
        assertEquals(1, suffixBusiness.rollbacks);
    }

    @Test
    void foreignConsumedAndUnregisteredWindowMarksCannotCrossTheOwnershipBoundary() {
        var energy = energy();
        var prefix = token(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        var batch = batch(List.of(), List.of(prefix), List.of());
        var source = batch.sources().getFirst();
        assertThrows(IllegalStateException.class, batch::savepoint);
        applyAndStage(batch, source);
        var mark = batch.savepoint();
        var foreign = batch(List.of(), List.of(), List.of());
        assertTrue(foreign.stage(() -> true));
        var foreignMark = foreign.savepoint();
        assertThrows(IllegalStateException.class, () -> batch.rollbackTo(foreignMark));
        foreign.releaseSavepoint(foreignMark);
        assertTrue(foreign.commit(() -> true));
        foreign.finish();
        var pending = new ArrayList<Token>();
        batch.acquireNext(() -> pending.add(token(energy.reserveExtractEnergy(energy.energySnapshot(), 30))));
        assertThrows(IllegalStateException.class, batch::savepoint);
        assertThrows(IllegalStateException.class, () -> batch.rollbackTo(mark));
        assertThrows(IllegalStateException.class, () -> batch.releaseSavepoint(mark));
        assertFalse(batch.readyToFinish());
        batch.append(pending, List.of());
        assertTrue(batch.rollbackTo(mark));
        assertThrows(IllegalStateException.class, () -> batch.append(List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> batch.stage(() -> true));
        assertThrows(IllegalStateException.class, () -> batch.commit(() -> true));
        assertTrue(source.resolve());
        assertTrue(batch.finishRollbackTo(mark));
        assertEquals(1, pending.getFirst().rollbacks);
        assertEquals(0, pending.getFirst().applies);
        assertThrows(IllegalStateException.class, () -> batch.rollbackTo(mark));
        assertTrue(batch.commit(() -> true));
        assertTrue(source.resolve());
        batch.finish();
        var ordinary = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(), List.of()));
        assertThrows(IllegalStateException.class, ordinary::savepoint);
        assertTrue(ordinary.commit(() -> true));
        ordinary.finish();
    }

    @Test
    void anEmptySuffixAndAnEmptyIntroducedSourceCanFinishWithoutReleasingTheOriginalFence() {
        var energy = energy();
        var batch = batch(List.of(energy.context()), List.of(), List.of());
        assertTrue(batch.stage(() -> true));
        var empty = batch.savepoint();
        assertTrue(batch.rollbackTo(empty));
        assertTrue(batch.finishRollbackTo(empty));
        var mark = batch.savepoint();
        appendEnergy(batch, energy, 30, false);
        var source = batch.sources().getFirst();
        applyAndStage(batch, source);
        assertTrue(batch.rollbackTo(mark));
        assertTrue(source.resolve());
        assertTrue(batch.finishRollbackTo(mark));
        assertEquals(ResourceBatch.SourceState.APPLIED, source.state());
        assertThrows(IllegalStateException.class, energy::savedEnergy, "Owner must still enter the original fence scope");
        assertTrue(batch.commit(() -> true));
        assertTrue(source.resolve());
        batch.finish();
        assertEquals(100, energy.savedEnergy());
    }

    private static class Token implements ResourceReservation {
        private final ResourceReservation delegate;
        int applies;
        int completions;
        int rollbacks;

        private Token(ResourceReservation delegate) {
            this.delegate = delegate;
        }

        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public boolean canApply() {
            return delegate.canApply();
        }

        @Override
        public void apply() {
            applies++;
            delegate.apply();
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
        public void complete() {
            completions++;
            delegate.complete();
        }

        @Override
        public void rollback() {
            rollbacks++;
            delegate.rollback();
        }

        @Override
        public Resolution resolution() {
            return delegate.resolution();
        }
    }

    private static class Business implements ReversibleTransaction {
        int commits;
        int completions;
        int rollbacks;
        private Resolution terminal = Resolution.REVERSIBLE;

        @Override
        public void commit() {
            commits++;
        }

        @Override
        public void complete() {
            completions++;
            terminal = Resolution.COMPLETED;
        }

        @Override
        public void rollback() {
            rollbacks++;
            terminal = Resolution.ROLLED_BACK;
        }

        @Override
        public Resolution resolution() {
            return terminal;
        }
    }
}
