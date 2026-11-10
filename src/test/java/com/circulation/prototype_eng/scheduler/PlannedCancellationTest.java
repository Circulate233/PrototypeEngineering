package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Registration;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannedCancellationTest {
    @Test
    void independentHandlesAndConcurrentRegistrationRemovalNotifyAtMostOnce() throws Exception {
        var token = new PlannedCancellationToken();
        var notifications = new ObjectArrayList<Reason>();
        Consumer<Reason> handler = notifications::add;
        try (var _ = token.onCancel(handler)) {
            try (var _ = token.onCancel(handler)) {
                assertFalse(token.cancelled());
            }
            assertTrue(token.cancel(Reason.EXPLICIT_CANCEL));
            assertEquals(List.of(Reason.EXPLICIT_CANCEL), notifications);
        }
        for (int iteration = 0; iteration < 64; iteration++) {
            var racing = new PlannedCancellationToken();
            boolean unregister = (iteration & 1) == 0;
            var calls = new int[1];
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var registering = new FutureTask<>(() -> {
                ready.countDown();
                assertTrue(start.await(5L, TimeUnit.SECONDS));
                var registration = racing.onCancel(reason -> {
                    assertEquals(Reason.MACHINE_UNLOAD, reason);
                    synchronized (calls) {
                        calls[0]++;
                    }
                });
                if (unregister) {
                    registration.close();
                }
                return registration;
            });
            var cancelling = new FutureTask<>(() -> {
                ready.countDown();
                assertTrue(start.await(5L, TimeUnit.SECONDS));
                return racing.cancel(Reason.MACHINE_UNLOAD);
            });
            Thread.ofPlatform().daemon().start(registering);
            Thread.ofPlatform().daemon().start(cancelling);
            try {
                assertTrue(ready.await(5L, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            try (var _ = registering.get(5L, TimeUnit.SECONDS)) {
                assertTrue(cancelling.get(5L, TimeUnit.SECONDS));
                if (unregister) {
                    assertTrue(calls[0] == 0 || calls[0] == 1);
                } else {
                    assertEquals(1, calls[0]);
                }
                assertFalse(racing.cancel(Reason.SERVER_STOP));
                assertEquals(Reason.MACHINE_UNLOAD, racing.reason());
            }
        }
    }

    @Test
    void callbackFailuresStillNotifyEveryRegistrationAndRetainTheFirstReason() {
        var token = new PlannedCancellationToken();
        var notifications = new ObjectArrayList<String>();
        var firstFailure = new AssertionError("first callback failed");
        var laterFailure = new IllegalStateException("later callback failed");
        try (var _ = token.onCancel(_ -> {
            notifications.add("first");
            throw firstFailure;
        }); var _ = token.onCancel(_ -> {
            notifications.add("second");
            throw laterFailure;
        }); var _ = token.onCancel(_ -> notifications.add("third"))) {
            assertSame(firstFailure, assertThrows(AssertionError.class, () -> token.cancel(Reason.WORKER_FAILURE)));
            assertArrayEquals(new Throwable[]{laterFailure}, firstFailure.getSuppressed());
            assertEquals(List.of("first", "second", "third"), notifications);
            assertTrue(token.cancelled());
            assertEquals(Reason.WORKER_FAILURE, token.reason());
            assertFalse(token.cancel(Reason.COMMIT_FAILURE));
            try (var _ = token.onCancel(reason -> {
                assertEquals(Reason.WORKER_FAILURE, reason);
                notifications.add("late");
            })) {
                assertEquals(List.of("first", "second", "third", "late"), notifications);
            }
        }
    }

    @Test
    void callbacksMayReenterAndCloseOtherHandlesWithoutHoldingTheRegistrationLock() {
        var token = new PlannedCancellationToken();
        var notifications = new ObjectArrayList<String>();
        var other = new Registration[1];
        try (var _ = token.onCancel(reason -> {
            notifications.add("first");
            assertFalse(token.cancel(Reason.EXPLICIT_CANCEL));
            other[0].close();
            var nested = new FutureTask<>(() -> {
                try (var _ = token.onCancel(innerReason -> {
                    assertEquals(reason, innerReason);
                    notifications.add("nested");
                })) {
                    assertTrue(token.cancelled());
                }
                return null;
            });
            Thread.ofPlatform().daemon().start(nested);
            assertDoesNotThrow(() -> nested.get(5L, TimeUnit.SECONDS));
        })) {
            try (var second = token.onCancel(_ -> notifications.add("second"))) {
                other[0] = second;
                assertTrue(token.cancel(Reason.DECISION_ROLLBACK));
                assertEquals(List.of("first", "nested", "second"), notifications);
            }
        }
    }
}
