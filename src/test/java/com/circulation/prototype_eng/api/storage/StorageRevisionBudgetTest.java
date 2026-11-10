package com.circulation.prototype_eng.api.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageRevisionBudgetTest {
    private static final KeyType TYPE = new KeyType("revision_budget");
    private static final Key KEY = new Key(TYPE, "material");

    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }

    private static SlottedResourceBuffer resources(long initialRevision, long amount) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100);
        var storage = new SlottedResourceBuffer(1, capacities, () -> {
        });
        initialize(storage.context(), initialRevision);
        assertEquals(amount, storage.insert(KEY, amount, Actionable.MODULATE));
        return storage;
    }

    private static EnergyBuffer energy(long initialRevision) {
        var energy = new EnergyBuffer(100, () -> {
        });
        initialize(energy.context(), initialRevision);
        energy.restore(60);
        return energy;
    }

    private static void initialize(StorageContext context, long initialRevision) {
        context.lock();
        try {
            context.initializeRevision(initialRevision);
        } finally {
            context.unlock();
        }
    }

    @Test
    void appliedInputAndInvalidationKeepRefundCreditsAfterOrdinaryChangesExhaustTheirBudget() {
        var storage = resources(Long.MAX_VALUE - 8, 40);
        var context = storage.context();
        var token = accepted(storage.reserveExtract(storage.snapshot(), KEY, 10));
        assertEquals(5, storage.insert(KEY, 5, Actionable.MODULATE));
        assertEquals(5, storage.extract(KEY, 5, Actionable.MODULATE));
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(context)));
        fence.access(() -> {
            assertThrows(IllegalStateException.class, () -> context.initializeRevision(Long.MAX_VALUE - 100));
            assertThrows(IllegalStateException.class, () -> storage.insert(KEY, 1, Actionable.MODULATE));
            assertFalse(storage.tryReplaceSlot(0, new GenericStack(KEY, 40), new GenericStack(KEY, 50)));
            assertThrows(IllegalStateException.class, () -> storage.reserveExtract(storage.snapshot(), KEY, 1));
            assertEquals(40, storage.amount(KEY));
            assertEquals(30, storage.availableAmount(KEY));
            token.apply();
            assertEquals(30, storage.amount(KEY));
            assertThrows(IllegalStateException.class, () -> storage.extract(KEY, 1, Actionable.MODULATE));
            context.invalidate();
            assertFalse(context.isValid());
            token.rollback();
            assertEquals(ResourceReservation.Resolution.ROLLED_BACK, token.resolution());
            assertEquals(40, storage.getAmount(0));
        });
        fence.release();
        assertEquals(0, storage.extract(KEY, 100, Actionable.MODULATE));
        assertThrows(IllegalStateException.class, () -> storage.restore(List.of(new GenericStack(KEY, 50))));
    }

    @Test
    void energyLimitChangesCannotSpendEitherTokensApplyOrCompletionCredits() {
        var energy = energy(Long.MAX_VALUE - 11);
        var take = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        var give = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 10));
        energy.setTransferLimits(99, 100);
        assertThrows(IllegalStateException.class, () -> energy.receiveEnergy(1, Actionable.MODULATE));
        assertThrows(IllegalStateException.class, () -> energy.extractEnergy(1, Actionable.MODULATE));
        assertThrows(IllegalStateException.class, () -> energy.setTransferLimits(98, 100));
        assertEquals(99, energy.energySnapshot().maxReceive());
        assertEquals(60, energy.storedEnergy());
        assertTrue(ResourceTransactions.commit(List.of(take, give)));
        assertEquals(50, energy.storedEnergy());
        energy.invalidate();
        assertEquals(50, energy.savedEnergy());
        assertEquals(0, energy.receiveEnergy(1, Actionable.MODULATE));
    }

    @Test
    void pendingRollbackReturnsUnusedApplyAndExposureCreditsForTwoFinalRestores() {
        var energy = energy(Long.MAX_VALUE - 6);
        var take = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        assertThrows(IllegalStateException.class, () -> energy.receiveEnergy(1, Actionable.MODULATE));
        ResourceTransactions.rollback(List.of(take));
        energy.restore(80);
        assertEquals(80, energy.storedEnergy());
        energy.restore(90);
        assertThrows(IllegalStateException.class, () -> energy.restore(95));
        energy.invalidate();
        assertEquals(90, energy.savedEnergy());

        var storage = resources(Long.MAX_VALUE - 6, 20);
        var extract = accepted(storage.reserveExtract(storage.snapshot(), KEY, 5));
        assertThrows(IllegalStateException.class, () -> storage.insert(KEY, 1, Actionable.MODULATE));
        ResourceTransactions.rollback(List.of(extract));
        storage.restore(List.of(new GenericStack(KEY, 30)));
        assertEquals(30, storage.amount(KEY));
        storage.restore(List.of(new GenericStack(KEY, 40)));
        assertThrows(IllegalStateException.class, () -> storage.restore(List.of(new GenericStack(KEY, 50))));
        storage.context().invalidate();
        assertEquals(40, storage.getAmount(0));
    }

    @Test
    void rejectedReservationDoesNotChangeBalancesOrLeakFutureRevisionCredits() {
        var storage = resources(Long.MAX_VALUE - 4, 20);
        assertThrows(IllegalStateException.class, () -> initialize(storage.context(), Long.MAX_VALUE - 100));
        assertThrows(IllegalStateException.class, () -> storage.reserveInsert(storage.snapshot(), KEY, 5));
        assertEquals(20, storage.availableAmount(KEY));
        assertEquals(1, storage.insert(KEY, 1, Actionable.MODULATE));
        assertEquals(1, storage.extract(KEY, 1, Actionable.MODULATE));
        assertThrows(IllegalStateException.class, () -> storage.insert(KEY, 1, Actionable.MODULATE));
        storage.context().invalidate();
        assertEquals(20, storage.getAmount(0));

        var energy = energy(Long.MAX_VALUE - 4);
        assertThrows(IllegalStateException.class, () -> energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        assertEquals(60, energy.energySnapshot().availableEnergy());
        assertEquals(1, energy.receiveEnergy(1, Actionable.MODULATE));
        assertEquals(1, energy.extractEnergy(1, Actionable.MODULATE));
        energy.invalidate();
        assertEquals(60, energy.savedEnergy());
    }

    @Test
    void mixedBusinessFailureCompensatesAllAppliedTokensAtExhaustion() {
        var storage = resources(Long.MAX_VALUE - 7, 60);
        var energy = energy(Long.MAX_VALUE - 7);
        var material = accepted(storage.reserveExtract(storage.snapshot(), KEY, 20));
        var power = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        storage.insert(KEY, 10, Actionable.MODULATE);
        energy.setTransferLimits(90, 100);
        var fault = new IllegalStateException("owner business rejected the applied batch");
        var thrown = assertThrows(IllegalStateException.class,
            () -> ResourceTransactions.commit(List.of(material, power), () -> {
                assertEquals(50, storage.amount(KEY));
                assertEquals(40, energy.storedEnergy());
                assertThrows(IllegalStateException.class, () -> storage.insert(KEY, 1, Actionable.MODULATE));
                assertThrows(IllegalStateException.class, () -> energy.receiveEnergy(1, Actionable.MODULATE));
                throw fault;
            }, () -> {
            }, () -> {
            }));
        assertSame(fault, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(70, storage.amount(KEY));
        assertEquals(60, energy.storedEnergy());
        assertEquals(70, storage.availableAmount(KEY));
        assertEquals(60, energy.energySnapshot().availableEnergy());
        storage.context().invalidate();
        energy.invalidate();
        assertEquals(70, storage.getAmount(0));
        assertEquals(60, energy.savedEnergy());
    }

    @Test
    void cancellingOnePendingTokenReturnsItsTwoUnusedCreditsWithoutTakingAnotherTokensTerminalCredit() {
        var storage = resources(Long.MAX_VALUE - 11, 20);
        var take = accepted(storage.reserveExtract(storage.snapshot(), KEY, 5));
        var give = accepted(storage.reserveInsert(storage.snapshot(), KEY, 7));
        storage.insert(KEY, 1, Actionable.MODULATE);
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(storage.context())));
        fence.access(() -> {
            take.apply();
            give.rollback();
            assertEquals(1, storage.extract(KEY, 1, Actionable.MODULATE));
            assertEquals(1, storage.extract(KEY, 1, Actionable.MODULATE));
            assertThrows(IllegalStateException.class, () -> storage.extract(KEY, 1, Actionable.MODULATE));
            take.complete();
            assertEquals(ResourceReservation.Resolution.COMPLETED, take.resolution());
            assertEquals(14, storage.amount(KEY));
            storage.context().invalidate();
        });
        fence.release();
        assertEquals(14, storage.getAmount(0));
    }

    @Test
    void exposureAndInvalidationCannotSpendTheLastPromisedCompensationCredit() {
        var storage = resources(Long.MAX_VALUE - 6, 40);
        var energy = energy(Long.MAX_VALUE - 6);
        var material = accepted(storage.reserveExtract(storage.snapshot(), KEY, 20));
        var power = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(storage.context(), energy.context())));
        try {
            fence.access(() -> {
                material.apply();
                power.apply();
                material.exposeApplied();
                power.exposeApplied();
                assertThrows(IllegalStateException.class, () -> storage.reserveInsert(storage.snapshot(), KEY, 1));
                assertThrows(IllegalStateException.class, () -> energy.reserveReceiveEnergy(energy.energySnapshot(), 1));
                storage.context().invalidate();
                energy.invalidate();
                material.rollback();
                power.rollback();
                assertEquals(Long.MAX_VALUE, storage.context().revision());
                assertEquals(Long.MAX_VALUE, energy.context().revision());
                assertEquals(40, storage.getAmount(0));
                assertEquals(60, energy.savedEnergy());
                assertEquals(ResourceReservation.Resolution.ROLLED_BACK, material.resolution());
                assertEquals(ResourceReservation.Resolution.ROLLED_BACK, power.resolution());
            });
        } finally {
            fence.release();
        }
    }
}
