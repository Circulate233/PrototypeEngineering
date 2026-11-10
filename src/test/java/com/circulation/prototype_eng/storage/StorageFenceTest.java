package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageFenceTest {
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void mixedSourceRollbackHidesChangesAndBlocksMutationUntilOwnerRelease() throws Exception {
        var notifications = new NotificationCounter();
        Runnable changed = notifications::increment;
        var type = new KeyType("fenced_resource");
        var key = new Key(type, "material");
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(type, 20);
        var resources = new SlottedResourceBuffer(1, capacities, changed);
        resources.insert(key, 10, Actionable.MODULATE);
        var energy = new EnergyBuffer(100, changed);
        energy.restore(70);
        ResourceReservation material = Objects.requireNonNull(resources.reserveExtract(resources.snapshot(), key, 6).reservation());
        ResourceReservation power = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 20).reservation());
        int before = notifications.value();
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(resources.context(), energy.context())));
        var reading = new CountDownLatch(1);
        var mutating = new CountDownLatch(1);
        @SuppressWarnings("resource")
        var pool = Executors.newFixedThreadPool(3, Thread.ofPlatform().daemon().factory());
        try {
            pool.submit(() -> fence.access(resources.context(), material::apply)).get(5, TimeUnit.SECONDS);
            pool.submit(() -> fence.access(energy.context(), power::apply)).get(5, TimeUnit.SECONDS);
            fence.access(() -> {
                assertEquals(4, resources.amount(key));
                assertEquals(50, energy.storedEnergy());
            });
            var reader = pool.submit(() -> {
                reading.countDown();
                return energy.savedEnergy();
            });
            var writer = pool.submit(() -> {
                mutating.countDown();
                return resources.insert(key, 2, Actionable.MODULATE);
            });
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertTrue(mutating.await(5, TimeUnit.SECONDS));
            assertFalse(reader.isDone());
            assertFalse(writer.isDone());
            resources.context().publishChanges();
            energy.context().publishChanges();
            assertEquals(before, notifications.value());
            pool.submit(() -> fence.access(energy.context(), power::rollback)).get(5, TimeUnit.SECONDS);
            pool.submit(() -> fence.access(resources.context(), material::rollback)).get(5, TimeUnit.SECONDS);
            assertFalse(reader.isDone());
            assertFalse(writer.isDone());
            fence.release();
            assertEquals(70, reader.get(5, TimeUnit.SECONDS));
            assertEquals(2, writer.get(5, TimeUnit.SECONDS));
            assertEquals(12, resources.amount(key));
            assertEquals(70, energy.storedEnergy());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sourceScopesSerializeAndReleaseRequiresActualScopeExit() throws Exception {
        var context = new StorageContext(() -> {
        });
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(context)));
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        @SuppressWarnings("resource")
        var pool = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            var first = pool.submit(() -> fence.access(context, () -> {
                entered.countDown();
                await(finish);
                context.changed();
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> {
                secondStarted.countDown();
                fence.access(context, secondEntered::countDown);
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1, secondEntered.getCount());
            assertThrows(IllegalStateException.class, fence::release);
            assertThrows(IllegalStateException.class, context::lock);
            finish.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            fence.release();
            context.lock();
            try {
                assertEquals(1, context.revision());
            } finally {
                context.unlock();
            }
        } finally {
            finish.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void conflictsNeverLeavePartialClaimsAndOrdinaryMultiDomainWaitReleasesEarlierLocks() throws Exception {
        var first = new StorageContext(() -> {
        });
        var second = new StorageContext(() -> {
        });
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(second)));
        assertNull(StorageFence.tryAcquire(List.of(first, second)));
        var waiting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        var pool = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            var batch = pool.submit(() -> {
                waiting.countDown();
                StorageContext.lockAll(List.of(first, second));
                try {
                    first.changed();
                    second.changed();
                } finally {
                    second.unlockDeferred();
                    first.unlockDeferred();
                }
            });
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            pool.submit(() -> {
                first.lock();
                try {
                    first.changed();
                } finally {
                    first.unlock();
                }
            }).get(5, TimeUnit.SECONDS);
            assertFalse(batch.isDone());
            fence.release();
            batch.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        var next = StorageFence.tryAcquire(List.of(second, first, first));
        assertNotNull(next);
        next.access(() -> {
            assertEquals(2, first.revision());
            assertEquals(1, second.revision());
        });
        next.release();
    }

    @Test
    void physicalLockContentionAlsoRejectsWithoutClaimingAnotherContext() throws Exception {
        var first = new StorageContext(() -> {
        });
        var second = new StorageContext(() -> {
        });
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        @SuppressWarnings("resource")
        var pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            var holding = pool.submit(() -> {
                second.lock();
                try {
                    entered.countDown();
                    await(finish);
                } finally {
                    second.unlock();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertNull(StorageFence.tryAcquire(List.of(first, second)));
            first.lock();
            try {
                first.changed();
            } finally {
                first.unlock();
            }
            finish.countDown();
            holding.get(5, TimeUnit.SECONDS);
            var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(first, second)));
            fence.release();
        } finally {
            finish.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void scopesRejectNestedBatchesOtherDomainsOwnerBypassAndPrematureUnlock() throws Exception {
        var first = new StorageContext(() -> {
        });
        var second = new StorageContext(() -> {
        });
        var outside = new StorageContext(() -> {
        });
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(first, second)));
        assertThrows(IllegalStateException.class, first::lock);
        fence.access(() -> {
            assertThrows(IllegalStateException.class, () -> fence.access(() -> {
            }));
            assertThrows(IllegalStateException.class, () -> StorageFence.tryAcquire(List.of(outside)));
            assertThrows(IllegalStateException.class, outside::lock);
            assertThrows(IllegalStateException.class, outside::publishChanges);
            assertThrows(IllegalStateException.class, first::unlockDeferred);
            first.lock();
            try {
                first.changed();
            } finally {
                first.unlock();
            }
        });
        @SuppressWarnings("resource")
        var pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            pool.submit(() -> {
                assertThrows(IllegalStateException.class, () -> fence.access(() -> {
                }));
                assertThrows(IllegalStateException.class, fence::release);
                fence.access(first, () -> {
                    assertThrows(IllegalStateException.class, second::lock);
                    first.requireLock();
                });
            }).get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        fence.release();
        assertThrows(IllegalStateException.class, () -> fence.access(first, () -> {
        }));
        first.lock();
        try {
            assertThrows(IllegalStateException.class, () -> StorageFence.tryAcquire(List.of(outside)));
        } finally {
            first.unlock();
        }
    }

    @Test
    void leakedNestedLocksAreUnwoundAndDoNotReplaceTheOriginalFailure() {
        var context = new StorageContext(() -> {
        });
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(context)));
        var fault = new IllegalArgumentException("business failed");
        var failure = assertThrows(IllegalArgumentException.class, () -> fence.access(() -> {
            context.lock();
            throw fault;
        }));
        assertSame(fault, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertThrows(IllegalStateException.class, () -> fence.access(context, context::lock));
        fence.access(context, context::changed);
        fence.release();
        context.lock();
        try {
            assertEquals(1, context.revision());
        } finally {
            context.unlock();
        }
    }

    @Test
    void invalidationRetainsFenceAndAllowsExactRollbackBeforePublication() throws Exception {
        int[] notifications = {0};
        var energy = new EnergyBuffer(100, () -> notifications[0]++);
        energy.restore(60);
        var token = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 20).reservation());
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(energy.context())));
        fence.access(energy.context(), token::apply);
        assertThrows(IllegalStateException.class, energy::invalidate);
        int before = notifications[0];
        fence.access(() -> {
            energy.invalidate();
            assertFalse(energy.isValid());
            token.rollback();
            assertEquals(60, energy.savedEnergy());
        });
        assertEquals(before, notifications[0]);
        var entered = new CountDownLatch(1);
        @SuppressWarnings("resource")
        var pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            var reader = pool.submit(() -> {
                entered.countDown();
                return energy.savedEnergy();
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(reader.isDone());
            fence.release();
            assertEquals(60, reader.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(before + 1, notifications[0]);
        assertEquals(0, energy.extractEnergy(100, Actionable.MODULATE));
    }

    @Test
    void releaseAggregatesNotificationFaultsAfterAllClaimsAreRemoved() {
        var owner = Thread.currentThread();
        var firstFault = new IllegalArgumentException("first notification");
        var secondFault = new IllegalStateException("second notification");
        int[] calls = {0};
        var first = new StorageContext(() -> {
            assertSame(owner, Thread.currentThread());
            calls[0]++;
            throw firstFault;
        });
        var second = new StorageContext(() -> {
            assertSame(owner, Thread.currentThread());
            calls[0]++;
            throw secondFault;
        });
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(first, second)));
        fence.access(() -> {
            first.changed();
            second.changed();
        });
        first.publishChanges();
        assertEquals(0, calls[0]);
        var fault = assertThrows(IllegalArgumentException.class, fence::release);
        assertSame(firstFault, fault);
        assertSame(secondFault, fault.getSuppressed()[0]);
        assertEquals(2, calls[0]);
        assertThrows(IllegalStateException.class, fence::release);
        var next = Objects.requireNonNull(StorageFence.tryAcquire(List.of(first, second)));
        next.release();
        assertEquals(2, calls[0]);
    }

    @Test
    void ordinaryNestedDomainWaitIsRejectedBeforeWaitingOnSourceWork() throws Exception {
        var first = new StorageContext(() -> {
        });
        var second = new StorageContext(() -> {
        });
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(second)));
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        @SuppressWarnings("resource")
        var pool = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            var source = pool.submit(() -> fence.access(second, () -> {
                entered.countDown();
                await(finish);
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.submit(() -> {
                first.lock();
                try {
                    assertThrows(IllegalStateException.class, second::lock);
                } finally {
                    first.unlock();
                }
            }).get(5, TimeUnit.SECONDS);
            finish.countDown();
            source.get(5, TimeUnit.SECONDS);
            fence.release();
        } finally {
            finish.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void readersCannotStealAnotherDomainsCapturedNotificationDuringOwnerPublication() throws Exception {
        var owner = Thread.currentThread();
        int[] notifications = {0};
        var peer = new StorageContext[1];
        @SuppressWarnings("resource")
        var pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            var first = new StorageContext(() -> {
                assertSame(owner, Thread.currentThread());
                try {
                    pool.submit(() -> {
                        var context = Objects.requireNonNull(peer[0]);
                        context.lock();
                        try {
                            assertEquals(1, context.revision());
                        } finally {
                            context.unlock();
                        }
                    }).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                notifications[0]++;
            });
            var second = new StorageContext(() -> {
                assertSame(owner, Thread.currentThread());
                notifications[0]++;
            });
            peer[0] = second;
            var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(first, second)));
            fence.access(() -> {
                first.changed();
                second.changed();
            });
            fence.release();
            assertEquals(2, notifications[0]);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class NotificationCounter {
        private int value;

        synchronized void increment() {
            value++;
        }

        synchronized int value() {
            return value;
        }
    }
}
