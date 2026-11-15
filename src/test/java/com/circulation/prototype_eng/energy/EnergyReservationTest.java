package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergyReservationTest {
    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }

    @Test
    void quantityAndCapacityHoldsProtectTheSharedBalanceUntilBatchCompletion() {
        var energy = new EnergyBuffer(100, () -> {
        });
        var forge = new ForgeEnergyPort(energy, HatchMode.MIXED);
        energy.restore(60);
        var take = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 30));
        var give = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 40));
        assertEquals(0, forge.receiveEnergy(100, false));
        assertEquals(30, forge.extractEnergy(100, true));
        assertEquals(30, forge.extractEnergy(100, false));
        assertEquals(0, forge.extractEnergy(100, false));
        assertEquals(30, energy.storedEnergy());
        assertTrue(ResourceTransactions.commit(List.of(take, give)));
        assertEquals(40, energy.storedEnergy());
        var refill = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 60));
        assertTrue(ResourceTransactions.commit(List.of(refill)));
        assertEquals(100, forge.extractEnergy(100, false));
        assertEquals(0, energy.storedEnergy());
    }

    @Test
    void externalLimitsDoNotRestrictPendingMachineWorkAndCompensation() {
        var energy = new EnergyBuffer(Long.MAX_VALUE, () -> {
        });
        energy.restore(Long.MAX_VALUE - 3);
        var stale = energy.energySnapshot();
        var give = accepted(energy.reserveReceiveEnergy(stale, 3));
        assertEquals(0, energy.receiveEnergy(Long.MAX_VALUE, Actionable.MODULATE));
        energy.setTransferLimits(0, 0);
        assertTrue(ResourceTransactions.commit(List.of(give)));
        assertEquals(Long.MAX_VALUE, energy.storedEnergy());
        assertEquals(ReservationResult.Status.STALE_SNAPSHOT, energy.reserveReceiveEnergy(stale, 1).status());
        assertEquals(ReservationResult.Status.INSUFFICIENT,
            energy.reserveReceiveEnergy(energy.energySnapshot(), 3).status());
        var port = new ForgeEnergyPort(energy, HatchMode.MIXED);
        assertEquals(0, port.extractEnergy(100, false));
        var take = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), Long.MAX_VALUE - 3));
        assertTrue(ResourceTransactions.commit(List.of(take)));
        assertEquals(3, energy.storedEnergy());
        assertEquals(0, port.receiveEnergy(100, false));
        var refunded = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), Long.MAX_VALUE - 3));
        ResourceTransactions.rollback(List.of(refunded));
        assertEquals(3, energy.storedEnergy());
        var invalidated = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 3));
        energy.invalidate();
        assertFalse(ResourceTransactions.commit(List.of(invalidated)));
        assertEquals(0, energy.extractEnergy(10, Actionable.MODULATE));
        assertEquals(3, energy.savedEnergy());
        assertThrows(IllegalStateException.class, () -> energy.restore(10));
    }

    @Test
    void aMixedResourceEnergyFailureRestoresBothBeforeConcurrentReadersCanObserveThem() throws Exception {
        var type = new KeyType("energy_batch_resource");
        var material = new Key(type, "material");
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(type, 20);
        var resources = new SlottedResourceBuffer(1, capacities, () -> {
        });
        resources.insert(material, 10, Actionable.MODULATE);
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.restore(70);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var observing = new CountDownLatch(1);
        var takeMaterial = accepted(resources.reserveExtract(resources.snapshot(), material, 6));
        var takeEnergy = new PausedReservation(accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 20)), entered, release);
        var giveEnergy = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 10));
        var fault = new IllegalStateException("later participant failed");
        var broken = new FailingReservation(new StorageContext(() -> {
        }), fault);

        // Bound teardown even when this test exposes a real storage deadlock.
        @SuppressWarnings("resource")
        var pool = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            Future<Boolean> writer = pool.submit(() -> ResourceTransactions.commit(List.of(takeMaterial, takeEnergy, giveEnergy, broken)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Future<long[]> reader = pool.submit(() -> {
                    observing.countDown();
                    return new long[]{energy.storedEnergy(), resources.amount(material)};
                });
                assertTrue(observing.await(5, TimeUnit.SECONDS));
                assertFalse(reader.isDone());
                release.countDown();
                var failure = assertThrows(ExecutionException.class, () -> writer.get(5, TimeUnit.SECONDS));
                assertSame(fault, failure.getCause());
                long[] observed = reader.get(5, TimeUnit.SECONDS);
                assertEquals(70, observed[0]);
                assertEquals(10, observed[1]);
            } finally {
                release.countDown();
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(70, energy.extractEnergy(100, Actionable.MODULATE));
        assertEquals(10, resources.extract(material, 20, Actionable.MODULATE));
    }

    @Test
    void concurrentReservationsCompeteForOneBalanceAndReleaseItForTheNextBatch() throws Exception {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.restore(100);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var tokens = new ObjectArrayList<ResourceReservation>();
        // ExecutorService.close could otherwise wait forever after a lock leak.
        @SuppressWarnings("resource")
        var pool = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            var futures = new ObjectArrayList<Future<ReservationResult>>();
            for (int index = 0; index < 2; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    var context = energy.context();
                    context.lock();
                    try {
                        return energy.reserveExtractEnergy(energy.energySnapshot(), 70);
                    } finally {
                        context.unlock();
                    }
                }));
            }
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                for (var future : futures) {
                    var result = future.get(5, TimeUnit.SECONDS);
                    if (result.status() == ReservationResult.Status.ACCEPTED) {
                        tokens.add(Objects.requireNonNull(result.reservation()));
                    } else {
                        assertEquals(ReservationResult.Status.INSUFFICIENT, result.status());
                    }
                }
            } finally {
                start.countDown();
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, tokens.size());
        assertEquals(30, energy.extractEnergy(100, Actionable.MODULATE));
        assertTrue(ResourceTransactions.commit(tokens));
        assertEquals(0, energy.storedEnergy());
        var refill = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 100));
        assertTrue(ResourceTransactions.commit(List.of(refill)));
        assertEquals(100, energy.extractEnergy(100, Actionable.MODULATE));
    }

    private record PausedReservation(ResourceReservation delegate, CountDownLatch entered,
                                     CountDownLatch release) implements ResourceReservation {
        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public boolean canApply() {
            return delegate.canApply();
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
        public void apply() {
            delegate.apply();
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }

    private record FailingReservation(StorageContext context, RuntimeException failure) implements ResourceReservation {
        @Override
        public boolean canApply() {
            context.requireLock();
            return true;
        }

        @Override
        public void apply() {
            context.requireLock();
            throw failure;
        }

        @Override
        public void complete() {
            throw new AssertionError("A failed apply cannot complete");
        }

        @Override
        public void rollback() {
            context.requireLock();
        }
    }
}
