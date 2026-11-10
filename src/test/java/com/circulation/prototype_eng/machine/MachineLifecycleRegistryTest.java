package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Each fixture-local registry owns subscriptions for the entire scenario; cancellation is under test.
@SuppressWarnings("resource")
class MachineLifecycleRegistryTest {
    @Test
    void equalListenersRemainIndependentAndOldSubscriptionsCannotCancelTheirReplacement() {
        var registry = new MachineLifecycleRegistry();
        var first = new EqualListener();
        var second = new EqualListener();
        var absent = new EqualListener();
        var firstSubscription = registry.register(first);
        var secondSubscription = registry.register(second);

        assertThrows(IllegalArgumentException.class, () -> registry.register(first));
        assertFalse(registry.unregister(absent));
        assertTrue(registry.unregister(first));
        assertFalse(firstSubscription.active());
        assertTrue(secondSubscription.active());
        assertSame(second, registry.snapshot().getFirst());

        var replacement = registry.register(first);
        firstSubscription.close();
        assertFalse(firstSubscription.cancel());
        assertTrue(replacement.active());
        assertSame(second, registry.snapshot().get(0));
        assertSame(first, registry.snapshot().get(1));

        secondSubscription.close();
        secondSubscription.close();
        assertFalse(secondSubscription.active());
        assertFalse(registry.unregister(second));
        assertSame(first, registry.snapshot().getFirst());
    }

    @Test
    void cancellationAndRegistrationDuringPublicationOnlyChangeTheNextSnapshot() {
        var registry = new MachineLifecycleRegistry();
        var first = new MachineLifecycleListener() {
        };
        var removed = new MachineLifecycleListener() {
        };
        var added = new MachineLifecycleListener() {
        };
        registry.register(first);
        var removedSubscription = registry.register(removed);
        var captured = registry.snapshot();
        var visited = new ObjectArrayList<MachineLifecycleListener>();

        for (var listener : captured) {
            if (listener == first) {
                assertTrue(removedSubscription.cancel());
                registry.register(added);
            }
            visited.add(listener);
        }

        assertEquals(List.of(first, removed), visited);
        assertEquals(List.of(first, removed), captured);
        assertEquals(List.of(first, added), registry.snapshot());
        assertThrows(UnsupportedOperationException.class, captured::removeFirst);
        assertThrows(UnsupportedOperationException.class, () -> registry.snapshot().add(removed));
    }

    @Test
    void concurrentRegistrationOfOneIdentityHasExactlyOneWinner() throws Exception {
        int participants = 8;
        try (var executor = Executors.newFixedThreadPool(participants, Thread.ofPlatform().daemon().factory())) {
            try {
                for (int round = 0; round < 64; round++) {
                    var registry = new MachineLifecycleRegistry();
                    var listener = new MachineLifecycleListener() {
                    };
                    var ready = new CountDownLatch(participants);
                    var start = new CountDownLatch(1);
                    var attempts = new ObjectArrayList<Future<MachineLifecycleRegistry.Subscription>>();
                    for (int participant = 0; participant < participants; participant++) {
                        attempts.add(executor.submit(() -> {
                            ready.countDown();
                            assertTrue(start.await(5, TimeUnit.SECONDS));
                            try {
                                return registry.register(listener);
                            } catch (IllegalArgumentException ignored) {
                                return null;
                            }
                        }));
                    }
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                    start.countDown();
                    int winners = 0;
                    for (var attempt : attempts) {
                        var subscription = attempt.get(5, TimeUnit.SECONDS);
                        if (subscription != null) {
                            winners++;
                            assertTrue(subscription.active());
                        }
                    }
                    assertEquals(1, winners, "concurrent registration round " + round);
                    assertEquals(1, registry.snapshot().size());
                    assertSame(listener, registry.snapshot().getFirst());
                    assertTrue(registry.unregister(listener));
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void concurrentCancellationAndUnregistrationRemoveOnlyTheOriginalEntry() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory())) {
            try {
                for (int round = 0; round < 64; round++) {
                    var registry = new MachineLifecycleRegistry();
                    var listener = new MachineLifecycleListener() {
                    };
                    var original = registry.register(listener);
                    var ready = new CountDownLatch(2);
                    var start = new CountDownLatch(1);
                    var cancel = executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return original.cancel();
                    });
                    var unregister = executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return registry.unregister(listener);
                    });
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                    start.countDown();
                    assertTrue(cancel.get(5, TimeUnit.SECONDS) ^ unregister.get(5, TimeUnit.SECONDS));
                    assertFalse(original.active());
                    assertTrue(registry.snapshot().isEmpty());

                    var replacement = registry.register(listener);
                    assertFalse(original.cancel());
                    original.close();
                    assertTrue(replacement.active());
                    assertSame(listener, registry.snapshot().getFirst());
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static final class EqualListener implements MachineLifecycleListener {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualListener;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }
}
