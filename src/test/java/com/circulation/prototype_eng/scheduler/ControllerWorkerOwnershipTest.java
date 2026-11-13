package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerWorkerOwnershipTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("controller_worker_ownership", name);
    }

    private static void await(CountDownLatch entered) {
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Both independent callbacks must enter before either exits");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Callback interrupted before overlap was established", failure);
        }
    }

    @Test
    void aLaterRefreshFailureReclaimsEarlierUnsubmittedDispatchAndLifecycleClaims() {
        var owner = Thread.currentThread();
        try (var scheduler = new TaskScheduler(owner, 2, 1);
             var workers = Executors.newFixedThreadPool(2);
             var sourceLane = Executors.newSingleThreadExecutor()) {
            var first = new Fixture(scheduler, "first");
            var second = new Fixture(scheduler, "second");
            try {
                var failure = new IllegalStateException("refresh after first worker claim");
                var refreshes = new AtomicInteger();
                var deadline = PlannedDeadline.after(Duration.ofSeconds(5));
                var execution = new MachineRecipeExecution(new PlannedTaskExecutor(workers, new ExecutorService[]{sourceLane}),
                    List.of(first.candidate(), second.candidate()), deadline, () -> {
                    if (refreshes.incrementAndGet() == 2) throw failure;
                }, () -> true);
                assertSame(failure, assertThrows(IllegalStateException.class, () -> execution.advance(deadline)));
                assertEquals(2, refreshes.get());
                assertEquals(0, first.callbacks.get());
                assertEquals(0, second.callbacks.get());
                assertTrue(execution.finished(), "Unsubmitted claims must not retain either machine's lifecycle gate");
                Objects.requireNonNull(first.machine).requirePersistenceBoundary();
                Objects.requireNonNull(second.machine).requirePersistenceBoundary();
            } finally {
                second.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                first.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    @Test
    @SuppressWarnings("ResultOfMethodCallIgnored")
        // Finally drains an interrupt so teardown can join and unload on the owner.
    void interruptWhileAnAcceptedCallbackRunsStillJoinsItAndReturnsBothClaimsToTheOwner() throws InterruptedException {
        var owner = Thread.currentThread();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var scheduler = new TaskScheduler(owner, 1, 1);
             var workers = Executors.newSingleThreadExecutor();
             var sourceLane = Executors.newSingleThreadExecutor()) {
            var fixture = new Fixture(scheduler, "interrupted");
            fixture.onPre = () -> {
                entered.countDown();
                boolean complete = false;
                while (!complete) {
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        complete = true;
                    } catch (InterruptedException ignored) {
                        // Actual callback exit is controlled independently of the scheduler's interrupt.
                    }
                }
            };
            var interrupter = Thread.ofPlatform().start(() -> {
                try {
                    if (entered.await(5, TimeUnit.SECONDS)) owner.interrupt();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } finally {
                    release.countDown();
                }
            });
            try {
                var deadline = PlannedDeadline.after(Duration.ofSeconds(5));
                var execution = new MachineRecipeExecution(new PlannedTaskExecutor(workers, new ExecutorService[]{sourceLane}),
                    List.of(fixture.candidate()), deadline, () -> {
                }, () -> true);
                assertThrows(IllegalStateException.class, () -> execution.advance(deadline));
                assertTrue(Thread.interrupted(), "The owner's interrupt must be restored only after actual worker exit");
                assertTrue(execution.finished());
                Objects.requireNonNull(fixture.machine).requirePersistenceBoundary();
            } finally {
                Thread.interrupted();
                release.countDown();
                interrupter.join(5000);
                fixture.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    @Test
    void sharedAffinityRunsBothMachineCallbackChunksInRegistrationOrderWithoutReentry() {
        var owner = Thread.currentThread();
        try (var scheduler = new TaskScheduler(owner, 2, 1);
             var workers = Executors.newFixedThreadPool(2);
             var sourceLane = Executors.newSingleThreadExecutor()) {
            var first = new Fixture(scheduler, "shared_first");
            var second = new Fixture(scheduler, "shared_second");
            var active = new AtomicInteger();
            var log = new ConcurrentLinkedQueue<String>();
            var threads = new Thread[2];
            first.onPre = () -> {
                assertEquals(1, active.incrementAndGet());
                try {
                    threads[0] = Thread.currentThread();
                    log.add("first");
                } finally {
                    assertEquals(0, active.decrementAndGet());
                }
            };
            second.onPre = () -> {
                assertEquals(1, active.incrementAndGet());
                try {
                    threads[1] = Thread.currentThread();
                    log.add("second");
                } finally {
                    assertEquals(0, active.decrementAndGet());
                }
            };
            try {
                var deadline = PlannedDeadline.after(Duration.ofSeconds(5));
                var shared = new Object();
                var execution = new MachineRecipeExecution(new PlannedTaskExecutor(workers, new ExecutorService[]{sourceLane}),
                    List.of(first.candidate(Set.of(shared)), second.candidate(Set.of(shared))), deadline, () -> {
                }, () -> true);
                assertTrue(execution.advance(deadline));
                assertEquals(List.of("first", "second"), List.copyOf(log));
                assertEquals(0, active.get());
                assertNotSame(owner, threads[0]);
                assertSame(threads[0], threads[1], "One affinity group must use one sequential callback chunk");
                Objects.requireNonNull(first.machine).requirePersistenceBoundary();
                Objects.requireNonNull(second.machine).requirePersistenceBoundary();
            } finally {
                second.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                first.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    @Test
    void independentAffinityGroupsActuallyOverlapTheirAsyncMachineCallbacks() {
        var owner = Thread.currentThread();
        var entered = new CountDownLatch(2);
        try (var scheduler = new TaskScheduler(owner, 2, 1);
             var workers = Executors.newFixedThreadPool(2);
             var sourceLane = Executors.newSingleThreadExecutor()) {
            var first = new Fixture(scheduler, "independent_first");
            var second = new Fixture(scheduler, "independent_second");
            var threads = new Thread[2];
            first.onPre = () -> {
                threads[0] = Thread.currentThread();
                entered.countDown();
                await(entered);
            };
            second.onPre = () -> {
                threads[1] = Thread.currentThread();
                entered.countDown();
                await(entered);
            };
            try {
                var deadline = PlannedDeadline.after(Duration.ofSeconds(10));
                var execution = new MachineRecipeExecution(new PlannedTaskExecutor(workers, new ExecutorService[]{sourceLane}),
                    List.of(first.candidate(Set.of(new Object())), second.candidate(Set.of(new Object()))), deadline, () -> {
                }, () -> true);
                assertTrue(execution.advance(deadline));
                assertEquals(0, entered.getCount());
                assertNotSame(owner, threads[0]);
                assertNotSame(owner, threads[1]);
                assertNotSame(threads[0], threads[1], "Independent groups must reach the latch on distinct workers");
                Objects.requireNonNull(first.machine).requirePersistenceBoundary();
                Objects.requireNonNull(second.machine).requirePersistenceBoundary();
            } finally {
                second.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                first.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    private static final class Fixture {
        private final AtomicInteger callbacks = new AtomicInteger();
        private final MachineController controller;
        private final MachineInstance machine;
        private Runnable onPre = () -> {
        };

        private Fixture(TaskScheduler scheduler, String name) {
            var callback = new ComponentType<>(id(name + "_callback"), _ -> "state", List.of(new MachineSystem<>() {
                @Override
                public ResourceLocation id() {
                    return ControllerWorkerOwnershipTest.id(name + "_system");
                }

                @Override
                public void onPreTick(MachineInstance instance, String component) {
                    callbacks.incrementAndGet();
                    onPre.run();
                }

                @Override
                public void onTick(MachineInstance instance, String component) {
                    callbacks.incrementAndGet();
                }
            }));
            var type = MachineType.builder(id(name), RecipeText.Cancelled, id("shape"))
                                  .executionPolicy(ControllerExecutionPolicy.FULL_ASYNC).component(callback).build();
            var assembly = MachineAssembly.builder(type, "worker-owner-v1").recreated(callback)
                                          .instanceFactory(MultithreadController::new).build();
            controller = new MachineController(assembly, new Host(Thread.currentThread(), type.id()), scheduler, new MachineLifecycleRegistry());
            controller.load(null);
            machine = Objects.requireNonNull(controller.machine());
        }

        private MachineRecipeExecution.Candidate candidate() {
            return candidate(Set.of());
        }

        private MachineRecipeExecution.Candidate candidate(Set<?> affinities) {
            return new MachineRecipeExecution.Candidate(machine, Objects.requireNonNull(machine.lifecycle()), () -> true,
                ControllerExecutionPolicy.FULL_ASYNC, affinities);
        }
    }

    private record Host(Thread owner, ResourceLocation type) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(type, 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("No game World in this ownership fixture");
        }

        @Override
        public void requireServerThread() {
            assertSame(owner, Thread.currentThread());
        }
    }
}
