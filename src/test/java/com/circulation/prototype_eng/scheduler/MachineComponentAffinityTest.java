package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.AffinityKeyProvider;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MachineComponentAffinityTest {
    private static final ComponentType<MachineInstance, Device> DEVICE = new ComponentType<>(
        new ResourceLocation("affinity_test", "external_device"), _ -> fail("The fixture supplies its external device"),
        List.of(new MachineSystem<>() {
            @Override
            public ResourceLocation id() {
                return new ResourceLocation("affinity_test", "external_work");
            }

            @Override
            public void onTick(MachineInstance owner, Device component) {
                component.action.run();
            }
        }));

    private static MachineInstance machine(Device device) {
        var machine = new MachineInstance();
        machine.components().put(DEVICE, device);
        return machine;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Independent machines did not overlap");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void sharedExternalDevicesUseOneLaneAndJoinedSourceChangesAffectTheNextRound() {
        var log = new ConcurrentLinkedQueue<String>();
        var threads = new ConcurrentLinkedQueue<Thread>();
        var captures = new AtomicInteger();
        Thread owner = Thread.currentThread();
        var left = new Device(() -> Set.of(new Source("shared")), () -> {
            log.add("left");
            threads.add(Thread.currentThread());
        });
        var right = new Device(() -> Set.of(new Source("shared")), () -> {
            log.add("right");
            threads.add(Thread.currentThread());
        });
        left.onCapture = right.onCapture = () -> {
            assertSame(owner, Thread.currentThread());
            captures.incrementAndGet();
        };
        try (var scheduler = new TaskScheduler(owner, 2, 2)) {
            scheduler.register(machine(left));
            scheduler.register(machine(right));
            scheduler.tick();
            assertEquals(List.of("left", "right"), List.copyOf(log));
            assertEquals(1, Set.copyOf(threads).size());
            assertEquals(2, captures.get());

            var entered = new CountDownLatch(2);
            left.keys = () -> Set.of("external:left");
            right.keys = () -> Set.of("external:right");
            left.action = right.action = () -> {
                entered.countDown();
                await(entered);
            };
            scheduler.tick();
            assertEquals(0, entered.getCount());
            assertEquals(4, captures.get(), "Changing only provider sources must not use component-map revision caching");

            log.clear();
            threads.clear();
            left.keys = right.keys = () -> Set.of("external:replacement");
            left.action = () -> {
                log.add("left");
                threads.add(Thread.currentThread());
            };
            right.action = () -> {
                log.add("right");
                threads.add(Thread.currentThread());
            };
            scheduler.tick();
            assertEquals(List.of("left", "right"), List.copyOf(log));
            assertEquals(1, Set.copyOf(threads).size());
        }
    }

    @Test
    void invalidExternalKeysOrCaptureFailuresRejectDispatchAfterThePreviousRoundWasJoined() {
        var failure = new IllegalArgumentException("external capture failed");
        List<Supplier<Set<?>>> failures = List.of(
            () -> null,
            () -> Collections.singleton(null),
            () -> {
                throw failure;
            });
        for (var invalid : failures) {
            var finished = new AtomicInteger();
            var first = new Device(() -> Set.of("first"), finished::incrementAndGet);
            var second = new Device(() -> Set.of("second"), finished::incrementAndGet);
            try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 2)) {
                scheduler.register(machine(first));
                scheduler.register(machine(second));
                scheduler.tick();
                assertEquals(2, finished.get());
                second.keys = invalid;
                scheduler.submit(() -> fail("A rejected metadata batch dispatched a worker"), ExecutionMode.CONCURRENT);
                var thrown = assertThrows(RuntimeException.class, scheduler::tick);
                if (invalid == failures.getLast()) {
                    assertSame(failure, thrown);
                } else {
                    assertInstanceOf(NullPointerException.class, thrown);
                }
                assertEquals(2, finished.get(), "Neither a prior worker nor the rejected batch may retain execution");
                scheduler.runAtBoundary(finished::incrementAndGet);
                assertEquals(3, finished.get());
            }
        }
    }

    private record Source(String id) {
    }

    private static final class Device implements AffinityKeyProvider {
        private Supplier<Set<?>> keys;
        private Runnable action;
        private Runnable onCapture = () -> {
        };

        private Device(Supplier<Set<?>> keys, Runnable action) {
            this.keys = keys;
            this.action = action;
        }

        @Override
        public Set<?> affinityKeys() {
            onCapture.run();
            return keys.get();
        }
    }
}
